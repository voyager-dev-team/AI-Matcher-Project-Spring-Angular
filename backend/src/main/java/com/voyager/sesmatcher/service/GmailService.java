package com.voyager.sesmatcher.service;

import com.google.api.client.util.Base64;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.*;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
public class GmailService {

    private final OAuthSessionTokenService oauthSessionTokenService;
    private final SesEmailFilterService filterService;

    @Autowired
    public GmailService(
            OAuthSessionTokenService oauthSessionTokenService,
            SesEmailFilterService filterService
    ) {
        this.oauthSessionTokenService = oauthSessionTokenService;
        this.filterService = filterService;
    }

    /**
     * Resolves the Gmail API client for the currently logged-in user's OAuth2 Access Token.
     */
    private Gmail getGmail() {
        return oauthSessionTokenService.resolveGmailClient();
    }

    public String getActiveSalesEmail() {
        return oauthSessionTokenService.getCurrentSalesEmail();
    }

    public boolean isConnected() {
        return getGmail() != null;
    }

    /**
     * Search emails matching specific query (e.g., subject:"[SES AI Matcher]人材→案件探す")
     */
    public List<Message> searchCandidateEmails(String query) throws Exception {
        Gmail client = getGmail();
        if (client == null) {
            log.warn("Gmail API client is not initialized. Please sign in with Google.");
            return Collections.emptyList();
        }

        ListMessagesResponse response = client.users().messages().list("me")
                .setQ(query)
                .setMaxResults(50L)
                .execute();

        return response.getMessages() != null ? response.getMessages() : Collections.emptyList();
    }

    /**
     * Fetch complete message payload details
     */
    public Message getMessageDetails(String messageId) throws Exception {
        Gmail client = getGmail();
        if (client == null) return null;
        return client.users().messages().get("me", messageId).setFormat("full").execute();
    }

    /**
     * Extract all attachments (PDF, Excel, Word, etc.) from the message payload
     */
    public Map<String, byte[]> extractAttachments(String messageId, Message message) throws Exception {
        Map<String, byte[]> attachments = new HashMap<>();
        Gmail client = getGmail();
        if (client == null || message == null || message.getPayload() == null) {
            return attachments;
        }

        collectAttachmentsRecursive(client, messageId, message.getPayload(), attachments);
        return attachments;
    }

    private void collectAttachmentsRecursive(Gmail client, String messageId, MessagePart part, Map<String, byte[]> result) throws Exception {
        if (part == null) return;

        String filename = part.getFilename();
        if (filename != null && !filename.trim().isEmpty() && part.getBody() != null && part.getBody().getAttachmentId() != null) {
            MessagePartBody attachmentPart = client.users().messages().attachments()
                    .get("me", messageId, part.getBody().getAttachmentId())
                    .execute();

            if (attachmentPart != null && attachmentPart.getData() != null) {
                byte[] data = Base64.decodeBase64(attachmentPart.getData());
                result.put(filename, data);
                log.info("Extracted attachment [{}] ({} bytes) from messageId [{}]", filename, data.length, messageId);
            }
        }

        if (part.getParts() != null) {
            for (MessagePart subPart : part.getParts()) {
                collectAttachmentsRecursive(client, messageId, subPart, result);
            }
        }
    }

    /**
     * Collects all attachment filenames from message payload
     */
    public List<String> getAttachmentFilenames(Message message) {
        List<String> filenames = new ArrayList<>();
        if (message != null && message.getPayload() != null) {
            collectFilenamesRecursive(message.getPayload(), filenames);
        }
        return filenames;
    }

    private void collectFilenamesRecursive(MessagePart part, List<String> filenames) {
        if (part == null) return;
        if (part.getFilename() != null && !part.getFilename().isBlank()) {
            filenames.add(part.getFilename());
        }
        if (part.getParts() != null) {
            for (MessagePart subPart : part.getParts()) {
                collectFilenamesRecursive(subPart, filenames);
            }
        }
    }

    /**
     * Fetch project inquiry emails from the currently logged-in Sales Rep's inbox,
     * strictly validating that they are authentic SES project proposals.
     */
    public List<Map<String, String>> fetchProjectEmails() throws Exception {
        List<Map<String, String>> projects = new ArrayList<>();
        Gmail client = getGmail();
        if (client == null) {
            return projects;
        }

        String targetSalesEmail = getActiveSalesEmail();
        if (targetSalesEmail == null || targetSalesEmail.isBlank()) {
            log.warn("No authenticated sales user email found in session.");
            return projects;
        }

        // Search query targeting project emails received by the logged-in sales representative,
        // excluding automated senders, calendar attachments, and self-generated drafts
        String query = "to:" + targetSalesEmail + " (案件 OR 【案件】 OR 募集 OR 要員) -from:(noreply OR no-reply OR notifications OR calendar-notification) -filename:ics -subject:\"【提案下書き】\"";
        ListMessagesResponse response = client.users().messages().list("me")
                .setQ(query)
                .setMaxResults(30L)
                .execute();

        if (response.getMessages() == null) {
            // Fallback search in INBOX with exclusions
            response = client.users().messages().list("me")
                    .setQ("label:INBOX (案件 OR 募集 OR 要員) -from:(noreply OR no-reply OR notifications) -filename:ics -subject:\"【提案下書き】\"")
                    .setMaxResults(20L)
                    .execute();
        }

        if (response.getMessages() == null) return projects;

        for (Message item : response.getMessages()) {
            Message full = getMessageDetails(item.getId());
            if (full == null) continue;

            String subject = getHeaderValue(full, "Subject");
            String sender = getHeaderValue(full, "From");
            String body = extractMessageBody(full.getPayload());
            List<String> attachments = getAttachmentFilenames(full);

            // Comprehensive SES project validation
            if (filterService.isLegitimateProjectEmail(subject, sender, body, attachments)) {
                LocalDateTime receivedAt = extractReceivedAt(full);
                Map<String, String> project = new HashMap<>();
                project.put("id", full.getId());
                project.put("subject", subject != null && !subject.isBlank() ? subject : "無題の案件メール");
                project.put("sender", sender);
                project.put("body", filterService.stripHtml(body));
                project.put("receivedAt", receivedAt.toString());
                projects.add(project);
                log.info("✅ Validated legitimate SES project email [id={}, receivedAt={}]: {}", full.getId(), receivedAt, subject);
            } else {
                log.info("⛔ Filtered out non-project email [id={}]: {}", full.getId(), subject);
            }
        }

        log.info("Fetched and validated {} genuine project emails from sales inbox ({})", projects.size(), targetSalesEmail);
        return projects;
    }

