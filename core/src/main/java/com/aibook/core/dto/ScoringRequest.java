package com.aibook.core.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Input to the scoring pipeline.
 * {@code features} is an immutable snapshot of whatever feature vector
 * the FeatureAssemblyRoute has built.
 */
public record ScoringRequest(
        String requestId,
        String entityId,
        String entityType,
        String scoringProfile,
        Map<String, Object> features,
        Instant requestTime
) {
    public ScoringRequest {
        requestId      = requestId      != null ? requestId      : java.util.UUID.randomUUID().toString();
        entityId       = entityId       != null ? entityId       : "";
        entityType     = entityType     != null ? entityType     : "";
        scoringProfile = scoringProfile != null ? scoringProfile : "";
        features       = features       != null ? Map.copyOf(features) : Map.of();
        requestTime    = requestTime    != null ? requestTime    : Instant.now();
    }
}