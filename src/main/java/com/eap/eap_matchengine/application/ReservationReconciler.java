package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
import com.eap.eap_matchengine.configuration.repository.TradeExecutionRepository;
import com.eap.eap_matchengine.domain.entity.TradeExecutionEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
@ConditionalOnProperty(
        name = "eap.match-engine.reservation-reconciler.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ReservationReconciler {

    private final RedisOrderBookService orderBookService;
    private final TradeExecutionRepository tradeExecutionRepository;
    private final ReservationReconcilerMetrics metrics;
    private final Duration orphanThreshold;
    private final int batchSize;

    public ReservationReconciler(
            RedisOrderBookService orderBookService,
            TradeExecutionRepository tradeExecutionRepository,
            ReservationReconcilerMetrics metrics,
            @Value("${eap.match-engine.reservation-reconciler.orphan-threshold-seconds:30}") long orphanThresholdSeconds,
            @Value("${eap.match-engine.reservation-reconciler.batch-size:100}") int batchSize) {
        this.orderBookService = orderBookService;
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.metrics = metrics;
        this.orphanThreshold = Duration.ofSeconds(orphanThresholdSeconds);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${eap.match-engine.reservation-reconciler.poll-interval-ms:5000}")
    public void reconcile() {
        reconcileOnce();
    }

    int reconcileOnce() {
        List<RedisOrderBookService.ReservationSnapshot> reservations =
                orderBookService.scanReservations(batchSize);
        int actions = 0;
        for (RedisOrderBookService.ReservationSnapshot reservation : reservations) {
            metrics.scanned();
            if (!reservation.valid()) {
                metrics.invalid();
                log.error("Invalid MatchEngine reservation: key={}, reason={}",
                        reservation.key(), reservation.invalidReason());
                continue;
            }
            actions += reconcileReservation(reservation);
        }
        return actions;
    }

    private int reconcileReservation(RedisOrderBookService.ReservationSnapshot reservation) {
        OrderConfirmedEvent reservedOrder = reservation.order();
        LocalDateTime reservedAt = reservedAtLowerBound(reservation);
        Optional<TradeExecutionEntity> durableTrade = tradeExecutionRepository
                .findFirstByCreatedAtGreaterThanEqualAndBuyerOrderIdOrCreatedAtGreaterThanEqualAndSellerOrderIdOrderByCreatedAtDesc(
                        reservedAt,
                        reservedOrder.getOrderId(),
                        reservedAt,
                        reservedOrder.getOrderId());
        if (durableTrade.isPresent()) {
            return convergeDurableTradeReservation(reservation, durableTrade.get());
        }
        if (!isOrphanReady(reservation)) {
            return 0;
        }
        try {
            orderBookService.releaseReservedOrder(reservedOrder);
            metrics.released();
            log.warn("Released orphan MatchEngine reservation without durable trade: orderId={}, key={}, amount={}",
                    reservedOrder.getOrderId(), reservation.key(), reservedOrder.getAmount());
            return 1;
        } catch (Exception e) {
            metrics.failure();
            log.error("Failed to release orphan MatchEngine reservation: orderId={}, key={}",
                    reservedOrder.getOrderId(), reservation.key(), e);
            return 0;
        }
    }

    private int convergeDurableTradeReservation(
            RedisOrderBookService.ReservationSnapshot reservation,
            TradeExecutionEntity trade) {
        OrderConfirmedEvent reservedOrder = reservation.order();
        int remainingAmount = reservedOrder.getAmount() - trade.getQuantity();
        try {
            if (remainingAmount > 0) {
                reservedOrder.setAmount(remainingAmount);
                orderBookService.releaseReservedOrder(reservedOrder);
                metrics.released();
                log.warn("Released remaining partial MatchEngine reservation after durable trade: tradeId={}, orderId={}, remainingAmount={}",
                        trade.getTradeId(), reservedOrder.getOrderId(), remainingAmount);
            } else {
                orderBookService.completeReservedOrder(reservedOrder);
                metrics.completed();
                log.warn("Completed MatchEngine reservation after durable trade: tradeId={}, orderId={}",
                        trade.getTradeId(), reservedOrder.getOrderId());
            }
            return 1;
        } catch (Exception e) {
            metrics.failure();
            log.error("Failed to converge MatchEngine reservation after durable trade: tradeId={}, orderId={}",
                    trade.getTradeId(), reservedOrder.getOrderId(), e);
            return 0;
        }
    }

    private boolean isOrphanReady(RedisOrderBookService.ReservationSnapshot reservation) {
        long reservedAtEpochMillis = reservation.reservedAtEpochMillis();
        if (reservedAtEpochMillis <= 0) {
            return true;
        }
        Instant reservedAt = Instant.ofEpochMilli(reservedAtEpochMillis);
        return reservedAt.plus(orphanThreshold).isBefore(Instant.now());
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
