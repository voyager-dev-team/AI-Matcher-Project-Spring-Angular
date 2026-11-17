package com.voyager.sesmatcher.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateSummaryDto {
    private String candidateEmailId;
    private String candidateFileName;
    private String displayName;
    private String candidateSubject;
    private int totalMatches;
    private int highFitMatches;
    private int draftsCreated;
    private int maxFitRate;
    private LocalDateTime latestMatchedAt;
    private String status;      // "SEARCHING" (🟢 案件探し中) or "ASSIGNED" (🔴 現場決定済)
    private boolean assigned;   // true if status == "ASSIGNED"
}
