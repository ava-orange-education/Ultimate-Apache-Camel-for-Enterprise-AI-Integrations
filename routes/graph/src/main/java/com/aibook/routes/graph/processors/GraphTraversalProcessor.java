package com.aibook.routes.graph.processors;

import com.aibook.ai.graph.GraphTraversalService;
import com.aibook.core.dto.GraphContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Performs a graph traversal starting from the entity identified by the
 * {@code entityId} header, and sets the resulting {@link GraphContext} as the
 * exchange body for downstream processors.
 *
 * <p>Uses {@link GraphTraversalService#traverseFromEntity(String, int, List)}
 * with the max depth from {@code aibook.graph.max-traversal-depth} (default 3).
 *
 * <p>Reads headers: {@code entityId} (required), {@code graphMaxDepth} (optional override).<br>
 * Writes body: {@link GraphContext}.<br>
 * Writes headers: {@code traversedNodeCount}, {@code traversedPathCount}.
 */
@Component
public class GraphTraversalProcessor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(GraphTraversalProcessor.class);

    @Value("${aibook.graph.max-traversal-depth:3}")
    private int defaultMaxDepth;

    private final GraphTraversalService graphTraversalService;

    public GraphTraversalProcessor(GraphTraversalService graphTraversalService) {
        this.graphTraversalService = graphTraversalService;
    }

    @Override
    public void process(Exchange exchange) {
        String entityId = exchange.getIn().getHeader("entityId", String.class);
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalStateException(
                    "GraphTraversalProcessor: 'entityId' header is required but missing");
        }

        // Allow per-exchange depth override
        int maxDepth = exchange.getIn().getHeader("graphMaxDepth", defaultMaxDepth, Integer.class);

        log.info("GraphTraversalProcessor: traversing entityId={} maxDepth={}", entityId, maxDepth);

        GraphContext ctx = graphTraversalService.traverseFromEntity(entityId, maxDepth, List.of());

        exchange.getIn().setBody(ctx);
        exchange.getIn().setHeader("traversedNodeCount", ctx.relatedNodes().size());
        exchange.getIn().setHeader("traversedPathCount", ctx.traversedPaths().size());

        log.info("GraphTraversalProcessor: entityId={} nodes={} paths={}",
                entityId, ctx.relatedNodes().size(), ctx.traversedPaths().size());
    }
}
