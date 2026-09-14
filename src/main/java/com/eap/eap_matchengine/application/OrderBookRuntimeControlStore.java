package com.eap.eap_matchengine.application;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class OrderBookRuntimeControlStore {

    static final String GLOBAL_SHARD = "CDA_GLOBAL";
    private static final long ACTIVATION_ADVISORY_LOCK = 0x4541504D41544348L;

    private final JdbcTemplate jdbc;

    public Control find() {
        List<Control> rows = jdbc.query("""
                SELECT shard_id, state, fence_epoch, generation, redis_run_id, version,
                       transition_reason, verification_manifest_id, verification_manifest,
                       verified_by, transitioned_at, updated_at
                FROM match_engine.order_book_runtime_control
                WHERE shard_id = ?
                """, (rs, rowNum) -> new Control(
                rs.getString("shard_id"),
                State.valueOf(rs.getString("state")),
                rs.getLong("fence_epoch"),
                rs.getObject("generation", UUID.class),
                rs.getString("redis_run_id"),
                rs.getLong("version"),
                rs.getString("transition_reason"),
                rs.getString("verification_manifest_id"),
                rs.getString("verification_manifest"),
                rs.getString("verified_by"),
                rs.getObject("transitioned_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)),
                GLOBAL_SHARD);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Control createRecoveringIfAbsent(UUID generation, String reason) {
        jdbc.update("""
                INSERT INTO match_engine.order_book_runtime_control
                    (shard_id, state, fence_epoch, generation, version,
                     transition_reason, transitioned_at, updated_at)
                VALUES (?, 'RECOVERING', 1, ?, 0, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (shard_id) DO NOTHING
                """, GLOBAL_SHARD, generation, truncate(reason));
        return find();
    }

    public Control beginRecovery(Control expected, UUID nextGeneration, String reason) {
        int updated = jdbc.update("""
                UPDATE match_engine.order_book_runtime_control
                SET state = 'RECOVERING',
                    fence_epoch = fence_epoch + 1,
                    generation = ?,
                    redis_run_id = NULL,
                    version = version + 1,
                    transition_reason = ?,
                    verification_manifest_id = NULL,
                    verification_manifest = NULL,
                    verified_by = NULL,
                    transitioned_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE shard_id = ? AND state = 'READY' AND version = ?
                  AND fence_epoch = ? AND generation = ? AND redis_run_id = ?
                """, nextGeneration, truncate(reason), GLOBAL_SHARD, expected.version(),
                expected.fenceEpoch(), expected.generation(), expected.redisRunId());
        return find();
    }

    /**
     * Serializes the low-frequency operator activation protocol across MatchEngine instances.
     * The session-level advisory lock is held on one dedicated JDBC connection while the
     * caller verifies Redis and performs the PostgreSQL CAS through normal pool connections.
     */
    public <T> T withActivationLock(Supplier<T> action) {
        return withAdvisoryLock("SELECT pg_advisory_lock(?)", "SELECT pg_advisory_unlock(?)", action);
    }

    /**
     * Places cancellation durable intake on the shared side of the activation barrier.
     * Concurrent cancellations remain concurrent, while activation waits until every
     * cancellation that started first has either recorded its Redis intent or durably
     * remained pending for the recovery verifier to observe.
     */
    public <T> T withCancellationIntakeLock(Supplier<T> action) {
        return withAdvisoryLock(
                "SELECT pg_advisory_lock_shared(?)",
                "SELECT pg_advisory_unlock_shared(?)",
                action);
    }

    private <T> T withAdvisoryLock(String lockSql, String unlockSql, Supplier<T> action) {
        return jdbc.execute((ConnectionCallback<T>) connection -> {
            try (PreparedStatement lock = connection.prepareStatement(lockSql)) {
                lock.setLong(1, ACTIVATION_ADVISORY_LOCK);
                try (ResultSet ignored = lock.executeQuery()) {
                    // executeQuery acquires the session lock before the recovery action starts.
                }
            }
            try {
                return action.get();
            } finally {
                try (PreparedStatement unlock = connection.prepareStatement(unlockSql)) {
                    unlock.setLong(1, ACTIVATION_ADVISORY_LOCK);
                    unlock.executeQuery().close();
                }
            }
        });
    }

    public boolean markReady(
            Control expected,
            String redisRunId,
            String reason,
            String manifestId,
            String manifest,
            String verifiedBy) {
        return jdbc.update("""
                UPDATE match_engine.order_book_runtime_control
                SET state = 'READY', redis_run_id = ?, version = version + 1,
                    transition_reason = ?, verification_manifest_id = ?,
                    verification_manifest = ?, verified_by = ?,
                    transitioned_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE shard_id = ? AND state = 'RECOVERING'
                  AND fence_epoch = ? AND generation = ? AND version = ?
                """,
                redisRunId,
                truncate(reason),
                manifestId,
                manifest,
                verifiedBy,
                GLOBAL_SHARD,
                expected.fenceEpoch(),
                expected.generation(),
                expected.version()) == 1;
    }

    public long durableFactCount() {
        Long count = jdbc.queryForObject("""
                SELECT
                    (SELECT COUNT(*) FROM match_engine.order_admission_inbox)
                  + (SELECT COUNT(*) FROM match_engine.trade_executions)
                  + (SELECT COUNT(*) FROM match_engine.order_cancellations)
                  + (SELECT COUNT(*) FROM match_engine.trade_outbox)
                  + (SELECT COUNT(*) FROM match_engine.reservation_cleanup_tasks)
                  + (SELECT COUNT(*) FROM match_engine.reservation_reconciliation_issues)
                """, Long.class);
        return count == null ? 0L : count;
    }

    public long pendingCancellationCount() {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM match_engine.order_cancellations
                WHERE status IN ('PENDING', 'IN_PROGRESS')
                """, Long.class);
        return count == null ? 0L : count;
    }

    public Map<UUID, UUID> pendingCancellations() {
        return jdbc.query("""
                SELECT order_id, cancellation_id
                FROM match_engine.order_cancellations
                WHERE status IN ('PENDING', 'IN_PROGRESS')
                """, (rs, rowNum) -> Map.entry(
                rs.getObject("order_id", UUID.class),
                rs.getObject("cancellation_id", UUID.class)))
                .stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public long maxDurableTradeSequence() {
        Long sequence = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence), 0) FROM match_engine.trade_executions",
                Long.class);
        return sequence == null ? 0L : sequence;
    }

    public long maxReceivedOrderSequence() {
        Long sequence = jdbc.queryForObject(
                "SELECT COALESCE(MAX(market_sequence), 0) FROM match_engine.order_admission_inbox",
                Long.class);
        return sequence == null ? 0L : sequence;
    }

    /**
     * PostgreSQL is the durable authority for completed admission markers. Redis bitmaps are
     * only a fast idempotency projection and must be rebuilt to exactly this set before a
     * generation can be activated.
     */
    public List<CompletedAdmission> completedAdmissions() {
        return jdbc.query("""
                SELECT market_id, market_sequence
                FROM match_engine.order_admission_inbox
                WHERE status = 'APPLIED'
                ORDER BY market_id, market_sequence
                """, (rs, rowNum) -> new CompletedAdmission(
                rs.getString("market_id"),
                rs.getLong("market_sequence")));
    }

    public List<CancellationFact> cancellationFacts() {
        return jdbc.query("""
                SELECT cancellation_id, order_id, user_id, status, market_id,
                       market_sequence, order_type, limit_price, cancelled_amount,
                       order_created_at
                FROM match_engine.order_cancellations
                ORDER BY order_id
                """, (rs, rowNum) -> new CancellationFact(
                rs.getObject("cancellation_id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("status"),
                rs.getString("market_id"),
                rs.getObject("market_sequence", Long.class),
                rs.getString("order_type"),
                rs.getObject("limit_price", Integer.class),
                rs.getObject("cancelled_amount", Integer.class),
                rs.getObject("order_created_at", LocalDateTime.class)));
    }

    public enum State {
        RECOVERING,
        READY
    }

    public record Control(
            String shardId,
            State state,
            long fenceEpoch,
            UUID generation,
            String redisRunId,
            long version,
            String transitionReason,
            String verificationManifestId,
            String verificationManifest,
            String verifiedBy,
            LocalDateTime transitionedAt,
            LocalDateTime updatedAt) {
    }

    public record CompletedAdmission(String marketId, long marketSequence) {
    }

    public record CancellationFact(
            UUID cancellationId,
            UUID orderId,
            UUID userId,
            String status,
            String marketId,
            Long marketSequence,
            String orderType,
            Integer limitPrice,
            Integer cancelledAmount,
            LocalDateTime orderCreatedAt) {
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return "No reason supplied";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
