import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { CandidateListComponent } from './components/candidate-list/candidate-list.component';
import { DashboardComponent } from './components/dashboard/dashboard.component';
import { MatchService } from './services/match.service';
import { AuthGuard } from './guards/auth.guard';
import { AuthUser, CandidateSummary, AuditLogEntry } from './models/match.model';

type ViewMode = 'MENU' | 'CANDIDATE_LIST' | 'DASHBOARD';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, CandidateListComponent, DashboardComponent],
  template: `
    <!-- Loading Auth State -->
    <div class="login-screen" *ngIf="checkingAuth()">
      <div class="login-card">
        <div class="menu-logo">🤖</div>
        <h1 class="menu-title">SES AI Matcher</h1>
        <p class="menu-subtitle">認証状態を確認しています...</p>
      </div>
    </div>

    <!-- Unauthenticated State: Google Login Screen Only ("Googleでログイン" button only) -->
    <div class="login-screen" *ngIf="!checkingAuth() && !authUser()?.authenticated">
      <div class="login-card">
        <div class="menu-logo">🤖</div>
        <h1 class="menu-title">SES AI Matcher</h1>
        <p class="menu-subtitle">
          Google アカウントでログインして自身の Gmail と連携します
        </p>

        <div class="auth-notice" *ngIf="authNotice()">
          {{ authNotice() }}
        </div>

        <div class="login-actions">
          <button type="button" class="btn-google-signin" (click)="signInWithGoogle()">
            <svg class="google-icon" viewBox="0 0 24 24" width="20" height="20">
              <path fill="#4285F4" d="M23.745 12.27c0-.7-.06-1.4-.19-2.07H12v4.51h6.6c-.29 1.52-1.14 2.82-2.4 3.68v3.05h3.88c2.27-2.09 3.665-5.17 3.665-9.17z"/>
              <path fill="#34A853" d="M12 24c3.24 0 5.95-1.08 7.93-2.91l-3.88-3.05c-1.08.72-2.45 1.16-4.05 1.16-3.12 0-5.77-2.11-6.72-4.96H1.29v3.14C3.26 21.3 7.31 24 12 24z"/>
              <path fill="#FBBC05" d="M5.28 14.24c-.24-.72-.38-1.49-.38-2.24s.14-1.52.38-2.24V6.62H1.29C.47 8.24 0 10.06 0 12s.47 3.76 1.29 5.38l3.99-3.14z"/>
              <path fill="#EA4335" d="M12 4.75c1.77 0 3.35.61 4.6 1.8l3.42-3.42C17.95 1.19 15.24 0 12 0 7.31 0 3.26 2.7 1.29 6.62l3.99 3.14c.95-2.85 3.6-4.96 6.72-4.96z"/>
            </svg>
            <span>Googleでログイン</span>
          </button>
        </div>

        <div class="login-hint">
          <div>🔒 データベース不使用（HTTPセッション &amp; メモリキャッシュ管理）</div>
          <div>📩 ログインした営業担当者の Gmail 受信トレイ・下書きと自動連携</div>
        </div>
      </div>
    </div>

    <!-- Authenticated State (Protected by AuthGuard) -->
    <div class="authenticated-wrapper" *ngIf="!checkingAuth() && authUser()?.authenticated">
      <!-- Global Top User Bar (Shows logged-in Sales Rep Name, Email, Profile Icon, Audit History, and Logout Button) -->
      <div class="user-top-bar">
        <div class="user-bar-left">
          <span class="app-mini-badge">🤖 SES AI Matcher</span>
          <span class="session-badge">🟢 OAuth2 セッション有効（DB不使用）</span>
        </div>

        <div class="user-bar-right">
          <div class="user-profile-chip">
            <img
              *ngIf="authUser()?.picture"
              [src]="authUser()?.picture"
              alt="Profile"
              class="user-avatar"
              referrerpolicy="no-referrer" />
            <div *ngIf="!authUser()?.picture" class="user-avatar-placeholder">
              {{ getUserInitial(authUser()?.name || authUser()?.email) }}
            </div>
            <div class="user-meta">
              <span class="user-name">{{ authUser()?.name }}</span>
              <span class="user-email">{{ authUser()?.email }}</span>
            </div>
          </div>

          <button type="button" class="btn-audit-log" (click)="openAuditLogsModal()">
            📋 利用履歴
          </button>

          <button type="button" class="btn-logout" (click)="logout()">
            🚪 ログアウト
          </button>
        </div>
      </div>

      <!-- Step 1: Initial Menu Screen -->
      <div class="menu-screen" *ngIf="currentView() === 'MENU'">
        <div class="menu-card">
          <div class="menu-logo">🤖</div>
          <h1 class="menu-title">SES AI Matcher</h1>
          <p class="menu-subtitle">
            Gmail 連携 &amp; Gemini AI による自動マッチング・提案メール作成システム
          </p>

          <div class="menu-actions">
            <button class="menu-feature-btn" (click)="openCandidateList()">
              <span class="btn-icon">🔍</span>
              <span class="btn-text">人材→案件探す</span>
              <span class="btn-arrow">→</span>
            </button>
          </div>

          <div class="menu-footer">
            <span>👤 ログイン中: <strong>{{ authUser()?.email }}</strong></span>
            <span>•</span>
            <span>⏱️ 5分毎自動監視</span>
          </div>
        </div>
      </div>

      <!-- Step 2: Candidate (Resume) Selection Screen -->
      <app-candidate-list
        *ngIf="currentView() === 'CANDIDATE_LIST'"
        (backToMenu)="backToMenu()"
        (selectCandidate)="openCandidateDashboard($event)">
      </app-candidate-list>

      <!-- Step 3: Candidate-Specific Project Matching Dashboard -->
      <app-dashboard
        *ngIf="currentView() === 'DASHBOARD'"
        [candidate]="selectedCandidate()"
        (backToCandidates)="openCandidateList()">
      </app-dashboard>

      <!-- Audit Log Modal (📋 利用履歴ポップアップ) -->
      <div class="audit-modal-backdrop" *ngIf="showAuditModal()" (click)="closeAuditLogsModal()">
        <div class="audit-modal-card" (click)="$event.stopPropagation()">
          <div class="audit-modal-header">
            <div>
              <h2 class="audit-modal-title">📋 マルチユーザー利用履歴（監査ログ）</h2>
              <p class="audit-modal-sub">
                直近の操作履歴（ログイン、案件再取得、AIマッチング実行、下書き作成、ステータス変更）
              </p>
            </div>
            <div class="audit-header-actions">
              <button type="button" class="btn-audit-refresh" (click)="fetchAuditLogs()" [disabled]="loadingAuditLogs()">
                🔄 更新
              </button>
              <button type="button" class="audit-modal-close" (click)="closeAuditLogsModal()" title="閉じる">✕</button>
            </div>
          </div>

          <div class="audit-modal-body">
            <div *ngIf="loadingAuditLogs()" class="audit-empty">
              ⏳ 利用履歴を読み込んでいます...
            </div>

            <div *ngIf="!loadingAuditLogs() && auditLogs().length === 0" class="audit-empty">
              📭 記録された操作履歴はまだありません。
            </div>

            <div class="audit-table-wrapper" *ngIf="!loadingAuditLogs() && auditLogs().length > 0">
              <table class="audit-table">
                <thead>
                  <tr>
                    <th style="width: 155px;">日時</th>
                    <th style="width: 220px;">営業担当者</th>
                    <th style="width: 170px;">操作内容</th>
                    <th>対象詳細</th>
                  </tr>
                </thead>
                <tbody>
                  <tr *ngFor="let log of auditLogs()">
                    <td class="audit-ts">{{ log.timestamp }}</td>
                    <td>
                      <div class="audit-user-box">
                        <span class="audit-user-name">{{ log.userName }}</span>
                        <span class="audit-user-email">{{ log.userEmail }}</span>
                      </div>
                    </td>
                    <td>
                      <span class="audit-action-badge" [ngClass]="getAuditActionClass(log.action)">
                        {{ log.action }}
                      </span>
                    </td>
                    <td class="audit-details">{{ log.details }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <div class="audit-modal-footer">
            <span class="audit-count-label">表示件数: {{ auditLogs().length }} 件（最大500件保持）</span>
            <button type="button" class="btn-audit-close" (click)="closeAuditLogsModal()">閉じる</button>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    /* Login Screen */
    .login-screen {
      min-height: 100vh;
      background: linear-gradient(135deg, #0f172a 0%, #1e293b 50%, #334155 100%);
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 24px;
      color: #1e293b;
    }

    .login-card {
      background: #ffffff;
      border: 1px solid #e2e8f0;
      border-radius: 18px;
      padding: 48px 40px;
      max-width: 500px;
      width: 100%;
      text-align: center;
      box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.35);
    }

    .auth-notice {
      background: #fef2f2;
      border: 1px solid #fecaca;
      color: #b91c1c;
      padding: 10px 14px;
      border-radius: 8px;
      font-size: 13px;
      margin-bottom: 20px;
    }

    .login-actions {
      display: flex;
      flex-direction: column;
      gap: 12px;
      margin-bottom: 28px;
    }

    .btn-google-signin {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 12px;
      width: 100%;
      padding: 15px 20px;
      background: #ffffff;
      color: #1f2937;
      border: 2px solid #cbd5e1;
      border-radius: 12px;
      font-size: 15.5px;
      font-weight: 700;
      cursor: pointer;
      transition: all 0.2s ease;
      box-shadow: 0 2px 6px rgba(15, 23, 42, 0.06);
    }

    .btn-google-signin:hover {
      background: #f8fafc;
      border-color: #2563eb;
      transform: translateY(-1px);
      box-shadow: 0 6px 16px rgba(37, 99, 235, 0.15);
    }

    .login-hint {
      font-size: 12px;
      color: #64748b;
      line-height: 1.8;
      border-top: 1px solid #f1f5f9;
      padding-top: 18px;
    }

    /* Authenticated Global User Bar */
    .user-top-bar {
      background: #0f172a;
      color: #f8fafc;
      padding: 8px 32px;
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 12px;
      font-size: 13px;
    }

    .user-bar-left {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .app-mini-badge {
      font-weight: 700;
      color: #e2e8f0;
    }

    .session-badge {
      font-size: 11.5px;
      background: rgba(16, 185, 129, 0.15);
      color: #6ee7b7;
      padding: 3px 10px;
      border-radius: 12px;
      border: 1px solid rgba(16, 185, 129, 0.3);
    }

    .user-bar-right {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .user-profile-chip {
      display: flex;
      align-items: center;
      gap: 10px;
      background: rgba(255, 255, 255, 0.08);
      padding: 4px 12px 4px 6px;
      border-radius: 24px;
      border: 1px solid rgba(255, 255, 255, 0.12);
    }

    .user-avatar {
      width: 28px;
      height: 28px;
      border-radius: 50%;
      object-fit: cover;
    }

    .user-avatar-placeholder {
      width: 28px;
      height: 28px;
      border-radius: 50%;
      background: #2563eb;
      color: #ffffff;
      font-weight: 700;
      font-size: 13px;
      display: flex;
      align-items: center;
      justify-content: center;
    }

    .user-meta {
      display: flex;
      flex-direction: column;
      line-height: 1.2;
    }

    .user-name {
      font-weight: 700;
      font-size: 12.5px;
      color: #ffffff;
    }

    .user-email {
      font-size: 11px;
      color: #cbd5e1;
    }

    .btn-audit-log {
      background: rgba(59, 130, 246, 0.18);
      color: #93c5fd;
      border: 1px solid rgba(59, 130, 246, 0.4);
      padding: 6px 12px;
      border-radius: 8px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
      transition: all 0.2s;
    }

    .btn-audit-log:hover {
      background: #2563eb;
      color: #ffffff;
      border-color: #2563eb;
    }

    .btn-logout {
      background: rgba(239, 68, 68, 0.15);
      color: #fca5a5;
      border: 1px solid rgba(239, 68, 68, 0.35);
      padding: 6px 12px;
      border-radius: 8px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
      transition: all 0.2s;
    }

    .btn-logout:hover {
      background: #dc2626;
      color: #ffffff;
      border-color: #dc2626;
    }

    /* Menu Screen */
    .menu-screen {
      min-height: calc(100vh - 46px);
      background: linear-gradient(135deg, #f8fafc 0%, #e2e8f0 100%);
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 24px;
      color: #1e293b;
    }

    .menu-card {
      background: #ffffff;
      border: 1px solid #e2e8f0;
      border-radius: 16px;
      padding: 48px 40px;
      max-width: 520px;
      width: 100%;
      text-align: center;
      box-shadow: 0 10px 25px -5px rgba(15, 23, 42, 0.08),
                  0 8px 10px -6px rgba(15, 23, 42, 0.04);
    }

    .menu-logo {
      font-size: 56px;
      margin-bottom: 16px;
      line-height: 1;
    }

    .menu-title {
      font-size: 28px;
      font-weight: 800;
      color: #0f172a;
      letter-spacing: -0.5px;
      margin: 0 0 10px;
    }

    .menu-subtitle {
      font-size: 14px;
      color: #64748b;
      line-height: 1.6;
      margin: 0 0 32px;
    }

    .menu-actions {
      display: flex;
      flex-direction: column;
      gap: 16px;
      margin-bottom: 32px;
    }

    .menu-feature-btn {
      display: flex;
      align-items: center;
      justify-content: space-between;
      width: 100%;
      padding: 18px 24px;
      background: #2563eb;
      color: #ffffff;
      border: none;
      border-radius: 12px;
      font-size: 18px;
      font-weight: 700;
      cursor: pointer;
      transition: all 0.2s ease;
      box-shadow: 0 4px 12px rgba(37, 99, 235, 0.25);
    }

    .menu-feature-btn:hover {
      background: #1d4ed8;
      transform: translateY(-2px);
      box-shadow: 0 6px 16px rgba(37, 99, 235, 0.35);
    }

    .menu-feature-btn:active {
      transform: translateY(0);
    }

    .btn-icon {
      font-size: 22px;
    }

    .btn-text {
      flex: 1;
      text-align: center;
    }

    .btn-arrow {
      font-size: 20px;
      opacity: 0.9;
    }

    .menu-footer {
      display: flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      flex-wrap: wrap;
      font-size: 12.5px;
      color: #64748b;
      border-top: 1px solid #f1f5f9;
      padding-top: 20px;
    }

    /* Audit Log Modal */
    .audit-modal-backdrop {
      position: fixed;
      inset: 0;
      background: rgba(15, 23, 42, 0.65);
      backdrop-filter: blur(2px);
      display: flex;
      align-items: center;
      justify-content: center;
      z-index: 2000;
      padding: 20px;
    }

    .audit-modal-card {
      background: #ffffff;
      border-radius: 14px;
      width: 100%;
      max-width: 960px;
      max-height: 85vh;
      display: flex;
      flex-direction: column;
      box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.3);
      overflow: hidden;
      color: #1e293b;
    }

    .audit-modal-header {
      padding: 18px 24px;
      border-bottom: 1px solid #e2e8f0;
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 16px;
    }

    .audit-modal-title {
      font-size: 18px;
      font-weight: 800;
      color: #0f172a;
      margin: 0;
    }

    .audit-modal-sub {
      font-size: 12.5px;
      color: #64748b;
      margin: 4px 0 0;
    }

    .audit-header-actions {
      display: flex;
      align-items: center;
      gap: 10px;
    }

    .btn-audit-refresh {
      background: #f1f5f9;
      border: 1px solid #cbd5e1;
      color: #334155;
      padding: 6px 12px;
      border-radius: 6px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
    }

    .btn-audit-refresh:hover:not(:disabled) {
      background: #e2e8f0;
    }

    .audit-modal-close {
      background: none;
      border: none;
      font-size: 20px;
      color: #94a3b8;
      cursor: pointer;
      line-height: 1;
    }

    .audit-modal-close:hover {
      color: #0f172a;
    }

    .audit-modal-body {
      padding: 0;
      overflow-y: auto;
      flex: 1;
    }

    .audit-empty {
      padding: 48px 24px;
      text-align: center;
      color: #64748b;
      font-size: 14px;
    }

    .audit-table-wrapper {
      width: 100%;
      overflow-x: auto;
    }

    .audit-table {
      width: 100%;
      border-collapse: collapse;
      text-align: left;
      font-size: 12.5px;
    }

    .audit-table th {
      background: #f8fafc;
      color: #475569;
      font-weight: 700;
      padding: 12px 16px;
      border-bottom: 1px solid #e2e8f0;
      position: sticky;
      top: 0;
    }

    .audit-table td {
      padding: 12px 16px;
      border-bottom: 1px solid #f1f5f9;
      vertical-align: middle;
    }

    .audit-ts {
      font-family: monospace;
      color: #475569;
      white-space: nowrap;
    }

    .audit-user-box {
      display: flex;
      flex-direction: column;
      line-height: 1.3;
    }

    .audit-user-name {
      font-weight: 700;
      color: #0f172a;
    }

    .audit-user-email {
      font-size: 11px;
      color: #64748b;
    }

    .audit-action-badge {
      display: inline-block;
      padding: 3px 8px;
      border-radius: 6px;
      font-size: 11.5px;
      font-weight: 700;
      white-space: nowrap;
    }

    .action-login {
      background: #dcfce7;
      color: #166534;
    }

    .action-sync {
      background: #eff6ff;
      color: #1d4ed8;
    }

    .action-match {
      background: #f3e8ff;
      color: #6b21a8;
    }

    .action-draft {
      background: #fef3c7;
      color: #b45309;
    }

    .action-status {
      background: #f1f5f9;
      color: #334155;
    }

    .audit-details {
      color: #334155;
      line-height: 1.45;
    }

    .audit-modal-footer {
      padding: 14px 24px;
      border-top: 1px solid #e2e8f0;
      background: #f8fafc;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }

    .audit-count-label {
      font-size: 12px;
      color: #64748b;
    }

    .btn-audit-close {
      background: #0f172a;
      color: #ffffff;
      border: none;
      padding: 8px 18px;
      border-radius: 8px;
      font-size: 13px;
      font-weight: 600;
      cursor: pointer;
    }

    .btn-audit-close:hover {
      background: #1e293b;
    }
  `]
})
export class AppComponent implements OnInit {
  private readonly matchService = inject(MatchService);
  private readonly authGuard = inject(AuthGuard);

