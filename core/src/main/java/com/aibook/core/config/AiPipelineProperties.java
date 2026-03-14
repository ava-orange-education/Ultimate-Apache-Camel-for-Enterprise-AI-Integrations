package com.aibook.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Strongly-typed binding for all {@code aibook.*} properties in application.yml.
 *
 * <p>Enable with {@code @EnableConfigurationProperties(AiPipelineProperties.class)}
 * on the Spring Boot main class (already done in {@code Application.java}).
 *
 * <p>Example YAML:
 * <pre>
 * aibook:
 *   llm:
 *     endpoint: http://localhost:11434
 *     model-name: llama3.2
 *     api-key: ""
 *   embeddings:
 *     model: local
 *     dimensions: 384
 *   qdrant:
 *     host: localhost
 *     port: 6334
 *   neo4j:
 *     uri: bolt://localhost:7687
 *     username: neo4j
 *     password: password
 *   scoring:
 *     confidence-threshold: 0.6
 *   human-review:
 *     queue: direct:human-review
 * </pre>
 */
@ConfigurationProperties(prefix = "aibook", ignoreUnknownFields = true)
public record AiPipelineProperties(
        LlmConfig llm,
        EmbeddingsConfig embeddings,
        QdrantConfig qdrant,
        Neo4jConfig neo4j,
        ScoringConfig scoring,
        HumanReviewConfig humanReview
) {
    // ── Nested config records ─────────────────────────────────────────────────

    /**
     * LLM backend connection settings.
     * {@code apiKey} is optional — leave blank to use Ollama (no auth).
     */
    public record LlmConfig(
            @DefaultValue("http://localhost:11434") String endpoint,
            @DefaultValue("llama3.2")              String modelName,
            @DefaultValue("")                       String apiKey,
            @DefaultValue("120")                   int    timeoutSeconds,
            @DefaultValue("0.7")                   double temperature
    ) {}

    /**
     * Embedding model settings.
     * {@code model} is one of: {@code local} (all-MiniLM-L6-v2) or {@code openai}.
     */
    public record EmbeddingsConfig(
            @DefaultValue("local") String model,
            @DefaultValue("384")   int    dimensions
    ) {}

    /**
     * Qdrant vector database connection.
     * {@code port} is the gRPC port (6334); REST is 6333.
     */
    public record QdrantConfig(
            @DefaultValue("localhost") String host,
            @DefaultValue("6334")      int    port,
            @DefaultValue("documents") String collection,
            @DefaultValue("false")     boolean useTls
    ) {}

    /**
     * Neo4j graph database connection (Bolt protocol).
     */
    public record Neo4jConfig(
            @DefaultValue("bolt://localhost:7687") String uri,
            @DefaultValue("neo4j")                 String username,
            @DefaultValue("password")              String password,
            @DefaultValue("3")                     int    maxConnectionPoolSize
    ) {}

    /**
     * Scoring pipeline thresholds.
     * Results with {@code confidence < confidenceThreshold} are routed to human review.
     */
    public record ScoringConfig(
            @DefaultValue("0.6")      double confidenceThreshold,
            @DefaultValue("default")  String defaultProfile,
            @DefaultValue("false")    boolean alwaysFetchHistory,
            @DefaultValue("mock")     String contextSource,
            @DefaultValue("")         String outputDir
    ) {}

    /**
     * Human review escalation settings.
     */
    public record HumanReviewConfig(
            @DefaultValue("direct:human-review") String queue,
            @DefaultValue("MEDIUM")              String defaultPriority,
            SimulationConfig                     simulation
    ) {
        public record SimulationConfig(
                @DefaultValue("0") long delayMs
        ) {}
    }
}