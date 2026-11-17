# SES AI Matcher — システム仕様書 (SPEC.md)

> **プロジェクト名**: SES AI Matcher  
> **対象機能**: Feature 1: 人材→案件探し  
> **最終更新日**: 2026-10-26

---

## 1. システム概要

SES（システムエンジニアリングサービス）業務において、技術者の職務経歴書を Gmail から自動取得し、営業担当者の受信メールに含まれる案件情報と AI を用いて自動マッチングするシステムである。適合度が閾値（60%）以上の場合、提案メール下書きを Gmail に自動生成する。

### 1.1 主要アクター

| アクター | 説明 |
|---------|------|
| 営業担当者 (Sales Rep) | Google OAuth2 ログインユーザー — 案件メールの受信者、提案ドラフトの確認者 |
| パートナー企業 | 人材情報・職務経歴書の送信者 |
| Gemini AI | 人材と案件の適合度判定、提案メール文面の自動生成 |
| スケジューラー | 5分毎に Gmail 監視を自動実行するバッチ処理 |

### 1.2 技術スタック

| レイヤー | 技術 | バージョン |
|---------|------|-----------|
| Backend | Java, Spring Boot | 21, 3.2.4 |
| Frontend | Angular (TypeScript) | 17+ |
| Database | H2 (In-Memory) | — |
| ファイルパーサー | Apache Tika (AutoDetectParser) | — |
| メール連携 | Gmail API (OAuth 2.0) | v1 |
| AI エンジン | Google Gemini API | gemini-1.5-flash |
| ビルドツール | Maven, Angular CLI | — |

---

## 2. アーキテクチャ

```
┌──────────────────────────────────────────────────────────────────┐
│                        Angular Dashboard (Port 4200)             │
│  ┌─────────┐  ┌─────────────┐  ┌────────────┐  ┌─────────────┐ │
│  │ KPI Cards│  │ Match Table │  │ Search/Filter│  │ Proposal    │ │
│  │         │  │ + 原文ボタン │  │             │  │ Modal       │ │
│  └─────────┘  └──────┬──────┘  └─────────────┘  └─────────────┘ │
│                      │ HTTP (REST API)                           │
└──────────────────────┼───────────────────────────────────────────┘
                       │
┌──────────────────────┼───────────────────────────────────────────┐
│             Spring Boot Backend (Port 8080)                      │
│                      │                                           │
│  ┌───────────────────┼──────────────────────────────────────┐    │
│  │            MatchController (/api/matches)                 │    │
│  │  GET /           POST /trigger-sync      GET /status      │    │
│  │  GET /high-fit                                            │    │
│  └───────────────────┬──────────────────────────────────────┘    │
│                      │                                           │
│  ┌───────────────────┼──────────────────────────────────────┐    │
│  │       CandidateMonitoringScheduler (@Scheduled)           │    │
│  │  ┌─────────┐  ┌────────────┐  ┌────────────────────┐     │    │
│  │  │ Gmail   │  │ Resume     │  │ Gemini Matching    │     │    │
│  │  │ Service │  │ Parser     │  │ Service            │     │    │
│  │  │         │  │ (Tika)     │  │                    │     │    │
│  │  └────┬────┘  └────────────┘  └────────────────────┘     │    │
│  │       │                                                   │    │
│  │  ┌────┴──────────────────────────────────────────────┐    │    │
│  │  │         SesEmailFilterService                      │    │    │
│  │  │  isExcludedEmail / isLegitimateProjectEmail        │    │    │
│  │  │  isLegitimateCandidateEmail                        │    │    │
│  │  └───────────────────────────────────────────────────┘    │    │
│  └───────────────────────────────────────────────────────────┘    │
│                      │                                           │
│  ┌───────────────────┼──────────────────────────────────────┐    │
│  │          H2 Database (match_records)                      │    │
│  └───────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────┘
                       │
              ┌────────┴────────┐
              │    Gmail API    │
              │  (OAuth 2.0)   │
              └────────┬────────┘
                       │
              ┌────────┴────────┐
              │  Gemini API     │
              │  (REST)         │
              └─────────────────┘
```

