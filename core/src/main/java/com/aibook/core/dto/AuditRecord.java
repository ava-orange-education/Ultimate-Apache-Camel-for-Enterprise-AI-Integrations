package com.aibook.core.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable audit record written at every significant pipeline step.
 * {@code inputs} and {@code outputs} capture serialized snapshots of the
 * exchange body/headers before and after processing.
 */
public record AuditRecord(
        String auditId,
        String decisionId,
        String pipelineStage,
        Map<String, Object> inputs,
        Map<String, Object> outputs,
        String modelVersion,
        Instant timestamp
) {
    public AuditRecord {
        auditId       = auditId       != null ? auditId       : java.util.UUID.randomUUID().toString();
        decisionId    = decisionId    != null ? decisionId    : "";
        pipelineStage = pipelineStage != null ? pipelineStage : "";
        inputs        = inputs        != null ? Map.copyOf(inputs)   : Map.of();
        outputs       = outputs       != null ? Map.copyOf(outputs)  : Map.of();
        modelVersion  = modelVersion  != null ? modelVersion  : "unknown";
        timestamp     = timestamp     != null ? timestamp     : Instant.now();
    }
}