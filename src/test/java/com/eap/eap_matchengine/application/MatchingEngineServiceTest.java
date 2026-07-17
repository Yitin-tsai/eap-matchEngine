package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
import com.eap.common.event.TradeExecutedEvent;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class MatchingEngineServiceTest {

    private final RedisOrderBookService orderBookService = mock(RedisOrderBookService.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final TradeExecutionRecorder tradeExecutionRecorder = mock(TradeExecutionRecorder.class);
    private final MatchingEngineService service = new MatchingEngineService(
            orderBookService,
            rabbitTemplate,
            redisTemplate,
            redissonClient,
            tradeExecutionRecorder);

    @Test
    void tryMatch_whenTradePersistenceSucceeds_shouldCompleteReservedRestingOrder() throws Exception {
        ReflectionTestUtils.setField(service, "legacyOrderMatchedPublishEnabled", false);
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

        when(orderBookService.reserveBestMatchOrderLua(incomingBuy)).thenReturn(restingSell);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("match:id:sequence")).thenReturn(42L);

        service.tryMatch(incomingBuy);

        verify(tradeExecutionRecorder).record(argThat(trade ->
                trade.getTradeId().equals("TEST-MARKET-42")
                        && trade.getBuyerOrderId().equals(incomingBuy.getOrderId())
                        && trade.getSellerOrderId().equals(restingSell.getOrderId())
                        && trade.getQuantity() == 1));
        verify(orderBookService).completeReservedOrder(restingSell);
        verify(orderBookService, never()).releaseReservedOrder(any());
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
        assertThat(incomingBuy.getAmount()).isZero();
        assertThat(restingSell.getAmount()).isZero();
    }

    @Test
    void tryMatch_whenTradePersistenceFailsAfterReservation_shouldReleaseRestingOrder() throws Exception {
        ReflectionTestUtils.setField(service, "legacyOrderMatchedPublishEnabled", false);
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

        when(orderBookService.reserveBestMatchOrderLua(incomingBuy)).thenReturn(restingSell);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("match:id:sequence")).thenReturn(42L);
        doThrow(new IllegalStateException("db unavailable"))
                .when(tradeExecutionRecorder).record(any(TradeExecutedEvent.class));

        assertThatThrownBy(() -> service.tryMatch(incomingBuy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db unavailable");

        verify(orderBookService).releaseReservedOrder(argThat(order ->
                order.getOrderId().equals(restingSell.getOrderId())
                        && order.getAmount() == 1
                        && order.getOrderType().equals("SELL")));
        verify(orderBookService, never()).completeReservedOrder(any());
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
        assertThat(incomingBuy.getAmount()).isEqualTo(1);
        assertThat(restingSell.getAmount()).isEqualTo(1);
    }

    @Test
    void tryMatch_whenMatchIdGenerationFailsAfterReservation_shouldReleaseRestingOrder() throws Exception {
        ReflectionTestUtils.setField(service, "legacyOrderMatchedPublishEnabled", false);
        OrderConfirmedEvent incomingBuy = order(
                "BUY",
                "00000000-0000-0000-0000-000000000011",
                "00000000-0000-0000-0000-000000000012",
                201L,
                1);
        OrderConfirmedEvent restingSell = order(
                "SELL",
                "00000000-0000-0000-0000-000000000013",
                "00000000-0000-0000-0000-000000000014",
                200L,
                1);

        when(orderBookService.reserveBestMatchOrderLua(incomingBuy)).thenReturn(restingSell);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("match:id:sequence"))
                .thenThrow(new IllegalStateException("redis increment unavailable"));

        assertThatThrownBy(() -> service.tryMatch(incomingBuy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis increment unavailable");

        verify(orderBookService).releaseReservedOrder(argThat(order ->
                order.getOrderId().equals(restingSell.getOrderId())
                        && order.getAmount() == 1
                        && order.getOrderType().equals("SELL")));
        verify(tradeExecutionRecorder, never()).record(any());
        verify(orderBookService, never()).completeReservedOrder(any());
        assertThat(incomingBuy.getAmount()).isEqualTo(1);
        assertThat(restingSell.getAmount()).isEqualTo(1);
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
