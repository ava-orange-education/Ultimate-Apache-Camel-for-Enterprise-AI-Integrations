package com.aibook.ai.graph;

import com.aibook.core.dto.GraphContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-level graph traversal service used by graph-pipeline route processors.
 *
 * <h3>Cypher Query Loading</h3>
 * Named Cypher queries are loaded from {@code graph/cypher-queries.yml} on the
 * classpath (lives in the {@code app} module's {@code resources/graph/} folder
 * and lands on the application classpath at runtime).
 *
 * <p>The YAML structure is:
 * <pre>
 * queries:
 *   fetch-entity-subgraph: |
 *     MATCH path = (e {id: $entityId})-[*0..$depth]->(n) RETURN ...
 *   upsert-document: |
 *     MERGE (d:Document {id: $id}) SET d += $props
 * </pre>
 *
 * <h3>traverseFromEntity</h3>
 * Uses the named query {@code "fetch-entity-subgraph"} (falling back to an
 * inline query if the key is absent).  Parameters {@code $entityId},
 * {@code $depth}, and {@code $relTypes} are injected automatically.
 */
@Service
public class GraphTraversalService {

    private static final Logger log = LoggerFactory.getLogger(GraphTraversalService.class);

    /** Classpath location of the Cypher query registry. */
    private static final String CYPHER_YAML_PATH = "graph/cypher-queries.yml";

    /** Fallback traversal query when the YAML key is absent. */
    private static final String FALLBACK_TRAVERSE_CYPHER = """
            MATCH path = (root)-[*0..3]->(neighbor)
            WHERE root.id = $entityId
            RETURN [n IN nodes(path) | n {.*}]          AS nodes,
                   [r IN relationships(path) | type(r) + ':' + startNode(r).id + '->' + endNode(r).id]
                                                          AS pathStrings
            """;

    private final Neo4jGraphClient neo4jClient;

    /** Lazy-loaded Cypher query map from YAML — populated on first call. */
    private final ConcurrentHashMap<String, String> queryCache = new ConcurrentHashMap<>();
    private volatile boolean yamlLoaded = false;

    public GraphTraversalService(Neo4jGraphClient neo4jClient) {
        this.neo4jClient = neo4jClient;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Traverse the graph starting from {@code entityId} up to {@code maxDepth} hops,
     * optionally filtering to specific {@code relationshipTypes}.
     *
     * <p>The Cypher query is loaded by key {@code "fetch-entity-subgraph"} from
     * {@code graph/cypher-queries.yml}.  If the key is missing, a built-in fallback
     * query is used instead.
     *
     * @param entityId          the {@code id} property of the starting node
     * @param maxDepth          maximum relationship hops to traverse (1–5 recommended)
     * @param relationshipTypes optional filter list; pass empty list to include all types
     * @return populated {@link GraphContext} DTO
     */
    public GraphContext traverseFromEntity(String entityId,
                                           int maxDepth,
                                           List<String> relationshipTypes) {
        log.debug("GraphTraversalService.traverseFromEntity: entityId={} depth={} relTypes={}",
                entityId, maxDepth, relationshipTypes);

        ensureYamlLoaded();
        String cypher = queryCache.getOrDefault("fetch-entity-subgraph", FALLBACK_TRAVERSE_CYPHER);

        Map<String, Object> params = new HashMap<>();
        params.put("entityId", entityId);
        params.put("depth",    maxDepth);
        if (relationshipTypes != null && !relationshipTypes.isEmpty()) {
            params.put("relTypes", relationshipTypes);
        }

        List<Map<String, Object>> rows = neo4jClient.executeCypher(cypher, params);

        // Collect relatedNodes and traversedPaths from result rows
        List<Map<String, Object>> relatedNodes  = new ArrayList<>();
        List<String>              traversedPaths = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            Object nodesObj = row.get("nodes");
            if (nodesObj instanceof List<?> nodeList) {
                for (Object n : nodeList) {
                    if (n instanceof Map<?, ?> nodeMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> cast = (Map<String, Object>) nodeMap;
                        relatedNodes.add(cast);
                    }
                }
            }
            Object pathObj = row.get("pathStrings");
            if (pathObj instanceof List<?> pathList) {
                for (Object p : pathList) {
                    if (p != null) traversedPaths.add(p.toString());
                }
            } else if (pathObj != null) {
                traversedPaths.add(pathObj.toString());
            }
        }

        log.debug("GraphTraversalService.traverseFromEntity: found {} nodes, {} paths",
                relatedNodes.size(), traversedPaths.size());

        return new GraphContext(null, entityId, traversedPaths, relatedNodes, maxDepth);
    }

    /**
     * Convenience: fetch a subgraph by node type (uses {@link #traverseFromEntity}).
     */
    public GraphContext fetchSubgraph(String rootNodeId, String rootNodeType, int depth) {
        return traverseFromEntity(rootNodeId, depth, List.of());
    }

    /**
     * MERGE a node into the graph using the given label and property map.
     * The {@code properties} map must contain an {@code "id"} key.
     */
    public void mergeNode(String nodeType, String id, Map<String, Object> properties) {
        Map<String, Object> props = new HashMap<>(properties);
        props.put("id", id);
        neo4jClient.writeNode(nodeType, props);
    }

    /**
     * Look up a named Cypher query by key from the YAML registry.
     *
     * @param key the YAML query key (e.g. {@code "fetch-entity-subgraph"})
     * @return the Cypher string, or {@code null} if the key is not found
     */
    public String getCypherQuery(String key) {
        ensureYamlLoaded();
        return queryCache.get(key);
    }

    // ── YAML loading ──────────────────────────────────────────────────────────

    private void ensureYamlLoaded() {
        if (yamlLoaded) return;
        synchronized (this) {
            if (yamlLoaded) return;
            loadYaml();
            yamlLoaded = true;
        }
    }

    @SuppressWarnings("unchecked")
    private void loadYaml() {
        ClassPathResource resource = new ClassPathResource(CYPHER_YAML_PATH);
        if (!resource.exists()) {
            log.warn("GraphTraversalService: '{}' not found on classpath — using fallback queries",
                    CYPHER_YAML_PATH);
            return;
        }
        try (InputStream is = resource.getInputStream()) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(is);
            if (root != null && root.containsKey("queries")) {
                Map<String, Object> queries = (Map<String, Object>) root.get("queries");
                queries.forEach((k, v) -> {
                    if (v != null) queryCache.put(k, v.toString().strip());
                });
                log.info("GraphTraversalService: loaded {} Cypher queries from '{}'",
                        queryCache.size(), CYPHER_YAML_PATH);
            }
        } catch (IOException e) {
            log.error("GraphTraversalService: failed to load '{}': {}",
                    CYPHER_YAML_PATH, e.getMessage());
        }
    }
}