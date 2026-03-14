package com.aibook.ai.llm;

import com.aibook.core.config.AiPipelineProperties;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Unified LLM gateway — abstracts over any {@link ChatLanguageModel} implementation
 * (Ollama, OpenAI, Azure, Together, etc.) provided via Spring DI.
 *
 * <p>Every call is wrapped in a try-catch that converts any failure into an
 * {@link AiGatewayException} so Camel routes can handle AI failures consistently.
 *
 * <h3>Methods</h3>
 * <ul>
 *   <li>{@link #chat(String, String)} — system + user turn</li>
 *   <li>{@link #chatWithContext(String, String, String)} — system + retrieved context + user</li>
 *   <li>{@link #generateFromTemplate(String, Map)} — load prompt file, fill vars, call chat()</li>
 * </ul>
 */
@Service
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private final ChatLanguageModel chatModel;
    private final PromptLoader      promptLoader;
    private final String            modelName;

    public LlmGateway(ChatLanguageModel chatModel,
                      PromptLoader promptLoader,
                      AiPipelineProperties properties) {
        this.chatModel    = chatModel;
        this.promptLoader = promptLoader;
        this.modelName    = properties.llm().modelName();
        log.info("LlmGateway initialised: model={}", modelName);
    }

    /**
     * Send a system prompt + user message to the LLM and return its response text.
     *
     * @param systemPrompt the system/instruction message (role=system)
     * @param userMessage  the user turn message (role=user)
     * @return the assistant's reply as a plain string
     * @throws AiGatewayException if the LLM call fails for any reason
     */
    public String chat(String systemPrompt, String userMessage) throws AiGatewayException {
        log.debug("LlmGateway.chat: systemLen={} userLen={}",
                systemPrompt.length(), userMessage.length());
        try {
            Response<AiMessage> response = chatModel.generate(
                    List.of(SystemMessage.from(systemPrompt),
                            UserMessage.from(userMessage)));
            String text = response.content().text();
            log.debug("LlmGateway.chat: responseLen={}", text.length());
            return text;
        } catch (Exception e) {
            throw new AiGatewayException(
                    "LLM chat call failed: " + e.getMessage(), modelName, "chat", e);
        }
    }

    /**
     * Send a system prompt + retrieved context + user message to the LLM.
     *
     * <p>The context is injected into the system message so it is clearly
     * separated from the instruction prompt.
     *
     * @param systemPrompt the instruction/persona system message
     * @param context      retrieved document chunks or graph data
     * @param userMessage  the user's question
     * @return the assistant's reply as a plain string
     * @throws AiGatewayException if the LLM call fails for any reason
     */
    public String chatWithContext(String systemPrompt,
                                  String context,
                                  String userMessage) throws AiGatewayException {
        log.debug("LlmGateway.chatWithContext: systemLen={} contextLen={} userLen={}",
                systemPrompt.length(), context.length(), userMessage.length());
        try {
            String augmentedSystem = systemPrompt
                    + "\n\n---CONTEXT---\n" + context + "\n---END CONTEXT---";
            Response<AiMessage> response = chatModel.generate(
                    List.of(SystemMessage.from(augmentedSystem),
                            UserMessage.from(userMessage)));
            String text = response.content().text();
            log.debug("LlmGateway.chatWithContext: responseLen={}", text.length());
            return text;
        } catch (Exception e) {
            throw new AiGatewayException(
                    "LLM chatWithContext call failed: " + e.getMessage(),
                    modelName, "chatWithContext", e);
        }
    }

    /**
     * Load a prompt template from the classpath, fill {@code {{variable}}} placeholders
     * with the provided {@code vars} map, then call the LLM with the filled template
     * as the <em>user</em> message and a neutral system role.
     *
     * <p>The filled template is sent as the user turn so the LLM sees the full
     * instruction context and produces a grounded response.  This is the preferred
     * entry point for route processors that drive the LLM entirely via a prompt file.
     *
     * @param promptPath classpath path to the .txt prompt template
     *                   (e.g. {@code "prompts/scoring/score-explanation.txt"})
     * @param vars       variable substitution map ({@code key → value}); values are
     *                   converted to strings via {@link Object#toString()}
     * @return the LLM's generated text
     * @throws RuntimeException (wrapping {@link AiGatewayException}) if the call fails
     */
    public String generateFromTemplate(String promptPath, Map<String, Object> vars) {
        Map<String, String> stringVars = vars == null ? Map.of()
                : vars.entrySet().stream().collect(
                        Collectors.toMap(
                                Map.Entry::getKey,
                                e -> e.getValue() != null ? e.getValue().toString() : ""));

        String filledPrompt = promptLoader.loadAndFill(promptPath, stringVars);
        try {
            // Send the filled template as the USER message with a minimal system role.
            // Previously it was sent as the system message with an empty user message,
            // causing userLen=0 and no LLM output.
            return chat("You are a helpful AI assistant. Follow the instructions precisely.",
                        filledPrompt);
        } catch (AiGatewayException e) {
            throw new RuntimeException("generateFromTemplate failed for '" + promptPath + "'", e);
        }
    }

    /** Expose the model name for logging / audit. */
    public String getModelName() {
        return modelName;
    }
}