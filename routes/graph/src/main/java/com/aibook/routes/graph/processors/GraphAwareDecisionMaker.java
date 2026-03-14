package com.aibook.routes.graph.processors;

import com.aibook.core.dto.ScoringResult;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Combines the original scoring signal with graph-derived signals to produce a
 * final, graph-aware routing decision.
 *
 * <h3>Risk elevation rules (Chapter 12)</h3>
 * <p>Risk is elevated (i.e. {@code graphRiskElevated=true}) when ALL of:
 * <ol>
 *   <li>{@code connectedEntityCount} &gt; {@code riskClusterThreshold} (default 5)</li>
 *   <li>{@code avgRelationshipStrength} &gt; {@code strengthThreshold} (default 0.7)</li>
 * </ol>
 *
 * <p>Additionally, risk is elevated when:
 * <ul>
 *   <li>{@code clusterDensity} exceeds {@code densityThreshold} (default 0.8) — tightly
 *       interconnected clusters are strong fraud indicators</li>
 * </ul>
 *
 * <h3>Headers read</h3>
 * {@code connectedEntityCount}, {@code avgRelationshipStrength}, {@code clusterDensity},
 * {@code maxPathDepth}, {@code score}, {@code confidence}
 *
 * <h3>Headers written</h3>
 * <ul>
 *   <li>{@code graphRiskElevated}   — {@code true} if risk elevation triggered</li>
 *   <li>{@code graphRiskReason}     — human-readable explanation of the elevation</li>
 *   <li>{@code routingDecision}     — updated to "ESCALATE" if risk elevated</li>
 * </ul>
 *
 * <p>If the exchange body is a {@link ScoringResult}, it is rebuilt with the updated
 * {@code routingDecision}.
 */
@Component
public class GraphAwareDecisionMaker implements Processor {

    private static final Logger log = LoggerFactory.getLogger(GraphAwareDecisionMaker.class);

    @Value("${aibook.graph.risk-cluster-threshold:5}")
    private int riskClusterThreshold;

    @Value("${aibook.graph.risk-strength-threshold:0.7}")
    private double strengthThreshold;

    @Value("${aibook.graph.risk-density-threshold:0.8}")
    private double densityThreshold;

    @Override
    public void process(Exchange exchange) {
        int    connectedCount  = intHeader(exchange,    "connectedEntityCount",    0);
        double avgStrength     = doubleHeader(exchange, "avgRelationshipStrength", 0.0);
        double clusterDensity  = doubleHeader(exchange, "clusterDensity",          0.0);
        int    maxDepth        = intHeader(exchange,    "maxPathDepth",            0);
        double score           = doubleHeader(exchange, "score",                   0.0);
        double confidence      = doubleHeader(exchange, "confidence",              1.0);

        // ── Risk evaluation ───────────────────────────────────────────────────
        boolean clusterRisk  = connectedCount > riskClusterThreshold && avgStrength > strengthThreshold;
        boolean densityRisk  = clusterDensity > densityThreshold;
        boolean graphRiskElevated = clusterRisk || densityRisk;

        // Build a human-readable reason
        StringBuilder reason = new StringBuilder();
        if (clusterRisk) {
            reason.append(String.format(
                    "cluster risk: %d connected entities (threshold %d) with avg strength %.2f (threshold %.2f)",
                    connectedCount, riskClusterThreshold, avgStrength, strengthThreshold));
        }
        if (densityRisk) {
            if (!reason.isEmpty()) reason.append("; ");
            reason.append(String.format(
                    "density risk: cluster density %.2f exceeds threshold %.2f",
                    clusterDensity, densityThreshold));
        }
        if (!graphRiskElevated) {
            reason.append(String.format(
                    "no graph risk: entities=%d strength=%.2f density=%.2f",
                    connectedCount, avgStrength, clusterDensity));
        }

        // ── Set headers ───────────────────────────────────────────────────────
        exchange.getIn().setHeader("graphRiskElevated", graphRiskElevated);
        exchange.getIn().setHeader("graphRiskReason",   reason.toString());

        if (graphRiskElevated) {
            exchange.getIn().setHeader("routingDecision", "ESCALATE");
        }

        // ── Update ScoringResult body if present ──────────────────────────────
        Object body = exchange.getIn().getBody();
        if (body instanceof ScoringResult sr) {
            String newDecision = graphRiskElevated ? "ESCALATE" : sr.routingDecision();

            // Merge graph signals into featuresUsed
            Map<String, Object> enrichedFeatures = new HashMap<>(sr.featuresUsed());
            enrichedFeatures.put("graph_risk_elevated",           graphRiskElevated);
            enrichedFeatures.put("graph_connected_entity_count",  connectedCount);
            enrichedFeatures.put("graph_avg_relationship_strength", avgStrength);
            enrichedFeatures.put("graph_cluster_density",         clusterDensity);

            ScoringResult updated = new ScoringResult(
                    sr.requestId(), sr.entityId(), sr.score(), sr.confidence(),
                    sr.modelVersion(), newDecision, enrichedFeatures, sr.scoredAt());
            exchange.getIn().setBody(updated);
        }

        // ── Set audit exchange properties ─────────────────────────────────────
        exchange.setProperty("stage",   "graph-aware-decision");
        exchange.setProperty("signals", Map.of(
                "graphRiskElevated", graphRiskElevated,
                "connectedEntities", connectedCount,
                "avgStrength",       avgStrength,
                "clusterDensity",    clusterDensity
        ));

        log.info("GraphAwareDecisionMaker: entityId={} elevated={} reason={}",
                exchange.getIn().getHeader("entityId"), graphRiskElevated, reason);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double doubleHeader(Exchange ex, String name, double fallback) {
        Object val = ex.getIn().getHeader(name);
        if (val == null) return fallback;
        try { return Double.parseDouble(val.toString()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private int intHeader(Exchange ex, String name, int fallback) {
        Object val = ex.getIn().getHeader(name);
        if (val == null) return fallback;
        try { return Integer.parseInt(val.toString()); }
        catch (NumberFormatException e) { return fallback; }
    }
}