---

## 3. データフロー（処理パイプライン）

### 3.1 メイン処理フロー（6ステップ）

```
Step 1: Gmail 監視 (@Scheduled 5分毎)
    │
    ├── 人材候補メールを検索 (subject: "[SES AI Matcher]人材→案件探す" 等)
    │   └── 除外: 自動通知メール, カレンダー招待, ニュースレター
    │
    ▼
Step 2: 添付ファイル抽出 (Apache Tika)
    │
    ├── PDF, Excel (.xlsx/.xls), Word (.docx) をパース
    └── 経歴書テキストを抽出
    │
    ▼
Step 3: 案件メール取得
    │
    ├── ログイン中の営業担当者の受信トレイから案件メールを取得
    └── SesEmailFilterService で構造的バリデーション (≥3グループ一致)
    │
    ▼
Step 4: AI マッチング (Gemini API)
    │
    ├── 抽出済み経歴書テキスト + 案件メール本文を送信
    └── JSON で返却: fit_rate, fit_reason, proposal_draft
    │
    ▼
Step 5: 閾値判定 & ドラフト作成
    │
    ├── fit_rate ≥ 60%: Gmail Draft を自動作成
    └── fit_rate < 60%: DB に監査ログとして保存のみ
    │
    ▼
Step 6: Angular ダッシュボードで表示
    │
    ├── KPI カード (総数, 有望数, 下書き数)
    ├── マッチング結果テーブル (フィルタ・検索可)
    ├── 提案メール確認モーダル
    └── 原文メール開くボタン (📩 案件 / 📄 人材)
```

### 3.2 不正レコード自動パージ

各同期実行時に `purgeInvalidMatchRecords()` が走り、以下を削除：
- 自己マッチ (`candidateEmailId == projectEmailId`)
- 除外メール条件に合致するレコード
- 構造的バリデーションに不合格なレコード

---

## 4. REST API 仕様

### 4.1 エンドポイント一覧

| メソッド | パス | 説明 | レスポンス |
|---------|------|------|-----------|
| `GET` | `/api/matches` | 全マッチングレコードを作成日時降順で取得 | `MatchRecord[]` |
| `GET` | `/api/matches/high-fit` | 適合度60%以上のレコードのみ取得 | `MatchRecord[]` |
| `POST` | `/api/matches/trigger-sync` | 手動同期を即座にトリガー | `SyncResult` |
| `GET` | `/api/matches/status` | システム状態を取得 | `SystemStatus` |

### 4.2 レスポンスモデル

#### MatchRecord

| フィールド | 型 | 説明 |
|-----------|------|------|
| `id` | `Long` | レコード ID (自動採番) |
| `candidateEmailId` | `String` | 候補者メールの Gmail Message ID |
| `candidateSubject` | `String` | 候補者メールの件名 |
| `candidateFileName` | `String` | 抽出元ファイル名 |
| `candidateParsedText` | `String (TEXT)` | Tika 抽出テキスト (最大3000文字) |
| `projectEmailId` | `String` | 案件メールの Gmail Message ID |
| `projectSubject` | `String` | 案件メールの件名 |
| `projectBody` | `String (TEXT)` | 案件メール本文 (最大3000文字) |
| `fitRate` | `Integer` | AI 適合度スコア (0〜100%) |
| `fitReason` | `String (TEXT)` | AI による適合理由と懸念点 |
| `proposalDraft` | `String (TEXT)` | AI 自動生成の提案メール文面 |
| `draftCreated` | `Boolean` | Gmail 下書き作成済みフラグ |
| `draftId` | `String` | Gmail Draft ID |
| `createdAt` | `LocalDateTime` | レコード作成日時 |

#### SystemStatus

