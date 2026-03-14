package com.aibook.routes.rag.processors;

import com.aibook.ai.vector.QdrantVectorStore;
import com.aibook.ai.vector.QdrantVectorStore.ScoredDocument;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link QdrantRetriever}.
 *
 * <p>Mocks {@link QdrantVectorStore} to verify:
 * <ul>
 *   <li>Vector search is called with correct parameters</li>
 *   <li>Body is set to List of text chunks extracted from ScoredDocuments</li>
 *   <li>retrievedCount and relevanceScores headers are set</li>
 *   <li>Missing queryVector header throws IllegalStateException</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class QdrantRetrieverTest {

    @Mock
    QdrantVectorStore vectorStore;

    private DefaultCamelContext camelContext;
    private QdrantRetriever     retriever;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        retriever = new QdrantRetriever(vectorStore);
        setField(retriever, "topK",               5);
        setField(retriever, "minScore",            0.7);
        setField(retriever, "defaultCollection",   "knowledge-base");
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private Exchange exchange(float[] queryVector) {
        Exchange ex = new DefaultExchange(camelContext);
        if (queryVector != null) {
            ex.getIn().setHeader("queryVector",   queryVector);
            ex.getIn().setHeader("originalQuery", "test query");
        }
        return ex;
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Returns list of text chunks from Qdrant search results")
    void returnsTextChunksFromQdrant() throws Exception {
        float[] vector = new float[]{0.1f, 0.2f, 0.3f};
        List<ScoredDocument> docs = List.of(
                new ScoredDocument("id-1", Map.of("text", "Apache Camel overview"), 0.95),
                new ScoredDocument("id-2", Map.of("text", "LangChain4j embeddings"), 0.88)
        );
        when(vectorStore.search(anyString(), any(float[].class), anyInt(), anyDouble()))
                .thenReturn(docs);

        Exchange ex = exchange(vector);
        retriever.process(ex);

        @SuppressWarnings("unchecked")
        List<String> chunks = ex.getIn().getBody(List.class);
        assertThat(chunks).containsExactly("Apache Camel overview", "LangChain4j embeddings");
    }

    @Test
    @DisplayName("retrievedCount header reflects number of chunks returned")
    void retrievedCountHeader_set() throws Exception {
        float[] vector = new float[]{0.1f, 0.2f};
        when(vectorStore.search(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(
                        new ScoredDocument("id-1", Map.of("text", "chunk 1"), 0.9),
                        new ScoredDocument("id-2", Map.of("text", "chunk 2"), 0.8),
                        new ScoredDocument("id-3", Map.of("text", "chunk 3"), 0.75)
                ));

        Exchange ex = exchange(vector);
        retriever.process(ex);

        assertThat(ex.getIn().getHeader("retrievedCount", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("relevanceScores header contains parallel scores in descending order")
    void relevanceScoresHeader_setParallel() throws Exception {
        float[] vector = new float[]{0.5f};
        when(vectorStore.search(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of(
                        new ScoredDocument("id-1", Map.of("text", "chunk A"), 0.92),
                        new ScoredDocument("id-2", Map.of("text", "chunk B"), 0.85)
                ));

        Exchange ex = exchange(vector);
        retriever.process(ex);

        @SuppressWarnings("unchecked")
        List<Float> scores = ex.getIn().getHeader("relevanceScores", List.class);
        assertThat(scores).containsExactly(0.92f, 0.85f);
    }

    @Test
    @DisplayName("Uses default collection when qdrantCollection header is absent")
    void usesDefaultCollection_whenHeaderAbsent() throws Exception {
        when(vectorStore.search(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of());

        Exchange ex = exchange(new float[]{0.1f});
        retriever.process(ex);

        verify(vectorStore).search(eq("knowledge-base"), any(), eq(5), eq(0.7));
    }

    @Test
    @DisplayName("Uses qdrantCollection header override when present")
    void usesCollectionHeaderOverride() throws Exception {
        when(vectorStore.search(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of());

        Exchange ex = exchange(new float[]{0.1f});
        ex.getIn().setHeader("qdrantCollection", "custom-collection");
        retriever.process(ex);

        verify(vectorStore).search(eq("custom-collection"), any(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("Empty search result produces empty chunk list and retrievedCount=0")
    void emptyResults_zeroChunks() throws Exception {
        when(vectorStore.search(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of());

        Exchange ex = exchange(new float[]{0.1f});
        retriever.process(ex);

        @SuppressWarnings("unchecked")
        List<String> chunks = ex.getIn().getBody(List.class);
        assertThat(chunks).isEmpty();
        assertThat(ex.getIn().getHeader("retrievedCount", Integer.class)).isEqualTo(0);
    }

    @Test
    @DisplayName("Missing queryVector header throws IllegalStateException")
    void missingQueryVectorHeader_throwsIllegalState() {
        Exchange ex = new DefaultExchange(camelContext);
        // No queryVector header set

        assertThatThrownBy(() -> retriever.process(ex))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("queryVector");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void setField(Object target, String fieldName, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
