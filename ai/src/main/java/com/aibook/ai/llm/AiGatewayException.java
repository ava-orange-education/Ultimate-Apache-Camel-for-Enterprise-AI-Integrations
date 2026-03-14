package com.aibook.ai.llm;

/**
 * Checked exception thrown by {@link LlmGateway} when any LLM API call fails.
 *
 * <p>Wraps the underlying cause (network error, timeout, API error, etc.) so
 * Camel route {@code onException} clauses can handle AI failures distinctly
 * from general {@link RuntimeException}s.
 */
public class AiGatewayException extends Exception {

    /** The name of the LLM model that was being called when the failure occurred. */
    private final String modelName;

    /** The operation that failed: "chat" or "chatWithContext". */
    private final String operation;

    public AiGatewayException(String message, String modelName, String operation, Throwable cause) {
        super(message, cause);
        this.modelName = modelName;
        this.operation = operation;
    }

    public AiGatewayException(String message, String modelName, String operation) {
        super(message);
        this.modelName = modelName;
        this.operation = operation;
    }

    public String getModelName() {
        return modelName;
    }

    public String getOperation() {
        return operation;
    }

    @Override
    public String toString() {
        return "AiGatewayException{model=" + modelName
                + ", op=" + operation
                + ", message=" + getMessage() + "}";
    }
}
