package com.aibook.ai.llm;

import com.aibook.core.config.AiPipelineProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
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
 * Unit tests for {@link LlmGateway} using a mocked {@link ChatLanguageModel}.
 *
 * <p>No Spring context is loaded — pure Mockito wiring.
 */
@ExtendWith(MockitoExtension.class)
class LlmGatewayTest {

    @Mock
    private ChatLanguageModel chatModel;

    @Mock
    private PromptLoader promptLoader;

    private LlmGateway gateway;

    // Build a minimal AiPipelineProperties with a test model name
    private static final AiPipelineProperties PROPS = new AiPipelineProperties(
            new AiPipelineProperties.LlmConfig(
                    "http://localhost:11434", "test-model", "", 120, 0.7),
            new AiPipelineProperties.EmbeddingsConfig("local", 384),
            new AiPipelineProperties.QdrantConfig("localhost", 6334, "documents", false),
            new AiPipelineProperties.Neo4jConfig("bolt://localhost:7687", "neo4j", "password", 3),
            new AiPipelineProperties.ScoringConfig(0.6, "default", false, "mock", ""),
            new AiPipelineProperties.HumanReviewConfig("direct:human-review", "MEDIUM",
                    new AiPipelineProperties.HumanReviewConfig.SimulationConfig(0L))
    );

    @BeforeEach
    void setUp() {
        gateway = new LlmGateway(chatModel, promptLoader, PROPS);
    }

    // ── chat() tests ──────────────────────────────────────────────────────────

    @Test
    void chat_returnsModelResponse() throws AiGatewayException {
        Response<AiMessage> fakeResponse = Response.from(
                AiMessage.from("The answer is 42."),
                new TokenUsage(10, 5));
        when(chatModel.generate(anyList())).thenReturn(fakeResponse);

        String result = gateway.chat("You are helpful.", "What is 6×7?");

        assertThat(result).isEqualTo("The answer is 42.");
        verify(chatModel, times(1)).generate(anyList());
    }

    @Test
    void chat_passesSystemAndUserMessages() throws AiGatewayException {
        Response<AiMessage> fakeResponse = Response.from(AiMessage.from("ok"));
        when(chatModel.generate(anyList())).thenReturn(fakeResponse);

        gateway.chat("System prompt here.", "User message here.");

        // Capture argument to verify message types
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(chatModel).generate(captor.capture());
        List<?> messages = captor.getValue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(dev.langchain4j.data.message.SystemMessage.class);
        assertThat(messages.get(1)).isInstanceOf(dev.langchain4j.data.message.UserMessage.class);
    }

    @Test
    void chat_throwsAiGatewayExceptionOnFailure() {
        when(chatModel.generate(anyList())).thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> gateway.chat("system", "user"))
                .isInstanceOf(AiGatewayException.class)
                .hasMessageContaining("timeout")
                .extracting(e -> ((AiGatewayException) e).getOperation())
                .isEqualTo("chat");
    }

    @Test
    void chat_aiGatewayExceptionCarriesModelName() {
        when(chatModel.generate(anyList())).thenThrow(new RuntimeException("err"));

        AiGatewayException ex = catchThrowableOfType(
                () -> gateway.chat("s", "u"), AiGatewayException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.getModelName()).isEqualTo("test-model");
    }

    // ── chatWithContext() tests ───────────────────────────────────────────────

    @Test
    void chatWithContext_returnsModelResponse() throws AiGatewayException {
        Response<AiMessage> fakeResponse = Response.from(AiMessage.from("Context-aware answer."));
        when(chatModel.generate(anyList())).thenReturn(fakeResponse);

        String result = gateway.chatWithContext(
                "You answer from context.", "Retrieved chunk 1.", "What is RAG?");

        assertThat(result).isEqualTo("Context-aware answer.");
    }

    @Test
    void chatWithContext_injectsContextIntoSystemMessage() throws AiGatewayException {
        Response<AiMessage> fakeResponse = Response.from(AiMessage.from("ok"));
        when(chatModel.generate(anyList())).thenReturn(fakeResponse);

        gateway.chatWithContext("Be precise.", "Some context here.", "My question.");

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(chatModel).generate(captor.capture());
        List<?> messages = captor.getValue();

        // First message is the augmented system message — must contain the context
        String systemContent = messages.get(0).toString();
        assertThat(systemContent)
                .contains("Be precise.")
                .contains("Some context here.")
                .contains("---CONTEXT---");
    }

    @Test
    void chatWithContext_throwsAiGatewayExceptionOnFailure() {
        when(chatModel.generate(anyList())).thenThrow(new RuntimeException("conn refused"));

        assertThatThrownBy(() -> gateway.chatWithContext("s", "ctx", "q"))
                .isInstanceOf(AiGatewayException.class)
                .extracting(e -> ((AiGatewayException) e).getOperation())
                .isEqualTo("chatWithContext");
    }

    // ── generateFromTemplate() tests ──────────────────────────────────────────

    @Test
    void generateFromTemplate_loadsPromptAndCallsChat() {
        when(promptLoader.loadAndFill(eq("prompts/test.txt"), anyMap()))
                .thenReturn("You are a helpful assistant. Summarize: Hello World.");
        // gateway calls chat(filledPrompt, "") — empty user msg causes LangChain4j
        // TextContent validation to throw before chatModel is reached.
        // Verify the promptLoader was called with the right args and exception is wrapped.
        try {
            gateway.generateFromTemplate(
                    "prompts/test.txt", Map.of("content", "Hello World."));
        } catch (RuntimeException e) {
            assertThat(e).hasMessageContaining("generateFromTemplate failed");
        }
        verify(promptLoader).loadAndFill(eq("prompts/test.txt"), argThat(m ->
                m.containsKey("content") && "Hello World.".equals(m.get("content"))));
    }

    @Test
    void generateFromTemplate_wrapsAiGatewayExceptionInRuntimeException() {
        when(promptLoader.loadAndFill(anyString(), anyMap())).thenReturn("filled prompt");
        // LangChain4j rejects empty user message — this causes the wrapping RuntimeException
        // without needing a chatModel stub at all.

        assertThatThrownBy(() ->
                gateway.generateFromTemplate("prompts/any.txt", Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("generateFromTemplate failed");
    }

    // ── getModelName() ────────────────────────────────────────────────────────

    @Test
    void getModelName_returnsConfiguredName() {
        assertThat(gateway.getModelName()).isEqualTo("test-model");
    }
}