| フィールド | 型 | 説明 |
|-----------|------|------|
| `gmailConnected` | `boolean` | Gmail API 接続状態 |
| `salesEmail` | `String` | 営業担当メールアドレス |
| `threshold` | `int` | ドラフト作成閾値 (%) |
| `totalRecords` | `long` | 総レコード数 |
| `highFitRecords` | `int` | 閾値以上レコード数 |

#### SyncResult

| フィールド | 型 | 説明 |
|-----------|------|------|
| `status` | `String` | `SUCCESS`, `ERROR`, `GMAIL_NOT_CONNECTED` |
| `message` | `String` | 状態メッセージ |
| `candidatesFound` | `int` | 検出した候補者メール数 |
| `projectsFound` | `int` | 検出した案件メール数 |
| `evaluatedMatches` | `int` | AI 評価実行数 |
| `draftsCreated` | `int` | 下書き作成数 |
| `error` | `String` | エラーメッセージ |

---

## 5. メールフィルタリング仕様

### 5.1 除外条件 (isExcludedEmail)

以下のいずれかに該当するメールはマッチング対象から除外される。

**送信者除外リスト** (`ses.filter.exclude-senders`):
- `noreply`, `no-reply`, `notifications@`
- `calendar-notification`, `mailer-daemon`
- `github.com`, `gitlab.com`, `microsoft.com`
- `udemy.com`, `coursera.org`, `connpass.com`, `techplay.jp`

**件名キーワード除外リスト** (`ses.filter.exclude-subject-keywords`):
- カレンダー関連: `招待:`, `承諾:`, `辞退:`, `仮承諾:`, `invitation:`, `accepted:`, `declined:`, `tentative:`, `google calendar`, `zoom`
- 通知関連: `meeting notes`, `議事録`, `copilot`, `course is ready`, `受講`
- マーケティング: `セミナー`, `ウェビナー`, `ニュースレター`, `メルマガ`
- 再帰防止: `【提案下書き】`

**その他**:
- `.ics` 添付ファイルを含むメール
- Copilot 通知 (body に `github copilot` / `microsoft copilot` を含む)

### 5.2 案件メール判定 (isLegitimateProjectEmail)

以下の **6つの構造グループ** のうち、3つ以上（件名に明示的案件表記がある場合は2つ以上）に該当すること。

| グループ | 判定キーワード例 |
|---------|-----------------|
| 1. 案件名/業務内容 | `案件名`, `案件概要`, `業務内容`, `プロジェクト名` |
| 2. スキル要件 | `必須スキル`, `必須要件`, `求めるスキル`, `応募要件` |
| 3. 勤務地 | `勤務地`, `就業場所`, `最寄駅`, `リモート`, `テレワーク` |
| 4. 期間 | `期間`, `稼働時期`, `開始時期`, `入場時期`, `即日` |
| 5. 単価/契約 | `単価`, `精算`, `契約形態`, `報酬`, `募集人数`, `ses` |
| 6. 面談 | `面談`, `面談回数`, `web面談`, `商談` |

追加条件:
- 本文が80文字以上であること
- 候補者送信メール（`[SES AI Matcher]人材→案件探す` 等）は除外

### 5.3 候補者メール判定 (isLegitimateCandidateEmail)

以下のいずれかに該当すること:
1. 件名に `[SES AI Matcher]人材→案件探す` を含む
2. 添付ファイル名に `経歴書`, `履歴書`, `スキルシート`, `resume`, `cv` を含むドキュメント
3. 本文に氏名/年齢情報 + スキル情報 + 稼働情報 の3項目すべてを含む

---

## 6. Gemini AI マッチング仕様

### 6.1 入力

| 項目 | 内容 |
|------|------|
| 経歴書テキスト | Tika で抽出した職務経歴書の全文 (最大3000文字) |
| 案件本文 | Gmail から取得した案件メール本文 (最大3000文字) |
| ファイル名 | 候補者の経歴書ファイル名 |

### 6.2 出力 (JSON)

```json
{
  "fit_rate": 95,
  "fit_reason": "主要キーワード（java, spring, angular）が一致...",
  "proposal_draft": "ご担当者様\n\nいつも大変お世話になっております..."
}
```

