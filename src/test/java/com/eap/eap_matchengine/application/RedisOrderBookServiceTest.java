package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SuppressWarnings({"unchecked", "rawtypes"})
class RedisOrderBookServiceTest {

    private final RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RedisOrderBookService service = new RedisOrderBookService(
            redisTemplate,
            objectMapper);

    @Test
    void reserveBestMatchOrderLua_whenOrderbookDetailIsMissing_shouldFailFast() {
        UUID missingOrderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        doReturn("__MISSING_ORDER_DETAIL__:" + missingOrderId)
                .when(redisTemplate).execute(any(RedisCallback.class));

        assertThatThrownBy(() -> service.reserveBestMatchOrderLua(incomingBuyOrder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis orderbook detail missing")
                .hasMessageContaining(missingOrderId.toString());

        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    @Test
    void reserveBestMatchOrderLua_whenReservationAlreadyExists_shouldFailFast() {
        UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000004");
        doReturn("__RESERVATION_EXISTS__:" + orderId)
                .when(redisTemplate).execute(any(RedisCallback.class));

        assertThatThrownBy(() -> service.reserveBestMatchOrderLua(incomingBuyOrder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis order already reserved")
                .hasMessageContaining(orderId.toString());

        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    @Test
    void reserveBestMatchOrderWithSequenceLua_shouldReturnReservedOrderAndMatchId() throws Exception {
        OrderConfirmedEvent restingSell = OrderConfirmedEvent.builder()
                .orderId(UUID.fromString("00000000-0000-0000-0000-000000000004"))
                .userId(UUID.fromString("00000000-0000-0000-0000-000000000005"))
                .marketId("TEST-MARKET")
                .marketSequence(2L)
                .price(100)
                .amount(1)
                .orderType("SELL")
                .createdAt(LocalDateTime.of(2026, 7, 13, 12, 1))
                .build();
        doReturn(List.of(
                objectMapper.writeValueAsString(restingSell).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "42".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .when(redisTemplate).execute(any(RedisCallback.class));

        RedisOrderBookService.ReservedMatch result = service.reserveBestMatchOrderWithSequenceLua(incomingBuyOrder());

        assertThat(result.matchId()).isEqualTo(42L);
        assertThat(result.order().getOrderId()).isEqualTo(restingSell.getOrderId());
        assertThat(result.order().getOrderType()).isEqualTo("SELL");
        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    @Test
    void releaseReservedOrder_whenReservationDoesNotExist_shouldFailWithoutResurrectingOrder() {
        doReturn(0L).when(redisTemplate).execute(any(RedisCallback.class));

        assertThatThrownBy(() -> service.releaseReservedOrder(incomingBuyOrder()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to release reserved order");

        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    private OrderConfirmedEvent incomingBuyOrder() {
        return OrderConfirmedEvent.builder()
                .orderId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .userId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
                .marketId("TEST-MARKET")
                .marketSequence(1L)
                .price(100)
                .amount(1)
                .orderType("BUY")
                .createdAt(LocalDateTime.of(2026, 7, 13, 12, 0))
                .build();
    }
}
