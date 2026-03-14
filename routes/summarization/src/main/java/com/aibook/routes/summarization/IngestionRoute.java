package com.aibook.routes.summarization;

import com.aibook.core.error.AiErrorHandler;
import com.aibook.core.processors.ContentNormalizer;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ingestion routes — handle email and document input into the summarization pipeline.
 *
 * <h3>Route overview</h3>
 * <ol>
 *   <li>{@code direct:ingestEmail} — parses raw/JSON email bodies into {@link com.aibook.core.dto.EmailMessage},
 *       normalises content, sets {@code correlationId}, forwards to {@code direct:correlateContent}.</li>
 *   <li>{@code direct:ingestDocument} — parses raw/JSON document bodies into {@link com.aibook.core.dto.DocumentContent},
 *       normalises, sets {@code correlationId}, forwards to {@code direct:correlateContent}.</li>
 *   <li>{@code direct:correlateContent} — aggregates by {@code correlationId} using
 *       {@link ThreadAggregationStrategy}; on completion (timeout 60 s or max 10 items)
 *       forwards to {@code direct:prepareForSummarization}.</li>
 *   <li>IMAP trigger (optional) — polls configured mailbox and feeds {@code direct:ingestEmail}.</li>
 *   <li>File-drop polling — watches a local directory for plain-text documents.</li>
 * </ol>
 */
@Component
public class IngestionRoute extends RouteBuilder {

    // ── Config ────────────────────────────────────────────────────────────────

    @Value("${aibook.ingestion.file-drop-dir:${java.io.tmpdir}/aibook/ingest}")
    private String fileDropDir;

    @Value("${aibook.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${aibook.mail.host:imap.example.com}")
    private String mailHost;

    @Value("${aibook.mail.username:user@example.com}")
    private String mailUsername;

    @Value("${aibook.mail.password:secret}")
    private String mailPassword;

    @Value("${aibook.mail.folder:INBOX}")
    private String mailFolder;

    @Value("${aibook.mail.delete:false}")
    private boolean mailDelete;

    /** Max aggregation window in milliseconds before the thread is forwarded. */
    private static final long AGGREGATION_TIMEOUT_MS  = 60_000L;

    /** Max number of items in a single aggregation group. */
    private static final int  AGGREGATION_MAX_SIZE    = 10;

    // ── Dependencies ─────────────────────────────────────────────────────────

    private final EmailParsingProcessor    emailParsingProcessor;
    private final DocumentParsingProcessor documentParsingProcessor;
    private final ContentNormalizer        contentNormalizer;
    private final AiErrorHandler           aiErrorHandler;

