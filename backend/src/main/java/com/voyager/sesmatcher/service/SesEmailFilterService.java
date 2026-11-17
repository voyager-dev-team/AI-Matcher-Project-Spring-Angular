package com.voyager.sesmatcher.service;

import com.voyager.sesmatcher.config.SesFilterProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class SesEmailFilterService {

    private final SesFilterProperties filterProperties;

    // Pattern to strip HTML tags
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]*>");

    /**
     * Checks if an email is an unrelated message (e.g. newsletter, calendar invite, meeting note, copilot notification)
     */
    public boolean isExcludedEmail(String subject, String sender, String body, List<String> attachmentNames) {
        String safeSubject = subject != null ? subject.toLowerCase() : "";
        String safeSender = sender != null ? sender.toLowerCase() : "";
        String plainBody = stripHtml(body).toLowerCase();

        // 1. Sender validation
        for (String excludedSender : filterProperties.getExcludeSenders()) {
            if (safeSender.contains(excludedSender.toLowerCase())) {
                log.debug("Excluding email by sender [{}] matching rule [{}]", sender, excludedSender);
                return true;
            }
        }

        // 2. Subject keyword validation
        for (String keyword : filterProperties.getExcludeSubjectKeywords()) {
            if (safeSubject.contains(keyword.toLowerCase())) {
                log.debug("Excluding email by subject keyword [{}] in [{}]", keyword, subject);
                return true;
            }
        }

        // 3. Calendar attachment validation
        if (attachmentNames != null) {
            for (String name : attachmentNames) {
                if (name != null && name.toLowerCase().endsWith(".ics")) {
                    log.debug("Excluding email with iCalendar attachment: {}", name);
                    return true;
                }
            }
        }

        // 4. Copilot / automated notification markers in body
        if (safeSubject.contains("copilot") || plainBody.contains("github copilot") || plainBody.contains("microsoft copilot")) {
            if (!isActualSesContext(plainBody)) {
                log.debug("Excluding email containing Copilot notification: {}", subject);
                return true;
            }
        }

        return false;
    }

    /**
     * Validates whether an email is a genuine SES Project Proposal Email (案件情報メール).
     * Rejects emails that merely contain technical keywords or casual mentions.
     */
    public boolean isLegitimateProjectEmail(String subject, String sender, String body, List<String> attachmentNames) {
        if (isExcludedEmail(subject, sender, body, attachmentNames)) {
            return false;
        }

        String safeSubject = subject != null ? subject : "";
        String plainBody = stripHtml(body);

        // Disallow candidate submission emails from being treated as projects
        if (safeSubject.contains("[SES AI Matcher]人材→案件探す") ||
            safeSubject.contains("職務経歴書のご送付") ||
            safeSubject.contains("【要員提案】") ||
            safeSubject.contains("【技術者提案】")) {
            log.debug("Rejecting candidate email from project classification: {}", subject);
            return false;
        }

        // Must have non-trivial body length
        if (plainBody.trim().length() < filterProperties.getMinProjectBodyLength()) {
            log.debug("Rejecting email with insufficient body length ({} chars): {}", plainBody.trim().length(), subject);
            return false;
        }

        // Check SES structural indicators
        int matchedGroups = countProjectStructuralGroups(plainBody, safeSubject);

        // Subject with clear project brackets requires at least 2 groups; otherwise requires 3
        boolean hasExplicitProjectSubject = safeSubject.contains("【案件】") ||
                safeSubject.contains("[案件]") ||
                safeSubject.contains("【案件情報】") ||
                safeSubject.contains("【要員募集】") ||
                safeSubject.contains("案件のご案内") ||
                safeSubject.contains("開発案件");

        int threshold = hasExplicitProjectSubject ? 2 : filterProperties.getMinProjectStructuralIndicators();

        boolean isProject = matchedGroups >= threshold;
        if (!isProject) {
            log.debug("Email [{}] matched only {} structural groups (needed >= {}). Not a project email.",
                    subject, matchedGroups, threshold);
        }
        return isProject;
    }

    /**
     * Validates whether an email is a genuine SES Candidate Profile Email (人材情報・職務経歴書).
     */
    public boolean isLegitimateCandidateEmail(String subject, String sender, String body, Map<String, byte[]> attachments) {
        if (isExcludedEmail(subject, sender, body, attachments != null ? new ArrayList<>(attachments.keySet()) : null)) {
            return false;
        }

        String safeSubject = subject != null ? subject : "";
        String plainBody = stripHtml(body);

        // 1. Check if explicitly marked with candidate subject filter
        if (safeSubject.contains("[SES AI Matcher]人材→案件探す")) {
            return true;
        }

        // 2. Check attachments for resume keywords
        if (attachments != null && !attachments.isEmpty()) {
            for (String fileName : attachments.keySet()) {
                if (fileName == null) continue;
                String lower = fileName.toLowerCase();
                boolean isDocument = lower.endsWith(".pdf") || lower.endsWith(".xlsx") ||
                        lower.endsWith(".xls") || lower.endsWith(".docx") || lower.endsWith(".doc");

                if (isDocument) {
                    if (lower.contains("経歴書") || lower.contains("履歴書") || lower.contains("スキルシート") ||
                        lower.contains("resume") || lower.contains("cv") || lower.contains("職歴") ||
                        safeSubject.contains("人材") || safeSubject.contains("要員") || safeSubject.contains("技術者")) {
                        return true;
                    }
                }
            }
        }

        // 3. Check for body-based candidate profile markers (for emails without attachments)
        boolean hasNameOrAge = plainBody.contains("氏名") || plainBody.contains("イニシャル") ||
                plainBody.contains("年齢") || plainBody.contains("性別") || plainBody.contains("国籍");
        boolean hasSkill = plainBody.contains("スキル") || plainBody.contains("得意言語") || plainBody.contains("技術経験");
        boolean hasAvailability = plainBody.contains("稼働") || plainBody.contains("最寄駅") ||
                plainBody.contains("単価") || plainBody.contains("経歴");

        return hasNameOrAge && hasSkill && hasAvailability;
    }

    /**
     * Counts how many distinct SES project structural groups appear in the text
     */
    public int countProjectStructuralGroups(String body, String subject) {
        String text = (subject + "\n" + body).toLowerCase();
        int count = 0;

        // Group 1: Project Title / Task description
        if (containsAny(text, "案件名", "案件概要", "業務内容", "作業内容", "プロジェクト名", "【案件】", "【案件名】", "案件:", "案件内容", "システム開発")) {
            count++;
        }

        // Group 2: Skills & Requirements
        if (containsAny(text, "必須スキル", "スキル要件", "必須要件", "求めるスキル", "応募要件", "スキル:", "【必須】", "■ 必須", "必須:", "必須条件")) {
            count++;
        }

        // Group 3: Location / Workstyle
        if (containsAny(text, "勤務地", "就業場所", "作業場所", "最寄駅", "リモート", "テレワーク", "在宅", "出社", "ハイブリッド")) {
            count++;
        }

        // Group 4: Period / Timing
        if (containsAny(text, "期間", "稼働時期", "開始時期", "入場時期", "即日", "工期", "期間:", "■ 期間", "開始予定")) {
            count++;
        }

        // Group 5: Rate / Contract / Headcount
        if (containsAny(text, "単価", "精算", "精算幅", "単金", "契約形態", "報酬", "募集人数", "枠数", "ses")) {
            count++;
        }

        // Group 6: Interview
        if (containsAny(text, "面談", "面談回数", "web面談", "商談")) {
            count++;
        }

        return count;
    }

    private boolean isActualSesContext(String plainBody) {
        return plainBody.contains("案件名") && (plainBody.contains("必須スキル") || plainBody.contains("単価"));
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    public String stripHtml(String html) {
        if (html == null) return "";
        return HTML_TAG_PATTERN.matcher(html)
                .replaceAll(" ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
