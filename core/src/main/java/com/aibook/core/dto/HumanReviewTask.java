package com.aibook.core.dto;

import java.time.Instant;

/**
 * Task dispatched to a human reviewer when the pipeline cannot proceed automatically.
 * {@code priority} is one of: "LOW", "MEDIUM", "HIGH", "URGENT".
 */
public record HumanReviewTask(
        String taskId,
        String decisionId,
        ExplanationArtifact explanation,
        ScoringResult scoringResult,
        String reviewQueue,
        String priority,
        Instant createdAt
) {
    public HumanReviewTask {
        taskId      = taskId      != null ? taskId      : java.util.UUID.randomUUID().toString();
        decisionId  = decisionId  != null ? decisionId  : "";
        reviewQueue = reviewQueue != null ? reviewQueue : "direct:human-review";
        priority    = priority    != null ? priority    : "MEDIUM";
        createdAt   = createdAt   != null ? createdAt   : Instant.now();
    }
}