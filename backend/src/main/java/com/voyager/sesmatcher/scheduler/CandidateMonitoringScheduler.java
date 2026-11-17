package com.voyager.sesmatcher.scheduler;

import com.google.api.services.gmail.model.Message;
import com.voyager.sesmatcher.dto.AiMatchResponse;
import com.voyager.sesmatcher.entity.MatchRecord;
import com.voyager.sesmatcher.repository.MatchRecordRepository;
import com.voyager.sesmatcher.service.AuditLogService;
import com.voyager.sesmatcher.service.CandidateStatusService;
import com.voyager.sesmatcher.service.GeminiMatchingService;
import com.voyager.sesmatcher.service.GmailService;
import com.voyager.sesmatcher.service.ResumeParserService;
import com.voyager.sesmatcher.service.SesEmailFilterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class CandidateMonitoringScheduler {

    private final GmailService gmailService;
    private final ResumeParserService resumeParserService;
    private final GeminiMatchingService geminiMatchingService;
    private final MatchRecordRepository matchRecordRepository;
    private final SesEmailFilterService filterService;
    private final CandidateStatusService candidateStatusService;
    private final AuditLogService auditLogService;

    @Value("${ses.matcher.subject-filter}")
    private String subjectFilter;

    @Value("${ses.matcher.threshold:60}")
    private int matchThreshold;

    /**
     * Step 1: Every 5 minutes Gmail Monitoring Scheduler
     * Cron expression: 5 minutes interval
     */
    @Scheduled(cron = "0 */5 * * * *")
    public void executeCandidateMonitoringCron() {
        log.info("⏰ [CRON TRIGGER] Executing Scheduled Gmail Monitoring Task (5 min interval)...");
        runJobInternal();
    }

    /**
     * Executes the matching pipeline synchronously or on-demand
     */
    public Map<String, Object> runJobNow() {
        log.info("⚡ [MANUAL TRIGGER] Triggering SES Matching Job on demand...");
        return runJobInternal();
    }

    /**
     * Purges invalid match records (e.g., Copilot notifications, calendar invites, meeting notes, self-matches)
     * Requirement 9: Recalculate matching scores only from valid candidate-project pairs.
     */
    public int purgeInvalidMatchRecords() {
        List<MatchRecord> allRecords = matchRecordRepository.findAll();
        int purgedCount = 0;

        for (MatchRecord r : allRecords) {
            boolean isSelfMatch = r.getCandidateEmailId() != null && r.getCandidateEmailId().equals(r.getProjectEmailId());
            boolean isExcluded = filterService.isExcludedEmail(r.getProjectSubject(), null, r.getProjectBody(), null);
            boolean isLegitimateProject = filterService.isLegitimateProjectEmail(r.getProjectSubject(), "", r.getProjectBody(), Collections.emptyList());

            if (isSelfMatch || isExcluded || !isLegitimateProject) {
                log.info("Purging invalid match record [id={}] with project: {}", r.getId(), r.getProjectSubject());
                matchRecordRepository.delete(r);
                purgedCount++;
            }
        }

        if (purgedCount > 0) {
            log.info("Purged {} invalid match records from database.", purgedCount);
        }
        return purgedCount;
    }

    private Map<String, Object> runJobInternal() {
        Map<String, Object> report = new HashMap<>();
        int candidatesFound = 0;
        int projectsFound = 0;
        int evaluatedMatches = 0;
        int draftsCreated = 0;

        try {
            // Requirement 9: Clean up any invalid match records
            int purged = purgeInvalidMatchRecords();
            report.put("purgedInvalidRecords", purged);

            String activeSalesEmail = gmailService.getActiveSalesEmail();
            if (!gmailService.isConnected() || activeSalesEmail == null || activeSalesEmail.isBlank()) {
                log.warn("Gmail API client not connected or no Google OAuth2 user logged in.");
                report.put("status", "GMAIL_NOT_CONNECTED");
                report.put("message", "Google OAuth2 user is not logged in. Please sign in with Google.");
                return report;
            }

            // Step 1: Query candidate inquiry emails (excluding generated drafts)
            String query = "(subject:\"" + subjectFilter + "\" OR subject:\"人材情報\" OR subject:\"職務経歴書\" OR subject:\"スキルシート\") -subject:\"【提案下書き】\" -from:(noreply OR no-reply OR notifications) -filename:ics";
            List<Message> candidateMessages = gmailService.searchCandidateEmails(query);
            candidatesFound = candidateMessages.size();
            log.info("Step 1: Found {} candidate inquiry emails matching query [{}]", candidatesFound, query);

            if (candidateMessages.isEmpty()) {
                report.put("status", "SUCCESS");
                report.put("message", "No new candidate inquiry emails found.");
                report.put("candidatesFound", 0);
                return report;
            }

            // Step 3: Fetch active Project emails from logged-in Sales Rep Inbox
            // Only genuine SES project proposals are fetched and validated
            List<Map<String, String>> projectEmails = gmailService.fetchProjectEmails();
            projectsFound = projectEmails.size();
            log.info("Step 3: Found {} validated genuine project emails from sales inbox ({})", projectsFound, activeSalesEmail);
            auditLogService.recordLog(
                    "案件メール再取得",
                    String.format("人材メール %d 件 / 案件メール %d 件を Gmail (%s) から取得", candidatesFound, projectsFound, activeSalesEmail)
            );

            for (Message msgSummary : candidateMessages) {
                Message fullMsg = gmailService.getMessageDetails(msgSummary.getId());
                if (fullMsg == null) continue;

                String candidateSubject = gmailService.getHeaderValue(fullMsg, "Subject");
                String candidateSender = gmailService.getHeaderValue(fullMsg, "From");
                String emailBody = gmailService.extractMessageBody(fullMsg.getPayload());

                // Step 2: Attachment Extraction (PDF/Excel 経歴書) via Apache Tika
                Map<String, byte[]> attachments = gmailService.extractAttachments(fullMsg.getId(), fullMsg);

                // Validate if legitimate candidate email
                if (!filterService.isLegitimateCandidateEmail(candidateSubject, candidateSender, emailBody, attachments)) {
                    log.info("⛔ Skipping non-candidate email [id={}]: {}", fullMsg.getId(), candidateSubject);
                    continue;
                }

                // If email has no attachments, verify if the body is a legitimate profile
                if (attachments.isEmpty()) {
                    if (!emailBody.isBlank()) {
                        attachments.put("メール本文(経歴情報)", emailBody.getBytes());
                    }
                }

                for (Map.Entry<String, byte[]> entry : attachments.entrySet()) {
                    String fileName = entry.getKey();

                    // Register candidate metadata in local status store
                    candidateStatusService.registerCandidateMetadata(fileName, fullMsg.getId(), candidateSubject);

                    // Requirement 3: Skip matching if candidate is marked as ASSIGNED (🔴 現場決定済)
                    if (candidateStatusService.isAssigned(fileName)) {
                        log.info("⏸️ Skipping candidate [{}] — Status is ASSIGNED (現場決定済 / マッチング停止中)", fileName);
                        continue;
                    }

                    String parsedResumeText = resumeParserService.parseResume(entry.getValue(), fileName);

                    if (parsedResumeText.isBlank() || parsedResumeText.trim().length() < 30) {
                        log.warn("Parsed resume content was empty or trivial for file: {}", fileName);
                        continue;
                    }

                    int candidateEvaluated = 0;

                    // Compare with each validated project email
                    for (Map<String, String> project : projectEmails) {
                        String projectId = project.get("id");
                        String projectSubject = project.get("subject");
                        String projectBody = project.get("body");
                        String receivedAtStr = project.get("receivedAt");
                        LocalDateTime projectReceivedAt = LocalDateTime.now();
                        if (receivedAtStr != null && !receivedAtStr.isBlank()) {
                            try {
                                projectReceivedAt = LocalDateTime.parse(receivedAtStr);
                            } catch (Exception ignored) {
                            }
                        }

                        // Self-match prevention
                        if (fullMsg.getId().equals(projectId)) {
                            continue;
                        }

                        // Avoid duplicate processing of same candidate file and project pair
                        if (matchRecordRepository.existsByCandidateEmailIdAndCandidateFileNameAndProjectEmailId(fullMsg.getId(), fileName, projectId)) {
                            continue;
                        }

                        // Step 4: AI Matching Process via Gemini API
                        AiMatchResponse matchResult = geminiMatchingService.evaluateMatching(
                                parsedResumeText,
                                projectBody,
                                fileName
                        );
                        evaluatedMatches++;
                        candidateEvaluated++;

                        int score = matchResult.getFitRate() != null ? matchResult.getFitRate() : 0;
                        boolean meetsThreshold = score >= matchThreshold;

                        MatchRecord.MatchRecordBuilder recordBuilder = MatchRecord.builder()
                                .candidateEmailId(fullMsg.getId())
                                .candidateSubject(candidateSubject)
                                .candidateFileName(fileName)
                                .candidateParsedText(truncate(parsedResumeText, 3000))
                                .projectEmailId(projectId)
                                .projectSubject(projectSubject)
                                .projectBody(truncate(projectBody, 3000))
                                .receivedAt(projectReceivedAt)
                                .fitRate(score)
                                .fitReason(matchResult.getFitReason())
                                .proposalDraft(matchResult.getProposalDraft())
                                .conditionCheckResult(matchResult.getConditionCheckResult())
                                .createdAt(LocalDateTime.now())
                                .draftCreated(false);

                        // Step 5: 60% Threshold & Draft Creation
                        if (meetsThreshold) {
                            String draftSubject = String.format("【提案下書き】%s (%s 候補・適合率%d%%)", projectSubject, fileName, score);
                            String draftId = gmailService.createDraftEmail(
                                    activeSalesEmail,
                                    draftSubject,
                                    matchResult.getProposalDraft()
                            );
                            recordBuilder.draftCreated(true);
                            recordBuilder.draftId(draftId);
                            draftsCreated++;
                            log.info("🎯 Fit Rate {}% >= {}%. Created Gmail Draft ID [{}] for sales rep [{}]",
                                    score, matchThreshold, draftId, activeSalesEmail);
                            auditLogService.recordLog(
                                    "下書き（Draft）作成",
                                    String.format("候補者: %s / 案件: %s (適合度: %d%%, Draft ID: %s)", fileName, projectSubject, score, draftId)
                            );
                        } else {
                            log.info("Fit Rate {}% < {}%. Saved as audit log.", score, matchThreshold);
                        }

                        matchRecordRepository.save(recordBuilder.build());
                    }

                    if (candidateEvaluated > 0) {
                        auditLogService.recordLog(
                                "AIマッチング実行",
                                String.format("候補者: %s (メールID: %s) / 新規判定件数: %d 件", fileName, fullMsg.getId(), candidateEvaluated)
                        );
                    }
                }
            }

            report.put("status", "SUCCESS");
            report.put("candidatesFound", candidatesFound);
            report.put("projectsFound", projectsFound);
            report.put("evaluatedMatches", evaluatedMatches);
            report.put("draftsCreated", draftsCreated);

        } catch (Exception e) {
            log.error("Error executing candidate matching job: {}", e.getMessage(), e);
            report.put("status", "ERROR");
            report.put("error", e.getMessage());
        }

        return report;
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) : text;
    }
}
