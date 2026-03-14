package com.aibook.routes.graph;

import com.aibook.core.error.AiErrorHandler;
import com.aibook.routes.graph.processors.GraphContextMergeStrategy;
import com.aibook.routes.graph.processors.GraphEnrichmentDecider;
import com.aibook.routes.graph.processors.GraphSignalExtractor;
import com.aibook.routes.graph.processors.GraphTraversalProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.stereotype.Component;

/**
 * Graph enrichment route — conditionally traverses the entity graph and
 * extracts signals that augment the scoring pipeline's decision quality.
 *
 * <h3>Pipeline flow</h3>
 * <pre>
 *   REST POST /api/graph/enrich
 *     → direct:enrichWithGraph
 *         → graphEnrichmentDecider  (decides if traversal is needed)
 *         → choice()
 *               WHEN graphEnrichmentNeeded=true
 *                   → enrich("direct:traverseGraph", graphContextMergeStrategy)
 *               OTHERWISE
 *                   → log "skipped"
 *         → direct:graphDecision
 *
 *   direct:traverseGraph
 *     → graphTraversalProcessor  (calls GraphTraversalService.traverseFromEntity)
 *     → graphSignalExtractor     (computes connectedEntityCount, avgStrength, etc.)
 * </pre>
 *
 * <h3>Headers consumed</h3>
 * {@code confidence}, {@code score}, {@code entityId}, {@code riskFlagCount}
 *
 * <h3>Headers produced</h3>
 * {@code graphEnrichmentNeeded}, {@code connectedEntityCount},
 * {@code avgRelationshipStrength}, {@code clusterDensity}, {@code maxPathDepth}
 */
@Component
public class GraphEnrichmentRoute extends RouteBuilder {

    private final GraphEnrichmentDecider   graphEnrichmentDecider;
    private final GraphTraversalProcessor  graphTraversalProcessor;
    private final GraphSignalExtractor     graphSignalExtractor;
    private final GraphContextMergeStrategy graphContextMergeStrategy;
    private final AiErrorHandler            aiErrorHandler;
    private final ObjectMapper              objectMapper;

    public GraphEnrichmentRoute(GraphEnrichmentDecider graphEnrichmentDecider,
                                GraphTraversalProcessor graphTraversalProcessor,
                                GraphSignalExtractor graphSignalExtractor,
                                GraphContextMergeStrategy graphContextMergeStrategy,
                                AiErrorHandler aiErrorHandler,
                                ObjectMapper objectMapper) {
        this.graphEnrichmentDecider   = graphEnrichmentDecider;
        this.graphTraversalProcessor  = graphTraversalProcessor;
        this.graphSignalExtractor     = graphSignalExtractor;
        this.graphContextMergeStrategy = graphContextMergeStrategy;
        this.aiErrorHandler            = aiErrorHandler;
        this.objectMapper              = objectMapper;
    }

