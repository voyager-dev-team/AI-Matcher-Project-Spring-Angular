import { Component, OnInit, OnChanges, SimpleChanges, Input, Output, EventEmitter, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatchService } from '../../services/match.service';
import { MatchRecord, CandidateSummary, SystemStatus, SyncResult } from '../../models/match.model';

export type DateFilterMode = 'RECENT_WEEK' | 'ALL' | 'CUSTOM';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.css']
})
export class DashboardComponent implements OnInit, OnChanges {
  private readonly matchService = inject(MatchService);

  @Input() candidate: CandidateSummary | null = null;
  @Output() backToCandidates = new EventEmitter<void>();

  matches = signal<MatchRecord[]>([]);
  systemStatus = signal<SystemStatus | null>(null);
  loading = signal<boolean>(false);
  syncing = signal<boolean>(false);
  syncMessage = signal<string | null>(null);

  filterOnly60Plus = signal<boolean>(false);
  searchQuery = signal<string>('');
  selectedRecord = signal<MatchRecord | null>(null);
  selectedConditionRecord = signal<MatchRecord | null>(null);

  // Date Filter State (Default: 'RECENT_WEEK' — within 1 week of the latest received project email)
  dateFilterMode = signal<DateFilterMode>('RECENT_WEEK');
  fromDateInput = signal<string>('');
  toDateInput = signal<string>('');
  appliedFromDate = signal<string>('');
  appliedToDate = signal<string>('');

  // Computed signals
  filteredMatches = computed(() => {
    // 1. Sort by project email receivedAt descending (latest project email at the top)
    let list = [...this.matches()].sort((a, b) => {
      return this.getRecordTimestamp(b) - this.getRecordTimestamp(a);
    });

    // 2. Date Filtering
    const mode = this.dateFilterMode();
    if (mode === 'RECENT_WEEK' && list.length > 0) {
      // Base on the latest received project email timestamp
      const latestTimestamp = Math.max(...list.map(m => this.getRecordTimestamp(m)));
      const oneWeekMs = 7 * 24 * 60 * 60 * 1000;
      const cutoffTimestamp = latestTimestamp - oneWeekMs;
      list = list.filter(m => this.getRecordTimestamp(m) >= cutoffTimestamp);
    } else if (mode === 'CUSTOM') {
      const fromStr = this.appliedFromDate();
      const toStr = this.appliedToDate();
      const fromMs = fromStr ? new Date(`${fromStr}T00:00:00`).getTime() : null;
      const toMs = toStr ? new Date(`${toStr}T23:59:59.999`).getTime() : null;

      list = list.filter(m => {
        const ts = this.getRecordTimestamp(m);
        if (fromMs !== null && !isNaN(fromMs) && ts < fromMs) {
          return false;
        }
        if (toMs !== null && !isNaN(toMs) && ts > toMs) {
          return false;
        }
        return true;
      });
    }

    // 3. 60%+ Fit Rate Filter
    if (this.filterOnly60Plus()) {
      list = list.filter(m => m.fitRate >= 60);
    }

    // 4. Keyword Search Filter
    const query = this.searchQuery().toLowerCase().trim();
    if (query) {
      list = list.filter(m =>
        m.candidateFileName.toLowerCase().includes(query) ||
        m.projectSubject.toLowerCase().includes(query) ||
        m.fitReason.toLowerCase().includes(query) ||
        (m.conditionCheckResult?.tier1Details?.toLowerCase().includes(query) ?? false) ||
        (m.conditionCheckResult?.workCondition?.toLowerCase().includes(query) ?? false) ||
        (m.conditionCheckResult?.commercialCondition?.toLowerCase().includes(query) ?? false)
      );
    }
    return list;
  });

  highFitCount = computed(() => this.matches().filter(m => m.fitRate >= 60).length);
  draftCount = computed(() => this.matches().filter(m => m.draftCreated).length);

