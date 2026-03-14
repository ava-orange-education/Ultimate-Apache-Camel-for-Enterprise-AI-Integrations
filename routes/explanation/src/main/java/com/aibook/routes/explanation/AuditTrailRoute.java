package com.aibook.routes.explanation;

import com.aibook.core.error.AiErrorHandler;
import com.aibook.routes.explanation.processors.AuditRecordSerializer;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.rest.RestBindingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Audit trail route — persists the full decision audit record (artifact + trail)
 * to the audit log directory and conditionally triggers human review.
 *
 * <h3>Pipeline flow</h3>
 * <pre>
 *   direct:storeAudit
 *     → auditRecordSerializer         (builds JSON wrapper: artifact + auditTrail)
 *     → multicast()
 *           → file:output/audit/{decisionId}-audit.json
 *           → direct:checkHumanReviewNeeded
 *
 *   direct:checkHumanReviewNeeded
 *     → choice()
 *           WHEN routingDecision == "REVIEW"  → direct:submitHumanReview
 *           OTHERWISE                          → log "no review needed"
 *
 *   REST GET /api/audit/{decisionId}
 *     → direct:fetchAuditRecord
 *         → file:output/audit (poll-enrich / file read)
 * </pre>
 */
@Component
public class AuditTrailRoute extends RouteBuilder {

    @Value("${aibook.audit.log-dir:${java.io.tmpdir}/aibook/audit}")
    private String auditLogDir;

    private final AuditRecordSerializer auditRecordSerializer;
    private final AiErrorHandler        aiErrorHandler;

    public AuditTrailRoute(AuditRecordSerializer auditRecordSerializer,
                           AiErrorHandler aiErrorHandler) {
        this.auditRecordSerializer = auditRecordSerializer;
        this.aiErrorHandler        = aiErrorHandler;
    }

    @Override
    public void configure() {

        // ── Error handling ────────────────────────────────────────────────────
        errorHandler(aiErrorHandler.build());

        onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                     "AuditTrailRoute error [${routeId}]: ${exception.message}")
                .to("direct:deadLetter");

        // ── REST endpoints ────────────────────────────────────────────────────
        restConfiguration()
                .component("servlet")
                .bindingMode(RestBindingMode.off)
                .dataFormatProperty("prettyPrint", "true")
                .enableCORS(true);

        rest("/api/audit")
                .description("Audit record retrieval endpoint")
                .get("/{decisionId}")
                    .description("Retrieve the audit record for a given decision ID")
                    .produces("application/json")
                    .to("direct:fetchAuditRecord");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:storeAudit
        // Input:  ExplanationArtifact body + auditTrail exchange property
        // Output: JSON file persisted + optional human review submission
        // ═════════════════════════════════════════════════════════════════════
        from("direct:storeAudit")
                .routeId("audit-store")
                .log(LoggingLevel.INFO,
                     "AuditTrailRoute: storing audit for decisionId=${exchangeProperty.decisionId}")

                // Serialise artifact + audit trail into a single JSON wrapper
                .process(auditRecordSerializer)
                .log(LoggingLevel.DEBUG,
                     "AuditTrailRoute: serialised audit for decisionId=${header.auditDecisionId}")

                // Marshal Map to JSON bytes
                .marshal().json()

                // Fan out: persist to file AND check if review is needed
                .multicast()
                    .parallelProcessing(false)
                    .to("direct:auditFileWrite")
                    .to("direct:checkHumanReviewNeeded")
                .end();

        // ── File write sub-route ──────────────────────────────────────────────
        from("direct:auditFileWrite")
                .routeId("audit-file-write")
                .toD("file:" + auditLogDir
                        + "?fileName=${header.auditDecisionId}-audit.json"
                        + "&fileExist=Override&autoCreate=true")
                .log(LoggingLevel.INFO,
                     "AuditTrailRoute: audit file written for ${header.auditDecisionId}");

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:checkHumanReviewNeeded
        // Checks routingDecision header — routes to review queue if REVIEW.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:checkHumanReviewNeeded")
                .routeId("audit-check-review")
                .choice()
                    .when(header("routingDecision").isEqualTo("REVIEW"))
                        .log(LoggingLevel.INFO,
                             "AuditTrailRoute: routingDecision=REVIEW — submitting to human review")
                        .to("direct:submitHumanReview")
                    .otherwise()
                        .log(LoggingLevel.INFO,
                             "AuditTrailRoute: no human review needed for "
                             + "${header.auditDecisionId} (decision=${header.routingDecision})")
                .end();

        // ═════════════════════════════════════════════════════════════════════
        // Route: direct:fetchAuditRecord
        // Returns the JSON audit file for the given decisionId path param.
        // ═════════════════════════════════════════════════════════════════════
        from("direct:fetchAuditRecord")
                .routeId("audit-fetch")
                .log(LoggingLevel.INFO,
                     "AuditTrailRoute: fetching audit record for decisionId=${header.decisionId}")
                .process(exchange -> {
                    String decisionId = exchange.getIn().getHeader("decisionId", String.class);
                    if (decisionId == null || decisionId.isBlank()) {
                        exchange.getIn().setBody("{\"error\": \"decisionId is required\"}");
                        exchange.getIn().setHeader("CamelHttpResponseCode", 400);
                        return;
                    }
                    // Set the file name header so pollEnrich can find it in auditLogDir
                    exchange.getIn().setHeader("auditFileName",
                            decisionId + "-audit.json");
                })
                // pollEnrich with fixed directory + dynamic fileName option
                .pollEnrich().simple("file:" + auditLogDir
                                   + "?fileName=${header.auditFileName}"
                                   + "&noop=true&readLock=none"
                                   + "&startingDirectoryMustExist=false").timeout(3_000).end()
                .choice()
                    .when(body().isNull())
                        .setBody(simple("{\"error\": \"Audit record not found for decisionId: "
                                + "${header.decisionId}\"}"))
                        .setHeader("CamelHttpResponseCode", constant(404))
                    .otherwise()
                        .setHeader("Content-Type", constant("application/json"))
                .end();
    }
}