    @Override
    public void configure() {

        // ── Error handling ────────────────────────────────────────────────────
        errorHandler(aiErrorHandler.build());

        onException(IllegalStateException.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "GraphEnrichmentRoute: missing required header [${routeId}]: "
                     + "${exception.message}")
                .to("direct:deadLetter");

        onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "GraphEnrichmentRoute error [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        // ── REST entry point ──────────────────────────────────────────────────
        restConfiguration()
                .component("servlet")
                .bindingMode(RestBindingMode.off)
                .dataFormatProperty("prettyPrint", "true")
                .enableCORS(true);

        rest("/api/graph/enrich")
                .description("Graph-enrichment entry point for the scoring pipeline")
                .post()
                    .description("Enrich a scoring request with graph traversal signals")
                    .consumes("application/json")
                    .produces("application/json")
                    .to("direct:enrichWithGraph");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:enrichWithGraph
        // Input:  any body with entityId in header (ScoringResult, ScoringRequest, or Map)
        // Output: enriched body + graph signal headers → direct:graphDecision
        // ═════════════════════════════════════════════════════════════════════
        from("direct:enrichWithGraph")
                .routeId("graph-enrichment")
                .log(LoggingLevel.INFO,
                     "GraphEnrichmentRoute: evaluating enrichment need for entityId=${header.entityId}")

                // Deserialize JSON body and promote key fields into headers
                .process(exchange -> {
                    Object raw = exchange.getIn().getBody();
                    String json = switch (raw) {
                        case String s -> s;
                        case byte[] b -> new String(b, java.nio.charset.StandardCharsets.UTF_8);
                        case java.io.InputStream is ->
                                new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        default -> raw == null ? "{}" : raw.toString();
                    };
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> map =
                            objectMapper.readValue(json, java.util.Map.class);
                    // Replace body with the map so downstream processors see a known type
                    exchange.getIn().setBody(map);
                    // Promote headers only if not already set
                    setIfAbsent(exchange, "entityId",      map.get("entityId"));
                    setIfAbsent(exchange, "score",         map.get("score"));
                    setIfAbsent(exchange, "confidence",    map.get("confidence"));
                    setIfAbsent(exchange, "riskFlagCount", map.get("riskFlagCount"));
                })

                // Decide whether graph traversal is needed
                .process(graphEnrichmentDecider)
                .log(LoggingLevel.INFO,
                     "GraphEnrichmentRoute: graphEnrichmentNeeded=${header.graphEnrichmentNeeded} "
                     + "for entityId=${header.entityId}")

                // Conditional traversal: only run if enrichment is needed
                .choice()
                    .when(header("graphEnrichmentNeeded").isEqualTo(true))
                        .log(LoggingLevel.INFO,
                             "GraphEnrichmentRoute: starting graph traversal for entityId=${header.entityId}")
                        .enrich("direct:traverseGraph", graphContextMergeStrategy)
                        .log(LoggingLevel.INFO,
                             "GraphEnrichmentRoute: traversal complete — "
                             + "nodes=${header.connectedEntityCount} "
                             + "strength=${header.avgRelationshipStrength}")
                    .otherwise()
                        .log(LoggingLevel.INFO,
                             "GraphEnrichmentRoute: graph enrichment skipped for ${header.entityId}")
                .end()

                // Promote key fields from body map → headers so that
                // contextualRouting can dispatch on routingDecision
                .process(exchange -> {
                    Object body = exchange.getIn().getBody();
                    if (body instanceof java.util.Map<?,?> map) {
                        setIfAbsent(exchange, "routingDecision", map.get("routingDecision"));
                        setIfAbsent(exchange, "requestId",       map.get("requestId"));
                        setIfAbsent(exchange, "score",           map.get("score"));
                        setIfAbsent(exchange, "confidence",      map.get("confidence"));
                    }
                })

                .to("direct:graphDecision");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:traverseGraph
        // Called via enrich() — returns GraphContext in body + signal headers.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:traverseGraph")
                .routeId("graph-traversal")
                .log(LoggingLevel.DEBUG,
                     "GraphEnrichmentRoute: traversing graph for entityId=${header.entityId}")

                // Traverse from the entity and set GraphContext as body
                // Circuit breaker guards against Neo4j unavailability
                .circuitBreaker()
                    .resilience4jConfiguration()
                        .slidingWindowSize(5)
                        .failureRateThreshold(60)
                        .waitDurationInOpenState(20000)
                        .end()
                    .process(graphTraversalProcessor)
                .onFallback()
                    .process(exchange -> {
                        // Return an empty GraphContext-compatible body on open circuit
                        exchange.getIn().setHeader("connectedEntityCount",    0);
                        exchange.getIn().setHeader("avgRelationshipStrength", 0.0);
                        exchange.getIn().setHeader("clusterDensity",          0.0);
                        exchange.getIn().setHeader("maxPathDepth",            0);
                        exchange.getIn().setHeader("circuitBreakerFallback",  true);
                    })
                .end()

                // Extract numeric signals from GraphContext into headers
                .process(graphSignalExtractor)
                .log(LoggingLevel.INFO,
                     "GraphEnrichmentRoute: signals extracted — "
                     + "connectedEntities=${header.connectedEntityCount} "
                     + "clusterDensity=${header.clusterDensity}");
    }

    private void setIfAbsent(org.apache.camel.Exchange exchange, String key, Object value) {
        if (value != null && exchange.getIn().getHeader(key) == null) {
            exchange.getIn().setHeader(key, value);
        }
    }
}