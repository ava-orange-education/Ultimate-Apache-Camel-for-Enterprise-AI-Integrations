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
 * Writes (or merges) a labelled entity node into Neo4j.
 *
 * <p>Reads from the exchange body: a {@code Map<String, Object>} with the shape:
 * <pre>
 * {
 *   "entityId":   "customer-42",         // required — used as the node's id property
 *   "entityType": "Customer",            // required — becomes the Neo4j label
 *   "properties": { ... }               // optional extra properties to set on the node
 * }
 * </pre>
 *
 * <p>Writes:
 * <ul>
 *   <li>Header {@code entityId}   — echoed from the input</li>
 *   <li>Header {@code entityType} — echoed from the input</li>
 *   <li>Header {@code nodeWritten} — {@code true} after successful write</li>
 * </ul>
 *
 * <p>The node is created via {@link Neo4jGraphClient#writeNode(String, Map)}.
 * An {@code ingestedAt} timestamp is automatically added to every node.
 */
@Component
public class GraphNodeWriter implements Processor {

    private static final Logger log = LoggerFactory.getLogger(GraphNodeWriter.class);

    private final Neo4jGraphClient neo4jClient;

    public GraphNodeWriter(Neo4jGraphClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) {
        Map<String, Object> body = exchange.getIn().getBody(Map.class);
        if (body == null) {
            throw new IllegalArgumentException(
                    "GraphNodeWriter: body must be a Map<String,Object> with entityId and entityType");
        }

        String entityId   = getString(body, "entityId");
        String entityType = getString(body, "entityType");

        if (entityId.isBlank()) {
            throw new IllegalArgumentException("GraphNodeWriter: 'entityId' must not be blank");
        }
        if (entityType.isBlank()) {
            throw new IllegalArgumentException("GraphNodeWriter: 'entityType' must not be blank");
        }

        // Build the merged property map
        Map<String, Object> props = new HashMap<>();
        Object extra = body.get("properties");
        if (extra instanceof Map<?, ?> extraMap) {
            extraMap.forEach((k, v) -> props.put(k.toString(), v));
        }
        props.put("id",         entityId);
        props.put("type",       entityType);
        props.put("ingestedAt", Instant.now().toString());

        log.info("GraphNodeWriter: writing node label={} id={} props={}",
                entityType, entityId, props.size());

        neo4jClient.writeNode(entityType, props);

        // Set headers for downstream steps
        exchange.getIn().setHeader("entityId",    entityId);
        exchange.getIn().setHeader("entityType",  entityType);
        exchange.getIn().setHeader("nodeWritten", true);

        // Set audit properties
        exchange.setProperty("decisionId", entityId);
        exchange.setProperty("stage",      "graph-node-ingestion");

        log.info("GraphNodeWriter: node written — label={} id={}", entityType, entityId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }
}
