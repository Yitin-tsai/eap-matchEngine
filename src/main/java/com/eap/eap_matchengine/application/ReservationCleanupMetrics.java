package com.eap.eap_matchengine.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ReservationCleanupMetrics {

    private final Counter claimedTotal;
    private final Counter completedTotal;
    private final Counter failedTotal;
    private final Counter retryScheduledTotal;
    private final Timer claimDuration;
    private final Timer redisCleanupDuration;
    private final Timer markCompletedDuration;
    private final Timer batchDuration;

    public ReservationCleanupMetrics(MeterRegistry registry) {
        this.claimedTotal = Counter.builder("match_engine_reservation_cleanup_claimed_total")
                .description("Total Redis reservation cleanup tasks claimed")
                .register(registry);
        this.completedTotal = Counter.builder("match_engine_reservation_cleanup_completed_total")
                .description("Total Redis reservation cleanup tasks completed")
                .register(registry);
        this.failedTotal = Counter.builder("match_engine_reservation_cleanup_failed_total")
                .description("Total Redis reservation cleanup task failures")
                .register(registry);
        this.retryScheduledTotal = Counter.builder("match_engine_reservation_cleanup_retry_scheduled_total")
                .description("Total Redis reservation cleanup retries scheduled")
                .register(registry);
        this.claimDuration = timer(registry, "match_engine_reservation_cleanup_claim_duration",
                "Time spent claiming Redis reservation cleanup tasks");
        this.redisCleanupDuration = timer(registry, "match_engine_reservation_cleanup_redis_duration",
                "Time spent completing Redis reservations from cleanup tasks");
        this.markCompletedDuration = timer(registry, "match_engine_reservation_cleanup_mark_completed_duration",
                "Time spent marking Redis reservation cleanup tasks completed");
        this.batchDuration = timer(registry, "match_engine_reservation_cleanup_batch_duration",
                "Wall-clock time spent processing one Redis reservation cleanup batch");
    }

    void claimed(int count) {
        claimedTotal.increment(count);
    }

    void completed(int count) {
        completedTotal.increment(count);
    }

    void failed() {
        failedTotal.increment();
    }

    void retryScheduled() {
        retryScheduledTotal.increment();
    }

    void recordClaim(Duration duration) {
        claimDuration.record(duration);
    }

    void recordRedisCleanup(Duration duration) {
        redisCleanupDuration.record(duration);
    }

    void recordMarkCompleted(Duration duration) {
        markCompletedDuration.record(duration);
    }

    void recordBatch(Duration duration) {
        batchDuration.record(duration);
    }

    private Timer timer(MeterRegistry registry, String name, String description) {
        return Timer.builder(name)
                .description(description)
                .publishPercentileHistogram()
                .register(registry);
    }
}
