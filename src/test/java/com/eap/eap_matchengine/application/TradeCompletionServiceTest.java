package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderTradeAppliedEvent;
import com.eap.common.event.TradeExecutedEvent;
import com.eap.common.event.WalletTradeSettledEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class TradeCompletionServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final TradeCompletionService service = new TradeCompletionService(jdbcTemplate);

    @Test
    void markOrderApplied_shouldUseSingleUpsertForBuyerMarker() {
        LocalDateTime appliedAt = LocalDateTime.of(2026, 7, 3, 9, 30);
        OrderTradeAppliedEvent event = OrderTradeAppliedEvent.builder()
                .tradeId("trade-1")
                .side("BUY")
                .appliedAt(appliedAt)
                .build();

        service.markOrderApplied(event);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq("trade-1"), eq(appliedAt), eq(appliedAt));
        verifyNoMoreInteractions(jdbcTemplate);

        assertThat(sql.getValue())
                .contains("INSERT INTO match_engine.trade_completion_view")
                .contains("(trade_id, trade_executed_at, buyer_order_applied_at, updated_at)")
                .contains("ON CONFLICT (trade_id) DO UPDATE")
                .contains("SET buyer_order_applied_at = EXCLUDED.buyer_order_applied_at")
                .contains("match_engine.trade_completion_view.seller_order_applied_at IS NOT NULL")
                .contains("match_engine.trade_completion_view.wallet_settled_at IS NOT NULL")
                .doesNotContain("ON CONFLICT (trade_id) DO NOTHING")
                .doesNotContain("WHERE trade_id = ?");
    }

    @Test
    void markOrderApplied_shouldUseSingleUpsertForSellerMarker() {
        LocalDateTime appliedAt = LocalDateTime.of(2026, 7, 3, 9, 31);
        OrderTradeAppliedEvent event = OrderTradeAppliedEvent.builder()
                .tradeId("trade-2")
                .side("SELL")
                .appliedAt(appliedAt)
                .build();

        service.markOrderApplied(event);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq("trade-2"), eq(appliedAt), eq(appliedAt));
        verifyNoMoreInteractions(jdbcTemplate);

        assertThat(sql.getValue())
                .contains("(trade_id, trade_executed_at, seller_order_applied_at, updated_at)")
                .contains("SET seller_order_applied_at = EXCLUDED.seller_order_applied_at")
                .contains("match_engine.trade_completion_view.buyer_order_applied_at IS NOT NULL")
                .contains("ON CONFLICT (trade_id) DO UPDATE")
                .doesNotContain("ON CONFLICT (trade_id) DO NOTHING")
                .doesNotContain("WHERE trade_id = ?");
    }

    @Test
    void markWalletSettled_shouldCompleteInSameUpsert() {
        LocalDateTime settledAt = LocalDateTime.of(2026, 7, 3, 9, 32);
        WalletTradeSettledEvent event = WalletTradeSettledEvent.builder()
                .tradeId("trade-3")
                .settledAt(settledAt)
                .build();

        service.markWalletSettled(event);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq("trade-3"), eq(settledAt), eq(settledAt));
        verifyNoMoreInteractions(jdbcTemplate);

        assertThat(sql.getValue())
                .contains("ON CONFLICT (trade_id) DO UPDATE")
                .contains("SET wallet_settled_at = EXCLUDED.wallet_settled_at")
                .contains("completed_at = CASE")
                .contains("buyer_order_applied_at IS NOT NULL")
                .contains("seller_order_applied_at IS NOT NULL")
                .doesNotContain("WHERE trade_id = ?");
    }

    @Test
    void markTradeExecuted_shouldRemainSingleUpsert() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 3, 9, 33);
        TradeExecutedEvent event = TradeExecutedEvent.builder()
                .tradeId("trade-4")
                .occurredAt(occurredAt)
                .build();

        service.markTradeExecuted(event);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq("trade-4"), eq(occurredAt));
        verifyNoMoreInteractions(jdbcTemplate);

        assertThat(sql.getValue())
                .contains("INSERT INTO match_engine.trade_completion_view")
                .contains("ON CONFLICT (trade_id) DO UPDATE")
                .contains("SET trade_executed_at = EXCLUDED.trade_executed_at");
    }
}
