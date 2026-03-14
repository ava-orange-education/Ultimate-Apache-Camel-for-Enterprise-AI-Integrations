package com.aibook.routes.graph;

import com.aibook.core.error.AiErrorHandler;
import com.aibook.routes.graph.processors.GraphNodeWriter;
import com.aibook.routes.graph.processors.GraphRelationshipWriter;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.stereotype.Component;

/**
 * Graph ingestion route — writes entity nodes and relationships into Neo4j.
 *
 * <h3>Pipeline flow</h3>
 * <pre>
 *   REST POST /api/graph/ingest/entity
 *     → direct:ingestGraphEntity
 *         → graphNodeWriter          (MERGE node via Neo4jGraphClient)
 *         → direct:graphIngestionComplete
 *
 *   REST POST /api/graph/ingest/relationship
 *     → direct:ingestGraphRelationship
 *         → graphRelationshipWriter  (MERGE relationship via Neo4jGraphClient)
 *         → direct:graphIngestionComplete
 *
 *   direct:graphIngestionComplete
 *     → log summary
 * </pre>
 *
 * <h3>Input body shape (JSON)</h3>
 * <p>Entity:       {@code {"entityId":"...", "entityType":"...", "properties":{...}}}<br>
 * Relationship: {@code {"fromId":"...", "toId":"...", "relationshipType":"...", "properties":{...}}}
 */
@Component
public class GraphIngestionRoute extends RouteBuilder {

    private final GraphNodeWriter         graphNodeWriter;
    private final GraphRelationshipWriter graphRelationshipWriter;
    private final AiErrorHandler          aiErrorHandler;

    public GraphIngestionRoute(GraphNodeWriter graphNodeWriter,
                               GraphRelationshipWriter graphRelationshipWriter,
                               AiErrorHandler aiErrorHandler) {
        this.graphNodeWriter         = graphNodeWriter;
        this.graphRelationshipWriter = graphRelationshipWriter;
        this.aiErrorHandler          = aiErrorHandler;
    }

    @Override
    public void configure() {

        // ── Error handling ────────────────────────────────────────────────────
        errorHandler(aiErrorHandler.build());

        onException(IllegalArgumentException.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "GraphIngestionRoute: invalid input [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "GraphIngestionRoute error [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        // ── REST entry points ─────────────────────────────────────────────────
        restConfiguration()
                .component("servlet")
                .bindingMode(RestBindingMode.off)
                .dataFormatProperty("prettyPrint", "true")
                .enableCORS(true);

        rest("/api/graph/ingest")
                .description("Graph entity and relationship ingestion endpoints")

                .post("/entity")
                    .description("Ingest an entity node into Neo4j")
                    .consumes("application/json")
                    .produces("application/json")
                    .to("direct:ingestGraphEntity")

                .post("/relationship")
                    .description("Ingest a directed relationship into Neo4j")
                    .consumes("application/json")
                    .produces("application/json")
                    .to("direct:ingestGraphRelationship");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:ingestGraphEntity
        // Input:  JSON body → Map<String,Object> {entityId, entityType, properties}
        // Output: → direct:graphIngestionComplete
        // ═════════════════════════════════════════════════════════════════════
        from("direct:ingestGraphEntity")
                .routeId("graph-ingest-entity")
                .log(LoggingLevel.INFO,
                     "GraphIngestionRoute: ingesting entity [exchangeId=${exchangeId}]")

                // Deserialise JSON body to Map
                .unmarshal().json(java.util.Map.class)

                // Write node to Neo4j
                .process(graphNodeWriter)
                .log(LoggingLevel.INFO,
                     "GraphIngestionRoute: node written entityId=${header.entityId} "
                     + "type=${header.entityType}")

                .to("direct:graphIngestionComplete");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:ingestGraphRelationship
        // Input:  JSON body → Map<String,Object> {fromId, toId, relationshipType, properties}
        // Output: → direct:graphIngestionComplete
        // ═════════════════════════════════════════════════════════════════════
        from("direct:ingestGraphRelationship")
                .routeId("graph-ingest-relationship")
                .log(LoggingLevel.INFO,
                     "GraphIngestionRoute: ingesting relationship [exchangeId=${exchangeId}]")

                // Deserialise JSON body to Map
                .unmarshal().json(java.util.Map.class)

                // Write relationship to Neo4j
                .process(graphRelationshipWriter)
                .log(LoggingLevel.INFO,
                     "GraphIngestionRoute: relationship written "
                     + "${header.fromId}-[${header.relationshipType}]->${header.toId}")

                .to("direct:graphIngestionComplete");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:graphIngestionComplete
        // Shared completion step — logs summary.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:graphIngestionComplete")
                .routeId("graph-ingestion-complete")
                .log(LoggingLevel.INFO,
                     "GraphIngestionRoute: graph entity written: entityId=${header.entityId}");
    }
}