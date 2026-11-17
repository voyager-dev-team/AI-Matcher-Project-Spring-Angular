import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, tap } from 'rxjs';
import { MatchRecord, CandidateSummary, AuthUser, SystemStatus, SyncResult, AuditLogEntry } from '../models/match.model';

@Injectable({
  providedIn: 'root'
})
export class MatchService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = 'http://localhost:8080/api/matches';
  private readonly authUrl = 'http://localhost:8080/api/auth';
  private readonly auditLogsUrl = 'http://localhost:8080/api/audit-logs';

  readonly authUser = signal<AuthUser | null>(null);

  getCurrentUser(): Observable<AuthUser> {
    return this.http.get<AuthUser>(`${this.authUrl}/me`, { withCredentials: true }).pipe(
      tap((user) => this.authUser.set(user))
    );
  }

  logout(): Observable<{ status: string; authenticated: boolean }> {
    return this.http.post<{ status: string; authenticated: boolean }>(
      `${this.authUrl}/logout`,
      {},
      { withCredentials: true }
    ).pipe(
      tap(() => this.authUser.set({ authenticated: false }))
    );
  }

  getAuditLogs(): Observable<AuditLogEntry[]> {
    return this.http.get<AuditLogEntry[]>(this.auditLogsUrl, { withCredentials: true });
  }

  getMatches(): Observable<MatchRecord[]> {
    return this.http.get<MatchRecord[]>(this.baseUrl, { withCredentials: true });
  }

  getHighFitMatches(): Observable<MatchRecord[]> {
    return this.http.get<MatchRecord[]>(`${this.baseUrl}/high-fit`, { withCredentials: true });
  }

  getCandidates(): Observable<CandidateSummary[]> {
    return this.http.get<CandidateSummary[]>(`${this.baseUrl}/candidates`, { withCredentials: true });
  }

  updateCandidateStatus(
    candidateFileName: string,
    status: 'SEARCHING' | 'ASSIGNED',
    candidateEmailId?: string,
    candidateSubject?: string
  ): Observable<{ candidateFileName: string; status: 'SEARCHING' | 'ASSIGNED'; assigned: boolean }> {
    return this.http.put<{ candidateFileName: string; status: 'SEARCHING' | 'ASSIGNED'; assigned: boolean }>(
      `${this.baseUrl}/candidates/status`,
      {
        candidateFileName,
        status,
        candidateEmailId: candidateEmailId ?? '',
        candidateSubject: candidateSubject ?? ''
      },
      { withCredentials: true }
    );
  }

  deleteCandidateEmail(
    candidateEmailId: string,
    candidateFileName: string
  ): Observable<{ status: string; candidateEmailId?: string; candidateFileName?: string; error?: string }> {
    let params = new HttpParams();
    if (candidateEmailId) {
      params = params.set('candidateEmailId', candidateEmailId);
    }
    if (candidateFileName) {
      params = params.set('candidateFileName', candidateFileName);
    }
    return this.http.delete<{ status: string; candidateEmailId?: string; candidateFileName?: string; error?: string }>(
      `${this.baseUrl}/candidates`,
      { params, withCredentials: true }
    );
  }

  getMatchesByCandidate(fileName: string, candidateEmailId?: string): Observable<MatchRecord[]> {
    let params = new HttpParams().set('fileName', fileName);
    if (candidateEmailId) {
      params = params.set('candidateEmailId', candidateEmailId);
    }
    return this.http.get<MatchRecord[]>(`${this.baseUrl}/by-candidate`, { params, withCredentials: true });
  }

  getSystemStatus(): Observable<SystemStatus> {
    return this.http.get<SystemStatus>(`${this.baseUrl}/status`, { withCredentials: true });
  }

  triggerManualSync(): Observable<SyncResult> {
    return this.http.post<SyncResult>(`${this.baseUrl}/trigger-sync`, {}, { withCredentials: true });
  }
}
