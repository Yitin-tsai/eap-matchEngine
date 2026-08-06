package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncomingOrderProcessingStoreTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private IncomingOrderProcessingStore store;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        lenient().when(redisTemplate.opsForHash()).thenReturn((HashOperations) hashOperations);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        store = new IncomingOrderProcessingStore(redisTemplate);
    }

    @Test
    void state_shouldParseProcessingOwnerAndHeartbeatTimestamp() {
        when(hashOperations.get(anyString(), eq(ORDER_ID.toString())))
                .thenReturn("PROCESSING:owner-token:12345");

        IncomingOrderProcessingStore.State state = store.state(ORDER_ID);

        assertThat(state.status()).isEqualTo(IncomingOrderProcessingStore.Status.PROCESSING);
        assertThat(state.token()).isEqualTo("owner-token");
        assertThat(state.processingStartedAtEpochMillis()).isEqualTo(12345L);
    }

    @Test
    void state_shouldRejectMalformedProcessingState() {
        when(hashOperations.get(anyString(), eq(ORDER_ID.toString())))
                .thenReturn("PROCESSING:12345");

        assertThatThrownBy(() -> store.state(ORDER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid incoming order processing state");
    }

    @Test
    void replaceWithClaim_shouldPersistRecoverableState() {
        IncomingOrderProcessingStore.Claim claim = store.newClaim(order(101L));
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);

        store.replaceWithClaim(claim);

        verify(hashOperations).put(eq(claim.stateHashKey()), eq(ORDER_ID.toString()), value.capture());
        assertThat(value.getValue().toString())
                .startsWith("PROCESSING:" + claim.token() + ":");
    }

    @Test
    void newClaim_shouldShardCompletedBitmapByMarketSequence() {
        IncomingOrderProcessingStore.Claim lastInFirstShard = store.newClaim(order(10_000_000L));
        IncomingOrderProcessingStore.Claim firstInSecondShard = store.newClaim(order(10_000_001L));

        assertThat(lastInFirstShard.completedBitmapKey())
                .isEqualTo("match:incoming-order:completed:TEST-MARKET:0");
        assertThat(lastInFirstShard.completedBitOffset()).isEqualTo(9_999_999L);
        assertThat(firstInSecondShard.completedBitmapKey())
                .isEqualTo("match:incoming-order:completed:TEST-MARKET:1");
        assertThat(firstInSecondShard.completedBitOffset()).isZero();
    }

    @Test
    void state_whenCompletedBitIsSet_shouldNotReadProcessingHash() {
        OrderConfirmedEvent order = order(101L);
        when(valueOperations.getBit("match:incoming-order:completed:TEST-MARKET:0", 100L))
                .thenReturn(true);

        IncomingOrderProcessingStore.State state = store.state(order);

        assertThat(state.status()).isEqualTo(IncomingOrderProcessingStore.Status.COMPLETED);
        verify(hashOperations, never()).get(anyString(), any());
    }

    @Test
    void markCompleted_shouldSetBitmapAndDeleteProcessingLeaseAtomically() {
        OrderConfirmedEvent order = order(101L);

        store.markCompleted(order);

        verify(redisTemplate).execute(
                any(),
                eq(List.of(
                        "match:incoming-order:completed:TEST-MARKET:0",
                        "match:incoming-order:states:01")),
                eq("100"),
                eq(ORDER_ID.toString()));
    }

    private OrderConfirmedEvent order(long marketSequence) {
        return OrderConfirmedEvent.builder()
                .orderId(ORDER_ID)
                .userId(UUID.fromString("00000000-0000-0000-0000-000000000102"))
                .marketId("TEST-MARKET")
                .marketSequence(marketSequence)
                .price(100)
                .amount(1)
                .orderType("BUY")
                .createdAt(LocalDateTime.of(2026, 8, 6, 12, 0))
                .build();
    }
}
