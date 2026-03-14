package com.aibook.routes.summarization;

import com.aibook.ai.llm.PromptLoader;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link PromptAssembler}.
 *
 * <p>Mocks {@link PromptLoader} so the test does not need classpath template files.
 */
@ExtendWith(MockitoExtension.class)
class PromptAssemblerTest {

    @Mock
    PromptLoader promptLoader;

    private DefaultCamelContext camelContext;
    private PromptAssembler assembler;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        assembler = new PromptAssembler(promptLoader);
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private Exchange exchange(String body, String sourceType) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(body);
        ex.getIn().setHeader("SourceType", sourceType);
        return ex;
    }

    // ── Template selection ────────────────────────────────────────────────────

    @Test
    @DisplayName("email sourceType loads email-summary.txt template")
    void emailSourceType_loadsEmailTemplate() throws Exception {
        when(promptLoader.loadAndFill(anyString(), anyMap()))
                .thenReturn("SYSTEM: System prompt.\n---USER---\nUser: {{context}}");

        Exchange ex = exchange("email context", "email");
        assembler.process(ex);

        verify(promptLoader).loadAndFill(
                eq("prompts/summarization/email-summary.txt"), anyMap());
    }

    @Test
    @DisplayName("thread sourceType loads thread-summary.txt template")
    void threadSourceType_loadsThreadTemplate() throws Exception {
        when(promptLoader.loadAndFill(anyString(), anyMap()))
                .thenReturn("SYSTEM: Thread system.\n---USER---\nThread user message.");

        Exchange ex = exchange("thread context", "thread");
        assembler.process(ex);

        verify(promptLoader).loadAndFill(
                eq("prompts/summarization/thread-summary.txt"), anyMap());
    }

    @Test
    @DisplayName("document sourceType loads document-summary.txt template")
    void documentSourceType_loadsDocumentTemplate() throws Exception {
        when(promptLoader.loadAndFill(anyString(), anyMap()))
                .thenReturn("SYSTEM: Doc system.\n---USER---\nDoc user message.");

        Exchange ex = exchange("doc context", "document");
        assembler.process(ex);

        verify(promptLoader).loadAndFill(
                eq("prompts/summarization/document-summary.txt"), anyMap());
    }

    @Test
    @DisplayName("unknown sourceType falls back to document template")
    void unknownSourceType_fallsBackToDocumentTemplate() throws Exception {
        when(promptLoader.loadAndFill(anyString(), anyMap()))
                .thenReturn("SYSTEM: s.\n---USER---\nu.");

        Exchange ex = exchange("ctx", "OTHER");
        assembler.process(ex);

        verify(promptLoader).loadAndFill(
                eq("prompts/summarization/document-summary.txt"), anyMap());
    }

    // ── SYSTEM / USER splitting ────────────────────────────────────────────────

    @Test
    @DisplayName("splitTemplate correctly splits on ---USER--- separator")
    void splitTemplate_splitsOnSeparator() {
        String template = "SYSTEM: You are an analyst.\n---USER---\nSummarize this content.";

        String[] parts = assembler.splitTemplate(template);

        assertThat(parts[0]).isEqualTo("You are an analyst.");
        assertThat(parts[1]).isEqualTo("Summarize this content.");
    }

    @Test
    @DisplayName("splitTemplate with no separator uses default system prompt")
    void splitTemplate_noSeparator_usesDefaultSystemPrompt() {
        String template = "Just summarize this content without a separator.";

        String[] parts = assembler.splitTemplate(template);

        assertThat(parts[0]).isEqualTo(PromptAssembler.DEFAULT_SYSTEM_PROMPT);
        assertThat(parts[1]).isEqualTo(template);
    }

    @Test
    @DisplayName("splitTemplate strips 'SYSTEM:' prefix from system section")
    void splitTemplate_stripsSystemPrefix() {
        String template = "SYSTEM: Act as an expert.\n---USER---\nDo the task.";

        String[] parts = assembler.splitTemplate(template);

        assertThat(parts[0]).isEqualTo("Act as an expert.");
        assertThat(parts[0]).doesNotContain("SYSTEM:");
    }

    @Test
    @DisplayName("splitTemplate blank system section uses default system prompt")
    void splitTemplate_blankSystemSection_usesDefault() {
        String template = "\n---USER---\nUser section only.";

        String[] parts = assembler.splitTemplate(template);

        assertThat(parts[0]).isEqualTo(PromptAssembler.DEFAULT_SYSTEM_PROMPT);
        assertThat(parts[1]).isEqualTo("User section only.");
    }

    // ── Exchange headers ──────────────────────────────────────────────────────

    @Test
    @DisplayName("process() sets promptSystemMessage header")
    void process_setsSystemMessageHeader() throws Exception {
        when(promptLoader.loadAndFill(anyString(), anyMap()))
                .thenReturn("SYSTEM: I am the system.\n---USER---\nUser text here.");

        Exchange ex = exchange("ctx", "email");
        assembler.process(ex);

        assertThat(ex.getIn().getHeader("promptSystemMessage", String.class))
                .isEqualTo("I am the system.");
    }

    @Test
    @DisplayName("process() sets promptUserMessage header")
    void process_setsUserMessageHeader() throws Exception {
        when(promptLoader.loadAndFill(anyString(), anyMap()))
                .thenReturn("SYSTEM: system.\n---USER---\nUser message to send.");

        Exchange ex = exchange("ctx", "document");
        assembler.process(ex);

        assertThat(ex.getIn().getHeader("promptUserMessage", String.class))
                .isEqualTo("User message to send.");
    }

    @Test
    @DisplayName("process() sets PromptTemplate header to classpath path")
    void process_setsPromptTemplateHeader() throws Exception {
        when(promptLoader.loadAndFill(anyString(), anyMap()))
                .thenReturn("SYSTEM: s.\n---USER---\nu.");

        Exchange ex = exchange("ctx", "email");
        assembler.process(ex);

        assertThat(ex.getIn().getHeader("PromptTemplate", String.class))
                .isEqualTo("prompts/summarization/email-summary.txt");
    }

    @Test
    @DisplayName("process() replaces body with assembled user message")
    void process_replacesBodyWithUserMessage() throws Exception {
        when(promptLoader.loadAndFill(anyString(), anyMap()))
                .thenReturn("SYSTEM: sys.\n---USER---\nFinal user message for LLM.");

        Exchange ex = exchange("context body", "document");
        assembler.process(ex);

        assertThat(ex.getIn().getBody(String.class)).isEqualTo("Final user message for LLM.");
    }

    // ── Fallback on template load failure ─────────────────────────────────────

    @Test
    @DisplayName("process() falls back to inline prompt when template not found")
    void process_fallback_whenTemplateNotFound() throws Exception {
        when(promptLoader.loadAndFill(anyString(), anyMap()))
                .thenThrow(new IllegalStateException("Template not found"));

        Exchange ex = exchange("context body", "email");
        assembler.process(ex);  // should not throw

        // Body should still be set to something useful
        assertThat(ex.getIn().getBody(String.class)).isNotBlank();
        // PromptTemplate header should indicate fallback
        assertThat(ex.getIn().getHeader("PromptTemplate", String.class))
                .isEqualTo("INLINE_FALLBACK");
    }

    // ── Context injection ─────────────────────────────────────────────────────

    @Test
    @DisplayName("process() passes body string as 'context' variable to PromptLoader")
    void process_passesBodyAsContextVar() throws Exception {
        when(promptLoader.loadAndFill(anyString(), anyMap())).thenReturn("SYSTEM: s.\n---USER---\nu.");

        Exchange ex = exchange("my context body", "email");
        assembler.process(ex);

        // Verify loadAndFill was called with a map containing "context" → body value
        verify(promptLoader).loadAndFill(
                anyString(),
                org.mockito.ArgumentMatchers.argThat(map ->
                        "my context body".equals(map.get("context"))));
    }

    @Test
    @DisplayName("process() handles null body gracefully (passes empty context)")
    void process_nullBody_passesEmptyContext() throws Exception {
        when(promptLoader.loadAndFill(anyString(), anyMap())).thenReturn("SYSTEM: s.\n---USER---\nu.");

        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(null);
        ex.getIn().setHeader("SourceType", "document");

        assembler.process(ex);  // should not throw

        assertThat(ex.getIn().getBody(String.class)).isNotNull();
    }
}
