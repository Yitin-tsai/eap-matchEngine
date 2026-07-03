package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderTradeAppliedEvent;
import com.eap.common.event.TradeExecutedEvent;
import com.eap.common.event.WalletTradeSettledEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TradeCompletionService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public void markTradeExecuted(TradeExecutedEvent event) {
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
        jdbcTemplate.update("""
                INSERT INTO match_engine.trade_completion_view
                    (trade_id, trade_executed_at, order_applied_at, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (trade_id) DO UPDATE
                SET order_applied_at = EXCLUDED.order_applied_at,
                    completed_at = CASE
                        WHEN match_engine.trade_completion_view.completed_at IS NULL
                         AND match_engine.trade_completion_view.trade_executed_at IS NOT NULL
                         AND match_engine.trade_completion_view.wallet_settled_at IS NOT NULL
                        THEN CURRENT_TIMESTAMP
                        ELSE match_engine.trade_completion_view.completed_at
                    END,
                    updated_at = CURRENT_TIMESTAMP
                """,
                event.getTradeId(),
                appliedAt,
                appliedAt);
    }

    @Transactional
    public void markWalletSettled(WalletTradeSettledEvent event) {
        LocalDateTime settledAt = event.getSettledAt() == null ? LocalDateTime.now() : event.getSettledAt();
        jdbcTemplate.update("""
                INSERT INTO match_engine.trade_completion_view
                    (trade_id, trade_executed_at, wallet_settled_at, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (trade_id) DO UPDATE
                SET wallet_settled_at = EXCLUDED.wallet_settled_at,
                    completed_at = CASE
                        WHEN match_engine.trade_completion_view.completed_at IS NULL
                         AND match_engine.trade_completion_view.trade_executed_at IS NOT NULL
                         AND match_engine.trade_completion_view.order_applied_at IS NOT NULL
                        THEN CURRENT_TIMESTAMP
                        ELSE match_engine.trade_completion_view.completed_at
                    END,
                    updated_at = CURRENT_TIMESTAMP
                """, event.getTradeId(), settledAt, settledAt);
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
                UPDATE match_engine.trade_completion_view
                SET completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE completed_at IS NULL
                  AND trade_executed_at IS NOT NULL
                  AND order_applied_at IS NOT NULL
                  AND wallet_settled_at IS NOT NULL
                """);
    }

    public List<DelayedTradeCompletion> findDelayedCompletions(Duration threshold, int limit) {
        return jdbcTemplate.query("""
                SELECT trade_id,
                       trade_executed_at,
                       CONCAT_WS(',',
                           CASE WHEN order_applied_at IS NULL THEN 'ORDER_APPLIED' END,
                           CASE WHEN wallet_settled_at IS NULL THEN 'WALLET_SETTLED' END
                       ) AS missing_markers,
                       repair_attempt_count
                FROM match_engine.trade_completion_view
                WHERE completed_at IS NULL
                  AND trade_executed_at <= CURRENT_TIMESTAMP - (? * INTERVAL '1 second')
                ORDER BY trade_executed_at
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
                SELECT COUNT(*)
                FROM match_engine.trade_completion_view
                WHERE completed_at IS NULL
                  AND trade_executed_at <= CURRENT_TIMESTAMP - (? * INTERVAL '1 second')
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
}
