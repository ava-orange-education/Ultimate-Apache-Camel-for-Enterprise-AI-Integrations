package com.aibook.routes.graph;

import com.aibook.core.error.AiErrorHandler;
import com.aibook.core.processors.AuditArtifactCollector;
import com.aibook.routes.graph.processors.GraphAwareDecisionMaker;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Graph decision route — combines the scoring signal with graph-derived signals
 * to produce a final graph-aware routing decision.
 *
 * <h3>Pipeline flow</h3>
 * <pre>
 *   direct:graphDecision
 *     → graphAwareDecisionMaker  (evaluates cluster/density risk thresholds)
 *     → auditArtifactCollector   (captures audit trail entry)
 *     → choice()
 *           WHEN graphRiskElevated=true
 *               → direct:escalateFlow  (risk cluster detected)
 *           OTHERWISE
 *               → direct:contextualRouting  (continue normal scoring flow)
 * </pre>
 *
 * <h3>Graph risk elevation criteria (from {@link GraphAwareDecisionMaker})</h3>
 * <ul>
 *   <li>{@code connectedEntityCount} &gt; 5 AND {@code avgRelationshipStrength} &gt; 0.7</li>
 *   <li>OR {@code clusterDensity} &gt; 0.8</li>
 * </ul>
 */
@Component
public class GraphDecisionRoute extends RouteBuilder {

    private final GraphAwareDecisionMaker graphAwareDecisionMaker;
    private final AuditArtifactCollector  auditCollector;
    private final AiErrorHandler          aiErrorHandler;

    public GraphDecisionRoute(GraphAwareDecisionMaker graphAwareDecisionMaker,
                              AuditArtifactCollector auditCollector,
                              AiErrorHandler aiErrorHandler) {
        this.graphAwareDecisionMaker = graphAwareDecisionMaker;
        this.auditCollector          = auditCollector;
        this.aiErrorHandler          = aiErrorHandler;
    }

    @Override
    public void configure() {

        // ── Error handling ────────────────────────────────────────────────────
        errorHandler(aiErrorHandler.build());

        onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "GraphDecisionRoute error [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:graphDecision
        // Input:  exchange body (ScoringResult or GraphContext) + graph signal headers
        // Output: routed to either direct:escalateFlow or direct:contextualRouting
        // ═════════════════════════════════════════════════════════════════════
        from("direct:graphDecision")
                .routeId("graph-decision")
                .log(LoggingLevel.INFO,
                     "GraphDecisionRoute: evaluating graph-aware decision for "
                     + "entityId=${header.entityId} "
                     + "connectedEntities=${header.connectedEntityCount} "
                     + "strength=${header.avgRelationshipStrength}")

                // Combine score + graph signals → sets graphRiskElevated + routingDecision
                .process(graphAwareDecisionMaker)
                .log(LoggingLevel.INFO,
                     "GraphDecisionRoute: graphRiskElevated=${header.graphRiskElevated} "
                     + "reason=${header.graphRiskReason}")

                // Capture audit record
                .setProperty("stage", constant("graph-decision"))
                .process(auditCollector)

                // Route based on graph risk signal
                .choice()
                    .when(header("graphRiskElevated").isEqualTo(true))
                        .log(LoggingLevel.WARN,
                             "GraphDecisionRoute: GRAPH RISK ELEVATED for entityId=${header.entityId} "
                             + "— escalating. Reason: ${header.graphRiskReason}")
                        .to("direct:escalateFlow")

                    .otherwise()
                        .log(LoggingLevel.INFO,
                             "GraphDecisionRoute: no graph risk detected for "
                             + "entityId=${header.entityId} — continuing to contextual routing")
                        .to("direct:contextualRouting")
                .end();
    }
}