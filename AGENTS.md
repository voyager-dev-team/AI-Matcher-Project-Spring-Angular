# SES AI Matcher — エージェント定義書 (AGENTS.md)

> **プロジェクト名**: SES AI Matcher  
> **対象機能**: Feature 1: 人材→案件探し  
> **最終更新日**: 2026-10-26

---

## 1. エージェント概要

本システムは、以下の自律型エージェント（サービス / スケジューラー / コンポーネント）が協調して動作する。各エージェントは明確な責務を持ち、Spring の DI コンテナを通じて疎結合に連携する。

---

## 2. エージェント一覧

### 2.1 🕐 CandidateMonitoringScheduler（スケジューラーエージェント）

| 項目 | 内容 |
|------|------|
| **クラス** | `com.voyager.sesmatcher.scheduler.CandidateMonitoringScheduler` |
| **役割** | 全体のオーケストレーション。5分毎にパイプライン全体を起動する中央制御エージェント |
| **起動条件** | `@Scheduled(cron = "0 */5 * * * *")` (5分毎) または手動トリガー |
| **依存先** | GmailService, ResumeParserService, GeminiMatchingService, MatchRecordRepository, SesEmailFilterService |
| **入力** | なし（自律起動） |
| **出力** | `Map<String, Object>` (同期結果レポート) |

**処理フロー**:

```
executeCandidateMonitoringCron() / runJobNow()
  │
  ├── 1. purgeInvalidMatchRecords()     ← 不正レコード自動削除
  ├── 2. gmailService.isConnected()     ← 接続確認
  ├── 3. Gmail 検索 (候補者メール)       ← Step 1
  ├── 4. gmailService.fetchProjectEmails() ← Step 3
  │
  └── 各候補者 × 各案件 ループ:
       ├── 5. filterService.isLegitimateCandidateEmail() ← バリデーション
       ├── 6. resumeParserService.parseResume()          ← Step 2
       ├── 7. geminiMatchingService.evaluateMatching()   ← Step 4
       ├── 8. 閾値判定 → gmailService.createDraftEmail() ← Step 5
       └── 9. matchRecordRepository.save()               ← DB 保存
```

**設定値**:

| プロパティ | 用途 |
|-----------|------|
| `ses.matcher.subject-filter` | 候補者メール検索キーワード |
| `ses.matcher.threshold` | ドラフト作成閾値 (デフォルト: 60%) |
| `ses.sales.email` | 営業担当メールアドレス |

---

### 2.2 📧 GmailService（Gmail 連携エージェント）

| 項目 | 内容 |
|------|------|
| **クラス** | `com.voyager.sesmatcher.service.GmailService` |
| **役割** | Gmail API との全通信を担当。メール検索、本文取得、添付ファイル抽出、下書き作成 |
| **依存先** | GmailConfig (OAuth クライアント), SesEmailFilterService |
| **外部連携** | Gmail API (OAuth 2.0) |

**提供機能**:

| メソッド | 説明 |
|---------|------|
| `isConnected()` | Gmail API 接続状態の確認 |
| `searchCandidateEmails(query)` | 候補者メール検索（Gmail Query Language） |
| `fetchProjectEmails()` | 案件メール取得（構造的バリデーション付き） |
| `getMessageDetails(messageId)` | メール詳細取得 |
| `extractMessageBody(payload)` | メール本文の抽出 |
| `extractAttachments(messageId, message)` | 添付ファイルのバイナリ取得 |
| `getAttachmentFilenames(message)` | 添付ファイル名一覧取得 |
| `getHeaderValue(message, header)` | メールヘッダー値の取得 |
| `createDraftEmail(to, subject, body)` | Gmail 下書き作成 |

**Gmail 検索クエリ（候補者）**:
```
(subject:"[SES AI Matcher]人材→案件探す" OR subject:"人材情報" OR subject:"職務経歴書" OR subject:"スキルシート")
-subject:"【提案下書き】"
-from:(noreply OR no-reply OR notifications)
-filename:ics
```

**Gmail 検索クエリ（案件）**:
```
(案件 OR SES OR スキル OR 募集 OR 人材 OR 開発)
-subject:"【提案下書き】"
-from:(noreply OR no-reply)
-filename:ics
```

---

### 2.3 🔍 SesEmailFilterService（フィルタリングエージェント）

