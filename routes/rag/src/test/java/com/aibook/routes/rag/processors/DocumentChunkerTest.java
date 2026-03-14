package com.aibook.routes.rag.processors;

import com.aibook.core.dto.DocumentContent;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DocumentChunker}.
 *
 * <p>No Spring context — uses raw Camel exchange to test chunking logic in isolation.
 */
class DocumentChunkerTest {

    private DefaultCamelContext camelContext;
    private DocumentChunker     chunker;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        chunker = new DocumentChunker();
        // Inject defaults via reflection (normally injected by @Value)
        setField(chunker, "maxTokens",     512);
        setField(chunker, "overlapTokens", 50);
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private Exchange exchange(DocumentContent doc) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(doc);
        return ex;
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Short document produces a single chunk")
    void shortDocument_singleChunk() throws Exception {
        DocumentContent doc = new DocumentContent(
                "doc-001", "test.txt", null,
                "Apache Camel is an integration framework. It routes messages between endpoints.",
                "text/plain", Map.of());

        Exchange ex = exchange(doc);
        chunker.process(ex);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = ex.getIn().getBody(List.class);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).get("documentId")).isEqualTo("doc-001");
        assertThat(chunks.get(0).get("chunkIndex")).isEqualTo(0);
        assertThat(chunks.get(0).get("text").toString()).contains("Apache Camel");
    }

    @Test
    @DisplayName("chunkCount exchange property is set correctly")
    void chunkCount_setAsExchangeProperty() throws Exception {
        DocumentContent doc = new DocumentContent(
                "doc-002", "test.txt", null,
                "Sentence one. Sentence two. Sentence three.",
                "text/plain", Map.of());

        Exchange ex = exchange(doc);
        chunker.process(ex);

        Integer chunkCount = (Integer) ex.getProperty("chunkCount");
        assertThat(chunkCount).isGreaterThan(0);
    }

    @Test
    @DisplayName("documentId header is set from DTO")
    void documentIdHeader_setFromDto() throws Exception {
        DocumentContent doc = new DocumentContent(
                "my-doc-id", "file.txt", null, "Some text content here.", "text/plain", Map.of());

        Exchange ex = exchange(doc);
        chunker.process(ex);

        assertThat(ex.getIn().getHeader("documentId", String.class)).isEqualTo("my-doc-id");
    }

    @Test
    @DisplayName("Chunk map contains required fields: text, documentId, chunkIndex, source")
    void chunkMap_containsRequiredFields() throws Exception {
        DocumentContent doc = new DocumentContent(
                "doc-003", "chapter10.txt", null,
                "RAG pipelines retrieve relevant context before generating answers.",
                "text/plain", Map.of());

        Exchange ex = exchange(doc);
        chunker.process(ex);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = ex.getIn().getBody(List.class);
        assertThat(chunks).isNotEmpty();

        Map<String, Object> first = chunks.get(0);
        assertThat(first).containsKeys("text", "documentId", "chunkIndex", "source");
        assertThat(first.get("text").toString()).isNotBlank();
        assertThat(first.get("source")).isEqualTo("chapter10.txt");
    }

    @Test
    @DisplayName("Empty text produces zero chunks and sets chunkCount to 0")
    void emptyText_zeroChunks() throws Exception {
        DocumentContent doc = new DocumentContent(
                "doc-004", "empty.txt", null, "  ", "text/plain", Map.of());

        Exchange ex = exchange(doc);
        chunker.process(ex);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = ex.getIn().getBody(List.class);
        assertThat(chunks).isEmpty();
        assertThat(ex.getProperty("chunkCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("Null body throws IllegalArgumentException")
    void nullBody_throwsIllegalArgument() {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(null);

        assertThatThrownBy(() -> chunker.process(ex))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DocumentChunker");
    }

    @Test
    @DisplayName("Large document produces multiple chunks respecting max token limit")
    void largeDocument_multipleChunks() throws Exception {
        // Create a document with ~600 tokens worth of text (approx 2400 chars)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("This is sentence number ").append(i).append(" in a long document. ");
        }
        DocumentContent doc = new DocumentContent(
                "doc-005", "large.txt", null, sb.toString(), "text/plain", Map.of());

        // Use small max tokens so we get multiple chunks
        setField(chunker, "maxTokens", 50);   // 50 tokens ≈ 200 chars

        Exchange ex = exchange(doc);
        chunker.process(ex);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks = ex.getIn().getBody(List.class);
        assertThat(chunks).hasSizeGreaterThan(1);
        // All chunks should have correct documentId
        chunks.forEach(c -> assertThat(c.get("documentId")).isEqualTo("doc-005"));
        // Chunk indices should be sequential
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).get("chunkIndex")).isEqualTo(i);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void setField(Object target, String fieldName, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