    /**
     * Extracts the received date/time from Gmail message internalDate (epoch ms) or Date header.
     */
    public LocalDateTime extractReceivedAt(Message message) {
        ZoneId tokyoZone = ZoneId.of("Asia/Tokyo");
        if (message != null && message.getInternalDate() != null) {
            try {
                return Instant.ofEpochMilli(message.getInternalDate())
                        .atZone(tokyoZone)
                        .toLocalDateTime();
            } catch (Exception ignored) {
            }
        }
        if (message != null) {
            String dateHeader = getHeaderValue(message, "Date");
            if (dateHeader != null && !dateHeader.isBlank()) {
                try {
                    return ZonedDateTime.parse(dateHeader.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                            .withZoneSameInstant(tokyoZone)
                            .toLocalDateTime();
                } catch (Exception ignored) {
                }
            }
        }
        return LocalDateTime.now(tokyoZone);
    }

    /**
     * Create Gmail Draft under the authenticated account addressed to the logged-in Sales Rep
     */
    public String createDraftEmail(String toEmail, String subject, String bodyText) throws Exception {
        Gmail client = getGmail();
        if (client == null) {
            log.warn("Cannot create draft. Gmail client not initialized.");
            return null;
        }

        String recipientEmail = (toEmail != null && !toEmail.isBlank()) ? toEmail : getActiveSalesEmail();
        if (recipientEmail == null || recipientEmail.isBlank()) {
            log.warn("Cannot create draft. Logged-in sales email is not available.");
            return null;
        }

        Properties props = new Properties();
        Session session = Session.getDefaultInstance(props, null);

        MimeMessage email = new MimeMessage(session);
        email.setFrom(new InternetAddress(recipientEmail));
        email.addRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(recipientEmail));
        email.setSubject(subject, "UTF-8");
        email.setText(bodyText, "UTF-8");

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        email.writeTo(buffer);
        byte[] bytes = buffer.toByteArray();
        String encodedEmail = Base64.encodeBase64URLSafeString(bytes);

        Message message = new Message();
        message.setRaw(encodedEmail);

        Draft draft = new Draft();
        draft.setMessage(message);

        Draft createdDraft = client.users().drafts().create("me", draft).execute();
        log.info("Created Gmail Draft successfully! Draft ID: [{}] sent to [{}]", createdDraft.getId(), recipientEmail);
        return createdDraft.getId();
    }

    /**
     * Moves the specified Gmail message to Trash via Gmail API using the current user's token.
     */
    public void trashMessage(String messageId) throws Exception {
        Gmail client = getGmail();
        if (client == null) {
            throw new IllegalStateException("Gmail API client is not initialized.");
        }
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("Message ID is required to move email to trash.");
        }
        client.users().messages().trash("me", messageId.trim()).execute();
        log.info("🗑️ Moved Gmail message [{}] to Trash.", messageId);
    }

    public String getHeaderValue(Message message, String headerName) {
        if (message == null || message.getPayload() == null || message.getPayload().getHeaders() == null) {
            return "";
        }
        return message.getPayload().getHeaders().stream()
                .filter(h -> headerName.equalsIgnoreCase(h.getName()))
                .map(MessagePartHeader::getValue)
                .findFirst()
                .orElse("");
    }

    public String extractMessageBody(MessagePart part) {
        if (part == null) return "";

        // Check if current part contains text
        if (part.getBody() != null && part.getBody().getData() != null) {
            String mimeType = part.getMimeType();
            if (mimeType != null && (mimeType.contains("text/plain") || mimeType.contains("text/html"))) {
                return new String(Base64.decodeBase64(part.getBody().getData()), StandardCharsets.UTF_8);
            }
        }

        // Traverse sub-parts
        if (part.getParts() != null) {
            StringBuilder sb = new StringBuilder();
            for (MessagePart sub : part.getParts()) {
                String subBody = extractMessageBody(sub);
                if (!subBody.isBlank()) {
                    sb.append(subBody).append("\n");
                }
            }
            return sb.toString();
        }

        return "";
    }
}
