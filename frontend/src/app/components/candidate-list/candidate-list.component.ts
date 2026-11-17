import { Component, OnInit, Output, EventEmitter, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { MatchService } from '../../services/match.service';
import { CandidateSummary, SystemStatus, SyncResult } from '../../models/match.model';

@Component({
  selector: 'app-candidate-list',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './candidate-list.component.html',
  styleUrls: ['./candidate-list.component.css']
})
export class CandidateListComponent implements OnInit {
  private readonly matchService = inject(MatchService);

  @Output() backToMenu = new EventEmitter<void>();
  @Output() selectCandidate = new EventEmitter<CandidateSummary>();

  candidates = signal<CandidateSummary[]>([]);
  systemStatus = signal<SystemStatus | null>(null);
  loading = signal<boolean>(false);
  syncing = signal<boolean>(false);
  syncMessage = signal<string | null>(null);

  // Delete confirmation modal state
  candidateToDelete = signal<CandidateSummary | null>(null);
  deleting = signal<boolean>(false);

  ngOnInit(): void {
    this.loadCandidates();
  }

  loadCandidates(): void {
    this.loading.set(true);
    this.matchService.getCandidates().subscribe({
      next: (list) => {
        this.candidates.set(list);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Error fetching candidates:', err);
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
          this.syncMessage.set('⚠️ Gmail API 未接続: credentials/google-credentials.json を設定してください。');
        } else {
          this.syncMessage.set(`情報: ${result.message || result.error || '処理が終了しました'}`);
        }
        this.loadCandidates();
      },
      error: (err) => {
        this.syncing.set(false);
        this.syncMessage.set('❌ サーバーとの通信に失敗しました。');
        console.error(err);
      }
    });
  }

  onSelectCandidate(candidate: CandidateSummary): void {
    if (candidate.assigned) {
      return;
    }
    this.selectCandidate.emit(candidate);
  }

  setCandidateStatus(
    event: Event,
    candidate: CandidateSummary,
    newStatus: 'SEARCHING' | 'ASSIGNED'
  ): void {
    event.stopPropagation();
    if (candidate.status === newStatus) {
      return;
    }

    this.matchService
      .updateCandidateStatus(
        candidate.candidateFileName,
        newStatus,
        candidate.candidateEmailId,
        candidate.candidateSubject
      )
      .subscribe({
        next: (res) => {
          const updatedList = this.candidates().map((item) =>
            item.candidateFileName === candidate.candidateFileName
              ? { ...item, status: res.status, assigned: res.assigned }
              : item
          );
          this.candidates.set(updatedList);

          if (res.assigned) {
            this.syncMessage.set(
              `🔴 ${candidate.displayName} を「現場決定済（マッチング停止中）」に設定しました。定期監視・AIマッチングの対象から除外されます。`
            );
          } else {
            this.syncMessage.set(
              `🟢 ${candidate.displayName} を「案件探し中」に設定しました。案件マッチングが有効になりました。`
            );
          }
        },
        error: (err) => {
          console.error('Error updating candidate status:', err);
          alert('ステータスの更新に失敗しました。');
        }
      });
  }

  openCandidateEmail(event: Event, messageId: string | undefined | null): void {
    event.stopPropagation();
    if (!messageId || messageId.trim() === '') {
      alert('人材メールの元メールIDが登録されていません。');
      return;
    }
    const gmailUrl = `https://mail.google.com/mail/u/0/#inbox/${messageId.trim()}`;
    window.open(gmailUrl, '_blank', 'noopener,noreferrer');
  }

  promptDeleteCandidate(event: Event, candidate: CandidateSummary): void {
    event.stopPropagation();
    this.candidateToDelete.set(candidate);
  }

  cancelDelete(): void {
    if (this.deleting()) return;
    this.candidateToDelete.set(null);
  }

  confirmDeleteCandidate(): void {
    const target = this.candidateToDelete();
    if (!target || this.deleting()) return;

    this.deleting.set(true);
    this.matchService
      .deleteCandidateEmail(target.candidateEmailId, target.candidateFileName)
      .subscribe({
        next: () => {
          this.deleting.set(false);
          this.candidateToDelete.set(null);
          // Immediately remove the deleted candidate card from the UI
          this.candidates.set(
            this.candidates().filter(
              (c) =>
                c.candidateFileName !== target.candidateFileName &&
                (!target.candidateEmailId || c.candidateEmailId !== target.candidateEmailId)
            )
          );
          this.syncMessage.set(
            `🗑️ 「${target.displayName}」の人材メールを Gmail のゴミ箱へ移動し、候補者一覧から削除しました。`
          );
          this.loadCandidates();
        },
        error: (err) => {
          this.deleting.set(false);
          console.error('Error deleting candidate email:', err);
          alert('人材メールの削除に失敗しました。Gmail APIの接続状態をご確認ください。');
        }
      });
  }
}
