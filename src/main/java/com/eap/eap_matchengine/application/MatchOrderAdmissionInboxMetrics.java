package com.eap.eap_matchengine.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class MatchOrderAdmissionInboxMetrics {

    private final Counter accepted;
    private final Counter duplicate;
    private final Counter conflict;
    private final Counter applied;
    private final Counter retryScheduled;
    private final Counter prerequisiteScheduled;
    private final Counter permanentFailure;

    public MatchOrderAdmissionInboxMetrics(MeterRegistry registry) {
        accepted = counter(registry, "accepted", "New Match admission facts persisted before acknowledgement");
        duplicate = counter(registry, "duplicate", "Duplicate Match admission facts absorbed by identity");
        conflict = counter(registry, "conflict", "Conflicting Match admission identities detected");
        applied = counter(registry, "applied", "Match admission inbox rows applied to the order book");
        retryScheduled = counter(registry, "retry_scheduled", "Transient Match admission retries scheduled");
        prerequisiteScheduled = counter(
                registry, "prerequisite_scheduled", "Match admission recovery prerequisites deferred");
        permanentFailure = counter(registry, "permanent_failure", "Match admission rows requiring intervention");
    }

    void received(MatchOrderAdmissionInbox.ReceiveOutcome outcome) {
        switch (outcome) {
            case ACCEPTED -> accepted.increment();
            case DUPLICATE -> duplicate.increment();
            case CONFLICT -> conflict.increment();
        }
    }

    void applied() {
        applied.increment();
    }

    void retryScheduled() {
        retryScheduled.increment();
    }

    void prerequisiteScheduled() {
        prerequisiteScheduled.increment();
    }

    void permanentFailure() {
        permanentFailure.increment();
    }

    private Counter counter(MeterRegistry registry, String outcome, String description) {
        return Counter.builder("match_engine_order_admission_inbox_total")
                .tag("outcome", outcome)
                .description(description)
                .register(registry);
    }
}
