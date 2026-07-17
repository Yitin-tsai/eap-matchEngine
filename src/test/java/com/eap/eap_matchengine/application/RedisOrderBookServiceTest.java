package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SuppressWarnings({"unchecked", "rawtypes"})
class RedisOrderBookServiceTest {

    private final RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
    private final RedisOrderBookService service = new RedisOrderBookService(redisTemplate, new ObjectMapper());

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
