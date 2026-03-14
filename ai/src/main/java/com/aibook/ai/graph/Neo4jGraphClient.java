package com.aibook.ai.graph;

import com.aibook.core.config.AiPipelineProperties;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring-managed Neo4j client built directly on the Neo4j Java Driver.
 *
 * <p>Manages a single {@link Driver} instance (thread-safe, auto-pooled)
 * that is closed on application shutdown via {@link #close()}.
 *
 * <p>Public API:
 * <ul>
 *   <li>{@link #executeCypher(String, Map)} — run a read query, return rows as maps</li>
 *   <li>{@link #writeNode(String, Map)} — MERGE a labelled node by {@code id} property</li>
 *   <li>{@link #writeRelationship(String, String, String, Map)} — MERGE a relationship</li>
 * </ul>
 */
@Service
public class Neo4jGraphClient {

    private static final Logger log = LoggerFactory.getLogger(Neo4jGraphClient.class);

    private final Driver driver;

    @Autowired
    public Neo4jGraphClient(AiPipelineProperties properties) {
        AiPipelineProperties.Neo4jConfig cfg = properties.neo4j();
        this.driver = GraphDatabase.driver(
                cfg.uri(),
                AuthTokens.basic(cfg.username(), cfg.password()));
        log.info("Neo4jGraphClient connected: {}", cfg.uri());
    }

    /**
     * Package-private constructor for testing with an embedded harness driver.
     */
    Neo4jGraphClient(Driver driver) {
        this.driver = driver;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Execute a parameterised Cypher query (read or write) and return all result rows
     * as a list of string-keyed value maps.
     *
     * @param cypher Cypher query string (may include {@code $param} placeholders)
     * @param params parameter map matching the placeholders
     * @return list of result rows; each row is a {@code Map<String,Object>}
     */
    public List<Map<String, Object>> executeCypher(String cypher,
                                                    Map<String, Object> params) {
        log.debug("Neo4jGraphClient.executeCypher: {}", truncate(cypher));
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run(cypher, params != null ? Values.value(params) : Values.EmptyMap);
                List<Map<String, Object>> rows = new ArrayList<>();
                while (result.hasNext()) {
                    rows.add(toMap(result.next()));
                }
                return rows;
            });
        }
    }

    /**
     * MERGE a node with the given {@code label} and properties into the graph.
     *
     * <p>The node is identified by the {@code id} entry in {@code properties}
     * (required). All other properties are SET on the node.
     *
     * @param label      Neo4j node label (e.g. {@code "Document"})
     * @param properties property map; must contain key {@code "id"}
     * @throws IllegalArgumentException if {@code properties} does not contain {@code "id"}
     */
    public void writeNode(String label, Map<String, Object> properties) {
        if (!properties.containsKey("id")) {
            throw new IllegalArgumentException(
                    "Neo4jGraphClient.writeNode: properties must contain 'id'");
        }
        String cypher = "MERGE (n:" + sanitizeLabel(label) + " {id: $id}) SET n += $props";
        log.debug("Neo4jGraphClient.writeNode: label={} id={}", label, properties.get("id"));
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, Values.parameters("id", properties.get("id"), "props", properties));
                return null;
            });
        }
    }

    /**
     * MERGE a directed relationship between two nodes identified by their {@code id} property.
     *
     * @param fromId node id of the relationship source
     * @param toId   node id of the relationship target
     * @param type   relationship type (e.g. {@code "MENTIONS"})
     * @param props  optional property map for the relationship (may be empty)
     */
    public void writeRelationship(String fromId, String toId,
                                  String type, Map<String, Object> props) {
        String relType = sanitizeLabel(type);
        String cypher = """
                MATCH (a {id: $fromId}), (b {id: $toId})
                MERGE (a)-[r:%s]->(b)
                SET r += $props
                """.formatted(relType);
        log.debug("Neo4jGraphClient.writeRelationship: {}-[{}]->{}", fromId, type, toId);
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, Values.parameters(
                        "fromId", fromId, "toId", toId, "props", props));
                return null;
            });
        }
    }

    @PreDestroy
    public void close() {
        log.info("Neo4jGraphClient: closing driver");
        driver.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toMap(Record record) {
        Map<String, Object> row = new HashMap<>();
        record.keys().forEach(key -> {
            var val = record.get(key);
            // Unwrap Neo4j value types to plain Java objects
            try {
                row.put(key, val.asObject());
            } catch (Exception e) {
                row.put(key, val.toString());
            }
        });
        return row;
    }

    private String sanitizeLabel(String label) {
        // Allow only alphanumeric and underscores to prevent Cypher injection
        return label.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private String truncate(String s) {
        return s != null && s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }
}