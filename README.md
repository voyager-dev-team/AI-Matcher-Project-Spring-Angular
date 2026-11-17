# SES AI Matcher (Java Spring Boot 3.x + Angular 17+)

**Feature 1: 人材→案件探し (Candidate-to-Project Automated Matching & Proposal Draft Generator)**

SES（システムエンジニアリングサービス）およびIT人材紹介業務において、受信メールから技術者の職務経歴書を自動抽出し、営業担当者宛ての案件メールとAIマッチングを実施後、**適合度60%以上の有望案件に対してGmail下書き（提案メール）を全自動生成**するシステムです。

---

## 🌟 実装された機能と仕様 (Features & Specifications)

1. **営業担当者 (Sales Rep)**:
   - Google OAuth2 でログイン中の営業担当者アカウント（HTTPセッション管理）
2. **完全ダイナミックデータ (No Mock Data)**:
   - Gmail API によるリアルタイムメール取得 & Base64添付ファイルダウンロード
3. **5分間隔の自動監視スケジューラー (Step 1)**:
   - `@Scheduled(cron = "0 */5 * * * *")`
   - 監視対象件名: `[SES AI Matcher]人材→案件探す`
4. **経歴書テキスト自動抽出 (Step 2)**:
   - **Apache Tika** (`AutoDetectParser`) を採用。PDF, Excel (.xlsx/.xls), Word (.docx) の職務経歴書・スキルシートを完全パース。
5. **案件情報メール取得 (Step 3)**:
   - ログイン中の営業担当者の受信トレイから案件メールを自動取得。
6. **Gemini AI 適合度判定 (Step 4)**:
   - Google Gemini API による構造化JSON出力:
     - `fit_rate` (0〜100% 適合度スコア)
     - `fit_reason` (適合理由 & 懸念点)
     - `proposal_draft` (パートナー宛て提案メール文面)
7. **60% 閾値判定 & 下書き自動作成 (Step 5)**:
   - `fit_rate >= 60%`: Gmail API (`drafts.create`) によりログイン中の営業担当者宛てに提案メール下書きを自動作成。
   - `fit_rate < 60%`: 監査ログ/データベースのみに保存。
8. **Angular 17+ ダッシュボード (Step 6)**:
   - KPIカード (総突合数, 60%以上有望数, 下書き作成数)
   - リアルタイム同期ボタン (`🔄 今すぐ手動同期`)
   - 60%以上フィルタ & キーワード検索
   - ワンクリック提案文面確認 & コピーモーダル

---

## 📂 ディレクトリ構成 (Project Structure)

```text
ses-ai-matcher-spring-angular/
├── run.sh                                   # ワンクリック起動スクリプト
├── README.md                                # ドキュメント
├── backend/                                 # Spring Boot 3.x (Java 21)
│   ├── pom.xml                              # Maven 依存関係 (Gmail API, Tika, JPA, etc.)
│   └── src/main/
│       ├── java/com/voyager/sesmatcher/
│       │   ├── SesAiMatcherApplication.java # Spring Boot メインクラス (@EnableScheduling)
│       │   ├── config/
│       │   │   ├── GmailConfig.java         # Gmail OAuth2 クライアント初期化
│       │   │   └── WebMvcConfig.java        # CORS 設定
│       │   ├── controller/
│       │   └── MatchController.java     # REST API (/api/matches, /api/matches/trigger-sync)
│       │   ├── dto/
│       │   │   └── AiMatchResponse.java     # Gemini 構造化 JSON DTO
│       │   ├── entity/
│       │   │   └── MatchRecord.java         # JPA エンティティ (H2 / PostgreSQL)
│       │   ├── repository/
│       │   │   └── MatchRecordRepository.java
│       │   ├── scheduler/
│       │   │   └── CandidateMonitoringScheduler.java # 5分毎 Cron スケジューラー
│       │   └── service/
│       │       ├── GmailService.java        # Gmail 取得・添付ファイル・下書き作成
│       │       ├── ResumeParserService.java # Apache Tika 経歴書抽出
│       │       └── GeminiMatchingService.java # Gemini API マッチングエンジン
│       └── resources/
│           ├── application.yml              # 営業担当アドレス・API設定
│           └── credentials/
│               └── google-credentials.json  # Google Cloud OAuth クライアント資格情報
│
└── frontend/                                # Angular 17+ (TypeScript)
    ├── package.json
    ├── angular.json
    ├── tsconfig.json
    └── src/
        ├── index.html
        ├── main.ts
        └── app/
            ├── app.config.ts
            ├── app.component.ts
            ├── models/match.model.ts
            ├── services/match.service.ts
            └── components/dashboard/
                ├── dashboard.component.ts   # Angular Signals 状態管理
                ├── dashboard.component.html # モダン UI (KPI, テーブル, モーダル)
                └── dashboard.component.css  # スタイリング
```

---

## 🔑 Google Cloud API 設定手順

1. [Google Cloud Console](https://console.cloud.google.com/) にアクセスし、プロジェクトを作成します。
2. **APIs & Services** > **Library** から **Gmail API** を有効化（Enable）します。
3. **OAuth 同意画面 (OAuth consent screen)** を設定します:
   - スコープに以下を追加:
     - `https://www.googleapis.com/auth/gmail.readonly`
     - `https://www.googleapis.com/auth/gmail.compose`
     - `https://www.googleapis.com/auth/gmail.modify`
   - テストユーザーに各営業担当者の Google アカウントを追加します。
4. **Credentials** > **Create Credentials** > **OAuth client ID** (Desktop app) を作成し、JSON をダウンロードします。
5. ダウンロードしたファイルを以下のパスに配置します:
   ```text
   backend/src/main/resources/credentials/google-credentials.json
   ```

---

## 🚀 起動方法 (How to Run)

ターミナルで本ディレクトリに移動し、以下のコマンドを実行します:

```bash
cd /Users/wannashine/.gemini/antigravity/scratch/ses-ai-matcher-spring-angular

# Gemini API Key を設定 (未設定時は内蔵エンジンで動作)
export GEMINI_API_KEY="YOUR_GEMINI_API_KEY"

# ワンクリック起動
./run.sh
```

- **Angular Dashboard**: [http://localhost:4200](http://localhost:4200)
- **Spring Boot API**: [http://localhost:8080/api/matches](http://localhost:8080/api/matches)
- **H2 Database Console**: [http://localhost:8080/h2-console](http://localhost:8080/h2-console)
