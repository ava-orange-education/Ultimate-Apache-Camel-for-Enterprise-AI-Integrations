package com.aibook.core.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.ser.InstantSerializer;

import java.time.Instant;
import java.util.Map;

/**
 * Output of the scoring pipeline.
 * {@code routingDecision} is one of: "APPROVED", "REVIEW", "HIGH_RISK", "REJECTED".
 * {@code featuresUsed} is the subset of features that influenced the score.
 */
public record ScoringResult(
        String requestId,
        String entityId,
        double score,
        double confidence,
        String modelVersion,
        String routingDecision,
        Map<String, Object> featuresUsed,
        @JsonSerialize(using = InstantSerializer.class)
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant scoredAt
) {
    public ScoringResult {
        requestId        = requestId        != null ? requestId        : "";
        entityId         = entityId         != null ? entityId         : "";
        modelVersion     = modelVersion     != null ? modelVersion     : "unknown";
        routingDecision  = routingDecision  != null ? routingDecision  : "REVIEW";
        featuresUsed     = featuresUsed     != null ? Map.copyOf(featuresUsed) : Map.of();
        scoredAt         = scoredAt         != null ? scoredAt         : Instant.now();
    }
}