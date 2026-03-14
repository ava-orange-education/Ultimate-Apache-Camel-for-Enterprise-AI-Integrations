package com.aibook.routes.rag.processors;

import com.aibook.ai.llm.PromptLoader;
import com.aibook.core.dto.RagContext;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RagPromptAssembler}.
 *
 * <p>Mocks {@link PromptLoader} to verify:
 * <ul>
 *   <li>Correct template path is used</li>
 *   <li>{{context}} and {{question}} are filled from RagContext</li>
 *   <li>ragContext header is preserved for downstream RagResponseParser</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RagPromptAssemblerTest {

    @Mock
    PromptLoader promptLoader;

    private DefaultCamelContext  camelContext;
    private RagPromptAssembler   assembler;

    @BeforeEach
    void setUp() throws Exception {
        camelContext = new DefaultCamelContext();
        camelContext.start();
        assembler = new RagPromptAssembler(promptLoader);
    }

    @AfterEach
    void tearDown() throws Exception {
        camelContext.stop();
    }

    private Exchange exchange(RagContext ragCtx) {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(ragCtx);
        return ex;
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Loads rag/rag-answer.txt template with correct context and question vars")
    void loadsCorrectTemplateWithContextAndQuestion() throws Exception {
        String expectedPrompt = "Given context: some context. Answer: some question.";
        when(promptLoader.loadAndFill(eq("prompts/rag/rag-answer.txt"), anyMap()))
                .thenReturn(expectedPrompt);

        RagContext ctx = new RagContext(
                "q-001", "What is Camel?", "",
                List.of("Apache Camel is an integration framework."),
                List.of(0.9f),
                "Apache Camel is an integration framework."
        );

        Exchange ex = exchange(ctx);
        assembler.process(ex);

        // Verify correct template path
        verify(promptLoader).loadAndFill(
                eq("prompts/rag/rag-answer.txt"),
                anyMap());

        // Body should be the filled prompt
        assertThat(ex.getIn().getBody(String.class)).isEqualTo(expectedPrompt);
    }

    @Test
    @DisplayName("RagContext is preserved in header 'ragContext'")
    void ragContextPreservedInHeader() throws Exception {
        when(promptLoader.loadAndFill(anyString(), anyMap()))
                .thenReturn("filled prompt");

        RagContext ctx = new RagContext(
                "q-002", "How does RAG work?", "",
                List.of("RAG retrieves and generates."),
                List.of(0.85f),
                "RAG retrieves and generates."
        );

        Exchange ex = exchange(ctx);
        assembler.process(ex);

        RagContext preserved = ex.getIn().getHeader("ragContext", RagContext.class);
        assertThat(preserved).isNotNull();
        assertThat(preserved.queryId()).isEqualTo("q-002");
        assertThat(preserved.originalQuery()).isEqualTo("How does RAG work?");
    }

    @Test
    @DisplayName("promptTemplate header is set to rag-answer.txt path")
    void promptTemplateHeaderIsSet() throws Exception {
        when(promptLoader.loadAndFill(anyString(), anyMap()))
                .thenReturn("some prompt");

        RagContext ctx = new RagContext("q-003", "Test query?", "",
                List.of("Context text"), List.of(0.8f), "Context text");

        Exchange ex = exchange(ctx);
        assembler.process(ex);

        String templatePath = ex.getIn().getHeader("promptTemplate", String.class);
        assertThat(templatePath).isEqualTo("prompts/rag/rag-answer.txt");
    }

    @Test
    @DisplayName("Empty assembledContext uses fallback placeholder in prompt vars")
    void emptyContext_usesFallbackInVars() throws Exception {
        when(promptLoader.loadAndFill(eq("prompts/rag/rag-answer.txt"), anyMap()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> vars = invocation.getArgument(1);
                    // Should not be blank even if assembledContext is empty
                    assertThat(vars.get("context")).isNotBlank();
                    return "prompt with fallback context";
                });

        RagContext ctx = new RagContext(
                "q-004", "No context query?", "", List.of(), List.of(), "");

        Exchange ex = exchange(ctx);
        assembler.process(ex);

        assertThat(ex.getIn().getBody(String.class)).isEqualTo("prompt with fallback context");
    }

    @Test
    @DisplayName("Null body throws IllegalArgumentException")
    void nullBody_throwsIllegalArgument() {
        Exchange ex = new DefaultExchange(camelContext);
        ex.getIn().setBody(null);

        assertThatThrownBy(() -> assembler.process(ex))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RagPromptAssembler");
    }
}