  title = 'SES AI Matcher';
  checkingAuth = signal<boolean>(true);
  authUser = signal<AuthUser | null>(null);
  authNotice = signal<string | null>(null);

  currentView = signal<ViewMode>('MENU');
  selectedCandidate = signal<CandidateSummary | null>(null);

  // Audit Log Modal State
  showAuditModal = signal<boolean>(false);
  loadingAuditLogs = signal<boolean>(false);
  auditLogs = signal<AuditLogEntry[]>([]);

  ngOnInit(): void {
    this.checkAuthStatus();
  }

  checkAuthStatus(): void {
    this.checkingAuth.set(true);
    const params = new URLSearchParams(window.location.search);
    const authError = params.get('authError');

    if (authError === 'domain_restricted') {
      this.authNotice.set('@voyager-inc.comのアカウントのみ利用可能です');
    }

    this.matchService.getCurrentUser().subscribe({
      next: (user) => {
        if (user.authenticated && user.email && !user.email.toLowerCase().endsWith('@voyager-inc.com')) {
          this.authUser.set({ authenticated: false });
          this.authNotice.set('@voyager-inc.comのアカウントのみ利用可能です');
          this.checkingAuth.set(false);
          return;
        }
        this.authUser.set(user);
        this.checkingAuth.set(false);
        if (authError && !user.authenticated) {
          if (authError === 'domain_restricted') {
            this.authNotice.set('@voyager-inc.comのアカウントのみ利用可能です');
          } else {
            this.authNotice.set('Google OAuth2 ログインに失敗しました。Google Cloud Console の OAuth クライアント設定をご確認ください。');
          }
        }
      },
      error: () => {
        this.authUser.set({ authenticated: false });
        this.checkingAuth.set(false);
      }
    });
  }

