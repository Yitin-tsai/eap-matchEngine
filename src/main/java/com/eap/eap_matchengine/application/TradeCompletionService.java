package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderTradeAppliedEvent;
import com.eap.common.event.TradeExecutedEvent;
import com.eap.common.event.WalletTradeSettledEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
        String column = "SELL".equals(event.getSide()) ? "seller_order_applied_at" : "buyer_order_applied_at";
        jdbcTemplate.update("""
                INSERT INTO match_engine.trade_completion_view
                    (trade_id, trade_executed_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (trade_id) DO NOTHING
                """, event.getTradeId(), appliedAt);
        jdbcTemplate.update("""
                UPDATE match_engine.trade_completion_view
                SET %s = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE trade_id = ?
                """.formatted(column), appliedAt, event.getTradeId());
        markCompletedIfReady(event.getTradeId());
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
                    updated_at = CURRENT_TIMESTAMP
                """, event.getTradeId(), settledAt, settledAt);
        markCompletedIfReady(event.getTradeId());
    }

    private void markCompletedIfReady(String tradeId) {
        jdbcTemplate.update("""
                UPDATE match_engine.trade_completion_view
                SET completed_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE trade_id = ?
                  AND completed_at IS NULL
                  AND trade_executed_at IS NOT NULL
                  AND buyer_order_applied_at IS NOT NULL
                  AND seller_order_applied_at IS NOT NULL
                  AND wallet_settled_at IS NOT NULL
                """, tradeId);
    }
}
