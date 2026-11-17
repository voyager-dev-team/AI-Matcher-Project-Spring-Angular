package com.voyager.sesmatcher.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "ses.filter")
public class SesFilterProperties {

    /**
     * Senders or domains to exclude (e.g., newsletters, calendar, automated tools)
     */
    private List<String> excludeSenders = new ArrayList<>(List.of(
            "noreply", "no-reply", "notifications@", "calendar-notification",
            "mailer-daemon", "github.com", "gitlab.com", "microsoft.com",
            "udemy.com", "coursera.org", "connpass.com", "techplay.jp", "wantedly.com", "green-japan.com"
    ));

    /**
     * Subject keywords that indicate non-project/non-candidate emails
     */
    private List<String> excludeSubjectKeywords = new ArrayList<>(List.of(
            "招待:", "招待 - 更新:", "承諾:", "辞退:", "仮承諾:",
            "invitation:", "accepted:", "declined:", "tentative:", "updated invitation:", "canceled event:",
            "google calendar", "zoom", "meeting notes", "メモ: 「", "議事録", "ミーティングメモ",
            "copilot", "course is ready", "受講", "セミナー", "ウェビナー", "ニュースレター", "メルマガ",
            "【提案下書き】"
    ));

    /**
     * Candidate subject keywords accepted for monitoring
     */
    private List<String> candidateSubjectKeywords = new ArrayList<>(List.of(
            "[SES AI Matcher]人材→案件探す",
            "【人材情報】",
            "【要員情報】",
            "【技術者情報】",
            "【エンジニア提案】",
            "スキルシート",
            "職務経歴書"
    ));

    /**
     * Minimum structural indicator count required to classify an email body as SES project
     */
    private int minProjectStructuralIndicators = 3;

    /**
     * Minimum character length of project email body
     */
    private int minProjectBodyLength = 80;
}
