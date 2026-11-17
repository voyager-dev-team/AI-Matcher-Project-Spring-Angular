package com.voyager.sesmatcher.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class CandidateStatusService {

    public static final String STATUS_SEARCHING = "SEARCHING"; // 🟢 案件探し中
    public static final String STATUS_ASSIGNED = "ASSIGNED";   // 🔴 現場決定済

    private static final String STORAGE_FILE = "candidate-status.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, CandidateState> stateMap = new ConcurrentHashMap<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CandidateState {
        private String candidateFileName;
        private String candidateEmailId;
        private String candidateSubject;
        private String status; // "SEARCHING" or "ASSIGNED"
    }

    @PostConstruct
    public void init() {
        loadFromDisk();
    }

    /**
     * Registers or updates metadata for a candidate while preserving existing status.
     */
    public void registerCandidateMetadata(String fileName, String emailId, String subject) {
        if (fileName == null || fileName.isBlank()) return;
        stateMap.compute(fileName, (key, existing) -> {
            if (existing == null) {
                return CandidateState.builder()
                        .candidateFileName(fileName)
                        .candidateEmailId(emailId)
                        .candidateSubject(subject)
                        .status(STATUS_SEARCHING)
                        .build();
            } else {
                if (emailId != null && !emailId.isBlank()) existing.setCandidateEmailId(emailId);
                if (subject != null && !subject.isBlank()) existing.setCandidateSubject(subject);
                if (existing.getStatus() == null) existing.setStatus(STATUS_SEARCHING);
                return existing;
            }
        });
        saveToDisk();
    }

    public boolean isAssigned(String candidateFileName) {
        return STATUS_ASSIGNED.equalsIgnoreCase(getStatus(candidateFileName));
    }

    public String getStatus(String candidateFileName) {
        if (candidateFileName == null) return STATUS_SEARCHING;
        CandidateState state = stateMap.get(candidateFileName);
        return (state != null && state.getStatus() != null) ? state.getStatus() : STATUS_SEARCHING;
    }

    public CandidateState updateStatus(String candidateFileName, String newStatus, String emailId, String subject) {
        String normalized = STATUS_ASSIGNED.equalsIgnoreCase(newStatus) ? STATUS_ASSIGNED : STATUS_SEARCHING;
        CandidateState updated = stateMap.compute(candidateFileName, (key, existing) -> {
            if (existing == null) {
                return CandidateState.builder()
                        .candidateFileName(candidateFileName)
                        .candidateEmailId(emailId)
                        .candidateSubject(subject)
                        .status(normalized)
                        .build();
            } else {
                existing.setStatus(normalized);
                if (emailId != null && !emailId.isBlank()) existing.setCandidateEmailId(emailId);
                if (subject != null && !subject.isBlank()) existing.setCandidateSubject(subject);
                return existing;
            }
        });
        saveToDisk();
        log.info("Updated candidate [{}] status to [{}]", candidateFileName, normalized);
        return updated;
    }

    public Collection<CandidateState> getAllStates() {
        return stateMap.values();
    }

    public void removeCandidate(String candidateFileName, String candidateEmailId) {
        if (candidateFileName != null && !candidateFileName.isBlank()) {
            stateMap.remove(candidateFileName);
        }
        if (candidateEmailId != null && !candidateEmailId.isBlank()) {
            stateMap.entrySet().removeIf(entry ->
                    candidateEmailId.equals(entry.getValue().getCandidateEmailId())
            );
        }
        saveToDisk();
    }

    private synchronized void loadFromDisk() {
        File file = new File(STORAGE_FILE);
        if (!file.exists()) {
            return;
        }
        try {
            Map<String, CandidateState> loaded = objectMapper.readValue(
                    file,
                    new TypeReference<Map<String, CandidateState>>() {}
            );
            if (loaded != null) {
                stateMap.clear();
                stateMap.putAll(loaded);
                log.info("Loaded {} candidate status entries from {}", stateMap.size(), file.getAbsolutePath());
            }
        } catch (IOException e) {
            log.warn("Could not read {}: {}", STORAGE_FILE, e.getMessage());
        }
    }

    private synchronized void saveToDisk() {
        File file = new File(STORAGE_FILE);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, stateMap);
        } catch (IOException e) {
            log.error("Failed to save candidate status to {}: {}", STORAGE_FILE, e.getMessage());
        }
    }
}
