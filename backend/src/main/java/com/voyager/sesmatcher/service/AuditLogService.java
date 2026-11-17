package com.voyager.sesmatcher.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyager.sesmatcher.dto.AuditLogEntry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private static final int MAX_LOG_ENTRIES = 500;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    private final OAuthSessionTokenService oauthSessionTokenService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ses.audit.log-file:logs/audit.log}")
    private String auditLogFilePath;

    // Stores entries in chronological order (newest at the front via addFirst)
    private final Deque<AuditLogEntry> recentLogs = new ConcurrentLinkedDeque<>();

    @PostConstruct
    public void init() {
        loadExistingLogs();
    }

    /**
     * Records an audit log entry using the currently logged-in sales rep's email and display name.
     */
    public void recordLog(String action, String details) {
        String email = oauthSessionTokenService.getCurrentSalesEmail();
        String name = oauthSessionTokenService.getCurrentSalesName();
        recordLogForUser(
                email != null && !email.isBlank() ? email : "システム / 自動バッチ",
                name != null && !name.isBlank() ? name : (email != null && !email.isBlank() ? email : "System Cron"),
                action,
                details
        );
    }

    /**
     * Records an audit log entry for an explicit user (e.g., upon OAuth2 login completion).
     */
    public synchronized void recordLogForUser(String userEmail, String userName, String action, String details) {
        AuditLogEntry entry = AuditLogEntry.builder()
                .timestamp(LocalDateTime.now().format(FORMATTER))
                .userEmail(userEmail != null && !userEmail.isBlank() ? userEmail : "不明")
                .userName(userName != null && !userName.isBlank() ? userName : userEmail)
                .action(action)
                .details(details)
                .build();

        recentLogs.addFirst(entry);
        while (recentLogs.size() > MAX_LOG_ENTRIES) {
            recentLogs.removeLast();
        }

        appendEntryToFile(entry);
        log.info("📋 [AUDIT LOG] user={} ({}) | action={} | details={}",
                entry.getUserName(), entry.getUserEmail(), entry.getAction(), entry.getDetails());
    }

    /**
     * Returns up to 500 most recent audit log entries (newest first).
     */
    public List<AuditLogEntry> getRecentLogs() {
        return new ArrayList<>(recentLogs);
    }

    private void loadExistingLogs() {
        try {
            Path path = Paths.get(auditLogFilePath);
            if (!Files.exists(path)) {
                return;
            }
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - MAX_LOG_ENTRIES);
            for (int i = start; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (!line.isEmpty()) {
                    try {
                        AuditLogEntry entry = objectMapper.readValue(line, AuditLogEntry.class);
                        recentLogs.addFirst(entry);
                    } catch (Exception ignored) {
                    }
                }
            }
            log.info("Loaded {} existing audit log entries from {}", recentLogs.size(), path.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Could not read existing audit log file {}: {}", auditLogFilePath, e.getMessage());
        }
    }

    private void appendEntryToFile(AuditLogEntry entry) {
        try {
            Path path = Paths.get(auditLogFilePath);
            if (path.getParent() != null && !Files.exists(path.getParent())) {
                Files.createDirectories(path.getParent());
            }
            String jsonLine = objectMapper.writeValueAsString(entry);
            try (BufferedWriter writer = Files.newBufferedWriter(
                    path,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            )) {
                writer.write(jsonLine);
                writer.newLine();
            }
        } catch (Exception e) {
            log.warn("Failed to append audit log entry to {}: {}", auditLogFilePath, e.getMessage());
        }
    }
}
