package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderAssetReservationSucceededEvent;
import com.eap.eap_matchengine.configuration.repository.TradeExecutionRepository;
import com.eap.eap_matchengine.domain.entity.TradeExecutionEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.eap.eap_matchengine.configuration.config.MatchEngineSchedulerConfig.RESERVATION_MAINTENANCE_SCHEDULER;

@Component
@Slf4j
@ConditionalOnProperty(
        name = "eap.match-engine.reservation-reconciler.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ReservationReconciler {

    private final RedisOrderBookService orderBookService;
    private final TradeExecutionRepository tradeExecutionRepository;
    private final ReservationCleanupTaskStore cleanupTaskStore;
    private final ReservationReconciliationIssueStore issueStore;
    private final ReservationReconcilerMetrics metrics;
    private final OrderBookRuntimeGuard runtimeGuard;
    private final Duration orphanThreshold;
    private final int batchSize;
    private final int maxAttempts;

    @Autowired
    public ReservationReconciler(
            RedisOrderBookService orderBookService,
            TradeExecutionRepository tradeExecutionRepository,
            ReservationCleanupTaskStore cleanupTaskStore,
            ReservationReconciliationIssueStore issueStore,
            ReservationReconcilerMetrics metrics,
            OrderBookRuntimeGuard runtimeGuard,
            @Value("${eap.match-engine.reservation-reconciler.orphan-threshold-seconds:30}") long orphanThresholdSeconds,
            @Value("${eap.match-engine.reservation-reconciler.batch-size:100}") int batchSize,
            @Value("${eap.match-engine.reservation-reconciler.max-attempts:10}") int maxAttempts) {
        this.orderBookService = orderBookService;
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.cleanupTaskStore = cleanupTaskStore;
        this.issueStore = issueStore;
        this.metrics = metrics;
        this.runtimeGuard = runtimeGuard;
        this.orphanThreshold = Duration.ofSeconds(orphanThresholdSeconds);
        this.batchSize = batchSize;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    ReservationReconciler(
            RedisOrderBookService orderBookService,
            TradeExecutionRepository tradeExecutionRepository,
            ReservationCleanupTaskStore cleanupTaskStore,
            ReservationReconciliationIssueStore issueStore,
            ReservationReconcilerMetrics metrics,
            long orphanThresholdSeconds,
            int batchSize,
            int maxAttempts) {
        this.orderBookService = orderBookService;
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.cleanupTaskStore = cleanupTaskStore;
        this.issueStore = issueStore;
        this.metrics = metrics;
        this.runtimeGuard = null;
        this.orphanThreshold = Duration.ofSeconds(orphanThresholdSeconds);
        this.batchSize = batchSize;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Scheduled(
            fixedDelayString = "${eap.match-engine.reservation-reconciler.poll-interval-ms:5000}",
            scheduler = RESERVATION_MAINTENANCE_SCHEDULER)
    public void reconcile() {
        reconcileOnce();
    }

    int reconcileOnce() {
        if (runtimeGuard != null && !runtimeGuard.isReady()) {
            return 0;
        }
        List<RedisOrderBookService.ReservationSnapshot> reservations =
                orderBookService.scanReservations(batchSize);
        Set<String> currentIssueIds = reservations.stream()
                .map(issueStore::fingerprint)
                .collect(Collectors.toSet());
        Set<String> terminalIssueIds = issueStore.findTerminalIssueIds(currentIssueIds);
        reconcileAbsentRetryableIssues();
        List<RedisOrderBookService.ReservationSnapshot> readyReservations = new ArrayList<>();
        Set<String> readyTradeIds = new HashSet<>();
        int consumedBudget = 0;
        for (RedisOrderBookService.ReservationSnapshot reservation : reservations) {
            metrics.scanned();
            if (terminalIssueIds.contains(issueStore.fingerprint(reservation))) {
                continue;
            }
            if (!reservation.valid()) {
                if (consumedBudget >= batchSize) {
                    continue;
                }
                consumedBudget++;
                metrics.invalid();
                issueStore.recordTerminal(
                        reservation,
                        "INVALID_RESERVATION_PAYLOAD",
                        reservation.invalidReason());
                log.error("Invalid MatchEngine reservation: key={}, reason={}",
                        reservation.key(), reservation.invalidReason());
                continue;
            }
            if (!isOrphanReady(reservation)) {
                continue;
            }
            readyReservations.add(reservation);
            if (reservation.tradeId() != null) {
                readyTradeIds.add(reservation.tradeId());
            }
        }
        if (readyReservations.isEmpty()) {
            return 0;
        }

        Set<String> activeCleanupTradeIds = cleanupTaskStore.findActiveTradeIds(readyTradeIds);
        int actions = 0;
        for (RedisOrderBookService.ReservationSnapshot reservation : readyReservations) {
            if (activeCleanupTradeIds.contains(reservation.tradeId())) {
                metrics.deferredToCleanup();
                continue;
            }
            if (consumedBudget >= batchSize) {
                break;
            }
            consumedBudget++;
            actions += reconcileReservation(reservation);
        }
        return actions;
    }

    private void reconcileAbsentRetryableIssues() {
        Set<String> absentIssueIds = new HashSet<>();
        for (ReservationReconciliationIssueStore.RetryableIssue issue
                : issueStore.claimRetryableAbsenceChecks(batchSize)) {
            RedisOrderBookService.ReservationSnapshot current =
                    orderBookService.readReservation(issue.reservationKey());
            if (current == null || !issue.issueId().equals(issueStore.fingerprint(current))) {
                absentIssueIds.add(issue.issueId());
            }
        }
        issueStore.resolveAbsentRetryableIssues(absentIssueIds);
    }

    private int reconcileReservation(RedisOrderBookService.ReservationSnapshot reservation) {
        OrderAssetReservationSucceededEvent reservedOrder = reservation.order();
        Optional<TradeExecutionEntity> durableTrade = reservation.tradeId() == null
                ? findLegacyDurableTrade(reservedOrder, reservedAtLowerBound(reservation))
                : tradeExecutionRepository.findByTradeId(reservation.tradeId());
        if (durableTrade.isPresent()) {
            if (!validDurableTradeIdentity(reservedOrder, durableTrade.get())) {
                String error = "Reservation references a durable trade with conflicting identity: key="
                        + reservation.key() + ", reservationTradeId=" + reservation.tradeId()
                        + ", orderId=" + reservedOrder.getOrderId()
                        + ", durableTradeId=" + durableTrade.get().getTradeId();
                issueStore.recordTerminal(
                        reservation, "DURABLE_TRADE_IDENTITY_CONFLICT", error);
                metrics.failure();
                log.error(error);
                return 0;
            }
            return convergeDurableTradeReservation(reservation, durableTrade.get());
        }
        try {
            orderBookService.releaseReservedOrder(reservedOrder, reservation.tradeId());
            issueStore.markResolved(issueStore.fingerprint(reservation));
            metrics.released();
            log.warn("Released orphan MatchEngine reservation without durable trade: orderId={}, key={}, amount={}",
                    reservedOrder.getOrderId(), reservation.key(), reservedOrder.getAmount());
            return 1;
        } catch (OrderBookRuntimeUnavailableException unavailable) {
            throw unavailable;
        } catch (Exception e) {
            recordTransientFailure(reservation, "TRANSIENT_ORPHAN_RELEASE", e);
            return 0;
        }
    }

    private int convergeDurableTradeReservation(
            RedisOrderBookService.ReservationSnapshot reservation,
            TradeExecutionEntity trade) {
        OrderAssetReservationSucceededEvent reservedOrder = reservation.order();
        int remainingAmount = reservedOrder.getAmount() - trade.getQuantity();
        try {
            if (remainingAmount > 0) {
                OrderAssetReservationSucceededEvent remainder =
                        copyWithAmount(reservedOrder, remainingAmount);
                orderBookService.releaseReservedOrder(remainder, reservation.tradeId());
                metrics.released();
                log.warn("Released remaining partial MatchEngine reservation after durable trade: tradeId={}, orderId={}, remainingAmount={}",
                        trade.getTradeId(), reservedOrder.getOrderId(), remainingAmount);
            } else {
                ReservationCompletionOutcome outcome =
                        orderBookService.completeReservedOrder(reservedOrder, reservation.tradeId());
                if (!outcome.successful()) {
                    String error = "Reservation completion ownership conflict: orderId="
                            + reservedOrder.getOrderId() + ", tradeId=" + reservation.tradeId()
                            + ", outcome=" + outcome;
                    issueStore.recordTerminal(
                            reservation, "RESERVATION_OWNERSHIP_CONFLICT", error);
                    metrics.failure();
                    log.error(error);
                    return 0;
                }
                metrics.completed();
                log.warn("Completed MatchEngine reservation after durable trade: tradeId={}, orderId={}",
                        trade.getTradeId(), reservedOrder.getOrderId());
            }
            issueStore.markResolved(issueStore.fingerprint(reservation));
            return 1;
        } catch (OrderBookRuntimeUnavailableException unavailable) {
            throw unavailable;
        } catch (Exception e) {
            recordTransientFailure(reservation, "TRANSIENT_DURABLE_TRADE_CONVERGENCE", e);
            return 0;
        }
    }

    private void recordTransientFailure(
            RedisOrderBookService.ReservationSnapshot reservation,
            String errorType,
            Exception failure) {
        metrics.failure();
        ReservationReconciliationIssueStore.FailureRecord record =
                issueStore.recordTransientFailure(reservation, errorType, failure, maxAttempts);
        if (record.terminal()) {
            log.error("Match reservation reconciliation exhausted retries: key={}, tradeId={}, attempts={}",
                    reservation.key(), reservation.tradeId(), record.attemptCount(), failure);
        } else {
            log.warn("Match reservation reconciliation will retry: key={}, tradeId={}, attempt={}",
                    reservation.key(), reservation.tradeId(), record.attemptCount(), failure);
        }
    }

    private boolean validDurableTradeIdentity(
            OrderAssetReservationSucceededEvent reservedOrder,
            TradeExecutionEntity trade) {
        if (!Objects.equals(reservedOrder.getMarketId(), trade.getMarketId())
                || trade.getQuantity() == null
                || trade.getQuantity() <= 0
                || reservedOrder.getAmount() == null
                || trade.getQuantity() > reservedOrder.getAmount()) {
            return false;
        }
        if ("BUY".equalsIgnoreCase(reservedOrder.getOrderType())) {
            return Objects.equals(reservedOrder.getOrderId(), trade.getBuyerOrderId())
                    && Objects.equals(reservedOrder.getUserId(), trade.getBuyerId())
                    && Objects.equals(
                            reservedOrder.getMarketSequence(), trade.getBuyerMarketSequence())
                    && Objects.equals(reservedOrder.getPrice(), trade.getOriginBuyerPrice());
        }
        if ("SELL".equalsIgnoreCase(reservedOrder.getOrderType())) {
            return Objects.equals(reservedOrder.getOrderId(), trade.getSellerOrderId())
                    && Objects.equals(reservedOrder.getUserId(), trade.getSellerId())
                    && Objects.equals(
                            reservedOrder.getMarketSequence(), trade.getSellerMarketSequence())
                    && Objects.equals(reservedOrder.getPrice(), trade.getOriginSellerPrice());
        }
        return false;
    }

    private OrderAssetReservationSucceededEvent copyWithAmount(
            OrderAssetReservationSucceededEvent source,
            int amount) {
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

    private boolean isOrphanReady(RedisOrderBookService.ReservationSnapshot reservation) {
        long reservedAtEpochMillis = reservation.reservedAtEpochMillis();
        if (reservedAtEpochMillis <= 0) {
            return true;
        }
        Instant reservedAt = Instant.ofEpochMilli(reservedAtEpochMillis);
        return reservedAt.plus(orphanThreshold).isBefore(Instant.now());
    }

    private Optional<TradeExecutionEntity> findLegacyDurableTrade(
            OrderAssetReservationSucceededEvent reservedOrder,
            LocalDateTime reservedAt) {
        return tradeExecutionRepository
                .findFirstByCreatedAtGreaterThanEqualAndBuyerOrderIdOrCreatedAtGreaterThanEqualAndSellerOrderIdOrderByCreatedAtDesc(
                        reservedAt,
                        reservedOrder.getOrderId(),
                        reservedAt,
                        reservedOrder.getOrderId());
    }

    private LocalDateTime reservedAtLowerBound(RedisOrderBookService.ReservationSnapshot reservation) {
        if (reservation.reservedAtEpochMillis() <= 0) {
            return LocalDateTime.of(1970, 1, 1, 0, 0);
        }
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(reservation.reservedAtEpochMillis()),
                ZoneId.systemDefault());
    }
}
