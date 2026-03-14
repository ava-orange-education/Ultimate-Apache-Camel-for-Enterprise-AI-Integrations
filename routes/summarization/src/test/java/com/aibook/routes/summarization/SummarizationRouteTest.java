package com.aibook.routes.summarization;

import com.aibook.ai.llm.AiGatewayException;
import com.aibook.ai.llm.LlmGateway;
import com.aibook.core.dto.SummaryResult;
import com.aibook.core.error.AiErrorHandler;
import com.aibook.core.processors.AuditArtifactCollector;
import com.aibook.core.processors.ContentNormalizer;
import com.aibook.core.processors.SummaryValidator;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration-style test for the summarization main route.
 *
 * <p>Strategy:
 * <ul>
 *   <li>LlmGateway and PromptLoader are mocked via {@code @MockBean}.</li>
 *   <li>SummaryOutputRoute and IngestionRoute are excluded via
 *       {@code camel.springboot.java-routes-exclude-pattern} in the test application.yml,
 *       so stub routes can define {@code direct:summaryOutput} and
 *       {@code direct:summaryFallback} without causing "multiple consumers" conflicts.</li>
 *   <li>The {@code summary-fallback} route (defined in SummarizationRoute) forwards to
 *       {@code direct:deadLetter}, which is also stubbed.</li>
 *   <li>Entry point is {@code direct:prepareForSummarization}.</li>
 * </ul>
 */
@CamelSpringBootTest
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
@SpringBootTest(classes = {
        SummarizationTestApplication.class,
        SummarizationRoute.class,
        MultiDocContextBuilder.class,
        PromptAssembler.class,
        SummaryValidator.class,
        AiErrorHandler.class,
        ContentNormalizer.class,
        EmailParsingProcessor.class,
        DocumentParsingProcessor.class,
        AuditArtifactCollector.class,
        SummarizationRouteTest.StubRoutes.class
})
class SummarizationRouteTest {

    /** Captures exchanges landing on direct:summaryOutput */
    static final BlockingQueue<Object> outputCapture   = new ArrayBlockingQueue<>(10);
    /** Captures exchanges landing on direct:summaryFallback (via stub or deadLetter stub) */
    static final BlockingQueue<Object> fallbackCapture = new ArrayBlockingQueue<>(10);

    /**
     * Stub routes for downstream endpoints needed by the summarization routes.
     */
    @TestConfiguration
    static class StubRoutes {
        @Bean
        RouteBuilder summaryStubs() {
            return new RouteBuilder() {
                @Override
                public void configure() {
                    // SummaryOutputRoute is excluded by camel.springboot.java-routes-exclude-pattern
                    // so we can safely define these endpoints here.
                    from("direct:summaryOutput")
                            .routeId("stub-summary-output")
                            .process(e -> outputCapture.offer(e.getIn().getBody()));

                    // summary-fallback route (defined in SummarizationRoute) sends to deadLetter.
                    // We stub deadLetter to capture fallback exchanges.
                    from("direct:deadLetter")
                            .routeId("stub-dead-letter")
                            .process(e -> fallbackCapture.offer(e.getIn().getBody()));
                }
            };
        }
    }

    @MockBean
    LlmGateway llmGateway;

    @MockBean
    com.aibook.ai.llm.PromptLoader promptLoader;

    // Prevents Spring from trying to create a real ObjectMapper bean
    @MockBean
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    ProducerTemplate producer;

