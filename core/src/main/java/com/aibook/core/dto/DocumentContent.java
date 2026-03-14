package com.aibook.core.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Represents a raw document (any MIME type) after text extraction.
 * {@code metadata} is an immutable copy of whatever key/value pairs
 * the extraction step produces (author, page-count, language, etc.).
 *
 * <p>{@code extractedText} also accepts the alias {@code "content"} to
 * stay compatible with ingestion payloads that use that field name.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentContent(
        String documentId,
        String fileName,
        String title,
        @JsonAlias("content")
        String extractedText,
        String contentType,
        Map<String, String> metadata
) {
    public DocumentContent {
        documentId    = documentId    != null ? documentId    : java.util.UUID.randomUUID().toString();
        fileName      = fileName      != null ? fileName      : "";
        title         = title         != null ? title         : "";
        extractedText = extractedText != null ? extractedText : "";
        contentType   = contentType   != null ? contentType   : "text/plain";
        metadata      = metadata      != null ? Map.copyOf(metadata) : Map.of();
    }
}