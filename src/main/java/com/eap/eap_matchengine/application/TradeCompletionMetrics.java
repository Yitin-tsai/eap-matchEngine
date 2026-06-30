package com.eap.eap_matchengine.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class TradeCompletionMetrics {

    private final Counter delayedDetectedTotal;
    private final Counter repairRequeuedTotal;

    public TradeCompletionMetrics(
            MeterRegistry registry,
            TradeCompletionService tradeCompletionService,
            @Value("${eap.match-engine.trade-completion-reconciler.delayed-threshold-seconds:30}") long delayedThresholdSeconds) {
        Duration delayedThreshold = Duration.ofSeconds(delayedThresholdSeconds);
        Gauge.builder("trade_completion_delayed", tradeCompletionService,
                        service -> service.countDelayedCompletions(delayedThreshold))
                .description("Number of incomplete trades older than the delayed completion threshold")
                .register(registry);
        this.delayedDetectedTotal = Counter.builder("trade_completion_delayed_detected_total")
                .description("Total delayed trade completion rows detected by reconciliation")
                .register(registry);
        this.repairRequeuedTotal = Counter.builder("trade_completion_repair_requeued_total")
                .description("Total TradeExecuted outbox records requeued by reconciliation")
                .register(registry);
    }

    public void delayedDetected() {
        delayedDetectedTotal.increment();
    }

    public void repairRequeued() {
        repairRequeuedTotal.increment();
    }
}
