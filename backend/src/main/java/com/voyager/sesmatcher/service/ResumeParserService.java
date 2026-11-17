package com.voyager.sesmatcher.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

@Service
@Slf4j
public class ResumeParserService {

    private final AutoDetectParser parser = new AutoDetectParser();

    /**
     * Extracts text content from PDF, Excel (.xlsx, .xls), Word (.docx, .doc), or text attachments
     * using Apache Tika AutoDetectParser.
     */
    public String parseResume(byte[] fileBytes, String fileName) {
        if (fileBytes == null || fileBytes.length == 0) {
            log.warn("Empty file content received for parsing: {}", fileName);
            return "";
        }

        try (InputStream stream = new ByteArrayInputStream(fileBytes)) {
            // Use -1 for unlimited character extraction from lengthy resumes
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
            ParseContext context = new ParseContext();

            parser.parse(stream, handler, metadata, context);
            String text = handler.toString().trim();
            log.info("Successfully parsed [{}] - Extracted {} characters", fileName, text.length());
            return text;
        } catch (Exception e) {
            log.error("Failed to parse resume file [{}]: {}", fileName, e.getMessage(), e);
            return "";
        }
    }
}
