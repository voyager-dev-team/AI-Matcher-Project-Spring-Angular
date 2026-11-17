package com.voyager.sesmatcher.entity;

import com.voyager.sesmatcher.config.ConditionCheckResultConverter;
import com.voyager.sesmatcher.dto.ConditionCheckResult;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "match_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MatchRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String candidateEmailId;
    private String candidateSubject;
    private String candidateFileName;

    @Column(columnDefinition = "TEXT")
    private String candidateParsedText;

    private String projectEmailId;
    private String projectSubject;

    @Column(columnDefinition = "TEXT")
    private String projectBody;

    /**
     * Received date/time of the project email extracted from Gmail API (internalDate / Date header).
     */
    private LocalDateTime receivedAt;

    private Integer fitRate;

    @Column(columnDefinition = "TEXT")
    private String fitReason;

    @Column(columnDefinition = "TEXT")
    private String proposalDraft;

    /**
     * Structured Tier 1 ~ Tier 4 SES condition evaluation result returned as JSON.
     */
    @Convert(converter = ConditionCheckResultConverter.class)
    @Column(columnDefinition = "TEXT")
    private ConditionCheckResult conditionCheckResult;

    private Boolean draftCreated;
    private String draftId;

    private LocalDateTime createdAt;
}
