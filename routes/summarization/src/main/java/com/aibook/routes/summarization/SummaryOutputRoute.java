package com.aibook.routes.summarization;

import com.aibook.core.error.AiErrorHandler;
import com.aibook.core.processors.AuditArtifactCollector;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Summary output routes and REST API entry points.
 *
 * <h3>Route overview</h3>
 * <ol>
 *   <li>{@code direct:summaryOutput} — marshals the validated {@link com.aibook.core.dto.SummaryResult}
 *       to JSON, writes it to a file output directory, records an audit artifact,
 *       and forwards to {@code direct:downstreamFlow} for cross-pipeline integration.</li>
 *   <li>REST endpoint — exposes:
 *     <ul>
 *       <li>{@code POST /api/summarize/email}    → {@code direct:ingestEmail}</li>
 *       <li>{@code POST /api/summarize/document} → {@code direct:ingestDocument}</li>
 *     </ul>
 *   </li>
 *   <li>{@code direct:downstreamFlow} — stub sink for downstream pipeline integration
 *       (e.g. scoring, graph enrichment). Logs and no-ops in base configuration.</li>
 * </ol>
 */
@Component
public class SummaryOutputRoute extends RouteBuilder {

    @Value("${aibook.summarization.output-dir:${java.io.tmpdir}/aibook/summaries}")
    private String outputDir;

    private final AuditArtifactCollector auditCollector;
    private final AiErrorHandler         aiErrorHandler;

    public SummaryOutputRoute(AuditArtifactCollector auditCollector,
                               AiErrorHandler aiErrorHandler) {
        this.auditCollector = auditCollector;
        this.aiErrorHandler = aiErrorHandler;
    }

    @Override
    public void configure() {

        // ── Error handling ────────────────────────────────────────────────────
        errorHandler(aiErrorHandler.build());

        onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "SummaryOutputRoute error [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        // ═════════════════════════════════════════════════════════════════════
        // REST configuration — served via camel-servlet on /camel/*
        // ═════════════════════════════════════════════════════════════════════
        restConfiguration()
                .component("servlet")
                .bindingMode(RestBindingMode.off)   // raw JSON in/out — we handle marshalling
                .dataFormatProperty("prettyPrint", "true")
                .enableCORS(true);

        // POST /api/summarize/email  — accepts raw JSON or plain text email body
        rest("/api/summarize")
                .description("Summarization pipeline entry points")

                .post("/email")
                    .description("Ingest an email (JSON EmailMessage or raw text) and trigger summarization")
                    .consumes("application/json,text/plain")
                    .produces("application/json")
                    .to("direct:ingestEmail")

                .post("/document")
                    .description("Ingest a document (JSON DocumentContent or raw text) and trigger summarization")
                    .consumes("application/json,text/plain")
                    .produces("application/json")
                    .to("direct:ingestDocument");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:summaryOutput
        // Persists the validated SummaryResult and emits audit record.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:summaryOutput")
                .routeId("summary-output")
                .log("Persisting summary: correlationId=${header.correlationId} " +
                     "sourceType=${body.summaryType}")

                // Set audit exchange properties
                .setProperty("stage",        constant("summary-output"))
                .setProperty("decisionId",   header("correlationId"))
                .setProperty("modelVersion", simple("${body.modelVersion}"))

                // Capture audit artifact before marshalling
                .process(auditCollector)

                // Marshal SummaryResult → JSON
                .marshal().json()

                // Write to output directory; file named <correlationId>-summary.json
                .toD("file:" + outputDir
                        + "?fileName=${header.correlationId}-summary.json&fileExist=Override&charset=UTF-8")
                .log("Summary written: " + outputDir + "/${header.correlationId}-summary.json")

                // Forward to audit trail
                .to("direct:storeAudit")

                // Forward to downstream pipelines (scoring, graph, etc.)
                .to("direct:downstreamFlow");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:downstreamFlow
        // Integration stub — downstream pipeline consumers can add steps here
        // (e.g. kick off scoring, graph enrichment, explanation generation).
        // ═════════════════════════════════════════════════════════════════════
        from("direct:downstreamFlow")
                .routeId("summary-downstream-flow")
                .log("Summary forwarded to downstream: correlationId=${header.correlationId}")
                // Placeholder — downstream pipelines wire in here
                .end();
    }
}