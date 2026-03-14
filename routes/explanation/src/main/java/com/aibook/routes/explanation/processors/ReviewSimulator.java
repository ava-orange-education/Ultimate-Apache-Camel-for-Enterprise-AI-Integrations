package com.aibook.routes.explanation.processors;

import com.aibook.core.dto.HumanReviewTask;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulates a human reviewer approving or rejecting a task.
 *
 * <p><strong>For testing/demo purposes only</strong> — in production this
 * step is replaced by an actual human decision submitted via the
 * {@code POST /api/review/decision/{taskId}} REST endpoint.
 *
 * <h3>Simulation behaviour</h3>
 * <ul>
 *   <li>Tasks with priority {@code URGENT} are always rejected (escalated again)</li>
 *   <li>Tasks with priority {@code HIGH} are rejected with 30% probability</li>
 *   <li>All other tasks are approved</li>
 *   <li>An optional configurable delay simulates reviewer think-time</li>
 * </ul>
 *
 * <p>Reads body: {@link HumanReviewTask}.<br>
 * Writes:
 * <ul>
 *   <li>Header {@code reviewOutcome} — "APPROVED" or "REJECTED"</li>
 *   <li>Header {@code reviewNotes}   — brief explanation</li>
 *   <li>Header {@code reviewerId}    — "simulator"</li>
 * </ul>
 */
@Component
public class ReviewSimulator implements Processor {

    private static final Logger log = LoggerFactory.getLogger(ReviewSimulator.class);

    @Value("${aibook.human-review.simulation.delay-ms:0}")
    private long simulationDelayMs;

    @Override
    public void process(Exchange exchange) throws Exception {
        HumanReviewTask task = exchange.getIn().getBody(HumanReviewTask.class);

        String priority = task != null ? task.priority() : "MEDIUM";
        String taskId   = task != null ? task.taskId()   : "unknown";

        // Simulate think-time (configurable, default 0 ms for tests)
        if (simulationDelayMs > 0) {
            Thread.sleep(simulationDelayMs);
        }

        // ── Simulate decision ─────────────────────────────────────────────────
        String outcome;
        String notes;

        switch (priority) {
            case "URGENT" -> {
                outcome = "REJECTED";
                notes   = "Simulator: URGENT tasks always require escalation review";
            }
            case "HIGH" -> {
                // 30% rejection rate for HIGH
                boolean reject = ThreadLocalRandom.current().nextInt(100) < 30;
                outcome = reject ? "REJECTED" : "APPROVED";
                notes   = "Simulator: " + (reject ? "HIGH-risk indicators confirmed" : "Risk acceptable");
            }
            default -> {
                outcome = "APPROVED";
                notes   = "Simulator: Standard auto-approval for " + priority + " priority tasks";
            }
        }

        exchange.getIn().setHeader("reviewOutcome", outcome);
        exchange.getIn().setHeader("reviewNotes",   notes);
        exchange.getIn().setHeader("reviewerId",    "simulator");

        log.info("ReviewSimulator: taskId={} priority={} outcome={} notes={}",
                taskId, priority, outcome, notes);
    }
}
