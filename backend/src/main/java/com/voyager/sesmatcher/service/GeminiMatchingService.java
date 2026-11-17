package com.voyager.sesmatcher.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyager.sesmatcher.dto.AiMatchResponse;
import com.voyager.sesmatcher.dto.ConditionCheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiMatchingService {

    @Value("${ses.gemini.api-key}")
    private String apiKey;

    @Value("${ses.gemini.model:gemini-1.5-flash}")
    private String model;

    @Value("${ses.gemini.api-url}")
    private String apiUrlTemplate;

    private final OAuthSessionTokenService oauthSessionTokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    private static final Pattern AGE_PATTERN = Pattern.compile("(\\d{2})\\s*歳");
    private static final Pattern AGE_LIMIT_PATTERN = Pattern.compile("(?:上限|〜|~|まで|以下)\\s*(\\d{2})\\s*歳|(\\d{2})\\s*歳\\s*(?:まで|以下|上限)|(\\d)0代(?:まで|以下)");
    private static final Pattern REQUIRED_EXP_PATTERN = Pattern.compile("(?:必須|経験|実務)[^\\n。、]{0,12}?(\\d{1,2})\\s*年\\s*以上");
    private static final Pattern CANDIDATE_EXP_PATTERN = Pattern.compile("(?:経験|実務|開発経験|キャリア)[^\\n。、]{0,12}?(\\d{1,2})\\s*年");
    private static final Pattern PRICE_PATTERN = Pattern.compile("(\\d{2,3})\\s*(?:〜|~|-|－)?\\s*(\\d{2,3})?\\s*万");
    private static final Pattern COMMUTE_MIN_PATTERN = Pattern.compile("通勤[^\\n。、]{0,6}?(\\d{2,3})\\s*分");

    /**
     * Sends Engineer Resume Text and Project Details to Gemini API
     * and receives structured JSON including Tier 1 ~ Tier 4 conditionCheckResult.
     */
    public AiMatchResponse evaluateMatching(String candidateResumeText, String projectDetails, String candidateName) {
        if (apiKey != null && !apiKey.trim().isEmpty() && !apiKey.contains("YOUR_GEMINI_API_KEY")) {
            try {
                AiMatchResponse response = callGeminiApi(candidateResumeText, projectDetails, candidateName);
                return normalizeResponse(response, candidateResumeText, projectDetails, candidateName);
            } catch (Exception e) {
                log.error("Gemini API call failed: {}. Falling back to domain heuristic evaluation.", e.getMessage());
            }
        } else {
            log.warn("Gemini API key is not configured. Running domain heuristic evaluation.");
        }

        return evaluateHeuristically(candidateResumeText, projectDetails, candidateName);
    }

    private AiMatchResponse callGeminiApi(String resumeText, String projectDetails, String candidateName) throws Exception {
        String systemInstruction = """
            あなたは日本のIT・SES（システムエンジニアリングサービス）業界におけるトップクラスの営業マッチングスペシャリストです。
            提示された「技術者の職務経歴書（スキルシート）」と「案件メールの募集要項」を照合・精査し、以下の【Tier 1〜Tier 4】の4段階評価基準に基づいて適合度と提案文を評価してください。

            【SES 4段階条件判定基準（Tier 1〜Tier 4）】
            - 【Tier 1（絶対足切り）】：国籍/日本語力制約（外国籍不可・日本国籍のみ・N1必須等）、年齢制限（例：45歳まで等）、必須実務経験年数。
              ※ 1つでも不一致の場合は即座に適合度 0% ("fit_rate": 0) とし、"fit_reason" および "tier1Details" に「足切り：〜〜」と明記し、"tier1Passed": false とすること。
            - 【Tier 2（稼働・勤務条件）】：稼働開始時期（即日/翌月1日〜等）、勤務形態（フルリモート/ハイブリッド/出社週3/常駐等）、勤務地・通勤時間。
            - 【Tier 3（商業条件）】：商流制限（プロパー限定/1社先まで等）、単価・予算レンジ（例：予算内(70万)/単価合致(75万/予算80万)等）、面談回数（1回/2回等）。
            - 【Tier 4（スキル適合）】：必須言語・フレームワーク、担当工程（要件定義・基本設計・詳細設計・開発・テスト・保守）、ドメイン知識によるスコアリング（0〜100%）。

            【出力必須要件】
            以下のJSONスキーマに従った純粋なJSONオブジェクトのみを出力してください（Markdownの記号やコードブロックは一切含めないこと）:
            {
              "fit_rate": 85,
              "fit_reason": "Java 17, Spring Boot, AWSの必須要件に合致。実務リーダー経験3年以上あり。勤務地（リモート併用）の確認が必要。",
              "proposal_draft": "ご担当者様\\n\\nいつも大変お世話になっております。株式会社VOYAGERの営業担当でございます。\\n...",
              "conditionCheckResult": {
                "tier1Passed": true,
                "tier1Details": "年齢OK(32歳/上限45歳)、経験OK(5年/必須3年)、国籍・日本語OK",
                "workCondition": "即日可 / ハイブリッド(週2出社)",
                "commercialCondition": "単価合致(75万/予算80万) / プロパー合致",
                "ngReasons": ["面談2回"]
              }
            }

            ※ Tier 1（絶対足切り）で不一致がある場合の出力例:
            {
              "fit_rate": 0,
              "fit_reason": "足切り：案件の年齢上限（40歳まで）に対し候補者が46歳のため不適合。",
              "proposal_draft": "",
              "conditionCheckResult": {
                "tier1Passed": false,
                "tier1Details": "足切り: 年齢上限超過(46歳/上限40歳)",
                "workCondition": "即日 / 出社週3",
                "commercialCondition": "予算内(70万)",
                "ngReasons": ["足切り: 年齢上限超過"]
              }
            }
            """;

        String userPrompt = String.format("""
            【技術者 職務経歴書情報 (候補: %s)】
            %s

            ====================================================
            【案件メール募集要件】
            %s
            """, candidateName, truncateText(resumeText, 3500), truncateText(projectDetails, 3500));

        Map<String, Object> requestBody = Map.of(
            "contents", List.of(
                Map.of("role", "user", "parts", List.of(
                    Map.of("text", systemInstruction + "\n\n" + userPrompt)
                ))
            ),
            "generationConfig", Map.of(
                "responseMimeType", "application/json",
                "temperature", 0.2
            )
        );

        String url = apiUrlTemplate.replace("{model}", model).replace("{key}", apiKey);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode root = objectMapper.readTree(response.getBody());
            String responseText = root.path("candidates").get(0)
                    .path("content").path("parts").get(0)
                    .path("text").asText();

            return objectMapper.readValue(responseText, AiMatchResponse.class);
        }

        throw new RuntimeException("Unexpected response from Gemini API: " + response.getStatusCode());
    }

    /**
     * Ensures that if Gemini API returned partial data or Tier 1 failed, consistency rules are enforced.
     */
    private AiMatchResponse normalizeResponse(AiMatchResponse response, String resumeText, String projectDetails, String candidateName) {
        if (response == null) {
            return evaluateHeuristically(resumeText, projectDetails, candidateName);
        }
        if (response.getConditionCheckResult() == null) {
            AiMatchResponse fallback = evaluateHeuristically(resumeText, projectDetails, candidateName);
            response.setConditionCheckResult(fallback.getConditionCheckResult());
        }
        ConditionCheckResult check = response.getConditionCheckResult();
        if (check != null && !check.isTier1Passed()) {
            response.setFitRate(0);
            if (response.getFitReason() == null || !response.getFitReason().startsWith("足切り")) {
                String detail = check.getTier1Details() != null ? check.getTier1Details() : "絶対条件不一致";
                response.setFitReason(detail.startsWith("足切り") ? detail : "足切り：" + detail);
            }
        }
        return response;
    }

    /**
     * Fallback heuristic evaluator that performs Tier 1 ~ Tier 4 SES condition analysis.
     */
    public AiMatchResponse evaluateHeuristically(String resumeText, String projectDetails, String candidateName) {
        String safeResume = resumeText != null ? resumeText : "";
        String safeProject = projectDetails != null ? projectDetails : "";
        String lowerResume = safeResume.toLowerCase();
        String lowerProject = safeProject.toLowerCase();

        List<String> ngReasons = new ArrayList<>();
        List<String> tier1PassNotes = new ArrayList<>();
        List<String> tier1FailNotes = new ArrayList<>();

        // ====================================================================
        // Tier 1: 絶対足切り (国籍/日本語力制約、年齢制限、実務経験年数)
        // ====================================================================
        boolean projectRestrictsForeign = safeProject.contains("外国籍不可")
                || safeProject.contains("日本国籍のみ")
                || safeProject.contains("日本人のみ")
                || safeProject.contains("国籍：日本");
        boolean candidateIsForeign = safeResume.contains("外国籍")
                || safeResume.contains("国籍：中国")
                || safeResume.contains("国籍：韓国")
                || safeResume.contains("国籍：ベトナム")
                || safeResume.contains("JLPT")
                || safeResume.contains("日本語能力試験");

        if (projectRestrictsForeign && candidateIsForeign) {
            tier1FailNotes.add("外国籍不可案件");
            ngReasons.add("足切り: 外国籍不可");
        } else {
            tier1PassNotes.add("国籍・日本語OK");
        }

        // Age check
        Integer candidateAge = extractCandidateAge(safeResume);
        Integer projectMaxAge = extractProjectMaxAge(safeProject);
        if (candidateAge != null && projectMaxAge != null) {
            if (candidateAge > projectMaxAge) {
                tier1FailNotes.add(String.format("年齢上限超過(%d歳/上限%d歳)", candidateAge, projectMaxAge));
                ngReasons.add(String.format("足切り: 年齢上限超過(%d歳/上限%d歳)", candidateAge, projectMaxAge));
            } else {
                tier1PassNotes.add(String.format("年齢OK(%d歳/上限%d歳)", candidateAge, projectMaxAge));
            }
        } else if (projectMaxAge != null) {
            tier1PassNotes.add(String.format("年齢制限(上限%d歳)", projectMaxAge));
        } else {
            tier1PassNotes.add("年齢OK");
        }

        // Required years of experience check
        Integer requiredExp = extractRequiredExpYears(safeProject);
        Integer candidateExp = extractCandidateExpYears(safeResume);
        if (requiredExp != null && candidateExp != null) {
            if (candidateExp < requiredExp) {
                tier1FailNotes.add(String.format("実務経験年数不足(経験%d年/必須%d年)", candidateExp, requiredExp));
                ngReasons.add(String.format("足切り: 経験年数不足(%d年/必須%d年)", candidateExp, requiredExp));
            } else {
                tier1PassNotes.add(String.format("経験OK(%d年/必須%d年)", candidateExp, requiredExp));
            }
        } else if (requiredExp != null) {
            tier1PassNotes.add(String.format("経験OK(必須%d年以上)", requiredExp));
        } else {
            tier1PassNotes.add("経験年数OK");
        }

        boolean tier1Passed = tier1FailNotes.isEmpty();
        String tier1Details = tier1Passed
                ? String.join("、", tier1PassNotes)
                : "足切り: " + String.join(" / ", tier1FailNotes);

        // ====================================================================
        // Tier 2: 稼働・勤務条件 (稼働開始時期、勤務形態、勤務地・通勤時間)
        // ====================================================================
        String startTiming = extractStartTiming(safeProject, safeResume);
        String workStyle = extractWorkStyle(safeProject);
        String workCondition = startTiming + " / " + workStyle;

        Integer commuteMinutes = extractCommuteMinutes(safeResume, safeProject);
        if (commuteMinutes != null && commuteMinutes >= 90) {
            ngReasons.add("通勤" + commuteMinutes + "分");
        } else if (safeProject.contains("常駐") && safeResume.contains("フルリモート希望")) {
            ngReasons.add("常駐案件(リモート希望要確認)");
        }

        // ====================================================================
        // Tier 3: 商業条件 (商流制限、単価・予算レンジ、面談回数)
        // ====================================================================
        String priceInfo = extractPriceCondition(safeProject);
        String flowInfo = extractCommercialFlow(safeProject);
        String commercialCondition = priceInfo + " / " + flowInfo;

        if (safeProject.contains("面談2回") || safeProject.contains("面談：2回") || safeProject.contains("面談: 2回") || safeProject.contains("2回")) {
            if (safeProject.contains("面談")) {
                ngReasons.add("面談2回");
            }
        }
        if (safeProject.contains("プロパーのみ") || safeProject.contains("プロパー限定") || safeProject.contains("貴社社員のみ")) {
            ngReasons.add("プロパー限定要確認");
        }

        // ====================================================================
        // Tier 4: スキル適合 (必須言語・FW、担当工程、ドメイン知識)
        // ====================================================================
        List<String> keywords = List.of(
            "java", "spring", "angular", "react", "vue", "typescript", "python", "go", "kotlin", "c#", "php",
            "aws", "azure", "gcp", "docker", "kubernetes", "terraform", "sql", "mysql", "postgresql", "oracle",
            "設計", "要件定義", "基本設計", "詳細設計", "開発", "テスト", "リーダー", "リモート", "api", "マイクロサービス"
        );

        int matchedCount = 0;
        List<String> matchedKeywords = new ArrayList<>();
        for (String kw : keywords) {
            if (lowerProject.contains(kw) && lowerResume.contains(kw)) {
                matchedCount++;
                matchedKeywords.add(kw);
            }
        }

        int score;
        String reason;
        String matchedList = matchedKeywords.isEmpty() ? "一般Web開発スキル" : String.join(", ", matchedKeywords);

        if (!tier1Passed) {
            score = 0;
            reason = "足切り：" + String.join("、", tier1FailNotes) + " のため不適合（Tier 1 絶対条件不一致）。";
        } else {
            score = Math.min(95, 45 + (matchedCount * 10));
            if (matchedKeywords.isEmpty()) {
                reason = String.format("【Tier1通過: %s】基本スキルの合致が限定的です。経験言語・工程の詳細確認が推奨されます。", tier1Details);
                ngReasons.add("主要スキル要確認");
            } else {
                reason = String.format("【Tier1通過: %s】主要スキル（%s）が一致。稼働（%s）・商業条件（%s）ともに適合性が認められます。",
                        tier1Details, matchedList, workCondition, commercialCondition);
            }
        }

        ConditionCheckResult conditionCheckResult = ConditionCheckResult.builder()
                .tier1Passed(tier1Passed)
                .tier1Details(tier1Details)
                .workCondition(workCondition)
                .commercialCondition(commercialCondition)
                .ngReasons(ngReasons)
                .build();

        String currentEmail = oauthSessionTokenService.getCurrentSalesEmail();
        String signatureEmail = (currentEmail != null && !currentEmail.isBlank()) ? currentEmail : "営業担当者";

        String draft = "";
        if (tier1Passed) {
            draft = String.format("""
                ご担当者様

                いつも大変お世話になっております。
                株式会社VOYAGER 営業担当でございます。

                標記の案件（%s）につきまして、
                弊社よりマッチ度の高い技術者（%s 候補）をご提案可能でございます。

                【技術者概要・条件照合】
                ・必須条件(Tier 1): %s
                ・稼働/勤務条件: %s
                ・商業条件: %s
                ・得意領域 / スキル: %s
                ・適合度判定: %d%%

                つきましては、職務経歴書を添付にて送付させていただければと存じます。
                ご検討のほど、何卒よろしくお願い申し上げます。

                --------------------------------------------------
                株式会社VOYAGER
                営業担当: %s
                --------------------------------------------------
                """,
                truncateText(safeProject.split("\n")[0], 50),
                candidateName,
                tier1Details,
                workCondition,
                commercialCondition,
                matchedList,
                score,
                signatureEmail
            );
        }

        return AiMatchResponse.builder()
                .fitRate(score)
                .fitReason(reason)
                .proposalDraft(draft)
                .conditionCheckResult(conditionCheckResult)
                .build();
    }

    private Integer extractCandidateAge(String text) {
        Matcher m = AGE_PATTERN.matcher(text);
        if (m.find()) {
            try {
                int age = Integer.parseInt(m.group(1));
                if (age >= 18 && age <= 75) {
                    return age;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private Integer extractProjectMaxAge(String text) {
        Matcher m = AGE_LIMIT_PATTERN.matcher(text);
        if (m.find()) {
            try {
                if (m.group(1) != null) return Integer.parseInt(m.group(1));
                if (m.group(2) != null) return Integer.parseInt(m.group(2));
                if (m.group(3) != null) return Integer.parseInt(m.group(3)) * 10 + 9;
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private Integer extractRequiredExpYears(String text) {
        Matcher m = REQUIRED_EXP_PATTERN.matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private Integer extractCandidateExpYears(String text) {
        Matcher m = CANDIDATE_EXP_PATTERN.matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private String extractStartTiming(String projectText, String resumeText) {
        if (projectText.contains("即日") || resumeText.contains("即日")) {
            return "🚀 即日";
        }
        if (projectText.contains("翌月") || projectText.contains("来月")) {
            return "📅 翌月1日〜";
        }
        Matcher monthMatcher = Pattern.compile("(\\d{1,2}月)\\s*(?:〜|~|から|開始|稼働)").matcher(projectText);
        if (monthMatcher.find()) {
            return "📅 " + monthMatcher.group(1) + "〜";
        }
        return "🚀 即日〜応相談";
    }

    private String extractWorkStyle(String projectText) {
        if (projectText.contains("フルリモート") || projectText.contains("完全リモート") || projectText.contains("完全在宅")) {
            return "🏠 フルリモート";
        }
        if (projectText.contains("週1日出社") || projectText.contains("週1出社")) {
            return "🏢 出社週1(リモート併用)";
        }
        if (projectText.contains("週2日リモート") || projectText.contains("週3日出社") || projectText.contains("週3出社")) {
            return "🏢 出社週3";
        }
        if (projectText.contains("週2日出社") || projectText.contains("週2出社") || projectText.contains("リモートワーク可") || projectText.contains("リモート可") || projectText.contains("テレワーク")) {
            return "🏢 ハイブリッド(リモート併用)";
        }
        if (projectText.contains("常駐") || projectText.contains("原則出社") || projectText.contains("フル出社")) {
            return "🏢 常駐(出社)";
        }
        return "🏢 ハイブリッド";
    }

    private Integer extractCommuteMinutes(String resumeText, String projectText) {
        Matcher m1 = COMMUTE_MIN_PATTERN.matcher(resumeText);
        if (m1.find()) {
            try {
                return Integer.parseInt(m1.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        Matcher m2 = COMMUTE_MIN_PATTERN.matcher(projectText);
        if (m2.find()) {
            try {
                return Integer.parseInt(m2.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private String extractPriceCondition(String projectText) {
        Matcher m = PRICE_PATTERN.matcher(projectText);
        if (m.find()) {
            String low = m.group(1);
            String high = m.group(2);
            if (high != null && !high.isBlank()) {
                return String.format("💰 予算内(%s〜%s万)", low, high);
            }
            return String.format("💰 予算内(%s万)", low);
        }
        return "💰 単価スキル見合い";
    }

    private String extractCommercialFlow(String projectText) {
        if (projectText.contains("プロパーのみ") || projectText.contains("プロパー限定") || projectText.contains("貴社社員のみ")) {
            return "プロパー限定";
        }
        if (projectText.contains("1社先") || projectText.contains("一社先") || projectText.contains("エンド直")) {
            return "商流1社先までOK";
        }
        return "プロパー・BP可";
    }

    private String truncateText(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
