package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderAssetReservationSucceededEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SuppressWarnings({"unchecked", "rawtypes"})
class RedisOrderBookServiceTest {

    private final RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RedisOrderBookService service = new RedisOrderBookService(
            redisTemplate,
            objectMapper);

    @Test
    void arbitrateCancellation_whenLuaRemovesOrder_shouldReturnCancelled() {
        OrderAssetReservationSucceededEvent order = incomingBuyOrder();
        doReturn(List.of(
                "__CANCELLED__".getBytes(StandardCharsets.UTF_8),
                compactRedisOrderJson(order).getBytes(StandardCharsets.UTF_8)))
                .when(redisTemplate).execute(any(RedisCallback.class));

        RedisOrderBookService.CancellationArbitration result = service.arbitrateCancellation(
                order, UUID.randomUUID());

        assertThat(result.outcome()).isEqualTo(RedisOrderBookService.CancellationOutcome.CANCELLED);
        assertThat(result.cancelledOrder().getOrderId()).isEqualTo(order.getOrderId());
        assertThat(result.cancelledOrder().getAmount()).isEqualTo(order.getAmount());
    }

    @Test
    void arbitrateCancellation_whenMarkerAlreadyExists_shouldBeIdempotent() {
        OrderAssetReservationSucceededEvent order = incomingBuyOrder();
        doReturn(List.of(
                "__DUPLICATE__".getBytes(StandardCharsets.UTF_8),
                compactRedisOrderJson(order).getBytes(StandardCharsets.UTF_8)))
                .when(redisTemplate).execute(any(RedisCallback.class));

        RedisOrderBookService.CancellationArbitration result = service.arbitrateCancellation(
                order, UUID.randomUUID());

        assertThat(result.outcome())
                .isEqualTo(RedisOrderBookService.CancellationOutcome.ALREADY_CANCELLED_BY_REQUEST);
        assertThat(result.cancelledOrder().getAmount()).isEqualTo(order.getAmount());
    }

    @Test
    void recordCancellationIntent_whenSameRequestIsReplayed_shouldNotRefreshRedisTtl() {
        UUID orderId = UUID.randomUUID();
        UUID cancellationId = UUID.randomUUID();
        String key = "order:cancellation-intent:" + orderId;
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        doReturn(valueOperations).when(redisTemplate).opsForValue();
        doReturn(false).when(valueOperations)
                .setIfAbsent(key, cancellationId.toString(), Duration.ofDays(7));
        doReturn(cancellationId.toString()).when(valueOperations).get(key);

        service.recordCancellationIntent(orderId, cancellationId);

        verify(valueOperations).get(key);
        verify(redisTemplate, never()).expire(eq(key), any(Duration.class));
    }

