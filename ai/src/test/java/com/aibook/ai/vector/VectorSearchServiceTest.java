package com.aibook.ai.vector;

import com.aibook.ai.embeddings.EmbeddingService;
import com.aibook.ai.vector.QdrantVectorStore.ScoredDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link VectorSearchService} using mocked {@link EmbeddingService}
 * and {@link QdrantVectorStore}.
 *
 * <p>No Spring context — pure Mockito wiring with {@code @ExtendWith(MockitoExtension.class)}.
 */
@ExtendWith(MockitoExtension.class)
class VectorSearchServiceTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private QdrantVectorStore vectorStore;

    private VectorSearchService searchService;

    private static final float[] FAKE_VECTOR = new float[]{0.1f, 0.2f, 0.3f};
    private static final String COLLECTION   = "documents";

    @BeforeEach
    void setUp() {
        searchService = new VectorSearchService(embeddingService, vectorStore);
        // Default: embedding always returns FAKE_VECTOR
        when(embeddingService.embed(anyString())).thenReturn(FAKE_VECTOR);
    }

    // ── searchRelevantChunks(collectionName, queryText, topK) ────────────────

    @Test
    void searchRelevantChunks_embedsThenSearches() {
        List<ScoredDocument> storedDocs = List.of(
                new ScoredDocument("id-1", Map.of("text", "chunk one"), 0.9),
                new ScoredDocument("id-2", Map.of("text", "chunk two"), 0.8)
        );
        when(vectorStore.search(eq(COLLECTION), eq(FAKE_VECTOR), eq(5), anyDouble()))
                .thenReturn(storedDocs);

        List<String> chunks = searchService.searchRelevantChunks(COLLECTION, "test query", 5);

        assertThat(chunks).containsExactly("chunk one", "chunk two");
        verify(embeddingService).embed("test query");
        verify(vectorStore).search(eq(COLLECTION), eq(FAKE_VECTOR), eq(5), anyDouble());
    }

    @Test
    void searchRelevantChunks_returnsEmptyListWhenNoMatches() {
        when(vectorStore.search(any(), any(), anyInt(), anyDouble())).thenReturn(List.of());

        List<String> result = searchService.searchRelevantChunks(COLLECTION, "unknown query", 3);

        assertThat(result).isEmpty();
    }

    @Test
    void searchRelevantChunks_usesDocumentIdAsFallbackWhenTextMissing() {
        List<ScoredDocument> docs = List.of(
                new ScoredDocument("fallback-id", Map.of("source", "doc.pdf"), 0.75)
        );
        when(vectorStore.search(any(), any(), anyInt(), anyDouble())).thenReturn(docs);

        List<String> chunks = searchService.searchRelevantChunks(COLLECTION, "q", 1);

        // Falls back to id when 'text' key is absent
        assertThat(chunks).containsExactly("fallback-id");
    }

    @Test
    void searchRelevantChunks_respectsTopK() {
        when(vectorStore.search(any(), any(), eq(2), anyDouble())).thenReturn(
                List.of(
                        new ScoredDocument("a", Map.of("text", "alpha"), 0.95),
                        new ScoredDocument("b", Map.of("text", "beta"),  0.85)
                )
        );

        List<String> chunks = searchService.searchRelevantChunks(COLLECTION, "query", 2);

        assertThat(chunks).hasSize(2);
        verify(vectorStore).search(any(), any(), eq(2), anyDouble());
    }

    // ── searchRelevantChunks with explicit minScore ───────────────────────────

    @Test
    void searchRelevantChunks_withExplicitMinScore_passesCorrectThreshold() {
        when(vectorStore.search(eq(COLLECTION), eq(FAKE_VECTOR), eq(10), eq(0.7)))
                .thenReturn(List.of(
                        new ScoredDocument("x", Map.of("text", "result"), 0.8)
                ));

        List<String> chunks = searchService.searchRelevantChunks(COLLECTION, "q", 10, 0.7);

        assertThat(chunks).containsExactly("result");
        verify(vectorStore).search(COLLECTION, FAKE_VECTOR, 10, 0.7);
    }

    // ── Ordering preserved ────────────────────────────────────────────────────

    @Test
    void searchRelevantChunks_preservesOrderFromStore() {
        List<ScoredDocument> ordered = List.of(
                new ScoredDocument("1", Map.of("text", "first"),  0.99),
                new ScoredDocument("2", Map.of("text", "second"), 0.88),
                new ScoredDocument("3", Map.of("text", "third"),  0.70)
        );
        when(vectorStore.search(any(), any(), anyInt(), anyDouble())).thenReturn(ordered);

        List<String> result = searchService.searchRelevantChunks(COLLECTION, "q", 3);

        assertThat(result).containsExactly("first", "second", "third");
    }
}
