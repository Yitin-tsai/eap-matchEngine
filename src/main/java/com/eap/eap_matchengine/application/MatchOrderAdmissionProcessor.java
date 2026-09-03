package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderAssetReservationSucceededEvent;
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
public class MatchOrderAdmissionProcessor {

    private static final String INCOMING_ORDER_LOCK_PREFIX = "lock:incoming-order:";

    private final MatchingEngineService matchingEngineService;
    private final IncomingOrderProcessingStore processingStore;
    private final TradeExecutionRepository tradeExecutionRepository;
    private final OrderCancellationCoordinator cancellationCoordinator;
    private final RedissonClient redissonClient;
    private final Duration staleProcessingThreshold;

    public MatchOrderAdmissionProcessor(
            MatchingEngineService matchingEngineService,
            IncomingOrderProcessingStore processingStore,
            TradeExecutionRepository tradeExecutionRepository,
            OrderCancellationCoordinator cancellationCoordinator,
            RedissonClient redissonClient,
            @Value("${eap.match-engine.incoming-order-recovery.stale-processing-seconds:30}")
            long staleProcessingSeconds) {
        this.matchingEngineService = matchingEngineService;
        this.processingStore = processingStore;
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.cancellationCoordinator = cancellationCoordinator;
        this.redissonClient = redissonClient;
        this.staleProcessingThreshold = Duration.ofSeconds(Math.max(1, staleProcessingSeconds));
    }

    public void process(OrderAssetReservationSucceededEvent source) {
        validate(source);
        if (attemptGuarded(source, copyWithAmount(source, source.getAmount()))) {
            return;
        }
        recoverOrDefer(source);
    }

    private boolean attemptGuarded(OrderAssetReservationSucceededEvent source, OrderAssetReservationSucceededEvent orderToProcess) {
        IncomingOrderProcessingStore.Claim claim =
                processingStore.newClaim(source);
        MatchingEngineService.GuardedMatchResult result =
                matchingEngineService.tryMatchGuarded(orderToProcess, claim);
        return finishGuardedAttempt(source, orderToProcess, result);
    }

    private void recoverOrDefer(OrderAssetReservationSucceededEvent source) {
        IncomingOrderProcessingStore.State state = processingStore.state(source);
        if (isCompleted(state)) {
            return;
        }
        if (state == null) {
            if (attemptGuarded(source, copyWithAmount(source, source.getAmount()))) {
                return;
            }
            throw prerequisitePending(source, "a concurrent admission claim appeared");
        }
        if (remainingStaleWaitMillis(state) > 0) {
            throw prerequisitePending(source, "the existing admission claim is not stale");
        }
        if (!recoverUnderLock(source)) {
            throw prerequisitePending(source, "recovery ownership or reservation has not converged");
        }
    }