    @Test
    void arbitrateCancellation_whenMatchWon_shouldReturnNotOpen() {
        doReturn(List.of("__NOT_OPEN__".getBytes(StandardCharsets.UTF_8)))
                .when(redisTemplate).execute(any(RedisCallback.class));

        RedisOrderBookService.CancellationArbitration result = service.arbitrateCancellation(
                incomingBuyOrder(), UUID.randomUUID());

        assertThat(result.outcome()).isEqualTo(RedisOrderBookService.CancellationOutcome.NOT_OPEN);
        assertThat(result.cancelledOrder()).isNull();
    }

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
    void reserveBestMatchOrderLua_whenOrderbookOwnerIsMissing_shouldFailClosed() {
        UUID invalidOrderId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        doReturn("__INVALID_ORDER_DETAIL__:" + invalidOrderId)
                .when(redisTemplate).execute(any(RedisCallback.class));

        assertThatThrownBy(() -> service.reserveBestMatchOrderLua(incomingBuyOrder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis orderbook owner missing")
                .hasMessageContaining(invalidOrderId.toString());

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
        OrderAssetReservationSucceededEvent restingSell = OrderAssetReservationSucceededEvent.builder()
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
    void reserveBestMatchOrAddOrderWithSequenceLua_whenNoMatch_shouldReturnAdded() {
        doReturn(List.of("__ADDED__".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .when(redisTemplate).execute(any(RedisCallback.class));

        RedisOrderBookService.MatchOrAddResult result =
                service.reserveBestMatchOrAddOrderWithSequenceLua(incomingBuyOrder());

        assertThat(result.orderAdded()).isTrue();
        assertThat(result.reservedMatch()).isNull();
        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    @Test
    void reserveBestMatchOrAddOrderWithSequenceLua_whenGuardedAddCompletes_shouldReturnCompletedAdmission() {
        doReturn(List.of("__ADDED_COMPLETED__".getBytes(StandardCharsets.UTF_8)))
                .when(redisTemplate).execute(any(RedisCallback.class));
        IncomingOrderProcessingStore.Claim claim = new IncomingOrderProcessingStore.Claim(
                "match:incoming-order:states:00",
                incomingBuyOrder().getOrderId().toString(),
                "token",
                "match:incoming-order:completed:TEST:0",
                100L);

        RedisOrderBookService.MatchOrAddResult result =
                service.reserveBestMatchOrAddOrderWithSequenceLua(incomingBuyOrder(), claim);

        assertThat(result.orderAdded()).isTrue();
        assertThat(result.incomingOrderAdmission())
                .isEqualTo(RedisOrderBookService.IncomingOrderAdmission.COMPLETED);
    }

    @Test
    void reserveBestMatchOrAddOrderWithSequenceLua_whenGuardFindsCompletedOrder_shouldReturnDuplicate() {
        doReturn(List.of("__DUPLICATE__".getBytes(StandardCharsets.UTF_8)))
                .when(redisTemplate).execute(any(RedisCallback.class));
        IncomingOrderProcessingStore.Claim claim = new IncomingOrderProcessingStore.Claim(
                "match:incoming-order:states:00",
                incomingBuyOrder().getOrderId().toString(),
                "token",
                "match:incoming-order:completed:TEST:0",
                100L);

        RedisOrderBookService.MatchOrAddResult result =
                service.reserveBestMatchOrAddOrderWithSequenceLua(incomingBuyOrder(), claim);

        assertThat(result.incomingOrderAdmission())
                .isEqualTo(RedisOrderBookService.IncomingOrderAdmission.DUPLICATE);
        assertThat(result.reservedMatch()).isNull();
    }

    @Test
    void reserveBestMatchOrAddOrderWithSequenceLua_whenAnotherAttemptOwnsOrder_shouldReturnInProgress() {
        doReturn(List.of("__IN_PROGRESS__".getBytes(StandardCharsets.UTF_8)))
                .when(redisTemplate).execute(any(RedisCallback.class));
        IncomingOrderProcessingStore.Claim claim = new IncomingOrderProcessingStore.Claim(
                "match:incoming-order:states:00",
                incomingBuyOrder().getOrderId().toString(),
                "token",
                "match:incoming-order:completed:TEST:0",
                100L);

        RedisOrderBookService.MatchOrAddResult result =
                service.reserveBestMatchOrAddOrderWithSequenceLua(incomingBuyOrder(), claim);

        assertThat(result.incomingOrderAdmission())
                .isEqualTo(RedisOrderBookService.IncomingOrderAdmission.IN_PROGRESS);
        assertThat(result.reservedMatch()).isNull();
    }

    @Test
    void reserveBestMatchOrAddOrderWithSequenceLua_whenCancellationIntentExists_shouldStopAdmission() {
        doReturn(List.of("__CANCELLATION_PENDING__".getBytes(StandardCharsets.UTF_8)))
                .when(redisTemplate).execute(any(RedisCallback.class));
        IncomingOrderProcessingStore.Claim claim = new IncomingOrderProcessingStore.Claim(
                "match:incoming-order:states:00",
                incomingBuyOrder().getOrderId().toString(),
                "token",
                "match:incoming-order:completed:TEST:0",
                100L);

        RedisOrderBookService.MatchOrAddResult result =
                service.reserveBestMatchOrAddOrderWithSequenceLua(incomingBuyOrder(), claim);

        assertThat(result.incomingOrderAdmission())
                .isEqualTo(RedisOrderBookService.IncomingOrderAdmission.CANCELLATION_PENDING);
        assertThat(result.orderAdded()).isFalse();
        assertThat(result.reservedMatch()).isNull();
    }

    @Test
    void reserveBestMatchOrAddOrderWithSequenceLua_whenOrderbookOwnerIsMissing_shouldFailClosed() {
        UUID invalidOrderId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        doReturn(List.of(("__INVALID_ORDER_DETAIL__:" + invalidOrderId).getBytes(StandardCharsets.UTF_8)))
                .when(redisTemplate).execute(any(RedisCallback.class));

        assertThatThrownBy(() -> service.reserveBestMatchOrAddOrderWithSequenceLua(incomingBuyOrder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis orderbook owner missing")
                .hasMessageContaining(invalidOrderId.toString());

        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    @Test
    void reserveBestMatchOrAddOrderWithSequenceLua_whenUserIndexDisabled_shouldPassDisabledFlagToLua() {
        RedisConnection connection = mock(RedisConnection.class);
        RedisOrderBookService serviceWithoutUserIndex =
                new RedisOrderBookService(redisTemplate, objectMapper, null, false);
        doAnswer(invocation -> {
            Object[] arguments = invocation.getArguments();
            byte[] userIndexArgument = (byte[]) arguments[arguments.length - 7];
            byte[] marketIdArgument = (byte[]) arguments[arguments.length - 3];
            byte[] incomingUserIdArgument = (byte[]) arguments[arguments.length - 2];
            byte[] generationArgument = (byte[]) arguments[arguments.length - 1];
            assertThat(new String(userIndexArgument, StandardCharsets.UTF_8)).isEqualTo("0");
            assertThat(new String(marketIdArgument, StandardCharsets.UTF_8)).isEqualTo("TEST-MARKET");
            assertThat(new String(incomingUserIdArgument, StandardCharsets.UTF_8))
                    .isEqualTo(incomingBuyOrder().getUserId().toString());
            assertThat(new String(generationArgument, StandardCharsets.UTF_8)).isEmpty();
            return List.of("__ADDED__".getBytes(StandardCharsets.UTF_8));
        }).when(connection).evalSha(nullable(String.class), eq(ReturnType.MULTI), eq(9), any(byte[][].class));
        doAnswer(invocation -> {
            RedisCallback<?> callback = invocation.getArgument(0);
            return callback.doInRedis(connection);
        }).when(redisTemplate).execute(any(RedisCallback.class));

        RedisOrderBookService.MatchOrAddResult result =
                serviceWithoutUserIndex.reserveBestMatchOrAddOrderWithSequenceLua(incomingBuyOrder());

        assertThat(result.orderAdded()).isTrue();
        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    @Test
    void reserveBestMatchOrAddOrderWithSequenceLua_whenMatched_shouldReturnReservedOrderAndMatchId() throws Exception {
        OrderAssetReservationSucceededEvent restingSell = OrderAssetReservationSucceededEvent.builder()
                .orderId(UUID.fromString("00000000-0000-0000-0000-000000000014"))
                .userId(UUID.fromString("00000000-0000-0000-0000-000000000015"))
                .marketId("TEST-MARKET")
                .marketSequence(2L)
                .price(100)
                .amount(1)
                .orderType("SELL")
                .createdAt(LocalDateTime.of(2026, 7, 13, 12, 1))
                .build();
        doReturn(List.of(
                "__MATCH__".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                objectMapper.writeValueAsString(restingSell).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "43".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .when(redisTemplate).execute(any(RedisCallback.class));

        RedisOrderBookService.MatchOrAddResult result =
                service.reserveBestMatchOrAddOrderWithSequenceLua(incomingBuyOrder());

        assertThat(result.orderAdded()).isFalse();
        assertThat(result.reservedMatch().matchId()).isEqualTo(43L);
        assertThat(result.reservedMatch().order().getOrderId()).isEqualTo(restingSell.getOrderId());
        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    @Test
    void reserveBestMatchOrAddOrderWithSequenceLua_whenMatchedWithCompactRedisOrder_shouldReturnReservedOrder()
            throws Exception {
        OrderAssetReservationSucceededEvent restingSell = OrderAssetReservationSucceededEvent.builder()
                .orderId(UUID.fromString("00000000-0000-0000-0000-000000000024"))
                .userId(UUID.fromString("00000000-0000-0000-0000-000000000025"))
                .marketId("TEST-MARKET")
                .marketSequence(22L)
                .price(101)
                .amount(3)
                .orderType("SELL")
                .createdAt(LocalDateTime.of(2026, 7, 13, 12, 2))
                .build();
        doReturn(List.of(
                "__MATCH__".getBytes(StandardCharsets.UTF_8),
                compactRedisOrderJson(restingSell).getBytes(StandardCharsets.UTF_8),
                "44".getBytes(StandardCharsets.UTF_8)))
                .when(redisTemplate).execute(any(RedisCallback.class));

        RedisOrderBookService.MatchOrAddResult result =
                service.reserveBestMatchOrAddOrderWithSequenceLua(incomingBuyOrder());

        assertThat(result.orderAdded()).isFalse();
        assertThat(result.reservedMatch().matchId()).isEqualTo(44L);
        assertThat(result.reservedMatch().order().getOrderId()).isEqualTo(restingSell.getOrderId());
        assertThat(result.reservedMatch().order().getUserId()).isEqualTo(restingSell.getUserId());
        assertThat(result.reservedMatch().order().getMarketSequence()).isEqualTo(22L);
        assertThat(result.reservedMatch().order().getCreatedAt()).isEqualTo(restingSell.getCreatedAt());
        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    @Test
    void releaseReservedOrder_whenReservationDoesNotExist_shouldFailWithoutResurrectingOrder() {
        doReturn(0L).when(redisTemplate).execute(any(RedisCallback.class));

        assertThatThrownBy(() -> service.releaseReservedOrder(incomingBuyOrder(), "TEST-MARKET-42"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to release reserved order");

        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    @Test
    void completeReservedOrder_whenMetricsProvided_shouldRecordPreparationAndResultPhases() {
        MatchingEngineMetrics metrics = mock(MatchingEngineMetrics.class);
        RedisOrderBookService serviceWithMetrics = new RedisOrderBookService(redisTemplate, objectMapper, metrics);
        doReturn(1L).when(redisTemplate).execute(any(RedisCallback.class));

        assertThat(serviceWithMetrics.completeReservedOrder(incomingBuyOrder(), "TEST-MARKET-42"))
                .isEqualTo(ReservationCompletionOutcome.COMPLETED);

        verify(metrics).recordCompleteReservationPrepare(any(Duration.class));
        verify(metrics).recordCompleteReservationResult(any(Duration.class));
    }

    @Test
    void completeReservedOrder_shouldMapIdempotentAndOwnershipOutcomes() {
        doReturn(0L, -1L, -2L).when(redisTemplate).execute(any(RedisCallback.class));

        assertThat(service.completeReservedOrder(incomingBuyOrder(), "TEST-MARKET-42"))
                .isEqualTo(ReservationCompletionOutcome.ALREADY_COMPLETED);
        assertThat(service.completeReservedOrder(incomingBuyOrder(), "TEST-MARKET-42"))
                .isEqualTo(ReservationCompletionOutcome.ORDER_ID_MISMATCH);
        assertThat(service.completeReservedOrder(incomingBuyOrder(), "TEST-MARKET-42"))
                .isEqualTo(ReservationCompletionOutcome.NEWER_TRADE_OWNER);
    }

    @Test
    void completeReservedOrder_whenRedisReturnsNoResult_shouldFailClosed() {
        doReturn(null).when(redisTemplate).execute(any(RedisCallback.class));

        assertThatThrownBy(() -> service.completeReservedOrder(incomingBuyOrder(), "TEST-MARKET-42"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no reservation completion result");
    }

    @Test
    void completeReservedOrder_whenRedisReturnsUnknownCode_shouldFailClosed() {
        doReturn(99L).when(redisTemplate).execute(any(RedisCallback.class));

        assertThatThrownBy(() -> service.completeReservedOrder(incomingBuyOrder(), "TEST-MARKET-42"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected reservation completion result: 99");
    }

    @Test
    void getOrderByUserId_whenUserIndexDisabled_shouldNotReadRedisIndex() {
        RedisOrderBookService serviceWithoutUserIndex =
                new RedisOrderBookService(redisTemplate, objectMapper, null, false);

        assertThat(serviceWithoutUserIndex.getOrderByUserId(UUID.randomUUID())).isEmpty();

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void scanReservations_whenReservationStoresOrderId_shouldResolveOrderDetail() {
        UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000034");
        OrderAssetReservationSucceededEvent restingSell = OrderAssetReservationSucceededEvent.builder()
                .orderId(orderId)
                .userId(UUID.fromString("00000000-0000-0000-0000-000000000035"))
                .marketId("TEST-MARKET")
                .marketSequence(32L)
                .price(102)
                .amount(4)
                .orderType("SELL")
                .createdAt(LocalDateTime.of(2026, 7, 13, 12, 3))
                .build();
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        doReturn(List.of("order:reservation:" + orderId))
                .when(redisTemplate).execute(any(RedisCallback.class));
        doReturn(valueOperations).when(redisTemplate).opsForValue();
        doReturn("{\"reservedAtEpochMillis\":1783934580000,\"orderId\":\"" + orderId
                + "\",\"tradeId\":\"TEST-MARKET-43\"}")
                .when(valueOperations).get("order:reservation:" + orderId);
        doReturn(compactRedisOrderJson(restingSell))
                .when(valueOperations).get("order:" + orderId);

        List<RedisOrderBookService.ReservationSnapshot> snapshots = service.scanReservations(10);

        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).valid()).isTrue();
        assertThat(snapshots.get(0).reservedAtEpochMillis()).isEqualTo(1783934580000L);
        assertThat(snapshots.get(0).tradeId()).isEqualTo("TEST-MARKET-43");
        assertThat(snapshots.get(0).order().getOrderId()).isEqualTo(orderId);
        assertThat(snapshots.get(0).order().getAmount()).isEqualTo(4);
    }

    private OrderAssetReservationSucceededEvent incomingBuyOrder() {
        return OrderAssetReservationSucceededEvent.builder()
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

    private String compactRedisOrderJson(OrderAssetReservationSucceededEvent event) {
        return """
                {"i":"%s","u":"%s","m":"%s","s":%d,"p":%d,"a":%d,"t":"%s","c":"%s"}
                """.formatted(
                event.getOrderId(),
                event.getUserId(),
                event.getMarketId(),
                event.getMarketSequence(),
                event.getPrice(),
                event.getAmount(),
                event.getOrderType(),
                event.getCreatedAt());
    }
}
