package com.voyager.sesmatcher.controller;

import com.voyager.sesmatcher.dto.AiMatchResponse;
import com.voyager.sesmatcher.dto.CandidateSummaryDto;
import com.voyager.sesmatcher.entity.MatchRecord;
import com.voyager.sesmatcher.repository.MatchRecordRepository;
import com.voyager.sesmatcher.scheduler.CandidateMonitoringScheduler;
import com.voyager.sesmatcher.service.AuditLogService;
import com.voyager.sesmatcher.service.CandidateStatusService;
import com.voyager.sesmatcher.service.GeminiMatchingService;
import com.voyager.sesmatcher.service.GmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/matches")
@RequiredArgsConstructor
public class MatchController {

    private final MatchRecordRepository matchRecordRepository;
    private final CandidateMonitoringScheduler scheduler;
    private final GmailService gmailService;
    private final CandidateStatusService candidateStatusService;
    private final GeminiMatchingService geminiMatchingService;
    private final AuditLogService auditLogService;

    @Value("${ses.matcher.threshold:60}")
    private int threshold;

    @GetMapping
    public ResponseEntity<List<MatchRecord>> getAllMatches() {
        return ResponseEntity.ok(ensureConditionCheckResults(
                matchRecordRepository.findAllByOrderByReceivedAtDescCreatedAtDesc()
        ));
    }

    @GetMapping("/high-fit")
    public ResponseEntity<List<MatchRecord>> getHighFitMatches() {
        return ResponseEntity.ok(ensureConditionCheckResults(
                matchRecordRepository.findByFitRateGreaterThanEqualOrderByReceivedAtDescCreatedAtDesc(threshold)
        ));
    }

    /**
     * Returns a summary list of all registered candidate resumes (e.g., 職務経歴書(S.W), 職務経歴書(H.H))
     * along with their current placement status (SEARCHING / ASSIGNED).
     */
    @GetMapping("/candidates")
    public ResponseEntity<List<CandidateSummaryDto>> getCandidates() {
        List<MatchRecord> allRecords = matchRecordRepository.findAllByOrderByCreatedAtDesc();

        // Group by candidateFileName (preserving insertion order of most recent first)
        Map<String, List<MatchRecord>> grouped = new LinkedHashMap<>();
        for (MatchRecord r : allRecords) {
            String key = r.getCandidateFileName() != null ? r.getCandidateFileName() : "不明な経歴書";
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            candidateStatusService.registerCandidateMetadata(key, r.getCandidateEmailId(), r.getCandidateSubject());
        }

        List<CandidateSummaryDto> summaries = new ArrayList<>();
        Set<String> includedFiles = new HashSet<>();

        for (Map.Entry<String, List<MatchRecord>> entry : grouped.entrySet()) {
            String fileName = entry.getKey();
            includedFiles.add(fileName);
            List<MatchRecord> records = entry.getValue();
            MatchRecord first = records.get(0);

            int totalMatches = records.size();
            int highFitMatches = (int) records.stream()
                    .filter(r -> r.getFitRate() != null && r.getFitRate() >= threshold)
                    .count();
            int draftsCreated = (int) records.stream()
                    .filter(r -> Boolean.TRUE.equals(r.getDraftCreated()))
                    .count();
            int maxFitRate = records.stream()
                    .mapToInt(r -> r.getFitRate() != null ? r.getFitRate() : 0)
                    .max()
                    .orElse(0);
            LocalDateTime latestAt = records.stream()
                    .map(MatchRecord::getCreatedAt)
                    .filter(Objects::nonNull)
                    .max(LocalDateTime::compareTo)
                    .orElse(null);

            String status = candidateStatusService.getStatus(fileName);
            boolean assigned = CandidateStatusService.STATUS_ASSIGNED.equalsIgnoreCase(status);

            summaries.add(CandidateSummaryDto.builder()
                    .candidateEmailId(first.getCandidateEmailId())
                    .candidateFileName(fileName)
                    .displayName(stripExtension(fileName))
                    .candidateSubject(first.getCandidateSubject())
                    .totalMatches(totalMatches)
                    .highFitMatches(highFitMatches)
                    .draftsCreated(draftsCreated)
                    .maxFitRate(maxFitRate)
                    .latestMatchedAt(latestAt)
                    .status(status)
                    .assigned(assigned)
                    .build());
        }

        // Also include any candidates persisted in candidate-status.json that had matching skipped
        for (CandidateStatusService.CandidateState state : candidateStatusService.getAllStates()) {
            String fileName = state.getCandidateFileName();
            if (fileName != null && !includedFiles.contains(fileName)) {
                String status = state.getStatus() != null ? state.getStatus() : CandidateStatusService.STATUS_SEARCHING;
                boolean assigned = CandidateStatusService.STATUS_ASSIGNED.equalsIgnoreCase(status);
                summaries.add(CandidateSummaryDto.builder()
                        .candidateEmailId(state.getCandidateEmailId())
                        .candidateFileName(fileName)
                        .displayName(stripExtension(fileName))
                        .candidateSubject(state.getCandidateSubject())
                        .totalMatches(0)
                        .highFitMatches(0)
                        .draftsCreated(0)
                        .maxFitRate(0)
                        .latestMatchedAt(null)
                        .status(status)
                        .assigned(assigned)
                        .build());
            }
        }

        return ResponseEntity.ok(summaries);
    }

