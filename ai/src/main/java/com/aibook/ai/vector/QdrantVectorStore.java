package com.aibook.ai.vector;

import com.aibook.core.config.AiPipelineProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring-managed wrapper around LangChain4j's {@link QdrantEmbeddingStore}.
 *
 * <p>Maintains one {@link QdrantEmbeddingStore} per collection name (lazy, cached).
 * Exposes three operations:
 * <ul>
 *   <li>{@link #upsert} — store a vector with an id and metadata payload</li>
 *   <li>{@link #search} — nearest-neighbour search, returns {@link ScoredDocument}s</li>
 * </ul>
 *
 * <h3>ScoredDocument</h3>
 * An immutable record bundling the matched document's id, metadata, and similarity score.
 */
@Service
public class QdrantVectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    /**
     * Immutable result record returned by {@link #search}.
     *
     * @param id      the document / chunk identifier stored in Qdrant
     * @param payload the metadata map stored alongside the vector
     * @param score   cosine similarity score in [0, 1]
     */
    public record ScoredDocument(String id, Map<String, Object> payload, double score) {}

    // ── Config ────────────────────────────────────────────────────────────────

    private final String qdrantHost;
    private final int    qdrantPort;     // gRPC port (default 6334)
    private final int    qdrantHttpPort; // HTTP REST port (default 6333)

    /** Lazy cache: collectionName → QdrantEmbeddingStore */
    private final ConcurrentHashMap<String, QdrantEmbeddingStore> storeCache =
            new ConcurrentHashMap<>();

    /** Collections already known to exist in Qdrant (so we don't re-create). */
    private final Set<String> ensuredCollections = ConcurrentHashMap.newKeySet();

    public QdrantVectorStore(AiPipelineProperties properties) {
        this.qdrantHost     = properties.qdrant().host();
        this.qdrantPort     = properties.qdrant().port();
        this.qdrantHttpPort = 6333; // standard Qdrant HTTP REST port
        log.info("QdrantVectorStore configured: {}:{}", qdrantHost, qdrantPort);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Upsert a single vector into the given Qdrant collection.
     *
     * <p>The {@code payload} map is stored as metadata alongside the vector.
     * A mandatory {@code "text"} key in {@code payload} is used to reconstruct
     * the original text on retrieval.
     *
     * @param collectionName Qdrant collection to write into
     * @param id             stable document/chunk identifier
     * @param vector         pre-computed embedding vector
     * @param payload        metadata (must include {@code "text"} for retrieval)
     */
    public void upsert(String collectionName, String id,
                       float[] vector, Map<String, Object> payload) {
        log.debug("QdrantVectorStore.upsert: collection={} id={}", collectionName, id);

        QdrantEmbeddingStore store = getOrCreateStore(collectionName);
        Embedding embedding = Embedding.from(vector);

        // Store the id in payload/metadata so it can be recovered on retrieval
        payload.put("_id", id);
        String text = payload.getOrDefault("text", id).toString();
        TextSegment segment = buildSegment(text, payload);
        store.add(embedding, segment);
        log.debug("QdrantVectorStore.upsert: stored id={} in '{}'", id, collectionName);
    }

    /**
     * Perform a nearest-neighbour search via Qdrant's HTTP REST API.
     *
     * <p>Uses REST (port 6333) instead of gRPC to avoid client/server version
     * incompatibilities between the Java gRPC client (1.13.0) and Qdrant server (1.17+).
     *
     * @param collectionName Qdrant collection to query
     * @param queryVector    pre-computed query embedding
     * @param topK           maximum number of results
     * @param minScore       minimum cosine similarity (0–1)
     * @return ordered list of {@link ScoredDocument}s (highest score first)
     */
    public List<ScoredDocument> search(String collectionName, float[] queryVector,
                                       int topK, double minScore) {
        log.debug("QdrantVectorStore.search: collection={} topK={} minScore={}",
                collectionName, topK, minScore);

        try {
            // Build JSON search request body
            StringBuilder vectorJson = new StringBuilder("[");
            for (int i = 0; i < queryVector.length; i++) {
                if (i > 0) vectorJson.append(',');
                vectorJson.append(queryVector[i]);
            }
            vectorJson.append(']');
            String requestBody = String.format(
                    "{\"vector\":%s,\"limit\":%d,\"score_threshold\":%f,\"with_payload\":true}",
                    vectorJson, topK, minScore);

            String url = "http://" + qdrantHost + ":" + qdrantHttpPort
                    + "/collections/" + collectionName + "/points/search";

            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                log.warn("QdrantVectorStore.search: HTTP {} from Qdrant REST search", code);
                return List.of();
            }

            try (InputStream is = conn.getInputStream()) {
                JsonNode root = new ObjectMapper().readTree(is);
                JsonNode results = root.path("result");
                List<ScoredDocument> docs = new ArrayList<>();
                for (JsonNode hit : results) {
                    double score   = hit.path("score").asDouble(0.0);
                    String id      = hit.path("id").asText("");
                    JsonNode payloadNode = hit.path("payload");
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payloadNode.fields().forEachRemaining(e ->
                            payload.put(e.getKey(), e.getValue().asText()));
                    docs.add(new ScoredDocument(id, Map.copyOf(payload), score));
                }
                log.debug("QdrantVectorStore.search: returned {} results", docs.size());
                return docs;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            log.error("QdrantVectorStore.search: REST search failed: {}", e.getMessage(), e);
            throw new RuntimeException("Qdrant search failed: " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private QdrantEmbeddingStore getOrCreateStore(String collectionName) {
        ensureCollectionExists(collectionName);
        return storeCache.computeIfAbsent(collectionName, name ->
                QdrantEmbeddingStore.builder()
                        .host(qdrantHost)
                        .port(qdrantPort)
                        .collectionName(name)
                        .build());
    }

    /**
     * Creates the Qdrant collection via the HTTP REST API if it does not already exist.
     * Uses 384-dimensional cosine vectors to match the local all-MiniLM-L6-v2 embeddings.
     * Safe to call repeatedly — skips if the collection is already known to exist.
     */
    private void ensureCollectionExists(String collectionName) {
        if (ensuredCollections.contains(collectionName)) return;
        try {
            String url = "http://" + qdrantHost + ":" + qdrantHttpPort
                    + "/collections/" + collectionName;

            // Check if collection already exists
            HttpURLConnection check = (HttpURLConnection)
                    URI.create(url).toURL().openConnection();
            check.setRequestMethod("GET");
            check.setConnectTimeout(3000);
            check.setReadTimeout(3000);
            int checkCode = check.getResponseCode();
            check.disconnect();

            if (checkCode == 200) {
                log.info("QdrantVectorStore: collection '{}' already exists", collectionName);
                ensuredCollections.add(collectionName);
                return;
            }

            // Create collection with 384-dim cosine vectors (all-MiniLM-L6-v2)
            String body = """
                    {"vectors":{"size":384,"distance":"Cosine"}}
                    """.strip();

            HttpURLConnection create = (HttpURLConnection)
                    URI.create(url).toURL().openConnection();
            create.setRequestMethod("PUT");
            create.setRequestProperty("Content-Type", "application/json");
            create.setDoOutput(true);
            create.setConnectTimeout(5000);
            create.setReadTimeout(5000);
            try (OutputStream os = create.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int createCode = create.getResponseCode();
            create.disconnect();

            if (createCode == 200 || createCode == 201) {
                log.info("QdrantVectorStore: created collection '{}'", collectionName);
                ensuredCollections.add(collectionName);
            } else {
                log.warn("QdrantVectorStore: unexpected HTTP {} creating collection '{}'",
                        createCode, collectionName);
            }
        } catch (Exception e) {
            log.warn("QdrantVectorStore: could not ensure collection '{}' exists: {}",
                    collectionName, e.getMessage());
        }
    }

    private TextSegment buildSegment(String text, Map<String, Object> payload) {
        dev.langchain4j.data.document.Metadata metadata =
                new dev.langchain4j.data.document.Metadata();
        payload.forEach((k, v) -> {
            if (v != null) metadata.put(k, v.toString());
        });
        return TextSegment.from(text, metadata);
    }

    private ScoredDocument toScoredDocument(dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment> match) {
        String id = match.embeddingId();
        TextSegment segment = match.embedded();
        Map<String, Object> payload = segment != null && segment.metadata() != null
                ? new HashMap<>(segment.metadata().toMap())
                : Map.of();
        if (segment != null) {
            payload.put("text", segment.text());
        }
        return new ScoredDocument(id, Map.copyOf(payload), match.score());
    }
}