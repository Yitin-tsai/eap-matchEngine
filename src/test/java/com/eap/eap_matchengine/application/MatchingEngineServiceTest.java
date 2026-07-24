package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
import com.eap.common.event.TradeExecutedEvent;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchingEngineServiceTest {

    private final RedisOrderBookService orderBookService = mock(RedisOrderBookService.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final TradeExecutionRecorder tradeExecutionRecorder = mock(TradeExecutionRecorder.class);
    private final MatchingEngineMetrics matchingEngineMetrics = mock(MatchingEngineMetrics.class);
    private final MatchingEngineService service = new MatchingEngineService(
            orderBookService,
            redissonClient,
            tradeExecutionRecorder,
            matchingEngineMetrics);

    @Test
    void tryMatch_whenRecorderDoesNotDeferCleanup_shouldCompleteReservedRestingOrder() throws Exception {
        OrderConfirmedEvent incomingBuy = order(
                "BUY",
                "00000000-0000-0000-0000-000000000021",
                "00000000-0000-0000-0000-000000000022",
                301L,
                1);
        OrderConfirmedEvent restingSell = order(
                "SELL",
                "00000000-0000-0000-0000-000000000023",
                "00000000-0000-0000-0000-000000000024",
                300L,
                1);

        when(orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(incomingBuy))
                .thenReturn(RedisOrderBookService.MatchOrAddResult.matched(
                        new RedisOrderBookService.ReservedMatch(restingSell, 42L)));

        service.tryMatch(incomingBuy);

        verify(tradeExecutionRecorder).record(argThat(trade ->
                trade.getTradeId().equals("TEST-MARKET-42")
                        && trade.getBuyerOrderId().equals(incomingBuy.getOrderId())
                        && trade.getSellerOrderId().equals(restingSell.getOrderId())
                        && trade.getQuantity() == 1),
                argThat(task ->
                        task.tradeId().equals("TEST-MARKET-42")
                                && task.orderId().equals(restingSell.getOrderId())
                                && task.userId().equals(restingSell.getUserId())));
        verify(orderBookService).completeReservedOrder(restingSell);
        verify(orderBookService, never()).releaseReservedOrder(any());
        assertThat(incomingBuy.getAmount()).isZero();
        assertThat(restingSell.getAmount()).isZero();
    }

    @Test
    void tryMatch_whenRecorderDefersCleanup_shouldNotCompleteReservedRestingOrderSynchronously() throws Exception {
        OrderConfirmedEvent incomingBuy = order(
                "BUY",
                "00000000-0000-0000-0000-000000000121",
                "00000000-0000-0000-0000-000000000122",
                301L,
                1);
        OrderConfirmedEvent restingSell = order(
                "SELL",
                "00000000-0000-0000-0000-000000000123",
                "00000000-0000-0000-0000-000000000124",
                300L,
                1);

        when(orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(incomingBuy))
                .thenReturn(RedisOrderBookService.MatchOrAddResult.matched(
                        new RedisOrderBookService.ReservedMatch(restingSell, 43L)));
        when(tradeExecutionRecorder.record(any(TradeExecutedEvent.class), any(ReservationCleanupTask.class)))
                .thenReturn(true);

        service.tryMatch(incomingBuy);

        verify(tradeExecutionRecorder).record(any(TradeExecutedEvent.class), argThat(task ->
                task.tradeId().equals("TEST-MARKET-43")
                        && task.orderId().equals(restingSell.getOrderId())
                        && task.userId().equals(restingSell.getUserId())));
        verify(orderBookService, never()).completeReservedOrder(any());
        verify(orderBookService, never()).releaseReservedOrder(any());
        assertThat(incomingBuy.getAmount()).isZero();
        assertThat(restingSell.getAmount()).isZero();
    }

    @Test
    void tryMatch_whenTradePersistenceFailsAfterReservation_shouldReleaseRestingOrder() throws Exception {
        OrderConfirmedEvent incomingBuy = order(
                "BUY",
                "00000000-0000-0000-0000-000000000001",
                "00000000-0000-0000-0000-000000000002",
                101L,
                1);
        OrderConfirmedEvent restingSell = order(
                "SELL",
                "00000000-0000-0000-0000-000000000003",
                "00000000-0000-0000-0000-000000000004",
                100L,
                1);

        when(orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(incomingBuy))
                .thenReturn(RedisOrderBookService.MatchOrAddResult.matched(
                        new RedisOrderBookService.ReservedMatch(restingSell, 42L)));
        doThrow(new IllegalStateException("db unavailable"))
                .when(tradeExecutionRecorder).record(any(TradeExecutedEvent.class), any(ReservationCleanupTask.class));

        assertThatThrownBy(() -> service.tryMatch(incomingBuy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db unavailable");

        verify(orderBookService).releaseReservedOrder(argThat(order ->
                order.getOrderId().equals(restingSell.getOrderId())
                        && order.getAmount() == 1
                        && order.getOrderType().equals("SELL")));
        verify(orderBookService, never()).completeReservedOrder(any());
        assertThat(incomingBuy.getAmount()).isEqualTo(1);
        assertThat(restingSell.getAmount()).isEqualTo(1);
    }

    @Test
    void tryMatch_whenCombinedReservationFails_shouldNotRecordTrade() throws Exception {
        OrderConfirmedEvent incomingBuy = order(
                "BUY",
                "00000000-0000-0000-0000-000000000011",
                "00000000-0000-0000-0000-000000000012",
                201L,
                1);

        when(orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(incomingBuy))
                .thenThrow(new IllegalStateException("redis reservation unavailable"));

        assertThatThrownBy(() -> service.tryMatch(incomingBuy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis reservation unavailable");

        verify(tradeExecutionRecorder, never()).record(any());
        verify(orderBookService, never()).completeReservedOrder(any());
        verify(orderBookService, never()).releaseReservedOrder(any());
        assertThat(incomingBuy.getAmount()).isEqualTo(1);
    }

    @Test
    void tryMatch_whenNoMatch_shouldKeepOrderInRedisWithoutSecondAddCall() throws Exception {
        OrderConfirmedEvent incomingSell = order(
                "SELL",
                "00000000-0000-0000-0000-000000000031",
                "00000000-0000-0000-0000-000000000032",
                401L,
                1);

        when(orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(incomingSell))
                .thenReturn(RedisOrderBookService.MatchOrAddResult.added());

        service.tryMatch(incomingSell);

        verify(tradeExecutionRecorder, never()).record(any());
        verify(orderBookService, never()).addOrder(any());
        verify(orderBookService, never()).completeReservedOrder(any());
        verify(orderBookService, never()).releaseReservedOrder(any());
        verify(matchingEngineMetrics).orderAdded();
        assertThat(incomingSell.getAmount()).isEqualTo(1);
    }

    private OrderConfirmedEvent order(
            String side,
            String orderId,
            String userId,
            long marketSequence,
            int amount) {
        return OrderConfirmedEvent.builder()
                .orderId(UUID.fromString(orderId))
                .userId(UUID.fromString(userId))
                .marketId("TEST-MARKET")
                .marketSequence(marketSequence)
                .price(100)
                .amount(amount)
                .orderType(side)
                .createdAt(LocalDateTime.of(2026, 7, 14, 12, 0))
                .build();
    }
}
