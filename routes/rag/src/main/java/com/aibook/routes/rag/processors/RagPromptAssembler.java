package com.aibook.routes.rag.processors;

import com.aibook.ai.llm.PromptLoader;
import com.aibook.core.dto.RagContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Loads the {@code rag/rag-answer.txt} prompt template and fills
 * {@code {{context}}} and {@code {{question}}} placeholders.
 *
 * <p>Reads:
 * <ul>
 *   <li>Body: {@link RagContext} record (produced by {@link ContextAssembler})</li>
 * </ul>
 *
 * <p>Writes:
 * <ul>
 *   <li>Body: the fully filled prompt string (ready to send to the LLM)</li>
 *   <li>Header {@code ragContext} — the original {@link RagContext} (preserved for
 *       use by {@link RagResponseParser})</li>
 *   <li>Header {@code promptTemplate} — classpath path used (for audit)</li>
 * </ul>
 */
@Component
public class RagPromptAssembler implements Processor {

    private static final Logger log = LoggerFactory.getLogger(RagPromptAssembler.class);

    private static final String RAG_ANSWER_PROMPT = "prompts/rag/rag-answer.txt";

    private final PromptLoader promptLoader;

    public RagPromptAssembler(PromptLoader promptLoader) {
        this.promptLoader = promptLoader;
    }

    @Override
    public void process(Exchange exchange) {
        RagContext ctx = exchange.getIn().getBody(RagContext.class);
        if (ctx == null) {
            throw new IllegalArgumentException(
                    "RagPromptAssembler: body must be a RagContext record");
        }

        String context  = ctx.assembledContext();
        String question = ctx.originalQuery();

        if (context.isBlank()) {
            log.warn("RagPromptAssembler: assembledContext is empty for queryId={}", ctx.queryId());
        }

        String filledPrompt = promptLoader.loadAndFill(
                RAG_ANSWER_PROMPT,
                Map.of(
                        "context",  context.isBlank() ? "(no relevant context found)" : context,
                        "question", question
                )
        );

        log.debug("RagPromptAssembler: filled prompt for queryId={} ({} chars)",
                ctx.queryId(), filledPrompt.length());

        // Preserve RagContext in header so RagResponseParser can update it
        exchange.getIn().setHeader("ragContext",      ctx);
        exchange.getIn().setHeader("promptTemplate",  RAG_ANSWER_PROMPT);
        exchange.getIn().setBody(filledPrompt);
    }
}
