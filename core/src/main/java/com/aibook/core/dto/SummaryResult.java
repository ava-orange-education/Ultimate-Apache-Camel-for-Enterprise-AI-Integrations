package com.aibook.core.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.ser.InstantSerializer;

import java.time.Instant;

/**
 * Output of the summarization pipeline.
 * {@code summaryType} is one of: "email", "document", "thread".
 * {@code validated} is set to {@code true} by {@code SummaryValidator}.
 */
public record SummaryResult(
        String sourceId,
        String summaryText,
        String summaryType,
        @JsonSerialize(using = InstantSerializer.class)
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant generatedAt,
        String modelVersion,
        boolean validated
) {
    public SummaryResult {
        sourceId     = sourceId     != null ? sourceId     : "";
        summaryText  = summaryText  != null ? summaryText  : "";
        summaryType  = summaryType  != null ? summaryType  : "document";
        generatedAt  = generatedAt  != null ? generatedAt  : Instant.now();
        modelVersion = modelVersion != null ? modelVersion : "unknown";
    }
}