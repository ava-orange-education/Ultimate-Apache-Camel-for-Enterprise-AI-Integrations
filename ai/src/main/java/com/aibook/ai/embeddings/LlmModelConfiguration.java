package com.aibook.ai.embeddings;

import com.aibook.core.config.AiPipelineProperties;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Spring {@link Configuration} that creates the {@link ChatLanguageModel} bean
 * used by {@link com.aibook.ai.llm.LlmGateway}.
 *
 * <p>If {@code aibook.llm.api-key} is set, an OpenAI-compatible client is created
 * (works with OpenAI, Together, Groq, Azure, etc.).  Otherwise, an Ollama client
 * is created pointing at {@code aibook.llm.endpoint}.
 */
@Configuration
public class LlmModelConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LlmModelConfiguration.class);

    private final AiPipelineProperties properties;

    public LlmModelConfiguration(AiPipelineProperties properties) {
        this.properties = properties;
    }

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        String endpoint  = properties.llm().endpoint();
        String modelName = properties.llm().modelName();
        String apiKey    = properties.llm().apiKey();

        if (apiKey != null && !apiKey.isBlank()) {
            log.info("LlmModelConfiguration: OpenAI-compatible model '{}' at {}",
                    modelName, endpoint);
            return OpenAiChatModel.builder()
                    .baseUrl(endpoint)
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .temperature(0.7)
                    .timeout(Duration.ofSeconds(120))
                    .build();
        }

        log.info("LlmModelConfiguration: Ollama model '{}' at {}", modelName, endpoint);
        return OllamaChatModel.builder()
                .baseUrl(endpoint)
                .modelName(modelName)
                .temperature(0.7)
                .timeout(Duration.ofSeconds(120))
                .build();
    }
}
