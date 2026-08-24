package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderCancellationRequestedEvent;
import com.eap.common.event.OrderCancellationResultEvent;
import com.eap.common.event.OrderConfirmedEvent;
import com.eap.eap_matchengine.configuration.repository.TradeExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class OrderCancellationCoordinatorTest {

    private static final UUID CANCELLATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000702");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000703");
    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 8, 21, 10, 0);

    @Mock
    private RedisOrderBookService orderBook;
    @Mock
    private IncomingOrderProcessingStore processingStore;
    @Mock
    private OrderCancellationDecisionStore decisions;
    @Mock
    private TradeExecutionRepository trades;

    private OrderCancellationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new OrderCancellationCoordinator(orderBook, processingStore, decisions, trades);
    }

    @Test
    void cancellationBeforeOrderConfirmed_shouldRemainPendingBehindRedisIntent() {
        OrderCancellationDecisionStore.Decision pending = pending(null);
        when(decisions.begin(request(), null)).thenReturn(pending);
        when(decisions.find(CANCELLATION_ID)).thenReturn(pending);
        when(orderBook.findOpenOrder(ORDER_ID)).thenReturn(null);

        coordinator.request(request());

        InOrder sequence = inOrder(decisions, orderBook);
        sequence.verify(decisions).begin(request(), null);
        sequence.verify(orderBook).recordCancellationIntent(ORDER_ID, CANCELLATION_ID);
        verify(decisions, never()).complete(
                pending, OrderCancellationResultEvent.NOT_OPEN, null, null, null);
    }

    @Test
    void visibleRemainder_whenLuaRemovalWins_shouldCompleteWithExactRemovedSnapshot() {
        OrderConfirmedEvent visible = openOrder(3);
        OrderCancellationDecisionStore.Decision pending = pending(visible);
        when(decisions.begin(request(), null)).thenReturn(pending(null));
        when(decisions.find(CANCELLATION_ID)).thenReturn(pending(null));
        when(orderBook.findOpenOrder(ORDER_ID)).thenReturn(visible);
        when(decisions.refreshSnapshot(CANCELLATION_ID, visible)).thenReturn(pending);
        when(orderBook.arbitrateCancellation(visible, CANCELLATION_ID))
                .thenReturn(new RedisOrderBookService.CancellationArbitration(
                        RedisOrderBookService.CancellationOutcome.CANCELLED,
                        visible));

        coordinator.request(request());

        verify(decisions).complete(
                pending, OrderCancellationResultEvent.CANCELLED, null, visible, 5);
    }

    @Test
    void orderReservedByMatching_shouldKeepCancellationPending() {
        OrderConfirmedEvent snapshot = openOrder(5);
        OrderCancellationDecisionStore.Decision pending = pending(snapshot);
        when(decisions.begin(request(), null)).thenReturn(pending);
        when(decisions.find(CANCELLATION_ID)).thenReturn(pending);
        when(orderBook.findOpenOrder(ORDER_ID)).thenReturn(snapshot);
        when(decisions.refreshSnapshot(CANCELLATION_ID, snapshot)).thenReturn(pending);
        when(orderBook.arbitrateCancellation(snapshot, CANCELLATION_ID))
                .thenReturn(new RedisOrderBookService.CancellationArbitration(
                        RedisOrderBookService.CancellationOutcome.NOT_OPEN,
                        null));
        when(processingStore.isReserved(ORDER_ID)).thenReturn(true);

        coordinator.request(request());

        verify(decisions, never()).complete(
                pending, OrderCancellationResultEvent.ALREADY_MATCHED, null, null, null);
    }

    @Test
    void redisCancellationMarker_shouldHealCrashBeforeDecisionCommit() {
        OrderConfirmedEvent snapshot = openOrder(5);
        OrderCancellationDecisionStore.Decision pending = pending(snapshot);
        when(decisions.begin(request(), null)).thenReturn(pending);
        when(decisions.find(CANCELLATION_ID)).thenReturn(pending);
        when(orderBook.findOpenOrder(ORDER_ID)).thenReturn(null);
        when(orderBook.arbitrateCancellation(snapshot, CANCELLATION_ID))
                .thenReturn(new RedisOrderBookService.CancellationArbitration(
                        RedisOrderBookService.CancellationOutcome.ALREADY_CANCELLED_BY_REQUEST,
                        snapshot));

        coordinator.request(request());

        verify(decisions).complete(
                pending, OrderCancellationResultEvent.CANCELLED, null, snapshot, 5);
    }

    @Test
    void partialMatchRemainderReappearingAfterReservation_shouldBeCancelled() {
        OrderConfirmedEvent reservedSnapshot = openOrder(5);
        OrderConfirmedEvent remainder = openOrder(3);
        OrderCancellationDecisionStore.Decision beforeMatch = pending(reservedSnapshot);
        OrderCancellationDecisionStore.Decision afterRemainder = pending(remainder);
        when(decisions.begin(request(), null)).thenReturn(beforeMatch);
        when(decisions.find(CANCELLATION_ID)).thenReturn(beforeMatch);
        when(orderBook.findOpenOrder(ORDER_ID)).thenReturn(reservedSnapshot, remainder);
        when(decisions.refreshSnapshot(CANCELLATION_ID, reservedSnapshot)).thenReturn(beforeMatch);
        when(decisions.refreshSnapshot(CANCELLATION_ID, remainder)).thenReturn(afterRemainder);
        when(orderBook.arbitrateCancellation(reservedSnapshot, CANCELLATION_ID))
                .thenReturn(new RedisOrderBookService.CancellationArbitration(
                        RedisOrderBookService.CancellationOutcome.NOT_OPEN,
                        null));
        when(orderBook.arbitrateCancellation(remainder, CANCELLATION_ID))
                .thenReturn(new RedisOrderBookService.CancellationArbitration(
                        RedisOrderBookService.CancellationOutcome.CANCELLED,
                        remainder));
        when(processingStore.isReserved(ORDER_ID)).thenReturn(false);

        coordinator.request(request());

        verify(orderBook, times(2)).findOpenOrder(ORDER_ID);
        verify(decisions).complete(
                afterRemainder, OrderCancellationResultEvent.CANCELLED, null, remainder, 5);
    }

    @Test
    void fullyMatchedOrder_shouldRejectOnlyAfterCompletedAdmissionAndDurableTrade() {
        OrderConfirmedEvent snapshot = openOrder(5);
        OrderCancellationDecisionStore.Decision pending = pending(snapshot);
        when(decisions.begin(request(), null)).thenReturn(pending);
        when(decisions.find(CANCELLATION_ID)).thenReturn(pending);
        when(orderBook.findOpenOrder(ORDER_ID)).thenReturn(null);
        when(orderBook.arbitrateCancellation(snapshot, CANCELLATION_ID))
                .thenReturn(new RedisOrderBookService.CancellationArbitration(
                        RedisOrderBookService.CancellationOutcome.NOT_OPEN,
                        null));
        when(processingStore.isReserved(ORDER_ID)).thenReturn(false);
        when(trades.sumQuantityByBuyerOrderId(ORDER_ID)).thenReturn(5L);

        coordinator.request(request());

        verify(decisions).complete(
                pending,
                OrderCancellationResultEvent.ALREADY_MATCHED,
                "Order has already participated in a durable trade and has no open remainder",
                null,
                null);
    }

    @Test
    void pendingWithoutSnapshot_whenAdmissionIsStillProcessing_shouldWait() {
        OrderCancellationDecisionStore.Decision pending = pending(null);
        when(decisions.begin(request(), null)).thenReturn(pending);
        when(decisions.find(CANCELLATION_ID)).thenReturn(pending);
        when(processingStore.state(ORDER_ID)).thenReturn(
                IncomingOrderProcessingStore.State.processing("active", System.currentTimeMillis()));

        coordinator.request(request());

        verifyNoInteractions(trades);
        verify(decisions, never()).complete(
                pending, OrderCancellationResultEvent.ALREADY_MATCHED, null, null, null);
    }

    @Test
    void reconciliation_whenAdmissionStillProcessing_shouldReleaseLeaseWithBackoff() {
        OrderCancellationDecisionStore.Decision claimed = claimed(null, 1);
        when(decisions.claimRetryable(eq(50), anyString(), eq(30_000L)))
                .thenReturn(List.of(claimed));
        when(decisions.find(CANCELLATION_ID)).thenReturn(claimed);
        when(orderBook.findOpenOrder(ORDER_ID)).thenReturn(null);
        when(processingStore.state(ORDER_ID)).thenReturn(
                IncomingOrderProcessingStore.State.processing("active", System.currentTimeMillis()));

        coordinator.reconcilePending();

        verify(decisions).reschedule(eq(claimed), anyString(), isNull(), eq(250L));
    }

    @Test
    void reconciliation_whenRedisFails_shouldReleaseLeaseAndRecordFailure() {
        OrderCancellationDecisionStore.Decision claimed = claimed(null, 2);
        RuntimeException failure = new RuntimeException("redis unavailable");
        when(decisions.claimRetryable(eq(50), anyString(), eq(30_000L)))
                .thenReturn(List.of(claimed));
        org.mockito.Mockito.doThrow(failure)
                .when(orderBook).recordCancellationIntent(ORDER_ID, CANCELLATION_ID);

        coordinator.reconcilePending();

        verify(decisions).reschedule(eq(claimed), anyString(), eq(failure), eq(500L));
    }

    @Test
    void pendingWithoutSnapshot_whenAdmissionCompletedWithTrade_shouldRejectAsAlreadyMatched() {
        OrderCancellationDecisionStore.Decision pending = pending(null);
        when(decisions.begin(request(), null)).thenReturn(pending);
        when(decisions.find(CANCELLATION_ID)).thenReturn(pending);
        when(trades.sumQuantityByBuyerOrderId(ORDER_ID)).thenReturn(5L);

        coordinator.request(request());

        verify(decisions).complete(
                pending,
                OrderCancellationResultEvent.ALREADY_MATCHED,
                "Order has already participated in a durable trade and has no open remainder",
                null,
                null);
    }

    @Test
    void pendingIntentSeenByAdmission_shouldCancelOriginalOrderBeforeMatching() {
        OrderConfirmedEvent order = openOrder(5);
        OrderCancellationDecisionStore.Decision pending = pending(null);
        when(decisions.findByOrderId(ORDER_ID)).thenReturn(pending);

        coordinator.resolveAdmissionBlockedByCancellationIntent(order);

        verify(decisions).complete(
                pending, OrderCancellationResultEvent.CANCELLED, null, order, order.getAmount());
    }

    @Test
    void completedPreAdmissionCancellation_shouldBeIdempotentSoMarkerCanHeal() {
        OrderConfirmedEvent order = openOrder(5);
        OrderCancellationDecisionStore.Decision completed = new OrderCancellationDecisionStore.Decision(
                CANCELLATION_ID,
                ORDER_ID,
                USER_ID,
                OrderCancellationResultEvent.CANCELLED,
                null,
                order.getMarketId(),
                order.getOrderType(),
                order.getMarketSequence(),
                order.getPrice(),
                order.getAmount(),
                order.getAmount(),
                order.getCreatedAt(),
                REQUESTED_AT,
                REQUESTED_AT.plusSeconds(1),
                1);
        when(decisions.findByOrderId(ORDER_ID)).thenReturn(completed);

        coordinator.resolveAdmissionBlockedByCancellationIntent(order);

        verify(decisions, never()).complete(
                completed, OrderCancellationResultEvent.CANCELLED, null, order, order.getAmount());
    }

    @Test
    void completedCancellationIntentRetry_shouldToleratePostgresTimestampPrecision() {
        OrderConfirmedEvent order = openOrder(5);
        order.setCreatedAt(order.getCreatedAt().plusNanos(789));
        OrderCancellationDecisionStore.Decision completed = new OrderCancellationDecisionStore.Decision(
                CANCELLATION_ID,
                ORDER_ID,
                USER_ID,
                OrderCancellationResultEvent.CANCELLED,
                null,
                order.getMarketId(),
                order.getOrderType(),
                order.getMarketSequence(),
                order.getPrice(),
                order.getAmount(),
                order.getAmount(),
                order.getCreatedAt().withNano(0),
                REQUESTED_AT,
                REQUESTED_AT.plusSeconds(1),
                1);
        when(decisions.findByOrderId(ORDER_ID)).thenReturn(completed);

        coordinator.resolveAdmissionBlockedByCancellationIntent(order);

        verify(decisions, never()).complete(
                completed, OrderCancellationResultEvent.CANCELLED, null, order, order.getAmount());
    }

    @Test
    void completedPreAdmissionCancellation_shouldRejectConflictingOriginalAmount() {
        OrderConfirmedEvent original = openOrder(5);
        OrderCancellationDecisionStore.Decision completed = new OrderCancellationDecisionStore.Decision(
                CANCELLATION_ID,
                ORDER_ID,
                USER_ID,
                OrderCancellationResultEvent.CANCELLED,
                null,
                original.getMarketId(),
                original.getOrderType(),
                original.getMarketSequence(),
                original.getPrice(),
                original.getAmount(),
                original.getAmount(),
                original.getCreatedAt(),
                REQUESTED_AT,
                REQUESTED_AT.plusSeconds(1),
                1);
        OrderConfirmedEvent conflicting = openOrder(5);
        conflicting.setAmount(6);
        when(decisions.findByOrderId(ORDER_ID)).thenReturn(completed);

        assertThatThrownBy(() -> coordinator.resolveAdmissionBlockedByCancellationIntent(conflicting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicts with OrderConfirmed identity");

        verify(decisions, never()).complete(
                completed, OrderCancellationResultEvent.CANCELLED, null, conflicting, conflicting.getAmount());
    }

    private OrderCancellationRequestedEvent request() {
        return OrderCancellationRequestedEvent.builder()
                .cancellationId(CANCELLATION_ID)
                .orderId(ORDER_ID)
                .userId(USER_ID)
                .originalAmount(5)
                .requestedAt(REQUESTED_AT)
                .build();
    }

    private OrderConfirmedEvent openOrder(int amount) {
        return OrderConfirmedEvent.builder()
                .orderId(ORDER_ID)
                .userId(USER_ID)
                .marketId("ENERGY-SPOT")
                .marketSequence(7L)
                .orderType("BUY")
                .price(100)
                .amount(amount)
                .createdAt(REQUESTED_AT.minusMinutes(1))
                .build();
    }

    private OrderCancellationDecisionStore.Decision pending(OrderConfirmedEvent order) {
        return new OrderCancellationDecisionStore.Decision(
                CANCELLATION_ID,
                ORDER_ID,
                USER_ID,
                "PENDING",
                null,
                order == null ? null : order.getMarketId(),
                order == null ? null : order.getOrderType(),
                order == null ? null : order.getMarketSequence(),
                order == null ? null : order.getPrice(),
                5,
                order == null ? null : order.getAmount(),
                order == null ? null : order.getCreatedAt(),
                REQUESTED_AT,
                null,
                0);
    }

    private OrderCancellationDecisionStore.Decision claimed(OrderConfirmedEvent order, int attemptCount) {
        OrderCancellationDecisionStore.Decision pending = pending(order);
        return new OrderCancellationDecisionStore.Decision(
                pending.cancellationId(),
                pending.orderId(),
                pending.userId(),
                "IN_PROGRESS",
                pending.reason(),
                pending.marketId(),
                pending.orderType(),
                pending.marketSequence(),
                pending.limitPrice(),
                pending.originalAmount(),
                pending.cancelledAmount(),
                pending.orderCreatedAt(),
                pending.requestedAt(),
                pending.decidedAt(),
                attemptCount);
    }

}
