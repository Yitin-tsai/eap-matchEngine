package com.eap.eap_matchengine.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ReservationReconcilerMetrics {

    private final Counter scannedTotal;
    private final Counter completedTotal;
    private final Counter releasedTotal;
    private final Counter invalidTotal;
    private final Counter failureTotal;

    public ReservationReconcilerMetrics(MeterRegistry registry, RedisOrderBookService orderBookService) {
        Gauge.builder("match_engine_reservations_active", orderBookService, RedisOrderBookService::countActiveReservations)
                .description("Current Redis order reservation key count")
                .register(registry);
        this.scannedTotal = Counter.builder("match_engine_reservation_reconciler_scanned_total")
                .description("Total Redis order reservations scanned by MatchEngine reconciliation")
                .register(registry);
        this.completedTotal = Counter.builder("match_engine_reservation_reconciler_completed_total")
                .description("Total Redis order reservations completed by MatchEngine reconciliation")
                .register(registry);
        this.releasedTotal = Counter.builder("match_engine_reservation_reconciler_released_total")
                .description("Total Redis order reservations released by MatchEngine reconciliation")
                .register(registry);
        this.invalidTotal = Counter.builder("match_engine_reservation_reconciler_invalid_total")
                .description("Total invalid Redis order reservations found by MatchEngine reconciliation")
                .register(registry);
        this.failureTotal = Counter.builder("match_engine_reservation_reconciler_failure_total")
                .description("Total MatchEngine reservation reconciliation action failures")
                .register(registry);
    }

    public void scanned() {
        scannedTotal.increment();
    }

    public void completed() {
        completedTotal.increment();
    }

    public void released() {
        releasedTotal.increment();
    }

    public void invalid() {
        invalidTotal.increment();
    }

    public void failure() {
        failureTotal.increment();
    }
}
