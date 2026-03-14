package com.aibook.core.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Explainability artifact captured for every AI pipeline decision.
 * {@code evidence} holds raw feature/retrieval data that influenced the decision.
 * {@code signals} holds the derived or weighted signals computed from evidence.
 * {@code rationale} is the LLM-generated human-readable explanation.
 */
public record ExplanationArtifact(
        String decisionId,
        Map<String, Object> evidence,
        Map<String, Object> signals,
        String rationale,
        String modelVersion,
        Instant capturedAt
) {
    public ExplanationArtifact {
        decisionId   = decisionId   != null ? decisionId   : java.util.UUID.randomUUID().toString();
        evidence     = evidence     != null ? Map.copyOf(evidence) : Map.of();
        signals      = signals      != null ? Map.copyOf(signals)  : Map.of();
        rationale    = rationale    != null ? rationale    : "";
        modelVersion = modelVersion != null ? modelVersion : "unknown";
        capturedAt   = capturedAt   != null ? capturedAt   : Instant.now();
    }
}