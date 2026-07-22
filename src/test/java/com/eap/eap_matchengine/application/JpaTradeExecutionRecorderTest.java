package com.eap.eap_matchengine.application;

import com.eap.common.event.TradeExecutedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class JpaTradeExecutionRecorderTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final TradeCompletionService tradeCompletionService = mock(TradeCompletionService.class);
    private final MatchingEngineMetrics metrics = mock(MatchingEngineMetrics.class);
    private final JpaTradeExecutionRecorder recorder =
            new JpaTradeExecutionRecorder(jdbcTemplate, tradeCompletionService, metrics);

    @Test
    void record_shouldInsertTradeAndOutboxInOneStatementWithoutPreselect() {
        TradeExecutedEvent event = event("trade-1");
        when(jdbcTemplate.update(contains("INSERT INTO match_engine.trade_executions"), any(Object[].class)))
                .thenReturn(1);

        recorder.record(event);

        verify(jdbcTemplate).update(contains("INSERT INTO match_engine.trade_executions"), any(Object[].class));
        verify(tradeCompletionService).markTradeExecuted(event);
        verify(metrics).recordTradeRecordSerialize(any(Duration.class));
        verify(metrics).recordTradeRecordInsert(any(Duration.class));
        verify(metrics).recordTradeRecordCompletionMark(any(Duration.class));
        verify(metrics).recordTradeRecordTransactionBody(any(Duration.class));
        verify(metrics).recordTradeRecordTransactionTotal(any(Duration.class));
        verify(metrics).recordTradeRecordCommitGap(any(Duration.class));
        verifyNoMoreInteractions(tradeCompletionService);
    }

    @Test
    void record_whenTradeAlreadyExists_shouldFailBeforeOutboxInsert() {
        TradeExecutedEvent event = event("trade-duplicate");
        when(jdbcTemplate.update(contains("INSERT INTO match_engine.trade_executions"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> recorder.record(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Trade already executed")
                .hasMessageContaining("trade-duplicate");

        verify(jdbcTemplate).update(contains("INSERT INTO match_engine.trade_executions"), any(Object[].class));
        verify(tradeCompletionService, never()).markTradeExecuted(any());
        verify(metrics).recordTradeRecordSerialize(any(Duration.class));
        verify(metrics).recordTradeRecordInsert(any(Duration.class));
        verify(metrics, never()).recordTradeRecordCompletionMark(any(Duration.class));
        verify(metrics).recordTradeRecordTransactionBody(any(Duration.class));
        verify(metrics).recordTradeRecordTransactionTotal(any(Duration.class));
        verify(metrics).recordTradeRecordCommitGap(any(Duration.class));
    }

    @Test
    void record_shouldInsertTradeExecutedOutboxMetadataWithoutDuplicatingPayload() {
        TradeExecutedEvent event = event("trade-payload");
        when(jdbcTemplate.update(contains("INSERT INTO match_engine.trade_executions"), any(Object[].class)))
                .thenReturn(1);

        recorder.record(event);

        verify(jdbcTemplate).update(
                contains("INSERT INTO match_engine.trade_executions"),
                eq("trade-payload"),
                eq(1L),
                eq(1L),
                eq("M"),
                eq(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                eq(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                eq(UUID.fromString("00000000-0000-0000-0000-000000000003")),
                eq(UUID.fromString("00000000-0000-0000-0000-000000000004")),
                eq(10L),
                eq(20L),
                eq(100),
                eq(90),
                eq(90),
                eq(1),
                eq(LocalDateTime.of(2026, 7, 8, 12, 0)),
                eq("TradeExecutedEvent"),
                eq("TRADE"),
                eq("trade.executed"));
    }

    private TradeExecutedEvent event(String tradeId) {
        return TradeExecutedEvent.builder()
                .tradeId(tradeId)
                .sequence(1L)
                .legacyMatchId(1)
                .marketId("M")
                .buyerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .sellerId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .buyerOrderId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
                .sellerOrderId(UUID.fromString("00000000-0000-0000-0000-000000000004"))
                .buyerMarketSequence(10L)
                .sellerMarketSequence(20L)
                .originBuyerPrice(100)
                .originSellerPrice(90)
                .dealPrice(90)
                .quantity(1)
                .occurredAt(LocalDateTime.of(2026, 7, 8, 12, 0))
                .build();
    }
}
