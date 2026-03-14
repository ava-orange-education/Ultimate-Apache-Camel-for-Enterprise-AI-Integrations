package com.aibook.routes.shared.processors;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Injects tracing headers (PipelineId, TraceId, SpanId) if not already present.
 */
@Component
public class TracingHeadersProcessor implements Processor {

    private static final Logger log = LoggerFactory.getLogger(TracingHeadersProcessor.class);

    @Override
    public void process(Exchange exchange) {
        if (exchange.getIn().getHeader("PipelineId") == null) {
            exchange.getIn().setHeader("PipelineId", UUID.randomUUID().toString());
        }
        if (exchange.getIn().getHeader("TraceId") == null) {
            exchange.getIn().setHeader("TraceId", UUID.randomUUID().toString());
        }
        exchange.getIn().setHeader("SpanId", UUID.randomUUID().toString());
        log.debug("Tracing headers: PipelineId={} TraceId={}",
                exchange.getIn().getHeader("PipelineId"),
                exchange.getIn().getHeader("TraceId"));
    }
}
