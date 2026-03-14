package com.aibook.routes.graph.processors;

import com.aibook.ai.graph.Neo4jGraphClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Writes (or merges) a directed relationship between two existing nodes in Neo4j.
 *
 * <p>Reads from the exchange body: a {@code Map<String, Object>} with the shape:
 * <pre>
 * {
 *   "fromId":           "customer-42",   // required — source node id
 *   "toId":             "account-7",     // required — target node id
 *   "relationshipType": "OWNS",          // required — Neo4j relationship type
 *   "properties":       { ... }          // optional relationship properties
 * }
 * </pre>
 *
 * <p>Writes:
 * <ul>
 *   <li>Header {@code fromId}             — echoed from the input</li>
 *   <li>Header {@code toId}               — echoed from the input</li>
 *   <li>Header {@code relationshipType}   — echoed from the input</li>
 *   <li>Header {@code relationshipWritten} — {@code true} after successful write</li>
 * </ul>
 *
 * <p>The relationship is written via
 * {@link Neo4jGraphClient#writeRelationship(String, String, String, Map)}.
 * A {@code createdAt} timestamp is automatically added to every relationship.
 */
@Component
public class GraphRelationshipWriter implements Processor {

    private static final Logger log = LoggerFactory.getLogger(GraphRelationshipWriter.class);

    private final Neo4jGraphClient neo4jClient;

    public GraphRelationshipWriter(Neo4jGraphClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> body = exchange.getIn().getBody(Map.class);
        if (body == null) {
            throw new IllegalArgumentException(
                    "GraphRelationshipWriter: body must be a Map<String,Object> "
                    + "with fromId, toId, and relationshipType");
        }

        String fromId           = getString(body, "fromId");
        String toId             = getString(body, "toId");
        String relationshipType = getString(body, "relationshipType");

        if (fromId.isBlank()) {
            throw new IllegalArgumentException("GraphRelationshipWriter: 'fromId' must not be blank");
        }
        if (toId.isBlank()) {
            throw new IllegalArgumentException("GraphRelationshipWriter: 'toId' must not be blank");
        }
        if (relationshipType.isBlank()) {
            throw new IllegalArgumentException(
                    "GraphRelationshipWriter: 'relationshipType' must not be blank");
        }

        // Build the relationship property map
        Map<String, Object> relProps = new HashMap<>();
        Object extra = body.get("properties");
        if (extra instanceof Map<?, ?> extraMap) {
            extraMap.forEach((k, v) -> relProps.put(k.toString(), v));
        }
        relProps.put("createdAt", Instant.now().toString());

        log.info("GraphRelationshipWriter: writing relationship [{}-[{}]->{}] props={}",
                fromId, relationshipType, toId, relProps.size());

        neo4jClient.writeRelationship(fromId, toId, relationshipType, relProps);

        // Set headers for downstream steps
        exchange.getIn().setHeader("fromId",              fromId);
        exchange.getIn().setHeader("toId",                toId);
        exchange.getIn().setHeader("relationshipType",    relationshipType);
        exchange.getIn().setHeader("relationshipWritten", true);

        // Set audit properties
        exchange.setProperty("decisionId", fromId + "->" + toId);
        exchange.setProperty("stage",      "graph-relationship-ingestion");

        log.info("GraphRelationshipWriter: relationship written — {}-[{}]->{}",
                fromId, relationshipType, toId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }
}
