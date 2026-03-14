package com.aibook.ai.embeddings;

import com.aibook.core.config.AiPipelineProperties;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring {@link Configuration} that creates the correct {@link EmbeddingModel} bean
 * based on the {@code aibook.embeddings.model} property.
 *
 * <p>Supported model names:
 * <ul>
 *   <li>{@code all-minilm-l6-v2} (default) — runs locally via ONNX, no API key required</li>
 *   <li>{@code text-embedding-3-small} — OpenAI hosted embedding model</li>
 *   <li>{@code text-embedding-ada-002} — legacy OpenAI embedding model</li>
 * </ul>
 *
 * <p>Also creates the {@link ChatLanguageModel} bean used by {@link LlmGateway}.
 */
@Configuration
public class EmbeddingModelFactory {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelFactory.class);

    private final AiPipelineProperties properties;

    public EmbeddingModelFactory(AiPipelineProperties properties) {
        this.properties = properties;
    }

    /**
     * Produce the {@link EmbeddingModel} bean.
     *
     * @return an ONNX local model or an OpenAI model, depending on config
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        String modelName = properties.embeddings().model();
        log.info("EmbeddingModelFactory: creating model '{}'", modelName);

        return switch (modelName.toLowerCase().strip()) {
            case "local", "all-minilm-l6-v2", "all-minilm-l6-v2-q" -> {
                log.info("EmbeddingModelFactory: using local all-MiniLM-L6-v2 (ONNX)");
                yield new AllMiniLmL6V2EmbeddingModel();
            }
            case "text-embedding-3-small", "text-embedding-ada-002" -> {
                String apiKey = properties.llm().apiKey();
                if (apiKey == null || apiKey.isBlank()) {
                    throw new IllegalStateException(
                            "EmbeddingModelFactory: aibook.llm.api-key must be set "
                            + "for OpenAI embedding model '" + modelName + "'");
                }
                log.info("EmbeddingModelFactory: using OpenAI embedding model '{}'", modelName);
                yield OpenAiEmbeddingModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .build();
            }
            default -> throw new IllegalArgumentException(
                    "EmbeddingModelFactory: unsupported embedding model '" + modelName
                    + "'. Supported: all-minilm-l6-v2, text-embedding-3-small, "
                    + "text-embedding-ada-002");
        };
    }
}