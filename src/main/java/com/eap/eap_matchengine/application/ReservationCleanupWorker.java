package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderAssetReservationSucceededEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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

import static com.eap.eap_matchengine.configuration.config.MatchEngineSchedulerConfig.RESERVATION_MAINTENANCE_SCHEDULER;

@Component
@ConditionalOnProperty(
        name = "eap.match-engine.reservation-cleanup.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ReservationCleanupWorker {

    private final JdbcTemplate jdbcTemplate;
    private final RedisOrderBookService orderBookService;
    private final ReservationCleanupMetrics metrics;
    private final OrderBookRuntimeGuard runtimeGuard;
    private final int batchSize;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final long processingTimeoutSeconds;
    private final int leaseRenewalChunkSize;
    private final String claimOwner = UUID.randomUUID().toString();

    @Autowired
    public ReservationCleanupWorker(
            JdbcTemplate jdbcTemplate,
            RedisOrderBookService orderBookService,
            ReservationCleanupMetrics metrics,
            OrderBookRuntimeGuard runtimeGuard,
            @Value("${eap.match-engine.reservation-cleanup.batch-size:500}") int batchSize,
            @Value("${eap.match-engine.reservation-cleanup.max-attempts:10}") int maxAttempts,
            @Value("${eap.match-engine.reservation-cleanup.initial-backoff-ms:1000}") long initialBackoffMs,
            @Value("${eap.match-engine.reservation-cleanup.max-backoff-ms:300000}") long maxBackoffMs,
            @Value("${eap.match-engine.reservation-cleanup.processing-timeout-seconds:30}") long processingTimeoutSeconds,
            @Value("${eap.match-engine.reservation-cleanup.lease-renewal-chunk-size:50}") int leaseRenewalChunkSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderBookService = orderBookService;
        this.metrics = metrics;
        this.runtimeGuard = runtimeGuard;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.processingTimeoutSeconds = processingTimeoutSeconds;
        this.leaseRenewalChunkSize = Math.max(1, leaseRenewalChunkSize);
    }

    ReservationCleanupWorker(
            JdbcTemplate jdbcTemplate,
            RedisOrderBookService orderBookService,
            ReservationCleanupMetrics metrics,
            int batchSize,
            int maxAttempts,
            long initialBackoffMs,
            long maxBackoffMs,
            long processingTimeoutSeconds,
            int leaseRenewalChunkSize) {
        this.jdbcTemplate = jdbcTemplate;
        this.orderBookService = orderBookService;
        this.metrics = metrics;
        this.runtimeGuard = null;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.processingTimeoutSeconds = processingTimeoutSeconds;
        this.leaseRenewalChunkSize = Math.max(1, leaseRenewalChunkSize);
    }

    @Scheduled(
            fixedDelayString = "${eap.match-engine.reservation-cleanup.poll-interval-ms:100}",
            scheduler = RESERVATION_MAINTENANCE_SCHEDULER)
    public void cleanup() {
        cleanupOnce();
    }

    int cleanupOnce() {
        if (runtimeGuard != null && !runtimeGuard.isReady()) {
            return 0;
        }
        Instant batchStartedAt = Instant.now();
        List<CleanupRow> tasks = claimTasks();
        if (tasks.isEmpty()) {
            return 0;
        }

        int completedCount = 0;
        for (int start = 0; start < tasks.size(); start += leaseRenewalChunkSize) {
            int end = Math.min(start + leaseRenewalChunkSize, tasks.size());
            List<CleanupRow> chunk = tasks.subList(start, end);
            renewLeases(chunk);

            List<CleanupRow> completedTasks = new ArrayList<>(chunk.size());
            for (CleanupRow task : chunk) {
                Instant redisStartedAt = Instant.now();
                ReservationCompletionOutcome outcome;
                try {
                    outcome = orderBookService.completeReservedOrder(toOrder(task), task.tradeId());
                } catch (OrderBookRuntimeUnavailableException unavailable) {
                    // Leave this batch leased. Lease expiry will make it retryable after a
                    // verified generation is activated, without consuming technical attempts.
                    return 0;
                } catch (Exception e) {
                    recordFailure(task, e);
                    continue;
                } finally {
                    metrics.recordRedisCleanup(Duration.between(redisStartedAt, Instant.now()));
                }
                if (outcome.successful()) {
                    completedTasks.add(task);
                } else {
                    recordPermanentFailure(task, outcome);
                }
            }
            markCompleted(completedTasks);
            completedCount += completedTasks.size();
        }
        metrics.completed(completedCount);
        metrics.recordBatch(Duration.between(batchStartedAt, Instant.now()));
        return tasks.size();
    }

    void renewLeases(List<CleanupRow> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        UUID claimToken = sharedClaimToken(tasks);
        String placeholders = String.join(", ", Collections.nCopies(tasks.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(processingTimeoutSeconds);
        parameters.addAll(tasks.stream().map(CleanupRow::id).toList());
        parameters.add(claimOwner);
        parameters.add(claimToken);
        int updated = jdbcTemplate.update("""
                UPDATE match_engine.reservation_cleanup_tasks
                SET claim_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id IN (%s)
                  AND status = 'PROCESSING'
                  AND claim_owner = ?
                  AND claim_token = ?
                """.formatted(placeholders), parameters.toArray());
        if (updated != tasks.size()) {
            throw new IllegalStateException("Expected to renew " + tasks.size()
                    + " reservation cleanup leases, but updated " + updated);
        }
    }

    private List<CleanupRow> claimTasks() {
        Instant startedAt = Instant.now();
        try {
            UUID claimToken = UUID.randomUUID();
            List<CleanupRow> tasks = jdbcTemplate.query("""
                    WITH claimed AS (
                        SELECT id
                        FROM match_engine.reservation_cleanup_tasks
                        WHERE (status = 'PENDING' AND next_retry_at <= CURRENT_TIMESTAMP)
                           OR (status = 'PROCESSING'
                               AND COALESCE(claim_until,
                                   updated_at + (? * INTERVAL '1 second')) <= CURRENT_TIMESTAMP)
                        ORDER BY created_at, id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                    )
                    UPDATE match_engine.reservation_cleanup_tasks task
                    SET status = 'PROCESSING',
                        claim_owner = ?,
                        claim_token = ?,
                        claim_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                        updated_at = CURRENT_TIMESTAMP
                    FROM claimed
                    WHERE task.id = claimed.id
                    RETURNING task.id, task.trade_id, task.order_id, task.user_id,
                              task.attempt_count, task.claim_token
                    """,
                    (rs, rowNum) -> new CleanupRow(
                            rs.getLong("id"),
                            rs.getString("trade_id"),
                            rs.getObject("order_id", UUID.class),
                            rs.getObject("user_id", UUID.class),
                            rs.getInt("attempt_count"),
                            rs.getObject("claim_token", UUID.class)),
                    processingTimeoutSeconds,
                    batchSize,
                    claimOwner,
                    claimToken,
                    processingTimeoutSeconds);
            metrics.claimed(tasks.size());
            return tasks;
        } finally {
            metrics.recordClaim(Duration.between(startedAt, Instant.now()));
        }
    }

    void markCompleted(List<CleanupRow> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        Instant startedAt = Instant.now();
        try {
            UUID claimToken = sharedClaimToken(tasks);
            String placeholders = String.join(", ", Collections.nCopies(tasks.size(), "?"));
            List<Object> parameters = new ArrayList<>();
            parameters.addAll(tasks.stream().map(CleanupRow::id).toList());
            parameters.add(claimOwner);
            parameters.add(claimToken);
            int updated = jdbcTemplate.update("""
                    UPDATE match_engine.reservation_cleanup_tasks
                    SET status = 'COMPLETED',
                        last_error = NULL,
                        error_type = NULL,
                        claim_owner = NULL,
                        claim_token = NULL,
                        claim_until = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id IN (%s)
                      AND status = 'PROCESSING'
                      AND claim_owner = ?
                      AND claim_token = ?
                    """.formatted(placeholders), parameters.toArray());
            if (updated != tasks.size()) {
                throw new IllegalStateException("Expected to complete " + tasks.size()
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
            int updated = jdbcTemplate.update("""
                    UPDATE match_engine.reservation_cleanup_tasks
                    SET status = 'FAILED',
                        attempt_count = ?,
                        error_type = 'RETRY_EXHAUSTED_TECHNICAL_FAILURE',
                        last_error = ?,
                        claim_owner = NULL,
                        claim_token = NULL,
                        claim_until = NULL,
                        updated_at = ?
                    WHERE id = ?
                      AND status = 'PROCESSING'
                      AND claim_owner = ?
                      AND claim_token = ?
                    """, nextAttempt, truncatedError, updatedAt, task.id(),
                    claimOwner, task.claimToken());
            requireOwnedUpdate(task, updated, "fail permanently");
            return;
        }

        long backoffMs = calculateBackoffMs(nextAttempt);
        LocalDateTime nextRetryAt = updatedAt.plusNanos(TimeUnit.MILLISECONDS.toNanos(backoffMs));
        int updated = jdbcTemplate.update("""
                UPDATE match_engine.reservation_cleanup_tasks
                SET status = 'PENDING',
                    attempt_count = ?,
                    next_retry_at = ?,
                    error_type = 'TRANSIENT_CLEANUP_FAILURE',
                    last_error = ?,
                    claim_owner = NULL,
                    claim_token = NULL,
                    claim_until = NULL,
                    updated_at = ?
                WHERE id = ?
                  AND status = 'PROCESSING'
                  AND claim_owner = ?
                  AND claim_token = ?
                """, nextAttempt, nextRetryAt, truncatedError, updatedAt, task.id(),
                claimOwner, task.claimToken());
        requireOwnedUpdate(task, updated, "schedule retry for");
        metrics.retryScheduled();
    }

    private void recordPermanentFailure(CleanupRow task, ReservationCompletionOutcome outcome) {
        metrics.failed();
        int nextAttempt = task.attemptCount() + 1;
        String error = "Reservation ownership conflict: outcome=" + outcome
                + ", redisCode=" + outcome.redisCode()
                + ", orderId=" + task.orderId()
                + ", expectedTradeId=" + task.tradeId();
        LocalDateTime updatedAt = LocalDateTime.now();
        int updated = jdbcTemplate.update("""
                UPDATE match_engine.reservation_cleanup_tasks
                    SET status = 'FAILED',
                        attempt_count = ?,
                        error_type = 'RESERVATION_OWNERSHIP_CONFLICT',
                        last_error = ?,
                        claim_owner = NULL,
                        claim_token = NULL,
                        claim_until = NULL,
                        updated_at = ?
                    WHERE id = ?
                      AND status = 'PROCESSING'
                      AND claim_owner = ?
                      AND claim_token = ?
                """, nextAttempt, error, updatedAt, task.id(),
                claimOwner, task.claimToken());
        if (updated != 1) {
            throw new IllegalStateException("Expected to fail reservation cleanup task "
                    + task.id() + ", but updated " + updated);
        }
    }

    private long calculateBackoffMs(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 30);
        long multiplier = 1L << exponent;
        if (initialBackoffMs > maxBackoffMs / multiplier) {
            return maxBackoffMs;
        }
        return Math.min(initialBackoffMs * multiplier, maxBackoffMs);
    }

    private OrderAssetReservationSucceededEvent toOrder(CleanupRow task) {
        return OrderAssetReservationSucceededEvent.builder()
                .orderId(task.orderId())
                .userId(task.userId())
                .build();
    }

    private UUID sharedClaimToken(List<CleanupRow> tasks) {
        UUID claimToken = tasks.get(0).claimToken();
        if (claimToken == null || tasks.stream().anyMatch(task -> !claimToken.equals(task.claimToken()))) {
            throw new IllegalArgumentException("Reservation cleanup batch must have one non-null claim token");
        }
        return claimToken;
    }

    private void requireOwnedUpdate(CleanupRow task, int updated, String action) {
        if (updated != 1) {
            throw new IllegalStateException("Lost reservation cleanup lease while attempting to "
                    + action + " task " + task.id());
        }
    }

    String claimOwner() {
        return claimOwner;
    }

    record CleanupRow(
            long id,
            String tradeId,
            UUID orderId,
            UUID userId,
            int attemptCount,
            UUID claimToken) {

        CleanupRow(long id, String tradeId, UUID orderId, UUID userId, int attemptCount) {
            this(id, tradeId, orderId, userId, attemptCount, new UUID(0L, 0L));
        }
    }
}
