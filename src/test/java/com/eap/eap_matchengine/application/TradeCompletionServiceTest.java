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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class TradeCompletionServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final TradeCompletionService service = new TradeCompletionService(jdbcTemplate);

    @Test
    void markOrderApplied_shouldAppendIdempotentMarkerOnly() {
        LocalDateTime appliedAt = LocalDateTime.of(2026, 7, 3, 9, 30);
        OrderTradeAppliedEvent event = OrderTradeAppliedEvent.builder()
                .tradeId("trade-1")
                .appliedAt(appliedAt)
                .build();

        service.markOrderApplied(event);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq("trade-1"), eq("ORDER_APPLIED"), eq(appliedAt));
        verifyNoMoreInteractions(jdbcTemplate);

        assertThat(sql.getValue())
                .contains("INSERT INTO match_engine.trade_completion_markers")
                .contains("(trade_id, marker_type, marker_at)")
                .contains("ON CONFLICT (trade_id, marker_type) DO NOTHING")
                .doesNotContain("trade_completion_view")
                .doesNotContain("DO UPDATE");
    }

    @Test
    void markWalletSettled_shouldAppendIdempotentMarkerOnly() {
        LocalDateTime settledAt = LocalDateTime.of(2026, 7, 3, 9, 32);
        WalletTradeSettledEvent event = WalletTradeSettledEvent.builder()
                .tradeId("trade-3")
                .settledAt(settledAt)
                .build();

        service.markWalletSettled(event);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq("trade-3"), eq("WALLET_SETTLED"), eq(settledAt));
        verifyNoMoreInteractions(jdbcTemplate);

        assertThat(sql.getValue())
                .contains("INSERT INTO match_engine.trade_completion_markers")
                .contains("(trade_id, marker_type, marker_at)")
                .contains("ON CONFLICT (trade_id, marker_type) DO NOTHING")
                .doesNotContain("trade_completion_view")
                .doesNotContain("DO UPDATE");
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

    @Test
    void markTradeExecuted_whenHotPathCompletionViewDisabled_shouldSkipViewUpsert() {
        TradeCompletionService disabledService = new TradeCompletionService(jdbcTemplate, false);
        TradeExecutedEvent event = TradeExecutedEvent.builder()
                .tradeId("trade-5")
                .occurredAt(LocalDateTime.of(2026, 7, 3, 9, 34))
                .build();

        disabledService.markTradeExecuted(event);

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void completeReadyRows_shouldProjectMarkersIntoCompletionView() {
        service.completeReadyRows();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture());
        verifyNoMoreInteractions(jdbcTemplate);

        assertThat(sql.getValue())
                .contains("WITH marker_state AS")
                .contains("FROM match_engine.trade_completion_markers")
                .contains("UPDATE match_engine.trade_completion_view view")
                .contains("HAVING COUNT(DISTINCT marker_type) = 2")
                .contains("completed_at = COALESCE");
    }
}
