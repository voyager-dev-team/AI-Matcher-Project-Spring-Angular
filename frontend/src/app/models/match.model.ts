export interface ConditionCheckResult {
  tier1Passed: boolean;
  tier1Details: string;
  workCondition: string;
  commercialCondition: string;
  ngReasons: string[];
}

export interface AuditLogEntry {
  timestamp: string;
  userEmail: string;
  userName: string;
  action: string;
  details: string;
}

export interface MatchRecord {
  id: number;
  candidateEmailId: string;
  candidateSubject: string;
  candidateFileName: string;
  candidateParsedText: string;
  projectEmailId: string;
  projectSubject: string;
  projectBody: string;
  receivedAt?: string;
  fitRate: number;
  fitReason: string;
  proposalDraft: string;
  conditionCheckResult?: ConditionCheckResult;
  draftCreated: boolean;
  draftId?: string;
  createdAt: string;
}

export interface CandidateSummary {
  candidateEmailId: string;
  candidateFileName: string;
  displayName: string;
  candidateSubject: string;
  totalMatches: number;
  highFitMatches: number;
  draftsCreated: number;
  maxFitRate: number;
  latestMatchedAt: string;
  status: 'SEARCHING' | 'ASSIGNED';
  assigned: boolean;
}

export interface AuthUser {
  authenticated: boolean;
  email?: string;
  name?: string;
  picture?: string;
  gmailConnected?: boolean;
  loginUrl?: string;
}

export interface SystemStatus {
  gmailConnected: boolean;
  salesEmail: string;
  threshold: number;
  totalRecords: number;
  highFitRecords: number;
}

export interface SyncResult {
  status: string;
  message?: string;
  candidatesFound?: number;
  projectsFound?: number;
  evaluatedMatches?: number;
  draftsCreated?: number;
  error?: string;
}