    @BeforeEach
    void setUp() throws Exception {
        outputCapture.clear();
        fallbackCapture.clear();
        when(promptLoader.loadAndFill(anyString(), anyMap()))
                .thenReturn("SYSTEM: You are a helpful assistant.\n---USER---\n{context}");
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Valid LLM response flows to summaryOutput")
    void validLlmResponse_flowsToSummaryOutput() throws Exception {
        when(llmGateway.chat(anyString(), anyString()))
                .thenReturn("This is a valid summary of sufficient length to pass validation checks.");

        producer.sendBodyAndHeaders(
                "direct:prepareForSummarization",
                "=== EMAIL THREAD / DOCUMENT CONTEXT ===\nSome email body content here.",
                Map.of("correlationId", "test-corr-001", "SourceType", "email"));

        Object body = outputCapture.poll(5, TimeUnit.SECONDS);
        assertThat(body).isInstanceOf(SummaryResult.class);
        SummaryResult result = (SummaryResult) body;
        assertThat(result.summaryText()).contains("valid summary");
        assertThat(result.sourceId()).isEqualTo("test-corr-001");
        assertThat(result.summaryType()).isEqualTo("email");
        assertThat(fallbackCapture).isEmpty();
    }

    @Test
    @DisplayName("Document sourceType selects document prompt template")
    void documentSourceType_usesDocumentTemplate() throws Exception {
        when(llmGateway.chat(anyString(), anyString()))
                .thenReturn("A concise document summary that is well within the character limit.");

        producer.sendBodyAndHeaders(
                "direct:prepareForSummarization",
                "Some document body text content.",
                Map.of("correlationId", "doc-test-002", "SourceType", "document"));

        Object body = outputCapture.poll(5, TimeUnit.SECONDS);
        assertThat(body).isInstanceOf(SummaryResult.class);
        SummaryResult result = (SummaryResult) body;
        assertThat(result.summaryType()).isEqualTo("document");
    }

    @Test
    @DisplayName("Blank LLM response routes to summaryFallback")
    void blankLlmResponse_routesToFallback() throws Exception {
        when(llmGateway.chat(anyString(), anyString())).thenReturn("   ");

        producer.sendBodyAndHeaders(
                "direct:prepareForSummarization",
                "Some content.",
                Map.of("correlationId", "blank-test-003", "SourceType", "document"));

        // summary-fallback route → direct:deadLetter (stubbed) → fallbackCapture
        Object fallback = fallbackCapture.poll(5, TimeUnit.SECONDS);
        assertThat(fallback).isNotNull();
        assertThat(outputCapture).isEmpty();
    }

    @Test
    @DisplayName("LLM response over 2000 chars routes to summaryFallback")
    void tooLongLlmResponse_routesToFallback() throws Exception {
        String tooLong = "x".repeat(2_001);
        when(llmGateway.chat(anyString(), anyString())).thenReturn(tooLong);

        producer.sendBodyAndHeaders(
                "direct:prepareForSummarization",
                "Some content.",
                Map.of("correlationId", "long-test-004", "SourceType", "document"));

        Object fallback = fallbackCapture.poll(5, TimeUnit.SECONDS);
        assertThat(fallback).isNotNull();
        assertThat(outputCapture).isEmpty();
    }

    @Test
    @DisplayName("AiGatewayException is handled and routes to summaryFallback")
    void llmException_routesToFallback() throws Exception {
        when(llmGateway.chat(anyString(), anyString()))
                .thenThrow(new AiGatewayException("LLM unreachable", "llama3.2", "chat"));

        producer.sendBodyAndHeaders(
                "direct:prepareForSummarization",
                "Some content.",
                Map.of("correlationId", "exc-test-005", "SourceType", "email"));

        // onException(AiGatewayException) → direct:summaryFallback → direct:deadLetter (stubbed)
        Object fallback = fallbackCapture.poll(5, TimeUnit.SECONDS);
        assertThat(fallback).isNotNull();
        assertThat(outputCapture).isEmpty();
    }

    @Test
    @DisplayName("SummaryResult has correct modelVersion from config")
    void summaryResult_hasModelVersion() throws Exception {
        when(llmGateway.chat(anyString(), anyString()))
                .thenReturn("Adequate summary for testing model version field.");

        producer.sendBodyAndHeaders(
                "direct:prepareForSummarization",
                "Content for model version check.",
                Map.of("correlationId", "mv-test-006", "SourceType", "document"));

        Object body = outputCapture.poll(5, TimeUnit.SECONDS);
        assertThat(body).isInstanceOf(SummaryResult.class);
        SummaryResult result = (SummaryResult) body;
        assertThat(result.modelVersion()).isNotBlank();
    }
}
