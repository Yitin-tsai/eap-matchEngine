package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderTradeAppliedEvent;
import com.eap.common.event.TradeExecutedEvent;
import com.eap.common.event.WalletTradeSettledEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TradeCompletionServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final TradeCompletionService service =
            new TradeCompletionService(jdbcTemplate, TradeCompletionMarkerMetrics.noop(), true);

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
    void markOrderAppliedBatch_shouldBatchAppendIdempotentMarkersOnly() throws Exception {
        LocalDateTime appliedAt1 = LocalDateTime.of(2026, 7, 3, 9, 30);
        LocalDateTime appliedAt2 = LocalDateTime.of(2026, 7, 3, 9, 31);
        OrderTradeAppliedEvent event1 = OrderTradeAppliedEvent.builder()
                .tradeId("trade-1")
                .appliedAt(appliedAt1)
                .build();
        OrderTradeAppliedEvent event2 = OrderTradeAppliedEvent.builder()
                .tradeId("trade-2")
                .appliedAt(appliedAt2)
                .build();

        service.markOrderAppliedBatch(List.of(event1, event2));

        ArgumentCaptor<ConnectionCallback<Integer>> callback = connectionCallbackCaptor();
        verify(jdbcTemplate).execute(callback.capture());
        verifyNoMoreInteractions(jdbcTemplate);

        executeAndVerifyMarkerBatchCallback(
                callback.getValue(),
                new String[] {"trade-1", "trade-2"},
                new String[] {"ORDER_APPLIED", "ORDER_APPLIED"},
                new Timestamp[] {Timestamp.valueOf(appliedAt1), Timestamp.valueOf(appliedAt2)});
    }

    @Test
    void markWalletSettledBatch_shouldBatchAppendIdempotentMarkersOnly() throws Exception {
        LocalDateTime settledAt1 = LocalDateTime.of(2026, 7, 3, 9, 32);
        LocalDateTime settledAt2 = LocalDateTime.of(2026, 7, 3, 9, 33);
        WalletTradeSettledEvent event1 = WalletTradeSettledEvent.builder()
                .tradeId("trade-3")
                .settledAt(settledAt1)
                .build();
        WalletTradeSettledEvent event2 = WalletTradeSettledEvent.builder()
                .tradeId("trade-4")
                .settledAt(settledAt2)
                .build();

        service.markWalletSettledBatch(List.of(event1, event2));

        ArgumentCaptor<ConnectionCallback<Integer>> callback = connectionCallbackCaptor();
        verify(jdbcTemplate).execute(callback.capture());
        verifyNoMoreInteractions(jdbcTemplate);

        executeAndVerifyMarkerBatchCallback(
                callback.getValue(),
                new String[] {"trade-3", "trade-4"},
                new String[] {"WALLET_SETTLED", "WALLET_SETTLED"},
                new Timestamp[] {Timestamp.valueOf(settledAt1), Timestamp.valueOf(settledAt2)});
    }

    @Test
    void markOrderAppliedBatch_shouldRecordMarkerMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TradeCompletionMarkerMetrics metrics = new TradeCompletionMarkerMetrics(registry);
        TradeCompletionService meteredService = new TradeCompletionService(jdbcTemplate, metrics, true);
        OrderTradeAppliedEvent event1 = OrderTradeAppliedEvent.builder()
                .tradeId("trade-1")
                .appliedAt(LocalDateTime.of(2026, 7, 3, 9, 30))
                .build();
        OrderTradeAppliedEvent event2 = OrderTradeAppliedEvent.builder()
                .tradeId("trade-2")
                .appliedAt(LocalDateTime.of(2026, 7, 3, 9, 31))
                .build();

        meteredService.markOrderAppliedBatch(List.of(event1, event2));

        verify(jdbcTemplate).execute(any(ConnectionCallback.class));
        assertThat(registry.get("match_engine_trade_completion_marker_batches")
                .tag("marker_type", "ORDER_APPLIED")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(registry.get("match_engine_trade_completion_marker_events")
                .tag("marker_type", "ORDER_APPLIED")
                .counter()
                .count()).isEqualTo(2.0);
        assertThat(registry.get("match_engine_trade_completion_marker_insert_duration")
                .tag("marker_type", "ORDER_APPLIED")
                .timer()
                .count()).isEqualTo(1);
        metrics.recordListener("ORDER_APPLIED", java.time.Duration.ofMillis(5));
        assertThat(registry.get("match_engine_trade_completion_marker_listener_duration")
                .tag("marker_type", "ORDER_APPLIED")
                .timer()
                .count()).isEqualTo(1);
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
        TradeCompletionService disabledService =
                new TradeCompletionService(jdbcTemplate, TradeCompletionMarkerMetrics.noop(), false);
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

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<ConnectionCallback<Integer>> connectionCallbackCaptor() {
        return ArgumentCaptor.forClass(ConnectionCallback.class);
    }

    private void executeAndVerifyMarkerBatchCallback(
            ConnectionCallback<Integer> callback,
            String[] expectedTradeIds,
            String[] expectedMarkerTypes,
            Timestamp[] expectedMarkerTimes) throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        Array tradeIds = mock(Array.class);
        Array markerTypes = mock(Array.class);
        Array markerTimes = mock(Array.class);

        when(connection.prepareStatement(contains("FROM unnest(?::varchar[], ?::varchar[], ?::timestamp[])")))
                .thenReturn(statement);
        when(connection.createArrayOf(eq("varchar"), eq(expectedTradeIds))).thenReturn(tradeIds);
        when(connection.createArrayOf(eq("varchar"), eq(expectedMarkerTypes))).thenReturn(markerTypes);
        when(connection.createArrayOf(eq("timestamp"), eq(expectedMarkerTimes))).thenReturn(markerTimes);
        when(statement.executeUpdate()).thenReturn(expectedTradeIds.length);

        assertThat(callback.doInConnection(connection)).isEqualTo(expectedTradeIds.length);

        verify(statement).setArray(1, tradeIds);
        verify(statement).setArray(2, markerTypes);
        verify(statement).setArray(3, markerTimes);
        verify(statement).executeUpdate();
        verify(tradeIds).free();
        verify(markerTypes).free();
        verify(markerTimes).free();
    }
}
