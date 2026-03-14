package com.aibook.ai.embeddings;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Thin service wrapper around the injected {@link EmbeddingModel}.
 *
 * <p>Provides two operations:
 * <ul>
 *   <li>{@link #embed(String)} — embed a single text string</li>
 *   <li>{@link #embedBatch(List)} — embed multiple texts in one call</li>
 * </ul>
 *
 * <p>The underlying model is configured by {@link EmbeddingModelFactory} and
 * injected as a Spring {@code @Bean}; this service does not instantiate it directly.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * Embed a single text and return its vector representation.
     *
     * @param text the input text (should not be blank)
     * @return float array of the embedding vector
     * @throws IllegalArgumentException if {@code text} is null or blank
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("EmbeddingService.embed: text must not be null or blank");
        }
        log.debug("EmbeddingService.embed: {} chars", text.length());
        Response<Embedding> response = embeddingModel.embed(TextSegment.from(text));
        return response.content().vector();
    }

    /**
     * Embed a batch of texts in a single model call.
     *
     * <p>Returns an ordered list parallel to the input list — index {@code i} of the
     * result corresponds to index {@code i} of {@code texts}.
     *
     * @param texts list of input strings; individual entries must not be blank
     * @return list of float-array embedding vectors in the same order as {@code texts}
     * @throws IllegalArgumentException if {@code texts} is null or empty
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException(
                    "EmbeddingService.embedBatch: texts list must not be null or empty");
        }
        log.debug("EmbeddingService.embedBatch: {} texts", texts.size());
        List<TextSegment> segments = texts.stream()
                .map(TextSegment::from)
                .toList();
        Response<List<Embedding>> response = embeddingModel.embedAll(segments);
        return response.content().stream()
                .map(Embedding::vector)
                .toList();
    }

    /** Return the underlying model instance (useful for testing and store initialisation). */
    public EmbeddingModel getModel() {
        return embeddingModel;
    }
}