package com.aibook.routes.rag.processors;

import com.aibook.core.dto.RagContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Parses the plain-text LLM response and wraps it into an updated {@link RagContext}.
 *
 * <p>The LLM call body is a plain string (the answer text). This processor:
 * <ol>
 *   <li>Reads the original {@link RagContext} from header {@code ragContext}.</li>
 *   <li>Reads the LLM answer string from the exchange body.</li>
 *   <li>Constructs a new {@link RagContext} with {@code assembledContext}
 *       replaced by the LLM-generated answer.</li>
 *   <li>Sets the body to the updated {@link RagContext}.</li>
 * </ol>
 *
 * <p>The {@code assembledContext} field of the returned record holds
 * the LLM answer text so downstream serialization produces a single JSON
 * document with the complete answer.
 */
@Component
public class RagResponseParser implements Processor {

    private static final Logger log = LoggerFactory.getLogger(RagResponseParser.class);

    @Override
    public void process(Exchange exchange) {
        String llmAnswer = exchange.getIn().getBody(String.class);
        if (llmAnswer == null) {
            llmAnswer = "";
            log.warn("RagResponseParser: LLM returned null body");
        }

        RagContext originalCtx = exchange.getIn().getHeader("ragContext", RagContext.class);
        if (originalCtx == null) {
            throw new IllegalStateException(
                    "RagResponseParser: header 'ragContext' is missing — "
                    + "ensure RagPromptAssembler runs before RagResponseParser");
        }

        String trimmedAnswer = llmAnswer.trim();
        log.debug("RagResponseParser: queryId={} answerLen={}",
                originalCtx.queryId(), trimmedAnswer.length());

        // Build the final RagContext: answer stored in assembledContext field
        RagContext finalCtx = new RagContext(
                originalCtx.queryId(),
                originalCtx.originalQuery(),
                originalCtx.embeddedQuery(),
                originalCtx.retrievedChunks(),
                originalCtx.relevanceScores(),
                trimmedAnswer             // LLM-generated answer replaces assembled context
        );

        exchange.getIn().setBody(finalCtx);
        exchange.getIn().setHeader("ragAnswer",    trimmedAnswer);
        exchange.getIn().setHeader("retrievedCount", finalCtx.retrievedChunks().size());

        log.info("RagResponseParser: query='{}' answer='{}...' chunks={}",
                finalCtx.originalQuery().length() > 60
                        ? finalCtx.originalQuery().substring(0, 60) + "..."
                        : finalCtx.originalQuery(),
                trimmedAnswer.length() > 80
                        ? trimmedAnswer.substring(0, 80) + "..."
                        : trimmedAnswer,
                finalCtx.retrievedChunks().size());
    }
}
