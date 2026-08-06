package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(
        name = "eap.match-engine.reservation-cleanup.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ReservationCleanupWorker {

    private final JdbcTemplate jdbcTemplate;
    private final RedisOrderBookService orderBookService;
    private final ReservationCleanupMetrics metrics;
    private final int batchSize;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final long processingTimeoutSeconds;
    private final int leaseRenewalChunkSize;

    public ReservationCleanupWorker(
            JdbcTemplate jdbcTemplate,
            RedisOrderBookService orderBookService,
            ReservationCleanupMetrics metrics,
            @Value("${eap.match-engine.reservation-cleanup.batch-size:500}") int batchSize,
            @Value("${eap.match-engine.reservation-cleanup.max-attempts:10}") int maxAttempts,
            @Value("${eap.match-engine.reservation-cleanup.initial-backoff-ms:1000}") long initialBackoffMs,
            @Value("${eap.match-engine.reservation-cleanup.max-backoff-ms:300000}") long maxBackoffMs,
            @Value("${eap.match-engine.reservation-cleanup.processing-timeout-seconds:30}") long processingTimeoutSeconds,
            @Value("${eap.match-engine.reservation-cleanup.lease-renewal-chunk-size:50}") int leaseRenewalChunkSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderBookService = orderBookService;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.processingTimeoutSeconds = processingTimeoutSeconds;
        this.leaseRenewalChunkSize = Math.max(1, leaseRenewalChunkSize);
    }

    @Scheduled(fixedDelayString = "${eap.match-engine.reservation-cleanup.poll-interval-ms:100}")
    public void cleanup() {
        cleanupOnce();
    }

    int cleanupOnce() {
        Instant batchStartedAt = Instant.now();
        List<CleanupRow> tasks = claimTasks();
        if (tasks.isEmpty()) {
            return 0;
        }

        int completedCount = 0;
        for (int start = 0; start < tasks.size(); start += leaseRenewalChunkSize) {
            int end = Math.min(start + leaseRenewalChunkSize, tasks.size());
            List<CleanupRow> chunk = tasks.subList(start, end);
            renewLeases(chunk.stream().map(CleanupRow::id).toList());

            List<Long> completedTaskIds = new ArrayList<>(chunk.size());
            for (CleanupRow task : chunk) {
                Instant redisStartedAt = Instant.now();
                try {
                    orderBookService.completeReservedOrder(toOrder(task));
                    completedTaskIds.add(task.id());
                } catch (Exception e) {
                    recordFailure(task, e);
                } finally {
                    metrics.recordRedisCleanup(Duration.between(redisStartedAt, Instant.now()));
                }
            }
            markCompleted(completedTaskIds);
            completedCount += completedTaskIds.size();
        }
        metrics.completed(completedCount);
        metrics.recordBatch(Duration.between(batchStartedAt, Instant.now()));
        return tasks.size();
    }

    void renewLeases(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        int updated = jdbcTemplate.update("""
                UPDATE match_engine.reservation_cleanup_tasks
                SET updated_at = CURRENT_TIMESTAMP
                WHERE id IN (%s)
                  AND status = 'PROCESSING'
                """.formatted(placeholders), ids.toArray());
        if (updated != ids.size()) {
            throw new IllegalStateException("Expected to renew " + ids.size()
                    + " reservation cleanup leases, but updated " + updated);
        }
    }

    private List<CleanupRow> claimTasks() {
        Instant startedAt = Instant.now();
        try {
            List<CleanupRow> tasks = jdbcTemplate.query("""
                    WITH claimed AS (
                        SELECT id
                        FROM match_engine.reservation_cleanup_tasks
                        WHERE (status = 'PENDING' AND next_retry_at <= CURRENT_TIMESTAMP)
                           OR (status = 'PROCESSING'
                               AND updated_at <= CURRENT_TIMESTAMP - (? * INTERVAL '1 second'))
                        ORDER BY created_at, id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                    )
                    UPDATE match_engine.reservation_cleanup_tasks task
                    SET status = 'PROCESSING',
                        updated_at = CURRENT_TIMESTAMP
                    FROM claimed
                    WHERE task.id = claimed.id
                    RETURNING task.id, task.trade_id, task.order_id, task.user_id, task.attempt_count
                    """,
                    (rs, rowNum) -> new CleanupRow(
                            rs.getLong("id"),
                            rs.getString("trade_id"),
                            rs.getObject("order_id", UUID.class),
                            rs.getObject("user_id", UUID.class),
                            rs.getInt("attempt_count")),
                    processingTimeoutSeconds,
                    batchSize);
            metrics.claimed(tasks.size());
            return tasks;
        } finally {
            metrics.recordClaim(Duration.between(startedAt, Instant.now()));
        }
    }

    void markCompleted(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Instant startedAt = Instant.now();
        try {
            String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
            int updated = jdbcTemplate.update("""
                    UPDATE match_engine.reservation_cleanup_tasks
                    SET status = 'COMPLETED',
                        last_error = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id IN (%s)
                      AND status = 'PROCESSING'
                    """.formatted(placeholders), ids.toArray());
            if (updated != ids.size()) {
                throw new IllegalStateException("Expected to complete " + ids.size()
                        + " reservation cleanup tasks, but updated " + updated);
            }
        } finally {
            metrics.recordMarkCompleted(Duration.between(startedAt, Instant.now()));
        }
    }

    private void recordFailure(CleanupRow task, Exception failure) {
        metrics.failed();
        int nextAttempt = task.attemptCount() + 1;
        String error = failure.getClass().getSimpleName() + ": "
                + (failure.getMessage() == null ? "no message" : failure.getMessage());
        String truncatedError = error.substring(0, Math.min(error.length(), 1000));
        LocalDateTime updatedAt = LocalDateTime.now();

        if (nextAttempt >= maxAttempts) {
            jdbcTemplate.update("""
                    UPDATE match_engine.reservation_cleanup_tasks
                    SET status = 'FAILED',
                        attempt_count = ?,
                        last_error = ?,
                        updated_at = ?
                    WHERE id = ?
                      AND status = 'PROCESSING'
                    """, nextAttempt, truncatedError, updatedAt, task.id());
            return;
        }

        long backoffMs = calculateBackoffMs(nextAttempt);
        LocalDateTime nextRetryAt = updatedAt.plusNanos(TimeUnit.MILLISECONDS.toNanos(backoffMs));
        jdbcTemplate.update("""
                UPDATE match_engine.reservation_cleanup_tasks
                SET status = 'PENDING',
                    attempt_count = ?,
                    next_retry_at = ?,
                    last_error = ?,
                    updated_at = ?
                WHERE id = ?
                  AND status = 'PROCESSING'
                """, nextAttempt, nextRetryAt, truncatedError, updatedAt, task.id());
        metrics.retryScheduled();
    }

    private long calculateBackoffMs(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 30);
        long multiplier = 1L << exponent;
        if (initialBackoffMs > maxBackoffMs / multiplier) {
            return maxBackoffMs;
        }
        return Math.min(initialBackoffMs * multiplier, maxBackoffMs);
    }

    private OrderConfirmedEvent toOrder(CleanupRow task) {
        return OrderConfirmedEvent.builder()
                .orderId(task.orderId())
                .userId(task.userId())
                .build();
    }

    record CleanupRow(
            long id,
            String tradeId,
            UUID orderId,
            UUID userId,
            int attemptCount) {
    }
}