  ngOnInit(): void {
    this.refreshData();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['candidate'] && !changes['candidate'].firstChange) {
      this.refreshData();
    }
  }

  refreshData(): void {
    this.loading.set(true);
    const request$ = this.candidate
      ? this.matchService.getMatchesByCandidate(this.candidate.candidateFileName, this.candidate.candidateEmailId)
      : this.matchService.getMatches();

    request$.subscribe({
      next: (data) => {
        this.matches.set(data);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Error fetching matches:', err);
        this.loading.set(false);
      }
    });

    this.matchService.getSystemStatus().subscribe({
      next: (status) => this.systemStatus.set(status),
      error: (err) => console.error('Error fetching system status:', err)
    });
  }

  triggerSync(): void {
    this.syncing.set(true);
    this.syncMessage.set('Gmail API 監視 & Gemini AI マッチングを実行中...');

    this.matchService.triggerManualSync().subscribe({
      next: (result: SyncResult) => {
        this.syncing.set(false);
        if (result.status === 'SUCCESS') {
          this.syncMessage.set(
            `✅ 同期完了: 候補者メール ${result.candidatesFound ?? 0} 件, 案件 ${result.projectsFound ?? 0} 件, 下書き作成 ${result.draftsCreated ?? 0} 通`
          );
        } else if (result.status === 'GMAIL_NOT_CONNECTED') {
          this.syncMessage.set('⚠️ Gmail API 未接続: Google アカウントでログインしてください。');
        } else {
          this.syncMessage.set(`情報: ${result.message || result.error || '処理が終了しました'}`);
        }
        this.refreshData();
      },
      error: (err) => {
        this.syncing.set(false);
        this.syncMessage.set('❌ サーバーとの通信に失敗しました。');
        console.error(err);
      }
    });
  }

  /**
   * Quick switch between '直近1週間' (RECENT_WEEK) and '全期間' (ALL)
   */
  setQuickDateFilter(mode: 'RECENT_WEEK' | 'ALL'): void {
    this.dateFilterMode.set(mode);
  }

  /**
   * Apply custom date range search from From/To date inputs
   */
  applyDateRangeSearch(): void {
    const from = this.fromDateInput().trim();
    const to = this.toDateInput().trim();
    if (!from && !to) {
      return;
    }
    this.appliedFromDate.set(from);
    this.appliedToDate.set(to);
    this.dateFilterMode.set('CUSTOM');
  }

  /**
   * Clear custom date range inputs and reset to default 'RECENT_WEEK'
   */
  clearDateRangeSearch(): void {
    this.fromDateInput.set('');
    this.toDateInput.set('');
    this.appliedFromDate.set('');
    this.appliedToDate.set('');
    this.dateFilterMode.set('RECENT_WEEK');
  }

  /**
   * Extracts timestamp (ms) from record.receivedAt (fallback to record.createdAt)
   */
  getRecordTimestamp(record: MatchRecord): number {
    const raw = record.receivedAt || record.createdAt;
    if (!raw) return 0;
    const parsed = new Date(raw).getTime();
    return isNaN(parsed) ? 0 : parsed;
  }

  /**
   * Formats record.receivedAt (or createdAt fallback) as 'YYYY/MM/DD HH:mm'
   */
  formatReceivedAt(record: MatchRecord): string {
    const raw = record.receivedAt || record.createdAt;
    if (!raw) return '—';
    const d = new Date(raw);
    if (isNaN(d.getTime())) return raw;
    const yyyy = d.getFullYear();
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    const hh = String(d.getHours()).padStart(2, '0');
    const min = String(d.getMinutes()).padStart(2, '0');
    return `${yyyy}/${mm}/${dd} ${hh}:${min}`;
  }

  /**
   * Returns true if Tier 1 absolute condition check failed (足切り).
   */
  isTier1Ng(record: MatchRecord): boolean {
    if (record.conditionCheckResult && record.conditionCheckResult.tier1Passed === false) {
      return true;
    }
    return record.fitRate === 0 && (record.fitReason?.includes('足切り') ?? false);
  }

  /**
   * Splits Tier 1 passed details into individual compact green badges:
   * e.g., ["✅ 国籍/日本語", "✅ 年齢(〜45歳)", "✅ 経験(5年以上)"]
   */
  getTier1OkBadges(record: MatchRecord): string[] {
    if (this.isTier1Ng(record)) {
      return [];
    }
    const raw = record.conditionCheckResult?.tier1Details;
    if (!raw || !raw.trim()) {
      return ['✅ 必須条件OK'];
    }
    return raw
      .split(/[、,\/]/)
      .map(part => part.trim())
      .filter(part => part.length > 0)
      .map(part => {
        const cleaned = part
          .replace(/^✅\s*/, '')
          .replace(/国籍・日本語OK/, '国籍/日本語')
          .replace(/年齢OK$/, '年齢OK')
          .replace(/経験年数OK$/, '経験年数')
          .replace(/年齢OK\((.+)\)/, '年齢($1)')
          .replace(/経験OK\((.+)\)/, '経験($1)');
        return `✅ ${cleaned}`;
      });
  }

  /**
   * Returns prominent red badges for Tier 1 NG reasons (e.g., "❌ 外国籍不可", "❌ 年齢上限超過", "❌ 経験不足")
   */
  getTier1NgBadges(record: MatchRecord): string[] {
    if (!this.isTier1Ng(record)) {
      return [];
    }
    const check = record.conditionCheckResult;
    const badges: string[] = [];

    if (check?.ngReasons?.length) {
      for (const reason of check.ngReasons) {
        const cleaned = reason.replace(/^❌\s*/, '').replace(/^足切り[:：]\s*/, '').trim();
        const formatted = `❌ ${cleaned}`;
        if (!badges.includes(formatted)) {
          badges.push(formatted);
        }
      }
    }

    if (badges.length === 0 && check?.tier1Details) {
      const parts = check.tier1Details
        .replace(/^❌\s*/, '')
        .replace(/^足切り[:：]\s*/, '')
        .split(/[、,\/]/);
      for (const p of parts) {
        const trimmed = p.trim();
        if (trimmed) {
          badges.push(`❌ ${trimmed}`);
        }
      }
    }

    if (badges.length === 0) {
      badges.push('❌ 足切り条件不一致');
    }
    return badges;
  }

  /**
   * Extracts work condition badges (Tier 2: 参画時期 & 勤務形態)
   * e.g., ["🚀 即日", "🏠 フルリモート"] or ["📅 翌月1日〜", "🏢 出社週3"]
   */
  getWorkConditionBadges(record: MatchRecord): string[] {
    const raw = record.conditionCheckResult?.workCondition;
    if (!raw || !raw.trim()) {
      return [];
    }
    return raw
      .split('/')
      .map(part => part.trim())
      .filter(part => part.length > 0)
      .map(part => {
        if (/^[🚀📅🏠🏢]/.test(part)) {
          return part;
        }
        if (part.includes('フルリモート') || part.includes('完全在宅')) {
          return `🏠 ${part}`;
        }
        if (part.includes('出社') || part.includes('ハイブリッド') || part.includes('常駐') || part.includes('リモート')) {
          return `🏢 ${part}`;
        }
        if (part.includes('即日')) {
          return `🚀 ${part}`;
        }
        if (part.includes('月') || part.includes('日')) {
          return `📅 ${part}`;
        }
        return `🏢 ${part}`;
      });
  }

  /**
   * Extracts commercial condition badges (Tier 3: 単価・商流)
   * e.g., ["💰 予算内(70万)", "🤝 プロパー・BP可"]
   */
  getCommercialConditionBadges(record: MatchRecord): string[] {
    const raw = record.conditionCheckResult?.commercialCondition;
    if (!raw || !raw.trim()) {
      return [];
    }
    return raw
      .split('/')
      .map(part => part.trim())
      .filter(part => part.length > 0)
      .map(part => {
        if (/^[💰🤝]/.test(part)) {
          return part;
        }
        if (part.includes('万') || part.includes('単価') || part.includes('予算')) {
          return `💰 ${part}`;
        }
        return `🤝 ${part}`;
      });
  }

  /**
   * Extracts warning / concern badges (ngReasons when Tier 1 passed or non-Tier1 warnings)
   * e.g., ["⚠️ 面談2回", "⚠️ 通勤90分", "⚠️ 単価要相談"]
   */
  getWarningBadges(record: MatchRecord): string[] {
    const reasons = record.conditionCheckResult?.ngReasons;
    if (!reasons || reasons.length === 0) {
      return [];
    }
    return reasons
      .filter(r => !r.includes('足切り'))
      .map(r => {
        const cleaned = r.replace(/^⚠️\s*/, '').trim();
        return `⚠️ ${cleaned}`;
      });
  }

  /**
   * Opens the Condition & AI Fit Reason Detail Modal
   */
  openConditionDetail(record: MatchRecord): void {
    this.selectedConditionRecord.set(record);
  }

  /**
   * Closes the Condition & AI Fit Reason Detail Modal
   */
  closeConditionModal(): void {
    this.selectedConditionRecord.set(null);
  }

  viewProposal(record: MatchRecord): void {
    this.selectedRecord.set(record);
  }

  closeModal(): void {
    this.selectedRecord.set(null);
  }

  copyDraft(text: string): void {
    if (!text) return;
    navigator.clipboard.writeText(text).then(() => {
      alert('提案メール下書き文面をクリップボードにコピーしました！');
    });
  }

  /**
   * Opens the original Gmail email in a new browser tab.
   */
  openOriginalEmail(messageId: string | undefined | null, label: string): void {
    if (!messageId || messageId.trim() === '') {
      alert(`${label}の元メールIDが登録されていません。\nメールを特定できないため、開くことができません。`);
      return;
    }
    const gmailUrl = `https://mail.google.com/mail/u/0/#inbox/${messageId.trim()}`;
    window.open(gmailUrl, '_blank', 'noopener,noreferrer');
  }

  /**
   * Check if a Gmail message ID is valid (non-null, non-empty).
   */
  hasEmailId(messageId: string | undefined | null): boolean {
    return !!messageId && messageId.trim() !== '';
  }
}
