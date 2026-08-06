package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
import com.eap.eap_matchengine.configuration.repository.TradeExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderConfirmedProcessorTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final IncomingOrderProcessingStore.Claim CLAIM =
            new IncomingOrderProcessingStore.Claim(
                    "state-hash", ORDER_ID.toString(), "token", "completed-bitmap", 100L);

    @Mock
    private MatchingEngineService matchingEngineService;
    @Mock
    private IncomingOrderProcessingStore processingStore;
    @Mock
    private TradeExecutionRepository tradeExecutionRepository;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    private OrderConfirmedProcessor processor;

    @BeforeEach
    void setUp() {
        lenient().when(redissonClient.getLock("lock:incoming-order:" + ORDER_ID)).thenReturn(lock);
        lenient().when(processingStore.newClaim(any())).thenReturn(CLAIM);
        processor = new OrderConfirmedProcessor(
                matchingEngineService, processingStore, tradeExecutionRepository, redissonClient, 1);
    }

    @Test
    void processNewOrder_shouldGuardMatchAndMarkCompleted() {
        OrderConfirmedEvent source = order("BUY", 5);
        when(matchingEngineService.tryMatchGuarded(any(), any()))
                .thenReturn(MatchingEngineService.GuardedMatchResult.PROCESSED);

        processor.process(source);

        ArgumentCaptor<OrderConfirmedEvent> captor = ArgumentCaptor.forClass(OrderConfirmedEvent.class);
        InOrder sequence = inOrder(processingStore, matchingEngineService);
        sequence.verify(processingStore).newClaim(any());
        sequence.verify(matchingEngineService).tryMatchGuarded(captor.capture(), any());
        sequence.verify(processingStore).markCompleted(any(OrderConfirmedEvent.class));
        assertThat(captor.getValue()).isNotSameAs(source);
        assertThat(captor.getValue().getAmount()).isEqualTo(5);
        assertThat(source.getAmount()).isEqualTo(5);
        verifyNoInteractions(tradeExecutionRepository);
    }

    @Test
    void processCompletedRedelivery_shouldNotMatchAgain() {
        when(matchingEngineService.tryMatchGuarded(any(), any()))
                .thenReturn(MatchingEngineService.GuardedMatchResult.DUPLICATE);

        processor.process(order("SELL", 5));

        verify(processingStore, never()).markCompleted(any(OrderConfirmedEvent.class));
        verifyNoInteractions(tradeExecutionRepository);
        verifyNoInteractions(redissonClient);
    }

    @Test
    void processWithoutMarketSequence_shouldRejectUnsafeDeduplicationIdentity() {
        OrderConfirmedEvent source = order("BUY", 1);
        source.setMarketSequence(null);

        assertThatThrownBy(() -> processor.process(source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("marketSequence must be positive");

        verifyNoInteractions(matchingEngineService);
    }

    @Test
    void processConcurrentRedelivery_whenOriginalClaimIsFresh_shouldWaitForCompletionWithoutTakeover() {
        long now = System.currentTimeMillis();
        when(matchingEngineService.tryMatchGuarded(any(), any()))
                .thenReturn(MatchingEngineService.GuardedMatchResult.IN_PROGRESS);
        when(processingStore.state(any(OrderConfirmedEvent.class))).thenReturn(
                IncomingOrderProcessingStore.State.processing("original", now),
                IncomingOrderProcessingStore.State.completed());

        processor.process(order("BUY", 1));

        verify(matchingEngineService).tryMatchGuarded(any(), any());
        verify(processingStore, never()).replaceWithClaim(any());
        verifyNoInteractions(tradeExecutionRepository);
        verifyNoInteractions(redissonClient);
    }

    @Test
    void processRedelivery_whenCompletedMarkerWriteFailed_shouldConvergeWithoutSecondMatch() {
        RuntimeException markerFailure = new RuntimeException("redis marker unavailable");
        when(matchingEngineService.tryMatchGuarded(any(), any())).thenReturn(
                MatchingEngineService.GuardedMatchResult.PROCESSED,
                MatchingEngineService.GuardedMatchResult.IN_PROGRESS);
        doThrow(markerFailure).doNothing().when(processingStore).markCompleted(any(OrderConfirmedEvent.class));
        when(processingStore.state(any(OrderConfirmedEvent.class)))
                .thenReturn(IncomingOrderProcessingStore.State.processing("interrupted", 0L));
        when(processingStore.isVisible(ORDER_ID)).thenReturn(true);

        assertThatThrownBy(() -> processor.process(order("SELL", 1)))
                .isSameAs(markerFailure);

        processor.process(order("SELL", 1));

        verify(matchingEngineService, times(2)).tryMatchGuarded(any(), any());
        verify(processingStore).replaceWithClaim(any());
        verify(processingStore, times(2)).markCompleted(any(OrderConfirmedEvent.class));
        verifyNoInteractions(tradeExecutionRepository);
        verify(lock).unlock();
    }

    @Test
    void processInterruptedOrder_whenRemainderIsVisible_shouldConvergeWithoutMatchingAgain() {
        when(matchingEngineService.tryMatchGuarded(any(), any()))
                .thenReturn(MatchingEngineService.GuardedMatchResult.IN_PROGRESS);
        when(processingStore.state(any(OrderConfirmedEvent.class)))
                .thenReturn(IncomingOrderProcessingStore.State.processing("existing", 0L));
        when(processingStore.isVisible(ORDER_ID)).thenReturn(true);

        processor.process(order("BUY", 5));

        verify(processingStore).markCompleted(any(OrderConfirmedEvent.class));
        verify(matchingEngineService).tryMatchGuarded(any(), any());
        verifyNoInteractions(tradeExecutionRepository);
        verify(lock).unlock();
    }

    @Test
    void processInterruptedOrder_shouldResumeOnlyUnmatchedQuantity() {
        when(matchingEngineService.tryMatchGuarded(any(), any()))
                .thenReturn(
                        MatchingEngineService.GuardedMatchResult.IN_PROGRESS,
                        MatchingEngineService.GuardedMatchResult.PROCESSED);
        when(processingStore.state(any(OrderConfirmedEvent.class)))
                .thenReturn(IncomingOrderProcessingStore.State.processing("existing", 0L));
        when(tradeExecutionRepository.sumQuantityByBuyerOrderId(ORDER_ID)).thenReturn(3L);

        processor.process(order("BUY", 5));

        ArgumentCaptor<OrderConfirmedEvent> captor = ArgumentCaptor.forClass(OrderConfirmedEvent.class);
        verify(matchingEngineService, times(2)).tryMatchGuarded(captor.capture(), any());
        assertThat(captor.getAllValues().get(1).getAmount()).isEqualTo(2);
        verify(processingStore).markCompleted(any(OrderConfirmedEvent.class));
        verify(lock).unlock();
    }

    @Test
    void processInterruptedOrder_whenDurableTradesCoverAmount_shouldNotMatchAgain() {
        when(matchingEngineService.tryMatchGuarded(any(), any()))
                .thenReturn(MatchingEngineService.GuardedMatchResult.IN_PROGRESS);
        when(processingStore.state(any(OrderConfirmedEvent.class)))
                .thenReturn(IncomingOrderProcessingStore.State.processing("existing", 0L));
        when(tradeExecutionRepository.sumQuantityBySellerOrderId(ORDER_ID)).thenReturn(5L);

        processor.process(order("SELL", 5));

        verify(matchingEngineService).tryMatchGuarded(any(), any());
        verify(processingStore).markCompleted(any(OrderConfirmedEvent.class));
        verify(lock).unlock();
    }

    @Test
    void processInterruptedOrder_whenOrderIsStillReserved_shouldWaitForReservationConvergence() {
        when(matchingEngineService.tryMatchGuarded(any(), any()))
                .thenReturn(MatchingEngineService.GuardedMatchResult.IN_PROGRESS);
        when(processingStore.state(any(OrderConfirmedEvent.class)))
                .thenReturn(IncomingOrderProcessingStore.State.processing("existing", 0L));
        when(tradeExecutionRepository.sumQuantityByBuyerOrderId(ORDER_ID)).thenReturn(2L);
        when(processingStore.isReserved(ORDER_ID)).thenReturn(true);

        assertThatThrownBy(() -> processor.process(order("BUY", 5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reservation convergence");

        verify(matchingEngineService).tryMatchGuarded(any(), any());
        verify(processingStore, never()).markCompleted(any(OrderConfirmedEvent.class));
        verify(lock).unlock();
    }

    private OrderConfirmedEvent order(String side, int amount) {
        return OrderConfirmedEvent.builder()
                .orderId(ORDER_ID)
                .userId(UUID.fromString("00000000-0000-0000-0000-000000000102"))
                .marketId("TEST-MARKET")
                .marketSequence(101L)
                .price(100)
                .amount(amount)
                .orderType(side)
                .createdAt(LocalDateTime.of(2026, 8, 6, 12, 0))
                .build();
    }
}
