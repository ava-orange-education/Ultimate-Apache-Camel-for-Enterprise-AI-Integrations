package com.aibook.routes.graph.processors;

import com.aibook.core.dto.GraphContext;
import com.aibook.core.dto.ScoringResult;
import org.apache.camel.AggregationStrategy;
import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Aggregation strategy used by the Camel {@code enrich()} DSL to merge a
 * {@link GraphContext} (from {@code direct:traverseGraph}) back into the
 * original exchange that carries a {@link ScoringResult} or scoring-related body.
 *
 * <h3>Merge rules</h3>
 * <ol>
 *   <li>The original body is preserved unchanged.</li>
 *   <li>Graph signals computed by {@link GraphSignalExtractor} are merged from
 *       the resource exchange headers into the original exchange headers.</li>
 *   <li>The {@link GraphContext} from the resource body is stashed in the original
 *       exchange property {@code graphContext} for downstream audit.</li>
 *   <li>If the original body is a {@link ScoringResult}, its {@code featuresUsed}
 *       map is enriched with the graph signals (returned as a new immutable record).</li>
 * </ol>
 */
@Component
public class GraphContextMergeStrategy implements AggregationStrategy {

    private static final Logger log = LoggerFactory.getLogger(GraphContextMergeStrategy.class);

    /** Header names produced by {@link GraphSignalExtractor}. */
    private static final String[] SIGNAL_HEADERS = {
            "connectedEntityCount",
            "avgRelationshipStrength",
            "clusterDensity",
            "maxPathDepth",
            "graphSignalsExtracted",
            "traversedNodeCount",
            "traversedPathCount"
    };

    @Override
    public Exchange aggregate(Exchange original, Exchange resource) {
        if (original == null) return resource;
        if (resource  == null) return original;

        // ── Copy graph signal headers from resource into original ──────────────
        for (String headerName : SIGNAL_HEADERS) {
            Object val = resource.getIn().getHeader(headerName);
            if (val != null) {
                original.getIn().setHeader(headerName, val);
            }
        }

        // ── Stash the GraphContext for audit ──────────────────────────────────
        GraphContext graphCtx = resource.getIn().getBody(GraphContext.class);
        if (graphCtx != null) {
            original.setProperty("graphContext", graphCtx);
            log.debug("GraphContextMergeStrategy: stashed GraphContext entityId={} nodes={}",
                    graphCtx.entityId(), graphCtx.relatedNodes().size());
        }

        // ── Enrich ScoringResult.featuresUsed with graph signals ──────────────
        Object originalBody = original.getIn().getBody();
        if (originalBody instanceof ScoringResult sr && graphCtx != null) {
            Map<String, Object> enrichedFeatures = new HashMap<>(sr.featuresUsed());
            enrichedFeatures.put("graph_connected_entity_count",
                    original.getIn().getHeader("connectedEntityCount", 0, Integer.class));
            enrichedFeatures.put("graph_avg_relationship_strength",
                    original.getIn().getHeader("avgRelationshipStrength", 0.5, Double.class));
            enrichedFeatures.put("graph_cluster_density",
                    original.getIn().getHeader("clusterDensity", 0.0, Double.class));
            enrichedFeatures.put("graph_traversal_depth",
                    original.getIn().getHeader("maxPathDepth", 0, Integer.class));

            ScoringResult enriched = new ScoringResult(
                    sr.requestId(), sr.entityId(), sr.score(), sr.confidence(),
                    sr.modelVersion(), sr.routingDecision(), enrichedFeatures, sr.scoredAt());
            original.getIn().setBody(enriched);

            log.debug("GraphContextMergeStrategy: enriched ScoringResult features with {} graph signals",
                    enrichedFeatures.size() - sr.featuresUsed().size());
        }

        log.info("GraphContextMergeStrategy: merged graph context into original exchange");
        return original;
    }
}
