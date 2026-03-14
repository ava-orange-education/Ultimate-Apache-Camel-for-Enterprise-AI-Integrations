package com.aibook.routes.rag.processors;

import com.aibook.ai.vector.QdrantVectorStore;
import com.aibook.ai.vector.QdrantVectorStore.ScoredDocument;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Retrieves the most relevant document chunks from Qdrant for a given query embedding.
 *
 * <p>Reads:
 * <ul>
 *   <li>Header {@code queryVector}  — {@code float[]} embedding produced by {@link QueryEmbedder}</li>
 *   <li>Optional header {@code qdrantCollection} — override default collection</li>
 * </ul>
 *
 * <p>Sets:
 * <ul>
 *   <li>Body to {@code List<String>} — the retrieved text chunks (highest relevance first)</li>
 *   <li>Header {@code retrievedCount} — number of chunks returned</li>
 *   <li>Header {@code relevanceScores} — parallel {@code List<Float>} of cosine scores</li>
 * </ul>
 */
@Component
public class QdrantRetriever implements Processor {

    private static final Logger log = LoggerFactory.getLogger(QdrantRetriever.class);

    private final QdrantVectorStore vectorStore;

    @Value("${aibook.rag.top-k:5}")
    private int topK;

    @Value("${aibook.rag.min-score:0.7}")
    private double minScore;

    @Value("${aibook.qdrant.collection:knowledge-base}")
    private String defaultCollection;

    public QdrantRetriever(QdrantVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void process(Exchange exchange) {
        float[] queryVector = exchange.getIn().getHeader("queryVector", float[].class);
        if (queryVector == null) {
            throw new IllegalStateException(
                    "QdrantRetriever: header 'queryVector' is missing — "
                    + "ensure QueryEmbedder runs before QdrantRetriever");
        }

        String collection = exchange.getIn()
                .getHeader("qdrantCollection", defaultCollection, String.class);

        log.debug("QdrantRetriever: searching collection={} topK={} minScore={}",
                collection, topK, minScore);

        List<ScoredDocument> matches = vectorStore.search(collection, queryVector, topK, minScore);

        List<String>  chunks = new ArrayList<>(matches.size());
        List<Float>   scores = new ArrayList<>(matches.size());

        for (ScoredDocument doc : matches) {
            String text = doc.payload().getOrDefault("text", doc.id()).toString();
            chunks.add(text);
            scores.add((float) doc.score());
        }

        exchange.getIn().setBody(chunks);
        exchange.getIn().setHeader("retrievedCount",   chunks.size());
        exchange.getIn().setHeader("relevanceScores",  scores);

        log.info("QdrantRetriever: retrieved {} chunks from collection={}",
                chunks.size(), collection);
    }
}
