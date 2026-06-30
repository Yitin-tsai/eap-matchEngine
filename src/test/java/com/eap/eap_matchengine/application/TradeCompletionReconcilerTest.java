package com.eap.eap_matchengine.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeCompletionReconcilerTest {

    private final TradeCompletionService tradeCompletionService = mock(TradeCompletionService.class);
    private final TradeCompletionMetrics metrics = mock(TradeCompletionMetrics.class);

    @Test
    void reconcile_shouldRequeueDelayedTradeExecuted() {
        TradeCompletionService.DelayedTradeCompletion delayed =
                new TradeCompletionService.DelayedTradeCompletion(
                        "trade-1",
                        LocalDateTime.now().minusMinutes(5),
                        "BUYER_ORDER_APPLIED,WALLET_SETTLED",
                        0);
        when(tradeCompletionService.findDelayedCompletions(any(), anyInt()))
                .thenReturn(List.of(delayed));
        when(tradeCompletionService.markDelayedAndRequeueTradeExecuted(delayed)).thenReturn(true);

        reconciler().reconcile();

        verify(tradeCompletionService).backfillMissingCompletionRows();
        verify(tradeCompletionService).completeReadyRows();
        verify(metrics).delayedDetected();
        verify(metrics).repairRequeued();
    }

    @Test
    void reconcile_withoutDelayedRows_shouldNotRequeue() {
        when(tradeCompletionService.findDelayedCompletions(any(), anyInt()))
                .thenReturn(List.of());

        reconciler().reconcile();

        verify(tradeCompletionService).backfillMissingCompletionRows();
        verify(tradeCompletionService).completeReadyRows();
        verify(tradeCompletionService, never()).markDelayedAndRequeueTradeExecuted(any());
        verify(metrics, never()).repairRequeued();
    }

    private TradeCompletionReconciler reconciler() {
        return new TradeCompletionReconciler(tradeCompletionService, metrics, 30, 100);
    }
}