  /**
   * Redirects to Spring Security OAuth2 Google Login (/oauth2/authorization/google)
   */
  signInWithGoogle(): void {
    window.location.href = 'http://localhost:8080/oauth2/authorization/google';
  }

  logout(): void {
    this.matchService.logout().subscribe({
      next: () => {
        this.authUser.set({ authenticated: false });
        this.selectedCandidate.set(null);
        this.showAuditModal.set(false);
        this.currentView.set('MENU');
      },
      error: () => {
        this.authUser.set({ authenticated: false });
        this.selectedCandidate.set(null);
        this.showAuditModal.set(false);
        this.currentView.set('MENU');
      }
    });
  }

  openAuditLogsModal(): void {
    this.showAuditModal.set(true);
    this.fetchAuditLogs();
  }

  closeAuditLogsModal(): void {
    this.showAuditModal.set(false);
  }

  fetchAuditLogs(): void {
    this.loadingAuditLogs.set(true);
    this.matchService.getAuditLogs().subscribe({
      next: (logs) => {
        this.auditLogs.set(logs);
        this.loadingAuditLogs.set(false);
      },
      error: () => {
        this.loadingAuditLogs.set(false);
      }
    });
  }

  getAuditActionClass(action: string): string {
    if (action.includes('ログイン')) return 'action-login';
    if (action.includes('再取得') || action.includes('同期')) return 'action-sync';
    if (action.includes('マッチング')) return 'action-match';
    if (action.includes('下書き')) return 'action-draft';
    return 'action-status';
  }

  getUserInitial(nameOrEmail?: string): string {
    if (!nameOrEmail) return '👤';
    return nameOrEmail.trim().charAt(0).toUpperCase();
  }

  openCandidateList(): void {
    this.authGuard.canActivate().subscribe((allowed) => {
      if (allowed === true) {
        this.currentView.set('CANDIDATE_LIST');
      } else {
        this.authUser.set({ authenticated: false });
      }
    });
  }

  openCandidateDashboard(candidate: CandidateSummary): void {
    this.authGuard.canActivate().subscribe((allowed) => {
      if (allowed === true) {
        this.selectedCandidate.set(candidate);
        this.currentView.set('DASHBOARD');
      } else {
        this.authUser.set({ authenticated: false });
      }
    });
  }

  backToMenu(): void {
    this.selectedCandidate.set(null);
    this.currentView.set('MENU');
  }
}
