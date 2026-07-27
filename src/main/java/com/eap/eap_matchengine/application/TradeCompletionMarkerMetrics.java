package com.eap.eap_matchengine.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

@Component
public class TradeCompletionMarkerMetrics {

    private final Map<String, Counter> batchTotals;
    private final Map<String, Counter> eventTotals;
    private final Map<String, DistributionSummary> batchSizes;
    private final Map<String, Timer> insertDurations;
    private final Map<String, Timer> listenerDurations;

    @Autowired
    public TradeCompletionMarkerMetrics(MeterRegistry registry) {
        this.batchTotals = Map.of(
                "ORDER_APPLIED", counter(
                        registry,
                        "match_engine_trade_completion_marker_batches",
                        "ORDER_APPLIED",
                        "Total downstream completion marker listener batches"),
                "WALLET_SETTLED", counter(
                        registry,
                        "match_engine_trade_completion_marker_batches",
                        "WALLET_SETTLED",
                        "Total downstream completion marker listener batches"));
        this.eventTotals = Map.of(
                "ORDER_APPLIED", counter(
                        registry,
                        "match_engine_trade_completion_marker_events",
                        "ORDER_APPLIED",
                        "Total downstream completion marker events inserted"),
                "WALLET_SETTLED", counter(
                        registry,
                        "match_engine_trade_completion_marker_events",
                        "WALLET_SETTLED",
                        "Total downstream completion marker events inserted"));
        this.batchSizes = Map.of(
                "ORDER_APPLIED", batchSize(registry, "ORDER_APPLIED"),
                "WALLET_SETTLED", batchSize(registry, "WALLET_SETTLED"));
        this.insertDurations = Map.of(
                "ORDER_APPLIED", insertDuration(registry, "ORDER_APPLIED"),
                "WALLET_SETTLED", insertDuration(registry, "WALLET_SETTLED"));
        this.listenerDurations = Map.of(
                "ORDER_APPLIED", listenerDuration(registry, "ORDER_APPLIED"),
                "WALLET_SETTLED", listenerDuration(registry, "WALLET_SETTLED"));
    }

    private TradeCompletionMarkerMetrics() {
        this.batchTotals = Map.of();
        this.eventTotals = Map.of();
        this.batchSizes = Map.of();
        this.insertDurations = Map.of();
        this.listenerDurations = Map.of();
    }

    static TradeCompletionMarkerMetrics noop() {
        return new TradeCompletionMarkerMetrics();
    }

    void recordInsert(String markerType, int batchSize, Duration duration) {
        Counter batchTotal = batchTotals.get(markerType);
        Counter eventTotal = eventTotals.get(markerType);
        DistributionSummary batchSizeSummary = batchSizes.get(markerType);
        Timer insertDuration = insertDurations.get(markerType);
        if (batchTotal == null || eventTotal == null || batchSizeSummary == null || insertDuration == null) {
            return;
        }
        batchTotal.increment();
        eventTotal.increment(batchSize);
        batchSizeSummary.record(batchSize);
        insertDuration.record(duration);
    }

    void recordListener(String markerType, Duration duration) {
        Timer listenerDuration = listenerDurations.get(markerType);
        if (listenerDuration == null) {
            return;
        }
        listenerDuration.record(duration);
    }

    private Counter counter(MeterRegistry registry, String name, String markerType, String description) {
        return Counter.builder(name)
                .tag("marker_type", markerType)
                .description(description)
                .register(registry);
    }

    private DistributionSummary batchSize(MeterRegistry registry, String markerType) {
        return DistributionSummary.builder("match_engine_trade_completion_marker_batch_size")
                .tag("marker_type", markerType)
                .description("Number of downstream completion marker events inserted per listener batch")
                .register(registry);
    }

    private Timer insertDuration(MeterRegistry registry, String markerType) {
        return Timer.builder("match_engine_trade_completion_marker_insert_duration")
                .tag("marker_type", markerType)
                .description("Time spent inserting downstream completion markers")
                .publishPercentileHistogram()
                .register(registry);
    }

    private Timer listenerDuration(MeterRegistry registry, String markerType) {
        return Timer.builder("match_engine_trade_completion_marker_listener_duration")
                .tag("marker_type", markerType)
                .description("Wall-clock time spent in downstream completion marker listener batches")
                .publishPercentileHistogram()
                .register(registry);
    }
}
