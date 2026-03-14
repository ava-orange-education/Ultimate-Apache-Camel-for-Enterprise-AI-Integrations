package com.aibook.core.dto;

import java.time.Instant;
import java.util.List;

/**
 * Represents an inbound email message entering the summarization pipeline.
 * All collections are defensively copied and never null.
 */
public record EmailMessage(
        String messageId,
        String threadId,
        String subject,
        String body,
        String sender,
        Instant timestamp,
        List<String> attachmentPaths
) {
    public EmailMessage {
        messageId       = messageId       != null ? messageId       : java.util.UUID.randomUUID().toString();
        threadId        = threadId        != null ? threadId        : "";
        subject         = subject         != null ? subject         : "";
        body            = body            != null ? body            : "";
        sender          = sender          != null ? sender          : "";
        timestamp       = timestamp       != null ? timestamp       : Instant.now();
        attachmentPaths = attachmentPaths != null ? List.copyOf(attachmentPaths) : List.of();
    }
}