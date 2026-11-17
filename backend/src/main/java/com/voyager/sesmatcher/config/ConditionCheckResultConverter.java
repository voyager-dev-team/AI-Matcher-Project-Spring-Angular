package com.voyager.sesmatcher.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyager.sesmatcher.dto.ConditionCheckResult;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

@Converter
@Slf4j
public class ConditionCheckResultConverter implements AttributeConverter<ConditionCheckResult, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(ConditionCheckResult attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize ConditionCheckResult to JSON: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public ConditionCheckResult convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(dbData, ConditionCheckResult.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize ConditionCheckResult from JSON: {}", e.getMessage());
            return null;
        }
    }
}