| 項目 | 内容 |
|------|------|
| **クラス** | `com.voyager.sesmatcher.service.SesEmailFilterService` |
| **役割** | メールの分類・検証。不要メールの除外と、正当な案件/候補者メールの判定 |
| **依存先** | SesFilterProperties (設定値) |
| **設計方針** | 多層バリデーション: 送信者 → 件名 → 添付ファイル → 本文構造 |

**提供機能**:

| メソッド | 説明 |
|---------|------|
| `isExcludedEmail(subject, sender, body, attachmentNames)` | 除外対象メール判定 |
| `isLegitimateProjectEmail(subject, sender, body, attachmentNames)` | 正当な案件メール判定 |
| `isLegitimateCandidateEmail(subject, sender, body, attachments)` | 正当な候補者メール判定 |
| `countProjectStructuralGroups(body, subject)` | 案件構造グループ一致数算出 (6グループ) |
| `stripHtml(html)` | HTML タグ除去 |

**フィルタリング階層**:

```
Level 1: 送信者チェック (exclude-senders リスト)
  │
  ▼
Level 2: 件名キーワードチェック (exclude-subject-keywords リスト)
  │
  ▼
Level 3: 添付ファイルチェック (.ics カレンダー除外)
  │
  ▼
Level 4: コンテンツバリデーション (Copilot通知判定)
  │
  ▼
Level 5: 構造的バリデーション (6グループ中3つ以上一致)
```

---

### 2.4 📄 ResumeParserService（経歴書解析エージェント）

| 項目 | 内容 |
|------|------|
| **クラス** | `com.voyager.sesmatcher.service.ResumeParserService` |
| **役割** | 添付ファイル (PDF, Excel, Word) からテキストを抽出 |
| **依存先** | Apache Tika (AutoDetectParser) |
| **対応形式** | `.pdf`, `.xlsx`, `.xls`, `.docx`, `.doc` |

**提供機能**:

| メソッド | 説明 |
|---------|------|
| `parseResume(fileBytes, fileName)` | バイナリデータからテキストを抽出 |

**処理の特徴**:
- Apache Tika の `AutoDetectParser` を使用し、ファイル形式を自動判別
- 空白やトリビアルな結果（30文字未満）はスキップ
- メール本文自体も経歴情報として解析可能

---

### 2.5 🤖 GeminiMatchingService（AI マッチングエージェント）

| 項目 | 内容 |
|------|------|
| **クラス** | `com.voyager.sesmatcher.service.GeminiMatchingService` |
| **役割** | Gemini API を用いた人材-案件適合度判定と提案メール文面の自動生成 |
| **依存先** | Gemini API (REST) |
| **モデル** | `gemini-1.5-flash` |

**提供機能**:

| メソッド | 説明 |
|---------|------|
| `evaluateMatching(resumeText, projectBody, fileName)` | 適合度判定 + 提案文面生成 |

**入出力**:

```
入力:
  - resumeText: Tika 抽出済み経歴書テキスト
  - projectBody: 案件メール本文
  - fileName: 経歴書ファイル名

出力 (AiMatchResponse):
  - fitRate: Integer (0〜100)
  - fitReason: String
  - proposalDraft: String
```

---

### 2.6 🌐 MatchController（REST API エージェント）

| 項目 | 内容 |
|------|------|
| **クラス** | `com.voyager.sesmatcher.controller.MatchController` |
| **役割** | フロントエンドからの HTTP リクエストを処理するゲートウェイ |
| **ベースパス** | `/api/matches` |

**エンドポイント**:

| メソッド | パス | 機能 |
|---------|------|------|
| `GET` | `/api/matches` | 全レコード取得 (作成日時降順) |
| `GET` | `/api/matches/high-fit` | 高適合度レコードのみ取得 |
| `POST` | `/api/matches/trigger-sync` | 手動同期の即時トリガー |
| `GET` | `/api/matches/status` | システム状態取得 |

---

### 2.7 📊 DashboardComponent（フロントエンドエージェント）

| 項目 | 内容 |
|------|------|
| **ファイル** | `frontend/src/app/components/dashboard/dashboard.component.ts` |
| **役割** | マッチング結果の表示、フィルタリング、ユーザー操作の処理 |
| **フレームワーク** | Angular 17+ (Standalone Component, Signals) |

**状態管理 (Signals)**:

| Signal | 型 | 用途 |
|--------|-----|------|
| `matches` | `MatchRecord[]` | 全マッチングデータ |
| `systemStatus` | `SystemStatus \| null` | システム接続状態 |
| `loading` | `boolean` | データ読込中フラグ |
| `syncing` | `boolean` | 同期実行中フラグ |
| `syncMessage` | `string \| null` | 同期結果メッセージ |
| `filterOnly60Plus` | `boolean` | 60%以上フィルタ状態 |
| `searchQuery` | `string` | 検索クエリ |
| `selectedRecord` | `MatchRecord \| null` | モーダル表示対象 |

**Computed Signals**:

| Signal | 用途 |
|--------|------|
| `filteredMatches` | フィルタ・検索適用後のリスト |
| `highFitCount` | 60%以上の件数 |
| `draftCount` | 下書き作成済みの件数 |

**メソッド一覧**:

| メソッド | 機能 |
|---------|------|
| `refreshData()` | マッチングデータとステータスの再取得 |
| `triggerSync()` | 手動同期の実行 |
| `viewProposal(record)` | 提案書モーダルの表示 |
| `closeModal()` | モーダルを閉じる |
| `copyDraft(text)` | 下書き文面をクリップボードにコピー |
| `openOriginalEmail(messageId, label)` | 原文メールを Gmail で別タブに開く |
| `hasEmailId(messageId)` | メール ID の有効性チェック |

---

## 3. エージェント間の依存関係

```
                    ┌───────────────────────┐
                    │   MatchController     │ ← HTTP リクエスト受付
                    │   (REST Gateway)      │
                    └───────────┬───────────┘
                                │
                    ┌───────────┴───────────┐
                    │ CandidateMonitoring   │ ← 中央オーケストレーター
                    │ Scheduler             │
                    └───┬───┬───┬───┬───────┘
                        │   │   │   │
           ┌────────────┘   │   │   └────────────┐
           │                │   │                │
   ┌───────┴──────┐  ┌─────┴───┴──┐  ┌──────────┴─────────┐
   │ GmailService │  │ Resume     │  │ GeminiMatching     │
   │              │  │ Parser     │  │ Service            │
   │  - 検索      │  │ Service    │  │                    │
   │  - 取得      │  │            │  │  - AI 判定         │
   │  - 下書き    │  │  - Tika    │  │  - 提案文生成      │
   └───────┬──────┘  └────────────┘  └────────────────────┘
           │
   ┌───────┴──────────┐
   │ SesEmailFilter   │ ← 多層フィルタリング
   │ Service          │
   └───────┬──────────┘
           │
   ┌───────┴──────────┐
   │ SesFilter        │ ← 外部設定 (application.yml)
   │ Properties       │
   └──────────────────┘
```

---

## 4. エージェント間のメッセージフロー

```
[スケジューラー起動 (5分毎)]
    │
    ├──→ SesEmailFilterService.purgeInvalidMatchRecords()
    │        └── MatchRecordRepository.findAll() → delete()
    │
    ├──→ GmailService.isConnected()
    │        └── Gmail API OAuth 接続確認
    │
    ├──→ GmailService.searchCandidateEmails(query)
    │        └── Gmail API: users.messages.list()
    │
    ├──→ GmailService.fetchProjectEmails()
    │        ├── Gmail API: users.messages.list()
    │        └── SesEmailFilterService.isLegitimateProjectEmail() ← バリデーション
    │
    └──→ [各候補者 × 各案件のループ]
             │
             ├──→ GmailService.getMessageDetails(id)
             ├──→ SesEmailFilterService.isLegitimateCandidateEmail()
             ├──→ GmailService.extractAttachments()
             ├──→ ResumeParserService.parseResume()
             ├──→ GeminiMatchingService.evaluateMatching()
             │        └── Gemini API: generateContent
             │
             ├── [fit_rate >= 60%]
             │        └──→ GmailService.createDraftEmail()
             │                 └── Gmail API: users.drafts.create()
             │
             └──→ MatchRecordRepository.save()
```

---

## 5. 外部エージェント（サードパーティ API）

| エージェント | プロトコル | 認証方式 | 用途 |
|------------|-----------|---------|------|
| Gmail API | REST (HTTPS) | OAuth 2.0 | メール検索・取得・下書き作成 |
| Gemini API | REST (HTTPS) | API キー | AI マッチング判定 |
| H2 Database | JDBC | なし (In-Memory) | マッチングレコード永続化 |
