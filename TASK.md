# SES AI Matcher — タスク管理・開発ロードマップ (TASK.md)

> **プロジェクト名**: SES AI Matcher  
> **対象機能**: Feature 1: 人材→案件探し  
> **最終更新日**: 2026-10-28

---

## 1. 実装完了タスク一覧 (Completed Tasks)

### Phase 1: コアシステム構築（Step 1〜Step 6）
- [x] **プロジェクト基盤構築**
  - [x] Spring Boot 3.2.4 (Java 21) バックエンドのセットアップ
  - [x] Angular 17+ (Standalone Components, Signals) フロントエンドのセットアップ
  - [x] H2 インメモリデータベースおよび JPA エンティティ (`MatchRecord`) の定義
  - [x] ワンクリック起動スクリプト (`run.sh`) の作成
- [x] **Step 1: Gmail 監視スケジューラー (`CandidateMonitoringScheduler`)**
  - [x] `@Scheduled(cron = "0 */5 * * * *")` による5分間隔の自動ポーリング実装
  - [x] 監視件名 `[SES AI Matcher]人材→案件探す` 等を対象とした検索クエリ実装
  - [x] 手動同期トリガー API (`POST /api/matches/trigger-sync`) の実装
- [x] **Step 2: 職務経歴書テキスト抽出 (`ResumeParserService`)**
  - [x] Apache Tika (`AutoDetectParser`) の統合
  - [x] PDF, Excel (`.xlsx`/`.xls`), Word (`.docx`/`.doc`) 添付ファイルのテキスト抽出処理
- [x] **Step 3: 案件情報メール取得 (`GmailService`)**
  - [x] Google OAuth2 ログイン中の営業担当者の受信トレイからの案件メール取得処理
- [x] **Step 4: AI マッチング評価 (`GeminiMatchingService`)**
  - [x] Google Gemini API (`gemini-1.5-flash`) との REST 連携
  - [x] 構造化 JSON 出力 (`fit_rate`, `fit_reason`, `proposal_draft`) のパース処理
  - [x] API キー未設定時にも動作する内蔵評価エンジンのフォールバック実装
- [x] **Step 5: 60% 閾値判定 & Gmail 下書き自動作成**
  - [x] `fit_rate >= 60%` の場合に `users.drafts.create` で営業担当者宛ての下書きを自動生成
  - [x] `fit_rate < 60%` の場合に監査ログとして DB のみに保存する分岐処理
- [x] **Step 6: Angular 管理ダッシュボード (`DashboardComponent`)**
  - [x] KPI カード表示（総突合数、60%以上有望数、下書き作成数）
  - [x] 60%以上フィルタリング & キーワード検索機能
  - [x] 提案メール文面確認モーダル & クリップボードコピー機能

---

### Phase 2: 認証・ビルド安定化 & メールフィルタリング精度向上
- [x] **Google OAuth 2.0 認証フローの確立**
  - [x] `google-credentials.json` 読み込みと `LocalServerReceiver` (Port 8888) によるトークン取得
  - [x] `backend/tokens/StoredCredential` への永続化と接続ステータス API (`GET /api/matches/status`) の確認
- [x] **多層メールフィルタリング機能 (`SesEmailFilterService`)**
  - [x] `application.yml` による除外送信者・除外件名キーワードの外部設定化 (`SesFilterProperties`)
  - [x] カレンダー招待 (`.ics`)、Copilot 通知、議事録、メルマガ等の自動除外ロジック実装
  - [x] 6つの構造グループ（案件名、必須スキル、勤務地、期間、単価、面談）に基づく案件メール判定の実装
  - [x] 自己マッチング防止および生成済み下書き（`【提案下書き】`）の再帰マッチング防止
  - [x] 同期実行時の不正レコード自動パージ機能 (`purgeInvalidMatchRecords`) の実装
  - [x] 単体テスト (`SesEmailFilterServiceTest`) 全10ケースの作成およびパス確認

---

### Phase 3: UI/UX 機能拡張
- [x] **原文メールを直接開くボタンの追加**
  - [x] 各マッチング行のアクション列に「📩 案件メール」「📄 人材メール」ボタンを追加
  - [x] `projectEmailId` / `candidateEmailId` を用いた Gmail Web URL (`https://mail.google.com/mail/u/0/#inbox/{messageId}`) の生成
  - [x] `noopener,noreferrer` による安全な別タブ起動の実装
  - [x] メール ID 未登録時のボタン無効化・ツールチップ表示・アラート制御の実装

---

### Phase 4: ドキュメント整備
- [x] `README.md` — プロジェクト概要・セットアップ・起動手順書
- [x] `SPEC.md` — システム仕様書（アーキテクチャ、API、データモデル、フィルタリング仕様）
- [x] `AGENTS.md` — エージェント定義書（各サービス・コンポーネントの責務と連携フロー）
- [x] `TASK.md` — タスク管理・開発ロードマップ（本ドキュメント）

---

## 2. 運用・保守チェックリスト (Operations Checklist)

日常運用および動作確認時は以下の手順を実施してください。

1. **サーバー起動確認**
   - `./run.sh` を実行し、Backend (`http://localhost:8080`) と Frontend (`http://localhost:4200`) が起動していることを確認する。
2. **Gmail API 接続確認**
   - ダッシュボード右上のステータスバッジが `Gmail API: 接続完了`（緑色）になっていることを確認する。
   - 未接続の場合は `backend/src/main/resources/credentials/google-credentials.json` の配置と `backend/tokens` の認証状態を確認する。
3. **マッチング同期と結果確認**
   - 「🔄 今すぐ手動同期」ボタンを押下、または5分毎のスケジューラー実行を待つ。
   - KPI カードおよびテーブルに正当な候補者×案件ペアのみが表示されることを確認する。
4. **アクション動作確認**
   - 「📝 提案書確認」でモーダルが開き、文面コピーができることを確認する。
   - 「📩 案件メール」「📄 人材メール」でそれぞれ対応する Gmail 原文が別タブで開くことを確認する。

---

## 3. 今後の拡張候補 (Future Backlog)

将来的な機能拡張（Feature 2 以降や本番運用化）に向けたバックログ項目です。

- [ ] **永続化データベースへの移行**
  - 現在の H2 インメモリ DB から PostgreSQL / MySQL 等の永続 DB への切り替え（再起動時のデータ保持）
- [ ] **Feature 2: 案件→人材探し（逆方向マッチング）**
  - 新規案件メールをトリガーとして、保有している人材リスト（DB/メール）から最適な候補者を検索する機能
- [ ] **Gmail スレッド表示・マルチアカウント対応**
  - 複数営業担当者のメールドメイン対応や、Gmail アカウント番号 (`/u/0/`) の設定可能化
- [ ] **ダッシュボード上からのドラフト編集・直接送信機能**
  - モーダル内で提案文面を直接編集し、Gmail API 経由で下書き更新または送信する機能