    /**
     * Updates a candidate's status between SEARCHING (🟢 案件探し中) and ASSIGNED (🔴 現場決定済).
     */
    @PutMapping("/candidates/status")
    public ResponseEntity<Map<String, Object>> updateCandidateStatus(@RequestBody Map<String, String> payload) {
        String candidateFileName = payload.get("candidateFileName");
        String status = payload.get("status");
        String candidateEmailId = payload.get("candidateEmailId");
        String candidateSubject = payload.get("candidateSubject");

        if (candidateFileName == null || candidateFileName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "candidateFileName is required"));
        }

        // If status not explicitly provided, toggle current status
        if (status == null || status.isBlank()) {
            status = candidateStatusService.isAssigned(candidateFileName)
                    ? CandidateStatusService.STATUS_SEARCHING
                    : CandidateStatusService.STATUS_ASSIGNED;
        }

        CandidateStatusService.CandidateState updated = candidateStatusService.updateStatus(
                candidateFileName,
                status,
                candidateEmailId,
                candidateSubject
        );

        boolean isAssigned = CandidateStatusService.STATUS_ASSIGNED.equalsIgnoreCase(updated.getStatus());
        auditLogService.recordLog(
                "候補者ステータス変更",
                String.format("候補者: %s → %s",
                        updated.getCandidateFileName(),
                        isAssigned ? "🔴 現場決定済（マッチング停止）" : "🟢 案件探し中（マッチング再開）")
        );

        Map<String, Object> result = new HashMap<>();
        result.put("candidateFileName", updated.getCandidateFileName());
        result.put("status", updated.getStatus());
        result.put("assigned", isAssigned);
        return ResponseEntity.ok(result);
    }

    /**
     * Moves the candidate's original email to Gmail Trash and removes the candidate from the list.
     */
    @DeleteMapping("/candidates")
    public ResponseEntity<Map<String, Object>> deleteCandidateEmail(
            @RequestParam(value = "candidateEmailId", required = false) String candidateEmailId,
            @RequestParam(value = "candidateFileName", required = false) String candidateFileName
    ) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (candidateEmailId != null && !candidateEmailId.isBlank()) {
                gmailService.trashMessage(candidateEmailId);
                matchRecordRepository.deleteByCandidateEmailId(candidateEmailId);
            }
            if (candidateFileName != null && !candidateFileName.isBlank()) {
                matchRecordRepository.deleteByCandidateFileName(candidateFileName);
            }
            candidateStatusService.removeCandidate(candidateFileName, candidateEmailId);

            auditLogService.recordLog(
                    "人材メール削除",
                    String.format("候補者: %s (メールID: %s) をゴミ箱へ移動",
                            candidateFileName != null ? candidateFileName : "不明",
                            candidateEmailId != null ? candidateEmailId : "—")
            );

            response.put("status", "DELETED");
            response.put("candidateEmailId", candidateEmailId);
            response.put("candidateFileName", candidateFileName);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("status", "ERROR");
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Returns match records for a specific candidate resume ordered by receivedAt descending
     */
    @GetMapping("/by-candidate")
    public ResponseEntity<List<MatchRecord>> getMatchesByCandidate(
            @RequestParam("fileName") String fileName,
            @RequestParam(value = "candidateEmailId", required = false) String candidateEmailId
    ) {
        if (candidateEmailId != null && !candidateEmailId.isBlank()) {
            List<MatchRecord> byBoth = matchRecordRepository
                    .findByCandidateEmailIdAndCandidateFileNameOrderByReceivedAtDescCreatedAtDesc(candidateEmailId, fileName);
            if (!byBoth.isEmpty()) {
                return ResponseEntity.ok(ensureConditionCheckResults(byBoth));
            }
        }
        return ResponseEntity.ok(ensureConditionCheckResults(
                matchRecordRepository.findByCandidateFileNameOrderByReceivedAtDescCreatedAtDesc(fileName)
        ));
    }

    @PostMapping("/trigger-sync")
    public ResponseEntity<Map<String, Object>> triggerManualSync() {
        auditLogService.recordLog("案件メール再取得・手動同期", "ダッシュボードから手動同期（Gmail 監視 & AI マッチング）を実行");
        Map<String, Object> result = scheduler.runJobNow();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSystemStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("gmailConnected", gmailService.isConnected());
        status.put("salesEmail", gmailService.getActiveSalesEmail());
        status.put("threshold", threshold);
        status.put("totalRecords", matchRecordRepository.count());
        status.put("highFitRecords", matchRecordRepository.findByFitRateGreaterThanEqualOrderByCreatedAtDesc(threshold).size());
        return ResponseEntity.ok(status);
    }

    private List<MatchRecord> ensureConditionCheckResults(List<MatchRecord> records) {
        for (MatchRecord r : records) {
            if (r.getConditionCheckResult() == null) {
                AiMatchResponse evaluated = geminiMatchingService.evaluateHeuristically(
                        r.getCandidateParsedText(),
                        r.getProjectBody(),
                        r.getCandidateFileName()
                );
                r.setConditionCheckResult(evaluated.getConditionCheckResult());
                matchRecordRepository.save(r);
            }
        }
        return records;
    }

    private String stripExtension(String fileName) {
        if (fileName == null) return "";
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }
}
