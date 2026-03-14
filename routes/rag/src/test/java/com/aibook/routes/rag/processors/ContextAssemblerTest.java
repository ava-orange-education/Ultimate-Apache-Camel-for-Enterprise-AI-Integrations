package com.aibook.routes.rag.processors;

import com.aibook.core.dto.RagContext;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ContextAssembler}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>RagContext is built correctly from chunks and headers</li>
 *   <li>queryId is assigned (UUID) when not present</li>
 *   <li>Chunks are numbered in assembled context: [1], [2], ...</li>
 *   <li>Context size limit is respected (maxContextChars)</li>
 * </ul>
 */
class ContextAssemblerTest {

    private DefaultCamelContext camelContext;
    private ContextAssembler    assembler;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        assembler = new ContextAssembler();
        setField(assembler, "maxContextChars", 8000);
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private Exchange exchange(List<String> chunks, String query) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(chunks);
        ex.getIn().setHeader("originalQuery", query);
        return ex;
    }

    // ── queryId ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("queryId is assigned from UUID when header absent")
    void queryId_assignedWhenAbsent() throws Exception {
        Exchange ex = exchange(List.of("Chunk text."), "What is RAG?");
        assembler.process(ex);

        RagContext ctx = ex.getIn().getBody(RagContext.class);
        assertThat(ctx.queryId()).isNotBlank();
        // Should be a valid UUID
        assertThat(ctx.queryId()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("queryId header is set on exchange after processing")
    void queryId_setInExchangeHeader() throws Exception {
        Exchange ex = exchange(List.of("Chunk."), "A question.");
        assembler.process(ex);

        assertThat(ex.getIn().getHeader("queryId", String.class)).isNotBlank();
    }

    @Test
    @DisplayName("Existing queryId header is preserved (idempotent)")
    void existingQueryId_preserved() throws Exception {
        Exchange ex = exchange(List.of("Chunk."), "Query?");
        ex.getIn().setHeader("queryId", "fixed-query-id");
        assembler.process(ex);

        RagContext ctx = ex.getIn().getBody(RagContext.class);
        assertThat(ctx.queryId()).isEqualTo("fixed-query-id");
    }

    // ── Context assembly ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Chunks are numbered [1], [2], ... in assembled context")
    void chunks_numberedInAssembledContext() throws Exception {
        List<String> chunks = List.of("First chunk.", "Second chunk.", "Third chunk.");
        Exchange ex = exchange(chunks, "Test query?");
        assembler.process(ex);

        RagContext ctx = ex.getIn().getBody(RagContext.class);
        String assembled = ctx.assembledContext();
        assertThat(assembled).contains("[1] First chunk.");
        assertThat(assembled).contains("[2] Second chunk.");
        assertThat(assembled).contains("[3] Third chunk.");
    }

    @Test
    @DisplayName("RagContext contains original query and all chunks")
    void ragContext_containsQueryAndChunks() throws Exception {
        List<String> chunks = List.of("Chunk A.", "Chunk B.");
        Exchange ex = exchange(chunks, "What is embeddings?");
        assembler.process(ex);

        RagContext ctx = ex.getIn().getBody(RagContext.class);
        assertThat(ctx.originalQuery()).isEqualTo("What is embeddings?");
        assertThat(ctx.retrievedChunks()).containsExactlyElementsOf(chunks);
    }

    @Test
    @DisplayName("Empty chunk list produces empty assembled context")
    void emptyChunks_emptyAssembledContext() throws Exception {
        Exchange ex = exchange(List.of(), "Empty query?");
        assembler.process(ex);

        RagContext ctx = ex.getIn().getBody(RagContext.class);
        assertThat(ctx.retrievedChunks()).isEmpty();
        assertThat(ctx.assembledContext()).isEmpty();
    }

    @Test
    @DisplayName("Context size limit is respected — truncates at maxContextChars")
    void contextSizeLimit_respected() throws Exception {
        // Each chunk is ~100 chars; maxContextChars = 250 → should stop after 2 chunks
        setField(assembler, "maxContextChars", 250);

        List<String> chunks = List.of(
                "A".repeat(100),
                "B".repeat(100),
                "C".repeat(100)
        );
        Exchange ex = exchange(chunks, "Big context query?");
        assembler.process(ex);

        RagContext ctx = ex.getIn().getBody(RagContext.class);
        assertThat(ctx.assembledContext().length()).isLessThanOrEqualTo(260); // small slack for [n] prefix
        // Third chunk should be truncated
        assertThat(ctx.assembledContext()).doesNotContain("C".repeat(100));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void setField(Object target, String fieldName, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
