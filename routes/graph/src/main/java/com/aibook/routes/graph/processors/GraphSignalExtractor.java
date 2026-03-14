package com.aibook.routes.graph.processors;

import com.aibook.core.dto.GraphContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Converts a {@link GraphContext} into a flat set of numeric signals and sets them
 * as exchange headers for use by downstream processors (particularly
 * {@link GraphAwareDecisionMaker}).
 *
 * <h3>Signals computed</h3>
 * <table border="1">
 *   <tr><th>Header</th><th>Description</th></tr>
 *   <tr><td>{@code connectedEntityCount}</td><td>Number of nodes reachable from root</td></tr>
 *   <tr><td>{@code avgRelationshipStrength}</td>
 *       <td>Mean of {@code strength} property across traversed relationships; 0.5 if absent</td></tr>
 *   <tr><td>{@code clusterDensity}</td>
 *       <td>paths / max(nodes,1) — ratio of edges to nodes (approximates density)</td></tr>
 *   <tr><td>{@code maxPathDepth}</td><td>{@link GraphContext#traversalDepth()}</td></tr>
 *   <tr><td>{@code graphSignalsExtracted}</td><td>{@code true} once complete</td></tr>
 * </table>
 *
 * <p>Reads body: {@link GraphContext}.<br>
 * Preserves body unchanged.
 */
@Component
public class GraphSignalExtractor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(GraphSignalExtractor.class);

    @Override
    public void process(Exchange exchange) {
        GraphContext ctx = exchange.getIn().getBody(GraphContext.class);
        if (ctx == null) {
            log.warn("GraphSignalExtractor: body is null — skipping signal extraction");
            exchange.getIn().setHeader("graphSignalsExtracted", false);
            return;
        }

        List<Map<String, Object>> nodes = ctx.relatedNodes();
        List<String>              paths = ctx.traversedPaths();

        int connectedEntityCount = nodes.size();

        // Average the 'strength' property from node maps; default 0.5 if absent
        double totalStrength = 0.0;
        int    strengthCount = 0;
        for (Map<String, Object> node : nodes) {
            Object strength = node.get("strength");
            if (strength != null) {
                try {
                    totalStrength += Double.parseDouble(strength.toString());
                    strengthCount++;
                } catch (NumberFormatException ignored) { /* skip */ }
            }
        }
        double avgRelationshipStrength = strengthCount > 0
                ? totalStrength / strengthCount
                : 0.5;   // neutral default

        // Cluster density: edge/node ratio (paths / nodes), capped at 1.0
        double clusterDensity = connectedEntityCount > 0
                ? Math.min(1.0, (double) paths.size() / connectedEntityCount)
                : 0.0;

        int maxPathDepth = ctx.traversalDepth();

        // Set all signals as headers
        exchange.getIn().setHeader("connectedEntityCount",    connectedEntityCount);
        exchange.getIn().setHeader("avgRelationshipStrength", avgRelationshipStrength);
        exchange.getIn().setHeader("clusterDensity",          clusterDensity);
        exchange.getIn().setHeader("maxPathDepth",            maxPathDepth);
        exchange.getIn().setHeader("graphSignalsExtracted",   true);

        // Record in exchange properties for audit
        exchange.setProperty("signals", java.util.Map.of(
                "connectedEntityCount",    connectedEntityCount,
                "avgRelationshipStrength", avgRelationshipStrength,
                "clusterDensity",          clusterDensity,
                "maxPathDepth",            maxPathDepth
        ));

        log.info("GraphSignalExtractor: entityId={} nodes={} avgStrength={} density={} depth={}",
                ctx.entityId(), connectedEntityCount,
                String.format("%.2f", avgRelationshipStrength),
                String.format("%.2f", clusterDensity), maxPathDepth);
    }
}
