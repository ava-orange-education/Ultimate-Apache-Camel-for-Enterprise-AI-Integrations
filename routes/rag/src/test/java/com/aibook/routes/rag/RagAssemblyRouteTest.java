package com.aibook.routes.rag;

import com.aibook.ai.embeddings.EmbeddingService;
import com.aibook.ai.llm.LlmGateway;
import com.aibook.ai.llm.PromptLoader;
import com.aibook.ai.vector.QdrantVectorStore;
import com.aibook.core.dto.RagContext;
import com.aibook.core.error.AiErrorHandler;
import com.aibook.core.processors.AuditArtifactCollector;
import com.aibook.routes.rag.processors.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.EndpointInject;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Integration-style test for the complete RAG query pipeline:
 * {@code direct:embedQuery} → {@code direct:retrieveKnowledge} → {@code direct:assembleRagContext}.
 *
 * <p>Strategy:
 * <ul>
 *   <li>All RAG route and processor beans are loaded.</li>
 *   <li>{@link EmbeddingService}, {@link QdrantVectorStore}, and {@link LlmGateway} are mocked.</li>
 *   <li>AdviceWith intercepts {@code direct:ragOutput} with a mock endpoint.</li>
 * </ul>
 */
@CamelSpringBootTest
@DirtiesContext(classMode = ClassMode.BEFORE_EACH_TEST_METHOD)
@SpringBootTest(classes = {
        RagTestApplication.class,
        // Routes (query path only)
        EmbeddingPipelineRoute.class,
        QueryRetrievalRoute.class,
        RagAssemblyRoute.class,
        // Processors
        QueryEmbedder.class,
        QdrantRetriever.class,
        RetrievalFilter.class,
        ContextAssembler.class,
        RagPromptAssembler.class,
        RagResponseParser.class,
        // Shared
        AiErrorHandler.class,
        AuditArtifactCollector.class
}, properties = {
        // Resilience4j circuit breaker config for tests (mirrors application.yml)
        "camel.resilience4j.configurations.llm-circuit-breaker.sliding-window-size=10",
        "camel.resilience4j.configurations.llm-circuit-breaker.failure-rate-threshold=50",
        "camel.resilience4j.configurations.llm-circuit-breaker.wait-duration-in-open-state=30000"
})
class RagAssemblyRouteTest {

    @MockBean
    EmbeddingService embeddingService;

    @MockBean
    QdrantVectorStore vectorStore;

    @MockBean
    LlmGateway llmGateway;

    @MockBean
    PromptLoader promptLoader;

    @MockBean
    ObjectMapper objectMapper;

    @Autowired
    org.apache.camel.CamelContext camelContext;

    ProducerTemplate producer;

    @EndpointInject("mock:ragOutput")
    MockEndpoint mockRagOutput;

    @BeforeEach
    void wireAdvice() throws Exception {
        AdviceWith.adviceWith(camelContext, "rag-assembly", a ->
                a.weaveByToUri("direct:ragOutput").replace().to("mock:ragOutput"));
        producer = camelContext.createProducerTemplate();
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("RAG query pipeline produces RagContext with LLM answer")
    void ragQueryPipeline_producesRagContextWithAnswer() throws Exception {
        // Mock Qdrant retrieval — return 2 relevant chunks
        when(vectorStore.search(anyString(), any(float[].class), anyInt(), anyDouble()))
                .thenReturn(List.of(
                        new QdrantVectorStore.ScoredDocument("id-1",
                                Map.of("text", "Apache Camel is an integration framework."), 0.95),
                        new QdrantVectorStore.ScoredDocument("id-2",
                                Map.of("text", "Camel supports 300+ components."), 0.88)
                ));

        // Mock prompt loading
        when(promptLoader.loadAndFill(anyString(), anyMap()))
                .thenReturn("Filled RAG prompt with context and question.");

        // Mock LLM answer
        when(llmGateway.chat(anyString(), anyString()))
                .thenReturn("According to [1], Apache Camel is an integration framework " +
                            "that supports over 300 components.");

        mockRagOutput.expectedMessageCount(1);

        // Send directly to retrieveKnowledge with pre-set queryVector (bypassing embedding REST entry)
        float[] queryVector = new float[384];
        producer.sendBodyAndHeaders("direct:retrieveKnowledge", "What is Apache Camel?",
                Map.of("queryVector", queryVector, "originalQuery", "What is Apache Camel?"));

        mockRagOutput.assertIsSatisfied();

        // Verify the body reaching ragOutput is a RagContext
        Object body = mockRagOutput.getReceivedExchanges().get(0).getIn().getBody();
        assertThat(body).isInstanceOf(RagContext.class);

        RagContext ragCtx = (RagContext) body;
        assertThat(ragCtx.originalQuery()).isEqualTo("What is Apache Camel?");
        assertThat(ragCtx.assembledContext()).contains("Apache Camel is an integration framework");
        assertThat(ragCtx.retrievedChunks()).hasSize(2);
        assertThat(ragCtx.queryId()).isNotBlank();
    }

    @Test
    @DisplayName("Empty retrieval results still produce a RagContext with empty chunks")
    void emptyRetrieval_producesRagContextWithEmptyChunks() throws Exception {
        float[] queryVector = new float[384];
        when(vectorStore.search(anyString(), any(), anyInt(), anyDouble()))
                .thenReturn(List.of());
        when(promptLoader.loadAndFill(anyString(), anyMap()))
                .thenReturn("No context available for this question.");
        when(llmGateway.chat(anyString(), anyString()))
                .thenReturn("I don't have enough information to answer that question.");

        mockRagOutput.expectedMessageCount(1);

        producer.sendBodyAndHeaders("direct:retrieveKnowledge", "Unknown topic query?",
                Map.of("queryVector", queryVector, "originalQuery", "Unknown topic query?"));

        mockRagOutput.assertIsSatisfied();

        RagContext ragCtx = (RagContext)
                mockRagOutput.getReceivedExchanges().get(0).getIn().getBody();
        assertThat(ragCtx.retrievedChunks()).isEmpty();
        assertThat(ragCtx.assembledContext()).contains("don't have enough information");
    }
}
