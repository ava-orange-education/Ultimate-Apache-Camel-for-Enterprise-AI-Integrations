package com.aibook.ai.vector;

import com.aibook.ai.embeddings.EmbeddingService;
import com.aibook.ai.vector.QdrantVectorStore.ScoredDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Higher-level vector search orchestrator used by RAG route processors.
 *
 * <p>Combines embedding and retrieval into one call:
 * <ol>
 *   <li>Embed the query text via {@link EmbeddingService}</li>
 *   <li>Search the specified Qdrant collection via {@link QdrantVectorStore}</li>
 *   <li>Extract and return the raw text chunks from the matched documents</li>
 * </ol>
 *
 * <p>Routes and higher-level code should prefer this service over calling
 * {@link QdrantVectorStore} directly.
 */
@Service
public class VectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);

    private final EmbeddingService  embeddingService;
    private final QdrantVectorStore vectorStore;

    @Value("${aibook.rag.min-score:0.5}")
    private double defaultMinScore;

    public VectorSearchService(EmbeddingService embeddingService,
                               QdrantVectorStore vectorStore) {
        this.embeddingService = embeddingService;
        this.vectorStore      = vectorStore;
    }

    /**
     * Embed {@code queryText}, search {@code collectionName}, and return the
     * top-{@code topK} text chunks whose similarity exceeds {@link #defaultMinScore}.
     *
     * @param collectionName Qdrant collection to search
     * @param queryText      natural-language query string
     * @param topK           maximum number of chunks to return
     * @return ordered list of text chunk strings (highest relevance first)
     */
    public List<String> searchRelevantChunks(String collectionName,
                                             String queryText,
                                             int topK) {
        return searchRelevantChunks(collectionName, queryText, topK, defaultMinScore);
    }

    /**
     * Overload that accepts an explicit minimum similarity score.
     *
     * @param collectionName Qdrant collection to search
     * @param queryText      natural-language query string
     * @param topK           maximum number of chunks to return
     * @param minScore       minimum cosine similarity threshold [0, 1]
     * @return ordered list of text chunk strings (highest relevance first)
     */
    public List<String> searchRelevantChunks(String collectionName,
                                             String queryText,
                                             int topK,
                                             double minScore) {
        log.debug("VectorSearchService.searchRelevantChunks: collection={} topK={} minScore={}",
                collectionName, topK, minScore);

        float[] queryVector = embeddingService.embed(queryText);
        List<ScoredDocument> matches = vectorStore.search(
                collectionName, queryVector, topK, minScore);

        List<String> chunks = matches.stream()
                .map(doc -> doc.payload().getOrDefault("text", doc.id()).toString())
                .toList();

        log.debug("VectorSearchService.searchRelevantChunks: returning {} chunks", chunks.size());
        return chunks;
    }
}