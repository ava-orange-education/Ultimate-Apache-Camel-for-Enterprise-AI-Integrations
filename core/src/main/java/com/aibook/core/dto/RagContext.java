package com.aibook.core.dto;

import java.util.List;

/**
 * Carries all context assembled for one RAG query cycle.
 * {@code relevanceScores} is parallel to {@code retrievedChunks}.
 */
public record RagContext(
        String queryId,
        String originalQuery,
        String embeddedQuery,
        List<String> retrievedChunks,
        List<Float> relevanceScores,
        String assembledContext
) {
    public RagContext {
        queryId          = queryId          != null ? queryId          : java.util.UUID.randomUUID().toString();
        originalQuery    = originalQuery    != null ? originalQuery    : "";
        embeddedQuery    = embeddedQuery    != null ? embeddedQuery    : "";
        retrievedChunks  = retrievedChunks  != null ? List.copyOf(retrievedChunks)  : List.of();
        relevanceScores  = relevanceScores  != null ? List.copyOf(relevanceScores)  : List.of();
        assembledContext = assembledContext != null ? assembledContext : "";
    }
}