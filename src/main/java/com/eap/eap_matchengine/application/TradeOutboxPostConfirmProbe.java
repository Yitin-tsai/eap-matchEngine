package com.eap.eap_matchengine.application;

import java.util.List;

/**
 * Observation seam between successful RabbitMQ publisher confirms and the local
 * outbox {@code SENT} update. Normal runtime has no implementations; the load-test
 * profile may install an explicitly enabled failure-injection probe.
 */
@FunctionalInterface
public interface TradeOutboxPostConfirmProbe {

    void afterBrokerConfirmationBeforeMarkSent(List<Long> outboxIds);
}
