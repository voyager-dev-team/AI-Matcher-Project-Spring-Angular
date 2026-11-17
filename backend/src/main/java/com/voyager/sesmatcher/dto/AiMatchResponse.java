package com.voyager.sesmatcher.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiMatchResponse {

    @JsonProperty("fit_rate")
    @JsonAlias({"fitRate"})
    private Integer fitRate;

    @JsonProperty("fit_reason")
    @JsonAlias({"fitReason"})
    private String fitReason;

    @JsonProperty("proposal_draft")
    @JsonAlias({"proposalDraft"})
    private String proposalDraft;

    @JsonProperty("conditionCheckResult")
    @JsonAlias({"condition_check_result"})
    private ConditionCheckResult conditionCheckResult;
}