    private boolean recoverUnderLock(OrderAssetReservationSucceededEvent source) {
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

            OrderAssetReservationSucceededEvent recovered = recover(source);
            IncomingOrderProcessingStore.Claim recoveryClaim =
                    processingStore.newClaim(source);
            processingStore.replaceWithClaim(recoveryClaim);
            if (recovered != null) {
                MatchingEngineService.GuardedMatchResult result =
                        matchingEngineService.tryMatchGuarded(recovered, recoveryClaim);
                if (result == MatchingEngineService.GuardedMatchResult.IN_PROGRESS) {
                    return false;
                }
                if (finishGuardedAttempt(source, recovered, result)) {
                    return true;
                }
            }
            processingStore.markCompleted(source);
            return true;
        } finally {
            lock.unlock();
        }
    }

    private boolean finishGuardedAttempt(
            OrderAssetReservationSucceededEvent source,
            OrderAssetReservationSucceededEvent processedOrder,
            MatchingEngineService.GuardedMatchResult result) {
        return switch (result) {
            case PROCESSED_AND_COMPLETED -> true;
            case PROCESSED -> {
                processingStore.markCompleted(source);
                yield true;
            }
            case CANCELLATION_PENDING -> {
                cancellationCoordinator.resolveAdmissionBlockedByCancellationIntent(processedOrder);
                processingStore.markCompleted(source);
                yield true;
            }
            case DUPLICATE -> {
                log.debug("Ignoring completed asset-reservation success redelivery: orderId={}", source.getOrderId());
                yield true;
            }
            case IN_PROGRESS -> false;
        };
    }

    private boolean isCompleted(IncomingOrderProcessingStore.State state) {
        return state != null && state.status() == IncomingOrderProcessingStore.Status.COMPLETED;
    }

    private long remainingStaleWaitMillis(IncomingOrderProcessingStore.State state) {
        long ageMillis = Instant.now().toEpochMilli() - state.processingStartedAtEpochMillis();
        return staleProcessingThreshold.toMillis() - ageMillis;
    }

    private MatchOrderAdmissionPrerequisiteNotReadyException prerequisitePending(
            OrderAssetReservationSucceededEvent source,
            String reason) {
        return new MatchOrderAdmissionPrerequisiteNotReadyException(
                "Match order admission prerequisite is pending: orderId=" + source.getOrderId()
                        + ", reason=" + reason);
    }

    private OrderAssetReservationSucceededEvent recover(OrderAssetReservationSucceededEvent source) {
        UUID orderId = source.getOrderId();
        if (processingStore.isVisible(orderId)) {
            log.info("Recovered Match admission after incoming order became visible: orderId={}", orderId);
            return null;
        }

        long matchedQuantity = matchedQuantity(source);
        int remaining = Math.toIntExact((long) source.getAmount() - matchedQuantity);
        if (remaining <= 0) {
            log.info("Recovered completed Match admission from durable trades: orderId={}, matchedQuantity={}",
                    orderId, matchedQuantity);
            return null;
        }

        if (processingStore.isReserved(orderId)) {
            throw new MatchOrderAdmissionPrerequisiteNotReadyException(
                    "Incoming order recovery is waiting for reservation convergence: orderId=" + orderId);
        }

        log.warn("Resuming interrupted Match admission: orderId={}, originalAmount={}, matchedQuantity={}, remaining={}",
                orderId, source.getAmount(), matchedQuantity, remaining);
        return copyWithAmount(source, remaining);
    }

    private long matchedQuantity(OrderAssetReservationSucceededEvent source) {
        if (source.getOrderType().equalsIgnoreCase("BUY")) {
            return tradeExecutionRepository.sumQuantityByBuyerOrderId(source.getOrderId());
        }
        return tradeExecutionRepository.sumQuantityBySellerOrderId(source.getOrderId());
    }

    private OrderAssetReservationSucceededEvent copyWithAmount(OrderAssetReservationSucceededEvent source, int amount) {
        return OrderAssetReservationSucceededEvent.builder()
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

    private void validate(OrderAssetReservationSucceededEvent source) {
        if (source == null || source.getOrderId() == null) {
            throw new IllegalArgumentException("OrderAssetReservationSucceededEvent must contain orderId");
        }
        if (source.getAmount() == null || source.getAmount() <= 0) {
            throw new IllegalArgumentException("OrderAssetReservationSucceededEvent amount must be positive");
        }
        if (source.getMarketId() == null || source.getMarketId().isBlank()) {
            throw new IllegalArgumentException("OrderAssetReservationSucceededEvent must contain marketId");
        }
        if (source.getMarketSequence() == null || source.getMarketSequence() <= 0) {
            throw new IllegalArgumentException("OrderAssetReservationSucceededEvent marketSequence must be positive");
        }
        if (!"BUY".equalsIgnoreCase(source.getOrderType())
                && !"SELL".equalsIgnoreCase(source.getOrderType())) {
            throw new IllegalArgumentException("OrderAssetReservationSucceededEvent orderType must be BUY or SELL");
        }
    }
}
