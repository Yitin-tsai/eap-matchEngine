package com.eap.eap_matchengine.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class MatchingEngineMetrics {

    private final Timer tryMatchDuration;
    private final Timer reserveDuration;
    private final Timer addOrderDuration;
    private final Timer matchIdDuration;
    private final Timer tradeRecordDuration;
    private final Timer completeReservationDuration;
    private final Timer releaseReservationDuration;
    private final Timer legacyPublishDuration;
    private final Counter tradeRecordedTotal;
    private final Counter orderAddedTotal;
    private final Counter reservationCompletedTotal;
    private final Counter reservationReleasedTotal;

    public MatchingEngineMetrics(MeterRegistry registry) {
        this.tryMatchDuration = timer(registry, "match_engine_try_match_duration",
                "Wall-clock time spent handling one OrderConfirmed event");
        this.reserveDuration = timer(registry, "match_engine_reserve_order_duration",
                "Time spent reserving the best resting order from Redis");
        this.addOrderDuration = timer(registry, "match_engine_add_order_duration",
                "Time spent adding an unmatched order to the Redis order book");
        this.matchIdDuration = timer(registry, "match_engine_match_id_duration",
                "Time spent generating the distributed match id");
        this.tradeRecordDuration = timer(registry, "match_engine_trade_record_duration",
                "Time spent persisting TradeExecuted and its outbox record");
        this.completeReservationDuration = timer(registry, "match_engine_complete_reservation_duration",
                "Time spent completing a reserved resting order in Redis");
        this.releaseReservationDuration = timer(registry, "match_engine_release_reservation_duration",
                "Time spent releasing a reserved resting order back to Redis");
        this.legacyPublishDuration = timer(registry, "match_engine_legacy_publish_duration",
                "Time spent publishing the legacy OrderMatched event");
        this.tradeRecordedTotal = Counter.builder("match_engine_trade_recorded_total")
                .description("Total TradeExecuted records persisted by MatchEngine")
                .register(registry);
        this.orderAddedTotal = Counter.builder("match_engine_order_added_total")
                .description("Total unmatched orders added to the Redis order book")
                .register(registry);
        this.reservationCompletedTotal = Counter.builder("match_engine_reservation_completed_total")
                .description("Total reserved resting orders completed after durable trade persistence")
                .register(registry);
        this.reservationReleasedTotal = Counter.builder("match_engine_reservation_released_total")
                .description("Total reserved resting orders released after trade persistence failure or partial fill")
                .register(registry);
    }

    void recordTryMatch(Duration duration) {
        tryMatchDuration.record(duration);
    }

    void recordReserve(Duration duration) {
        reserveDuration.record(duration);
    }

    void recordAddOrder(Duration duration) {
        addOrderDuration.record(duration);
    }

    void recordMatchId(Duration duration) {
        matchIdDuration.record(duration);
    }

    void recordTradeRecord(Duration duration) {
        tradeRecordDuration.record(duration);
    }

    void recordCompleteReservation(Duration duration) {
        completeReservationDuration.record(duration);
    }

    void recordReleaseReservation(Duration duration) {
        releaseReservationDuration.record(duration);
    }

    void recordLegacyPublish(Duration duration) {
        legacyPublishDuration.record(duration);
    }

    void tradeRecorded() {
        tradeRecordedTotal.increment();
    }

    void orderAdded() {
        orderAddedTotal.increment();
    }

    void reservationCompleted() {
        reservationCompletedTotal.increment();
    }

    void reservationReleased() {
        reservationReleasedTotal.increment();
    }

    private Timer timer(MeterRegistry registry, String name, String description) {
        return Timer.builder(name)
                .description(description)
                .publishPercentileHistogram()
                .register(registry);
    }
}
