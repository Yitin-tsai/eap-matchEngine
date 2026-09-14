package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderAssetReservationSucceededEvent;
import com.eap.eap_matchengine.configuration.repository.TradeExecutionRepository;
import com.eap.eap_matchengine.domain.entity.TradeExecutionEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private final ReservationReconciliationIssueStore issueStore =
            mock(ReservationReconciliationIssueStore.class);
    private final ReservationReconcilerMetrics metrics = mock(ReservationReconcilerMetrics.class);
    private final OrderBookRuntimeGuard runtimeGuard = mock(OrderBookRuntimeGuard.class);

    @BeforeEach
    void setUp() {
        when(issueStore.fingerprint(any())).thenAnswer(invocation -> {
            RedisOrderBookService.ReservationSnapshot reservation = invocation.getArgument(0);
            return reservation.key() + "|" + reservation.tradeId()
                    + "|" + reservation.reservedAtEpochMillis();
        });
        when(issueStore.findTerminalIssueIds(any())).thenReturn(Set.of());
        when(issueStore.claimRetryableAbsenceChecks(anyInt())).thenReturn(List.of());
    }

    @Test
    void recoveringRuntime_shouldNotScanOrMutateReservations() {
        when(runtimeGuard.isReady()).thenReturn(false);

        int actions = new ReservationReconciler(
                orderBookService,
                tradeExecutionRepository,
                cleanupTaskStore,
                issueStore,
                metrics,
                runtimeGuard,
                30,
                100,
                3).reconcileOnce();

        org.assertj.core.api.Assertions.assertThat(actions).isZero();
        verifyNoInteractions(orderBookService, tradeExecutionRepository, cleanupTaskStore, issueStore, metrics);
    }

    @Test
    void reconcileOnce_whenReservationHasNoDurableTradeAndIsOld_shouldReleaseOrder() throws Exception {
        OrderAssetReservationSucceededEvent order = order(1);
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
        OrderAssetReservationSucceededEvent order = order(1);
        TradeExecutionEntity trade = trade(order, 1);
        when(orderBookService.scanReservations(100))
                .thenReturn(List.of(RedisOrderBookService.ReservationSnapshot.valid(
                        "order:reservation:" + order.getOrderId(),
                        order,
                        1L,
                        TRADE_ID)));
        when(tradeExecutionRepository.findByTradeId(TRADE_ID)).thenReturn(Optional.of(trade));
        when(orderBookService.completeReservedOrder(order, TRADE_ID))
                .thenReturn(ReservationCompletionOutcome.COMPLETED);

        reconciler(30).reconcileOnce();

        verify(orderBookService).completeReservedOrder(order, TRADE_ID);
        verify(orderBookService, never()).releaseReservedOrder(any(), anyString());
        verify(metrics).completed();
    }

    @Test
    void reconcileOnce_whenReservationCompletionOwnershipConflicts_shouldNotReportCompletion() throws Exception {
        OrderAssetReservationSucceededEvent order = order(1);
        TradeExecutionEntity trade = trade(order, 1);
        when(orderBookService.scanReservations(100))
                .thenReturn(List.of(RedisOrderBookService.ReservationSnapshot.valid(
                        "order:reservation:" + order.getOrderId(),
                        order,
                        1L,
                        TRADE_ID)));
        when(tradeExecutionRepository.findByTradeId(TRADE_ID)).thenReturn(Optional.of(trade));
        when(orderBookService.completeReservedOrder(order, TRADE_ID))
                .thenReturn(ReservationCompletionOutcome.NEWER_TRADE_OWNER);

        reconciler(30).reconcileOnce();

        verify(metrics).failure();
        verify(metrics, never()).completed();
        verify(issueStore).recordTerminal(
                any(),
                eq("RESERVATION_OWNERSHIP_CONFLICT"),
                org.mockito.ArgumentMatchers.contains("NEWER_TRADE_OWNER"));
    }

    @Test
    void reconcileOnce_whenReservationHasFreshDurableTrade_shouldLeaveCleanupWorkerAsOwner() throws Exception {
        OrderAssetReservationSucceededEvent order = order(1);
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
        OrderAssetReservationSucceededEvent order = order(1);
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
        OrderAssetReservationSucceededEvent order = order(3);
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
        verify(issueStore).recordTerminal(
                any(), eq("INVALID_RESERVATION_PAYLOAD"), eq("bad json"));
        verify(orderBookService, never()).releaseReservedOrder(any(), anyString());
        verify(orderBookService, never()).completeReservedOrder(any(), anyString());
    }

    @Test
    void reconcileOnce_whenTerminalIssueAlreadyExists_shouldNotRetryPoisonReservation() {
        String key = "order:reservation:bad";
        RedisOrderBookService.ReservationSnapshot invalid =
                RedisOrderBookService.ReservationSnapshot.invalid(key, "bad json", "{bad");
        when(orderBookService.scanReservations(100)).thenReturn(List.of(invalid));
        String issueId = "terminal-fingerprint";
        when(issueStore.fingerprint(invalid)).thenReturn(issueId);
        when(issueStore.findTerminalIssueIds(Set.of(issueId))).thenReturn(Set.of(issueId));

        reconciler(30).reconcileOnce();

        verify(issueStore, never()).recordTerminal(any(), anyString(), anyString());
        verify(metrics, never()).invalid();
        verifyNoInteractions(tradeExecutionRepository, cleanupTaskStore);
    }

    @Test
    void reconcileOnce_terminalIdentityMustNotConsumeActionBudget() throws Exception {
        RedisOrderBookService.ReservationSnapshot terminal =
                RedisOrderBookService.ReservationSnapshot.invalid(
                        "order:reservation:terminal", "bad json", "{bad");
        OrderAssetReservationSucceededEvent order = order(1);
        RedisOrderBookService.ReservationSnapshot actionable =
                RedisOrderBookService.ReservationSnapshot.valid(
                        "order:reservation:actionable", order, 1L, TRADE_ID);
        when(orderBookService.scanReservations(1)).thenReturn(List.of(terminal, actionable));
        when(issueStore.fingerprint(terminal)).thenReturn("terminal-fingerprint");
        when(issueStore.fingerprint(actionable)).thenReturn("actionable-fingerprint");
        when(issueStore.findTerminalIssueIds(Set.of(
                "terminal-fingerprint", "actionable-fingerprint")))
                .thenReturn(Set.of("terminal-fingerprint"));
        when(tradeExecutionRepository.findByTradeId(TRADE_ID)).thenReturn(Optional.empty());

        new ReservationReconciler(
                orderBookService,
                tradeExecutionRepository,
                cleanupTaskStore,
                issueStore,
                metrics,
                0,
                1,
                3).reconcileOnce();

        verify(orderBookService).releaseReservedOrder(order, TRADE_ID);
        verify(issueStore, never()).recordTerminal(
                eq(terminal), anyString(), anyString());
    }

    @Test
    void reconcileOnce_activeCleanupMustNotConsumeActionBudget() throws Exception {
        OrderAssetReservationSucceededEvent cleanupOwned = order(1);
        OrderAssetReservationSucceededEvent actionable = order(1);
        actionable.setOrderId(UUID.fromString("00000000-0000-0000-0000-000000000103"));
        actionable.setUserId(UUID.fromString("00000000-0000-0000-0000-000000000104"));
        String actionableTradeId = "TEST-MARKET-2";
        RedisOrderBookService.ReservationSnapshot first =
                RedisOrderBookService.ReservationSnapshot.valid(
                        "order:reservation:cleanup", cleanupOwned, 1L, TRADE_ID);
        RedisOrderBookService.ReservationSnapshot second =
                RedisOrderBookService.ReservationSnapshot.valid(
                        "order:reservation:actionable", actionable, 2L, actionableTradeId);
        when(orderBookService.scanReservations(1)).thenReturn(List.of(first, second));
        when(cleanupTaskStore.findActiveTradeIds(Set.of(TRADE_ID, actionableTradeId)))
                .thenReturn(Set.of(TRADE_ID));
        when(tradeExecutionRepository.findByTradeId(actionableTradeId))
                .thenReturn(Optional.empty());

        new ReservationReconciler(
                orderBookService,
                tradeExecutionRepository,
                cleanupTaskStore,
                issueStore,
                metrics,
                0,
                1,
                3).reconcileOnce();

        verify(metrics).deferredToCleanup();
        verify(orderBookService).releaseReservedOrder(actionable, actionableTradeId);
        verify(orderBookService, never()).releaseReservedOrder(cleanupOwned, TRADE_ID);
    }

    @Test
    void reconcileOnce_whenReleaseFails_shouldPersistBoundedRetryState() throws Exception {
        OrderAssetReservationSucceededEvent order = order(1);
        RedisOrderBookService.ReservationSnapshot reservation =
                RedisOrderBookService.ReservationSnapshot.valid(
                        "order:reservation:" + order.getOrderId(), order, 1L, TRADE_ID);
        when(orderBookService.scanReservations(100)).thenReturn(List.of(reservation));
        when(tradeExecutionRepository.findByTradeId(TRADE_ID)).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new IllegalStateException("temporary Redis failure"))
                .when(orderBookService).releaseReservedOrder(order, TRADE_ID);
        when(issueStore.recordTransientFailure(
                eq(reservation), eq("TRANSIENT_ORPHAN_RELEASE"), any(), eq(3)))
                .thenReturn(new ReservationReconciliationIssueStore.FailureRecord(false, 1));

        reconciler(0).reconcileOnce();

        verify(issueStore).recordTransientFailure(
                eq(reservation), eq("TRANSIENT_ORPHAN_RELEASE"), any(), eq(3));
        verify(metrics).failure();
    }

    @Test
    void reconcileOnce_terminalOldReservationMustNotQuarantineNewIdentityOnSameKey() throws Exception {
        String key = "order:reservation:shared";
        OrderAssetReservationSucceededEvent order = order(1);
        RedisOrderBookService.ReservationSnapshot current =
                RedisOrderBookService.ReservationSnapshot.valid(key, order, 2L, "TEST-MARKET-2");
        when(orderBookService.scanReservations(100)).thenReturn(List.of(current));
        when(issueStore.fingerprint(current)).thenReturn("new-fingerprint");
        when(tradeExecutionRepository.findByTradeId("TEST-MARKET-2"))
                .thenReturn(Optional.empty());

        reconciler(0).reconcileOnce();

        verify(orderBookService).releaseReservedOrder(order, "TEST-MARKET-2");
    }

    @Test
    void reconcileOnce_durableTradeIdentityConflictMustNotMutateRedis() throws Exception {
        OrderAssetReservationSucceededEvent order = order(1);
        RedisOrderBookService.ReservationSnapshot reservation =
                RedisOrderBookService.ReservationSnapshot.valid(
                        "order:reservation:" + order.getOrderId(), order, 1L, TRADE_ID);
        TradeExecutionEntity unrelatedTrade = new TradeExecutionEntity(
                TRADE_ID,
                1L,
                1L,
                order.getMarketId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1L,
                2L,
                100,
                100,
                100,
                1,
                LocalDateTime.now());
        when(orderBookService.scanReservations(100)).thenReturn(List.of(reservation));
        when(tradeExecutionRepository.findByTradeId(TRADE_ID)).thenReturn(Optional.of(unrelatedTrade));

        reconciler(0).reconcileOnce();

        verify(issueStore).recordTerminal(
                eq(reservation),
                eq("DURABLE_TRADE_IDENTITY_CONFLICT"),
                org.mockito.ArgumentMatchers.contains(TRADE_ID));
        verify(orderBookService, never()).completeReservedOrder(any(), anyString());
        verify(orderBookService, never()).releaseReservedOrder(any(), anyString());
    }

    private ReservationReconciler reconciler(long orphanThresholdSeconds) {
        return new ReservationReconciler(
                orderBookService,
                tradeExecutionRepository,
                cleanupTaskStore,
                issueStore,
                metrics,
                orphanThresholdSeconds,
                100,
                3);
    }

    private OrderAssetReservationSucceededEvent order(int amount) {
        return OrderAssetReservationSucceededEvent.builder()
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

    private TradeExecutionEntity trade(OrderAssetReservationSucceededEvent order, int quantity) {
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
