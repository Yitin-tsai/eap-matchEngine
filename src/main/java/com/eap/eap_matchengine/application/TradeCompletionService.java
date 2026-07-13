package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderTradeAppliedEvent;
import com.eap.common.event.TradeExecutedEvent;
import com.eap.common.event.WalletTradeSettledEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class TradeCompletionService {

    private static final String ORDER_APPLIED = "ORDER_APPLIED";
    private static final String WALLET_SETTLED = "WALLET_SETTLED";
    private static final String INSERT_COMPLETION_MARKERS_SQL = """
            INSERT INTO match_engine.trade_completion_markers
                (trade_id, marker_type, marker_at)
            SELECT trade_id, marker_type, marker_at
            FROM unnest(?::varchar[], ?::varchar[], ?::timestamp[])
                AS marker_input(trade_id, marker_type, marker_at)
            ON CONFLICT (trade_id, marker_type) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TradeCompletionMarkerMetrics markerMetrics;
    private final boolean hotPathCompletionViewEnabled;

    @Autowired
    public TradeCompletionService(
            JdbcTemplate jdbcTemplate,
            TradeCompletionMarkerMetrics markerMetrics,
            @Value("${eap.match-engine.trade-completion-view.hot-path-enabled:true}")
            boolean hotPathCompletionViewEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.markerMetrics = markerMetrics;
        this.hotPathCompletionViewEnabled = hotPathCompletionViewEnabled;
    }

    @Transactional
    public void markTradeExecuted(TradeExecutedEvent event) {
        if (!hotPathCompletionViewEnabled) {
            return;
        }
        LocalDateTime executedAt = event.getOccurredAt() == null ? LocalDateTime.now() : event.getOccurredAt();
        jdbcTemplate.update("""
                INSERT INTO match_engine.trade_completion_view
                    (trade_id, trade_executed_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (trade_id) DO UPDATE
                SET trade_executed_at = EXCLUDED.trade_executed_at,
                    updated_at = CURRENT_TIMESTAMP
                """, event.getTradeId(), executedAt);
    }

    @Transactional
    public void markOrderApplied(OrderTradeAppliedEvent event) {
        LocalDateTime appliedAt = event.getAppliedAt() == null ? LocalDateTime.now() : event.getAppliedAt();
        insertCompletionMarker(event.getTradeId(), ORDER_APPLIED, appliedAt);
    }

    @Transactional
    public void markOrderAppliedBatch(List<OrderTradeAppliedEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<CompletionMarker> markers = new ArrayList<>(events.size());
        for (OrderTradeAppliedEvent event : events) {
            LocalDateTime appliedAt = event.getAppliedAt() == null ? now : event.getAppliedAt();
            markers.add(new CompletionMarker(event.getTradeId(), ORDER_APPLIED, appliedAt));
        }
        insertCompletionMarkers(markers);
    }

    @Transactional
    public void markWalletSettled(WalletTradeSettledEvent event) {
        LocalDateTime settledAt = event.getSettledAt() == null ? LocalDateTime.now() : event.getSettledAt();
        insertCompletionMarker(event.getTradeId(), WALLET_SETTLED, settledAt);
    }

    @Transactional
    public void markWalletSettledBatch(List<WalletTradeSettledEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<CompletionMarker> markers = new ArrayList<>(events.size());
        for (WalletTradeSettledEvent event : events) {
            LocalDateTime settledAt = event.getSettledAt() == null ? now : event.getSettledAt();
            markers.add(new CompletionMarker(event.getTradeId(), WALLET_SETTLED, settledAt));
        }
        insertCompletionMarkers(markers);
    }

    private void insertCompletionMarker(String tradeId, String markerType, LocalDateTime markerAt) {
        Instant startedAt = Instant.now();
        try {
            jdbcTemplate.update("""
                    INSERT INTO match_engine.trade_completion_markers
                        (trade_id, marker_type, marker_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT (trade_id, marker_type) DO NOTHING
                    """, tradeId, markerType, markerAt);
        } finally {
            markerMetrics.recordInsert(markerType, 1, Duration.between(startedAt, Instant.now()));
        }
    }

    private void insertCompletionMarkers(List<CompletionMarker> markers) {
        String markerType = markers.get(0).markerType();
        Instant startedAt = Instant.now();
        try {
            jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
                Array tradeIds = null;
                Array markerTypes = null;
                Array markerTimes = null;
                try (PreparedStatement statement = connection.prepareStatement(INSERT_COMPLETION_MARKERS_SQL)) {
                    tradeIds = connection.createArrayOf("varchar", markers.stream()
                            .map(CompletionMarker::tradeId)
                            .toArray(String[]::new));
                    markerTypes = connection.createArrayOf("varchar", markers.stream()
                            .map(CompletionMarker::markerType)
                            .toArray(String[]::new));
                    markerTimes = connection.createArrayOf("timestamp", markers.stream()
                            .map(marker -> Timestamp.valueOf(marker.markerAt()))
                            .toArray(Timestamp[]::new));
                    statement.setArray(1, tradeIds);
                    statement.setArray(2, markerTypes);
                    statement.setArray(3, markerTimes);
                    return statement.executeUpdate();
                } finally {
                    freeQuietly(tradeIds);
                    freeQuietly(markerTypes);
                    freeQuietly(markerTimes);
                }
            });
        } finally {
            markerMetrics.recordInsert(markerType, markers.size(), Duration.between(startedAt, Instant.now()));
        }
    }

    private void freeQuietly(Array array) {
        if (array == null) {
            return;
        }
        try {
            array.free();
        } catch (Exception ignored) {
        }
    }

    @Transactional
    public int backfillMissingCompletionRows() {
        return jdbcTemplate.update("""
                INSERT INTO match_engine.trade_completion_view
                    (trade_id, trade_executed_at, updated_at)
                SELECT trade_id, occurred_at, CURRENT_TIMESTAMP
                FROM match_engine.trade_executions
                ON CONFLICT (trade_id) DO NOTHING
                """);
    }

    @Transactional
    public int completeReadyRows() {
        return jdbcTemplate.update("""
                WITH marker_state AS (
                    SELECT trade_id,
                           MAX(marker_at) FILTER (WHERE marker_type = 'ORDER_APPLIED') AS order_applied_at,
                           MAX(marker_at) FILTER (WHERE marker_type = 'WALLET_SETTLED') AS wallet_settled_at
                    FROM match_engine.trade_completion_markers
                    WHERE marker_type IN ('ORDER_APPLIED', 'WALLET_SETTLED')
                    GROUP BY trade_id
                    HAVING COUNT(DISTINCT marker_type) = 2
                )
                UPDATE match_engine.trade_completion_view view
                SET order_applied_at = COALESCE(view.order_applied_at, marker_state.order_applied_at),
                    wallet_settled_at = COALESCE(view.wallet_settled_at, marker_state.wallet_settled_at),
                    completed_at = COALESCE(view.completed_at, CURRENT_TIMESTAMP),
                    updated_at = CURRENT_TIMESTAMP
                FROM marker_state
                WHERE view.trade_id = marker_state.trade_id
                  AND view.trade_executed_at IS NOT NULL
                  AND view.completed_at IS NULL
                """);
    }

    public List<DelayedTradeCompletion> findDelayedCompletions(Duration threshold, int limit) {
        return jdbcTemplate.query("""
                WITH marker_state AS (
                    SELECT trade_id,
                           BOOL_OR(marker_type = 'ORDER_APPLIED') AS order_applied,
                           BOOL_OR(marker_type = 'WALLET_SETTLED') AS wallet_settled
                    FROM match_engine.trade_completion_markers
                    WHERE marker_type IN ('ORDER_APPLIED', 'WALLET_SETTLED')
                    GROUP BY trade_id
                )
                SELECT view.trade_id,
                       view.trade_executed_at,
                       CONCAT_WS(',',
                           CASE WHEN COALESCE(marker_state.order_applied, FALSE) = FALSE THEN 'ORDER_APPLIED' END,
                           CASE WHEN COALESCE(marker_state.wallet_settled, FALSE) = FALSE THEN 'WALLET_SETTLED' END
                       ) AS missing_markers,
                       view.repair_attempt_count
                FROM match_engine.trade_completion_view view
                LEFT JOIN marker_state ON marker_state.trade_id = view.trade_id
                WHERE view.completed_at IS NULL
                  AND view.trade_executed_at <= CURRENT_TIMESTAMP - (? * INTERVAL '1 second')
                ORDER BY view.trade_executed_at
                LIMIT ?
                """, (rs, rowNum) -> new DelayedTradeCompletion(
                rs.getString("trade_id"),
                rs.getObject("trade_executed_at", LocalDateTime.class),
                rs.getString("missing_markers"),
                rs.getInt("repair_attempt_count")
        ), threshold.toSeconds(), limit);
    }

    public long countDelayedCompletions(Duration threshold) {
        Long count = jdbcTemplate.queryForObject("""
                WITH marker_state AS (
                    SELECT trade_id,
                           COUNT(DISTINCT marker_type) AS marker_count
                    FROM match_engine.trade_completion_markers
                    WHERE marker_type IN ('ORDER_APPLIED', 'WALLET_SETTLED')
                    GROUP BY trade_id
                )
                SELECT COUNT(*)
                FROM match_engine.trade_completion_view view
                LEFT JOIN marker_state ON marker_state.trade_id = view.trade_id
                WHERE view.completed_at IS NULL
                  AND COALESCE(marker_state.marker_count, 0) < 2
                  AND view.trade_executed_at <= CURRENT_TIMESTAMP - (? * INTERVAL '1 second')
                """, Long.class, threshold.toSeconds());
        return count == null ? 0 : count;
    }

    @Transactional
    public boolean markDelayedAndRequeueTradeExecuted(DelayedTradeCompletion delayed) {
        jdbcTemplate.update("""
                UPDATE match_engine.trade_completion_view
                SET delayed_detected_at = COALESCE(delayed_detected_at, CURRENT_TIMESTAMP),
                    last_missing_markers = ?,
                    repair_attempt_count = repair_attempt_count + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE trade_id = ?
                  AND completed_at IS NULL
                """, delayed.missingMarkers(), delayed.tradeId());

        int requeued = jdbcTemplate.update("""
                UPDATE match_engine.trade_outbox
                SET status = 'PENDING',
                    attempt_count = 0,
                    next_retry_at = CURRENT_TIMESTAMP,
                    last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE event_type = 'TradeExecutedEvent'
                  AND aggregate_id = ?
                  AND status <> 'PENDING'
                """, delayed.tradeId());
        return requeued > 0;
    }

    public record DelayedTradeCompletion(
            String tradeId,
            LocalDateTime tradeExecutedAt,
            String missingMarkers,
            int repairAttemptCount) {
    }

    private record CompletionMarker(String tradeId, String markerType, LocalDateTime markerAt) {
    }
}
