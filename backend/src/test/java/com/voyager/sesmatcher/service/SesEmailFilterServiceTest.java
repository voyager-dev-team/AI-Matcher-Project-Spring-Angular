package com.voyager.sesmatcher.service;

import com.voyager.sesmatcher.config.SesFilterProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SesEmailFilterServiceTest {

    private SesEmailFilterService filterService;
    private SesFilterProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SesFilterProperties();
        filterService = new SesEmailFilterService(properties);
    }

    @Test
    @DisplayName("1. Copilot notifications must be excluded")
    void testCopilotNotificationExcluded() {
        String subject = "Copilot Chat をさらに活用する - Word、Excel、PowerPoint、Outlook などで利用可能になりました";
        String sender = "microsoft-noreply@microsoft.com";
        String body = "Copilot Chat は日々の業務を支援します。プログラミングのスキルアップやコード補完も可能です。";

        assertTrue(filterService.isExcludedEmail(subject, sender, body, Collections.emptyList()));
        assertFalse(filterService.isLegitimateProjectEmail(subject, sender, body, Collections.emptyList()));
    }

    @Test
    @DisplayName("2. Calendar invitations must be excluded")
    void testCalendarInvitationExcluded() {
        String subject = "招待:  1on1(シャインさん) - 2026年 2月 3日 (火) 午後3時 ～ 午後3:30 (JST)";
        String sender = "calendar-notification@google.com";
        String body = "この予定は Google カレンダーを通じて送信されました。";
        List<String> attachments = List.of("invite.ics");

        assertTrue(filterService.isExcludedEmail(subject, sender, body, attachments));
        assertFalse(filterService.isLegitimateProjectEmail(subject, sender, body, attachments));
    }

    @Test
    @DisplayName("3. Meeting notes (議事録・メモ) must be excluded")
    void testMeetingNotesExcluded() {
        String subject = "メモ: 「開発MTG(PH2)」（2026年7月29日）";
        String sender = "sales-rep@voyager-inc.com";
        String body = "本日の定例開発MTGのメモです。\n- Java Spring Bootの進捗確認\n- リリース日の調整";

        assertTrue(filterService.isExcludedEmail(subject, sender, body, Collections.emptyList()));
        assertFalse(filterService.isLegitimateProjectEmail(subject, sender, body, Collections.emptyList()));
    }

    @Test
    @DisplayName("4. Course & webinar notifications must be excluded")
    void testCourseNotificationExcluded() {
        String subject = "Wanna, your course is ready — start learning now";
        String sender = "no-reply@e.udemymail.com";
        String body = "Your Java & Spring Boot Full Stack course is now available. Sharpen your development skills today!";

        assertTrue(filterService.isExcludedEmail(subject, sender, body, Collections.emptyList()));
        assertFalse(filterService.isLegitimateProjectEmail(subject, sender, body, Collections.emptyList()));
    }

    @Test
    @DisplayName("5. Technical keywords alone without SES structure must NOT be classified as project")
    void testTechnicalKeywordsAloneRejected() {
        String subject = "社内スキル共有会のお知らせ";
        String sender = "member@voyager-inc.com";
        String body = "来週の金曜日に社内でJava, Python, AWS, Dockerに関する勉強会を開催します。興味のある方はご参加ください。";

        assertFalse(filterService.isLegitimateProjectEmail(subject, sender, body, Collections.emptyList()));
    }

    @Test
    @DisplayName("6. Candidate inquiry email itself must NOT be classified as a project")
    void testCandidateInquiryNotProject() {
        String subject = "[SES AI Matcher]人材→案件探す";
        String sender = "partner@agent.jp";
        String body = "<div dir=\"ltr\"><br></div>";

        assertFalse(filterService.isLegitimateProjectEmail(subject, sender, body, List.of("職務経歴書.xlsx")));
    }

    @Test
    @DisplayName("7. Legitimate standard SES project email must be accepted")
    void testLegitimateProjectEmailAccepted() {
        String subject = "Java・Spring Boot開発案件";
        String sender = "client-sales@partner-it.co.jp";
        String body = """
            SWN様
            いつもお世話になっております。株式会社ITパートナーです。
            下記案件について、人材のご紹介が可能かご確認をお願いいたします。

            ■ 案件名
            Java／Spring Bootを使用したWebシステム開発

            ■ 業務内容
            ・Java、Spring Bootを使用したWebアプリケーションの開発
            ・REST APIの設計、開発
            ・単体テスト、結合テストの実施

            ■ 必須スキル
            ・Javaでの開発経験2年以上
            ・Spring Bootの使用経験
            ・SQLの基本的な知識

            ■ 勤務地
            東京都内（週2日リモートワーク可）

            ■ 契約形態
            SES

            ■ 単価
            月額60万～70万円（スキル見合い）

            ■ 期間
            2026年11月開始予定（長期）

            ■ 面談
            2回
            """;

        assertFalse(filterService.isExcludedEmail(subject, sender, body, Collections.emptyList()));
        assertTrue(filterService.isLegitimateProjectEmail(subject, sender, body, Collections.emptyList()));
    }

    @Test
    @DisplayName("8. Legitimate SES project email with DIFFERENT subject line must be accepted")
    void testLegitimateProjectEmailDifferentSubjectAccepted() {
        String subject = "【急募】大手ECサイトリニューアル・バックエンド開発要員募集";
        String sender = "partner-recruit@agency.co.jp";
        String body = """
            お世話になっております。急募案件のご案内です。

            【案件名】大手ECリニューアルPJ
            【業務内容】マイクロサービスの設計・製造・テスト
            【必須要件】Go言語またはJavaの実務経験3年以上
            【就業場所】フルリモート（在宅勤務）
            【稼働時期】即日または翌月開始
            【単金】80万～90万／月
            【商談】Web面談1回
            """;

        assertFalse(filterService.isExcludedEmail(subject, sender, body, Collections.emptyList()));
        assertTrue(filterService.isLegitimateProjectEmail(subject, sender, body, Collections.emptyList()));
    }

    @Test
    @DisplayName("9. Legitimate candidate email with resume attachment must be accepted")
    void testLegitimateCandidateEmailAccepted() {
        String subject = "[SES AI Matcher]人材→案件探す";
        String sender = "talent-source@agency.com";
        String body = "弊社所属のエンジニアの経歴書をお送りいたします。よろしくお願いいたします。";
        Map<String, byte[]> attachments = Map.of("職務経歴書_Tanaka.xlsx", "Sample resume content".getBytes());

        assertTrue(filterService.isLegitimateCandidateEmail(subject, sender, body, attachments));
    }

    @Test
    @DisplayName("10. Legitimate candidate email with different subject must be accepted")
    void testLegitimateCandidateEmailDifferentSubjectAccepted() {
        String subject = "【要員情報】Java/AWSシニアエンジニアのご提案（即日稼働可）";
        String sender = "partner@subcontractor.com";
        String body = "お世話になっております。即日稼働可能な技術者情報をお送りいたします。";
        Map<String, byte[]> attachments = Map.of("スキルシート_Yamada.pdf", "Sample content".getBytes());

        assertTrue(filterService.isLegitimateCandidateEmail(subject, sender, body, attachments));
    }
}
