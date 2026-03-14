package com.aibook.core.dto;

import java.util.List;
import java.util.Map;

/**
 * Graph-traversal result for a single entity lookup.
 * {@code traversedPaths} holds the Cypher path strings for audit/explainability.
 * {@code relatedNodes} is an ordered list of property-maps, one per discovered node.
 */
public record GraphContext(
        String queryId,
        String entityId,
        List<String> traversedPaths,
        List<Map<String, Object>> relatedNodes,
        int traversalDepth
) {
    public GraphContext {
        queryId        = queryId        != null ? queryId        : java.util.UUID.randomUUID().toString();
        entityId       = entityId       != null ? entityId       : "";
        traversedPaths = traversedPaths != null ? List.copyOf(traversedPaths) : List.of();
        relatedNodes   = relatedNodes   != null ? List.copyOf(relatedNodes)   : List.of();
    }
}