package com.voyager.sesmatcher.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConditionCheckResult {

    @JsonProperty("tier1Passed")
    @JsonAlias({"tier1_passed"})
    private boolean tier1Passed;

    @JsonProperty("tier1Details")
    @JsonAlias({"tier1_details"})
    private String tier1Details;

    @JsonProperty("workCondition")
    @JsonAlias({"work_condition"})
    private String workCondition;

    @JsonProperty("commercialCondition")
    @JsonAlias({"commercial_condition"})
    private String commercialCondition;

    @JsonProperty("ngReasons")
    @JsonAlias({"ng_reasons"})
    @Builder.Default
    private List<String> ngReasons = new ArrayList<>();
}
