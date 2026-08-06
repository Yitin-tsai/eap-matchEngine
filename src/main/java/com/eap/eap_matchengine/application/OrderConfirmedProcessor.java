package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
import com.eap.eap_matchengine.configuration.repository.TradeExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
public class OrderConfirmedProcessor {

    private static final String INCOMING_ORDER_LOCK_PREFIX = "lock:incoming-order:";

    private final MatchingEngineService matchingEngineService;
    private final IncomingOrderProcessingStore processingStore;
    private final TradeExecutionRepository tradeExecutionRepository;
    private final RedissonClient redissonClient;
    private final Duration staleProcessingThreshold;

    public OrderConfirmedProcessor(
            MatchingEngineService matchingEngineService,
            IncomingOrderProcessingStore processingStore,
            TradeExecutionRepository tradeExecutionRepository,
            RedissonClient redissonClient,
            @Value("${eap.match-engine.incoming-order-recovery.stale-processing-seconds:30}")
            long staleProcessingSeconds) {
        this.matchingEngineService = matchingEngineService;
        this.processingStore = processingStore;
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.redissonClient = redissonClient;
        this.staleProcessingThreshold = Duration.ofSeconds(Math.max(1, staleProcessingSeconds));
    }

    public void process(OrderConfirmedEvent source) {
        validate(source);
        if (attemptGuarded(source, copyWithAmount(source, source.getAmount()))) {
            return;
        }
        awaitCompletionOrRecover(source);
    }

    private boolean attemptGuarded(OrderConfirmedEvent source, OrderConfirmedEvent orderToProcess) {
        IncomingOrderProcessingStore.Claim claim =
                processingStore.newClaim(source);
        MatchingEngineService.GuardedMatchResult result =
                matchingEngineService.tryMatchGuarded(orderToProcess, claim);
        if (result == MatchingEngineService.GuardedMatchResult.PROCESSED) {
            processingStore.markCompleted(source);
            return true;
        }
        if (result == MatchingEngineService.GuardedMatchResult.DUPLICATE) {
            log.debug("Ignoring completed OrderConfirmed redelivery: orderId={}", source.getOrderId());
            return true;
        }
        return false;
    }

    private void awaitCompletionOrRecover(OrderConfirmedEvent source) {
        while (true) {
            IncomingOrderProcessingStore.State state = processingStore.state(source);
            if (isCompleted(state)) {
                return;
            }
            if (state == null) {
                if (attemptGuarded(source, copyWithAmount(source, source.getAmount()))) {
                    return;
                }
                continue;
            }
            long remainingWaitMillis = remainingStaleWaitMillis(state);
            if (remainingWaitMillis > 0) {
                sleep(Math.min(50L, remainingWaitMillis));
                continue;
            }
            if (recoverUnderLock(source)) {
                return;
            }
        }
    }

    private boolean recoverUnderLock(OrderConfirmedEvent source) {
        UUID orderId = source.getOrderId();
        RLock lock = redissonClient.getLock(INCOMING_ORDER_LOCK_PREFIX + orderId);
        lock.lock();
        try {
            IncomingOrderProcessingStore.State state = processingStore.state(source);
            if (isCompleted(state)) {
                return true;
            }
            if (state == null) {
                return attemptGuarded(source, copyWithAmount(source, source.getAmount()));
            }
            if (remainingStaleWaitMillis(state) > 0) {
                return false;
            }

            OrderConfirmedEvent recovered = recover(source);
            IncomingOrderProcessingStore.Claim recoveryClaim =
                    processingStore.newClaim(source);
            processingStore.replaceWithClaim(recoveryClaim);
            if (recovered != null) {
                MatchingEngineService.GuardedMatchResult result =
                        matchingEngineService.tryMatchGuarded(recovered, recoveryClaim);
                if (result == MatchingEngineService.GuardedMatchResult.IN_PROGRESS) {
                    return false;
                }
            }
            processingStore.markCompleted(source);
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean isCompleted(IncomingOrderProcessingStore.State state) {
        return state != null && state.status() == IncomingOrderProcessingStore.Status.COMPLETED;
    }

    private long remainingStaleWaitMillis(IncomingOrderProcessingStore.State state) {
        long ageMillis = Instant.now().toEpochMilli() - state.processingStartedAtEpochMillis();
        return staleProcessingThreshold.toMillis() - ageMillis;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(Math.max(1L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for incoming order processing", e);
        }
    }

    private OrderConfirmedEvent recover(OrderConfirmedEvent source) {
        UUID orderId = source.getOrderId();
        if (processingStore.isVisible(orderId)) {
            log.info("Recovered OrderConfirmed after incoming order became visible: orderId={}", orderId);
            return null;
        }

        long matchedQuantity = matchedQuantity(source);
        int remaining = Math.toIntExact((long) source.getAmount() - matchedQuantity);
        if (remaining <= 0) {
            log.info("Recovered completed OrderConfirmed from durable trades: orderId={}, matchedQuantity={}",
                    orderId, matchedQuantity);
            return null;
        }

        if (processingStore.isReserved(orderId)) {
            throw new IllegalStateException(
                    "Incoming order recovery is waiting for reservation convergence: orderId=" + orderId);
        }

        log.warn("Resuming interrupted OrderConfirmed: orderId={}, originalAmount={}, matchedQuantity={}, remaining={}",
                orderId, source.getAmount(), matchedQuantity, remaining);
        return copyWithAmount(source, remaining);
    }

    private long matchedQuantity(OrderConfirmedEvent source) {
        if (source.getOrderType().equalsIgnoreCase("BUY")) {
            return tradeExecutionRepository.sumQuantityByBuyerOrderId(source.getOrderId());
        }
        return tradeExecutionRepository.sumQuantityBySellerOrderId(source.getOrderId());
    }

    private OrderConfirmedEvent copyWithAmount(OrderConfirmedEvent source, int amount) {
        return OrderConfirmedEvent.builder()
                .orderId(source.getOrderId())
                .userId(source.getUserId())
                .marketId(source.getMarketId())
                .marketSequence(source.getMarketSequence())
                .price(source.getPrice())
                .amount(amount)
                .orderType(source.getOrderType())
                .createdAt(source.getCreatedAt())
                .build();
    }

    private void validate(OrderConfirmedEvent source) {
        if (source == null || source.getOrderId() == null) {
            throw new IllegalArgumentException("OrderConfirmedEvent must contain orderId");
        }
        if (source.getAmount() == null || source.getAmount() <= 0) {
            throw new IllegalArgumentException("OrderConfirmedEvent amount must be positive");
        }
        if (source.getMarketId() == null || source.getMarketId().isBlank()) {
            throw new IllegalArgumentException("OrderConfirmedEvent must contain marketId");
        }
        if (source.getMarketSequence() == null || source.getMarketSequence() <= 0) {
            throw new IllegalArgumentException("OrderConfirmedEvent marketSequence must be positive");
        }
        if (!"BUY".equalsIgnoreCase(source.getOrderType())
                && !"SELL".equalsIgnoreCase(source.getOrderType())) {
            throw new IllegalArgumentException("OrderConfirmedEvent orderType must be BUY or SELL");
        }
    }
}