### 6.3 モデル設定

| 項目 | 値 |
|------|-----|
| モデル | `gemini-1.5-flash` |
| API URL | `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}` |
| API キー | 環境変数 `GEMINI_API_KEY` |

---

## 7. 設定一覧 (application.yml)

| 設定キー | デフォルト値 | 説明 |
|---------|-------------|------|
| `server.port` | `8080` | Backend サーバーポート |
| `spring.security.oauth2.client.registration.google.*` | — | Google OAuth2 クライアント設定 |
| `ses.matcher.threshold` | `60` | 下書き自動作成の閾値 (%) |
| `ses.matcher.subject-filter` | `[SES AI Matcher]人材→案件探す` | 候補者メール監視件名 |
| `ses.filter.min-project-structural-indicators` | `3` | 案件判定に必要な構造グループ数 |
| `ses.filter.min-project-body-length` | `80` | 案件メール本文の最小文字数 |
| `ses.filter.exclude-senders` | (リスト) | 除外送信者パターン |
| `ses.filter.exclude-subject-keywords` | (リスト) | 除外件名キーワード |
| `ses.google.credentials-file-path` | `credentials/google-credentials.json` | OAuth 資格情報パス |
| `ses.google.tokens-directory-path` | `tokens` | OAuth トークン保存ディレクトリ |
| `ses.gemini.api-key` | `${GEMINI_API_KEY:}` | Gemini API キー |
| `ses.gemini.model` | `gemini-1.5-flash` | Gemini モデル名 |

---

## 8. データベース仕様

| 項目 | 値 |
|------|-----|
| エンジン | H2 (In-Memory) |
| JDBC URL | `jdbc:h2:mem:sesmatcher;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE` |
| DDL | `hibernate.ddl-auto: update` |
| テーブル名 | `match_records` |
| コンソール | `http://localhost:8080/h2-console` |

> **注意**: H2 はインメモリモードのため、アプリケーション再起動時にデータはリセットされる。

---

## 9. Gmail OAuth 2.0 仕様

| 項目 | 値 |
|------|-----|
| プロバイダー | Google Cloud Console |
| 認証方式 | Spring Security OAuth2 Client (`oauth2Login`) |
| コールバックURI | `http://localhost:8080/login/oauth2/code/google` |
| スコープ | `openid`, `profile`, `email`, `gmail.readonly`, `gmail.compose`, `gmail.modify` |
| 対象ユーザー | ログイン中の各営業担当者 Google アカウント |
| 資格情報 | `backend/src/main/resources/credentials/google-credentials.json` |
| トークン保存先 | `backend/tokens/StoredCredential` |

---

## 10. フロントエンド仕様

### 10.1 画面構成

| コンポーネント | 説明 |
|--------------|------|
| ヘッダー | ロゴ, Gmail 接続状態表示, 手動同期ボタン |
| 情報バー | 営業担当アドレス, 監視件名, スケジューラー間隔, 閾値 |
| KPI カード | 総マッチング数, 60%以上有望数, 下書き作成数 |
| フィルタツールバー | 60%以上チェックボックス, キーワード検索, 更新ボタン |
| データテーブル | 適合度, 候補者, 案件, AI理由, 下書き状態, アクション |
| 提案モーダル | 宛先, 案件名, 下書き本文, コピーボタン |

### 10.2 アクションボタン

| ボタン | 色 | 動作 |
|-------|-----|------|
| 📝 提案書確認 | 青 (`#0284c7`) | 提案メール文面モーダルを表示 |
| 📩 案件メール | 紫 (`#7c3aed`) | Gmail で案件の原文メールを別タブで開く |
| 📄 人材メール | ティール (`#0891b2`) | Gmail で候補者の原文メールを別タブで開く |

- Gmail URL 形式: `https://mail.google.com/mail/u/0/#inbox/{messageId}`
- メール ID が未登録の場合はボタンを無効化し、ツールチップで通知
- `target="_blank" rel="noopener noreferrer"` でセキュアに別タブ表示