    public IngestionRoute(EmailParsingProcessor emailParsingProcessor,
                          DocumentParsingProcessor documentParsingProcessor,
                          ContentNormalizer contentNormalizer,
                          AiErrorHandler aiErrorHandler) {
        this.emailParsingProcessor    = emailParsingProcessor;
        this.documentParsingProcessor = documentParsingProcessor;
        this.contentNormalizer        = contentNormalizer;
        this.aiErrorHandler           = aiErrorHandler;
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void configure() {

        // ── Global error handling ─────────────────────────────────────────────
        errorHandler(aiErrorHandler.build());

        onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "IngestionRoute error [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        // ═════════════════════════════════════════════════════════════════════
        // Route 1: direct:ingestEmail
        // Entry point for REST POST /api/summarize/email and IMAP trigger.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:ingestEmail")
                .routeId("ingest-email")
                .log("Received email payload [exchangeId=${exchangeId}]")

                // Parse raw/JSON body into EmailMessage DTO
                .process(emailParsingProcessor)

                .log("Email parsed: correlationId=${header.correlationId} " +
                     "subject=${body.subject} sender=${body.sender}")

                // Normalise: strip MIME noise, collapse whitespace, fix timestamps
                .process(contentNormalizer)

                // Set pipeline metadata
                .setProperty("stage", constant("email-ingestion"))
                .setProperty("decisionId", header("correlationId"))

                // Forward to thread correlation / aggregation
                .to("direct:correlateContent");

        // ═════════════════════════════════════════════════════════════════════
        // Route 2: direct:ingestDocument
        // Entry point for REST POST /api/summarize/document and file-drop polling.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:ingestDocument")
                .routeId("ingest-document")
                .log("Received document payload [exchangeId=${exchangeId}]")

                // Parse raw/JSON body into DocumentContent DTO
                .process(documentParsingProcessor)

                .log("Document parsed: correlationId=${header.correlationId} " +
                     "fileName=${body.fileName}")

                // Normalise content
                .process(contentNormalizer)

                // Set pipeline metadata
                .setProperty("stage", constant("document-ingestion"))
                .setProperty("decisionId", header("correlationId"))

                // Forward to thread correlation / aggregation
                .to("direct:correlateContent");

        // ═════════════════════════════════════════════════════════════════════
        // Route 3: direct:correlateContent — aggregation
        // Groups messages by correlationId into a thread context, then forwards
        // to direct:prepareForSummarization when the group is complete.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:correlateContent")
                .routeId("correlate-content")
                .log("Correlating content: correlationId=${header.correlationId} " +
                     "sourceType=${header.SourceType}")

                .aggregate(header("correlationId"), new ThreadAggregationStrategy())
                    .completionTimeout(AGGREGATION_TIMEOUT_MS)
                    .completionSize(AGGREGATION_MAX_SIZE)
                    .eagerCheckCompletion()

                .log("Thread aggregation complete: correlationId=${header.correlationId} " +
                     "sourceType=${header.SourceType} contextLen=${body.length()}")
                .setProperty("stage", constant("thread-aggregation"))
                .to("direct:prepareForSummarization");

        // ═════════════════════════════════════════════════════════════════════
        // Route 4: File-drop polling (documents dropped as .txt / .json files)
        // ═════════════════════════════════════════════════════════════════════
        from("file:" + fileDropDir
                + "?noop=false&delay=30000&initialDelay=10000"
                + "&include=.*\\.(txt|json)&charset=UTF-8")
                .routeId("ingest-file-drop")
                .log("File drop ingestion: ${header.CamelFileName}")
                .setHeader("SourceType", constant("document"))
                // Convert GenericFile to String eagerly while the file handle is still valid,
                // before handing off to the SEDA queue.
                .convertBodyTo(String.class, "UTF-8")
                .to("seda:ingestDocumentAsync?waitForTaskToComplete=Never");

        // ── Async bridge: seda → document pipeline ────────────────────────────────────
        // The SEDA consumer runs on a non-HTTP thread, so it cannot reach
        // direct:ingestDocument via the Camel REST binding layer.
        // Instead we parse + normalise the JSON body here, set the required
        // headers, then hand off directly to direct:correlateContent.
        from("seda:ingestDocumentAsync?concurrentConsumers=1")
                .routeId("ingest-file-drop-bridge")
                .log(LoggingLevel.INFO,
                     "IngestionRoute: file-drop bridge processing body [exchangeId=${exchangeId}]")
                .process(documentParsingProcessor)
                .log(LoggingLevel.INFO,
                     "IngestionRoute: file-drop document parsed: correlationId=${header.correlationId}")
                .process(contentNormalizer)
                .setProperty("stage",      constant("file-drop-ingestion"))
                .setProperty("decisionId", header("correlationId"))
                .to("direct:correlateContent");

        // ═════════════════════════════════════════════════════════════════════
        // Route 5: IMAP trigger (enabled only when aibook.mail.enabled=true)
        // The URI is built conditionally to avoid IMAP connection errors in tests.
        // ═════════════════════════════════════════════════════════════════════
        if (mailEnabled) {
            String imapUri = String.format(
                    "imap:%s?username=%s&password=%s&folderName=%s&delete=%s" +
                    "&unseen=true&delay=60000",
                    mailHost, mailUsername, mailPassword, mailFolder, mailDelete);

            from(imapUri)
                    .routeId("ingest-imap")
                    .log("IMAP email received from ${header.From}")
                    .setHeader("SourceType", constant("email"))
                    .to("direct:ingestEmail");
        }
    }
}