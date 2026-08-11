package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
import com.eap.eap_matchengine.configuration.repository.TradeExecutionRepository;
import com.eap.eap_matchengine.domain.entity.TradeExecutionEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReservationReconcilerTest {

    private static final String TRADE_ID = "TEST-MARKET-1";

    private final RedisOrderBookService orderBookService = mock(RedisOrderBookService.class);
    private final TradeExecutionRepository tradeExecutionRepository = mock(TradeExecutionRepository.class);
    private final ReservationCleanupTaskStore cleanupTaskStore = mock(ReservationCleanupTaskStore.class);
    private final ReservationReconcilerMetrics metrics = mock(ReservationReconcilerMetrics.class);

    @Test
    void reconcileOnce_whenReservationHasNoDurableTradeAndIsOld_shouldReleaseOrder() throws Exception {
        OrderConfirmedEvent order = order(1);
        when(orderBookService.scanReservations(100))
                .thenReturn(List.of(RedisOrderBookService.ReservationSnapshot.valid(
                        "order:reservation:" + order.getOrderId(),
                        order,
                        1L,
                        TRADE_ID)));
        when(tradeExecutionRepository.findByTradeId(TRADE_ID)).thenReturn(Optional.empty());

        reconciler(0).reconcileOnce();

        verify(orderBookService).releaseReservedOrder(order, TRADE_ID);
        verify(orderBookService, never()).completeReservedOrder(any(), anyString());
        verify(metrics).released();
    }

    @Test
    void reconcileOnce_whenReservationHasFullDurableTrade_shouldCompleteReservation() throws Exception {
        OrderConfirmedEvent order = order(1);
        TradeExecutionEntity trade = trade(order, 1);
        when(orderBookService.scanReservations(100))
                .thenReturn(List.of(RedisOrderBookService.ReservationSnapshot.valid(
                        "order:reservation:" + order.getOrderId(),
                        order,
                        1L,
                        TRADE_ID)));
        when(tradeExecutionRepository.findByTradeId(TRADE_ID)).thenReturn(Optional.of(trade));

        reconciler(30).reconcileOnce();

        verify(orderBookService).completeReservedOrder(order, TRADE_ID);
        verify(orderBookService, never()).releaseReservedOrder(any(), anyString());
        verify(metrics).completed();
    }

    @Test
    void reconcileOnce_whenReservationHasFreshDurableTrade_shouldLeaveCleanupWorkerAsOwner() throws Exception {
        OrderConfirmedEvent order = order(1);
        when(orderBookService.scanReservations(100))
                .thenReturn(List.of(RedisOrderBookService.ReservationSnapshot.valid(
                        "order:reservation:" + order.getOrderId(),
                        order,
                        System.currentTimeMillis(),
                        TRADE_ID)));
        reconciler(30).reconcileOnce();

        verify(orderBookService, never()).completeReservedOrder(any(), anyString());
        verify(orderBookService, never()).releaseReservedOrder(any(), anyString());
        verifyNoInteractions(tradeExecutionRepository);
        verifyNoInteractions(cleanupTaskStore);
    }

    @Test
    void reconcileOnce_whenActiveCleanupTaskOwnsReservation_shouldDeferRecovery() throws Exception {
        OrderConfirmedEvent order = order(1);
        when(orderBookService.scanReservations(100))
                .thenReturn(List.of(RedisOrderBookService.ReservationSnapshot.valid(
                        "order:reservation:" + order.getOrderId(),
                        order,
                        1L,
                        TRADE_ID)));
        when(cleanupTaskStore.findActiveTradeIds(Set.of(TRADE_ID))).thenReturn(Set.of(TRADE_ID));

        reconciler(30).reconcileOnce();

        verify(metrics).deferredToCleanup();
        verifyNoInteractions(tradeExecutionRepository);
        verify(orderBookService, never()).completeReservedOrder(any(), anyString());
        verify(orderBookService, never()).releaseReservedOrder(any(), anyString());
    }

    @Test
    void reconcileOnce_whenReservationHasPartialDurableTrade_shouldReleaseRemainingAmount() throws Exception {
        OrderConfirmedEvent order = order(3);
        TradeExecutionEntity trade = trade(order, 1);
        when(orderBookService.scanReservations(100))
                .thenReturn(List.of(RedisOrderBookService.ReservationSnapshot.valid(
                        "order:reservation:" + order.getOrderId(),
                        order,
                        1L,
                        TRADE_ID)));
        when(tradeExecutionRepository.findByTradeId(TRADE_ID)).thenReturn(Optional.of(trade));

        reconciler(30).reconcileOnce();

        verify(orderBookService).releaseReservedOrder(org.mockito.ArgumentMatchers.argThat(released ->
                released.getOrderId().equals(order.getOrderId()) && released.getAmount() == 2), eq(TRADE_ID));
        verify(orderBookService, never()).completeReservedOrder(any(), anyString());
        verify(metrics).released();
    }

    @Test
    void reconcileOnce_whenReservationPayloadIsInvalid_shouldRecordInvalidMetric() throws Exception {
        when(orderBookService.scanReservations(100))
                .thenReturn(List.of(RedisOrderBookService.ReservationSnapshot.invalid(
                        "order:reservation:bad",
                        "bad json")));

        reconciler(30).reconcileOnce();

        verify(metrics).invalid();
        verify(orderBookService, never()).releaseReservedOrder(any(), anyString());
        verify(orderBookService, never()).completeReservedOrder(any(), anyString());
    }

    private ReservationReconciler reconciler(long orphanThresholdSeconds) {
        return new ReservationReconciler(
                orderBookService,
                tradeExecutionRepository,
                cleanupTaskStore,
                metrics,
                orphanThresholdSeconds,
                100);
    }

    private OrderConfirmedEvent order(int amount) {
        return OrderConfirmedEvent.builder()
                .orderId(UUID.fromString("00000000-0000-0000-0000-000000000101"))
                .userId(UUID.fromString("00000000-0000-0000-0000-000000000102"))
                .marketId("TEST-MARKET")
                .marketSequence(1L)
                .price(100)
                .amount(amount)
                .orderType("SELL")
                .createdAt(LocalDateTime.of(2026, 7, 17, 10, 0))
                .build();
    }

    private TradeExecutionEntity trade(OrderConfirmedEvent order, int quantity) {
        return new TradeExecutionEntity(
                "TEST-MARKET-1",
                1L,
                1L,
                order.getMarketId(),
                UUID.fromString("00000000-0000-0000-0000-000000000201"),
                order.getUserId(),
                UUID.fromString("00000000-0000-0000-0000-000000000202"),
                order.getOrderId(),
                2L,
                order.getMarketSequence(),
                100,
                order.getPrice(),
                order.getPrice(),
                quantity,
                LocalDateTime.of(2026, 7, 17, 10, 0, 1));
    }
}
