package com.aibook.routes.shared.validators;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Validates that the exchange body is non-null and non-empty.
 * Sets header {@code PayloadValid=true/false}.
 */
@Component
public class PayloadValidator implements Processor {

    private static final Logger log = LoggerFactory.getLogger(PayloadValidator.class);

    @Override
    public void process(Exchange exchange) {
        Object body = exchange.getIn().getBody();
        boolean valid = body != null
                && !(body instanceof String s && s.isBlank());

        exchange.getIn().setHeader("PayloadValid", valid);
        if (!valid) {
            log.warn("Empty or null payload in exchange {}", exchange.getExchangeId());
        }
    }
}
