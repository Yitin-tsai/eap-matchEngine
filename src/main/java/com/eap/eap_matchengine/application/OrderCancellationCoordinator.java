package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderCancellationRequestedEvent;
import com.eap.common.event.OrderCancellationResultEvent;
import com.eap.common.event.OrderAssetReservationSucceededEvent;
import com.eap.eap_matchengine.configuration.repository.TradeExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

@Component
@Slf4j
public class OrderCancellationCoordinator {

    private final RedisOrderBookService orderBook;
    private final IncomingOrderProcessingStore processingStore;
    private final OrderCancellationDecisionStore decisions;
    private final TradeExecutionRepository trades;
    private final OrderBookRuntimeGuard runtimeGuard;
    private final OrderBookRuntimeControlStore runtimeControls;
    private final String reconcileOwner = UUID.randomUUID().toString();

    @Value("${eap.match-engine.order-cancellation.reconcile-batch-size:50}")
    private int reconcileBatchSize = 50;

    @Value("${eap.match-engine.order-cancellation.reconcile-lease-ms:30000}")
    private long reconcileLeaseMs = 30_000L;

    @Autowired
    public OrderCancellationCoordinator(
            RedisOrderBookService orderBook,
            IncomingOrderProcessingStore processingStore,
            OrderCancellationDecisionStore decisions,
            TradeExecutionRepository trades,
            OrderBookRuntimeGuard runtimeGuard,
            OrderBookRuntimeControlStore runtimeControls) {
        this.orderBook = orderBook;
        this.processingStore = processingStore;
        this.decisions = decisions;
        this.trades = trades;
        this.runtimeGuard = runtimeGuard;
        this.runtimeControls = runtimeControls;
    }

    OrderCancellationCoordinator(
            RedisOrderBookService orderBook,
            IncomingOrderProcessingStore processingStore,
            OrderCancellationDecisionStore decisions,
            TradeExecutionRepository trades) {
        this(orderBook, processingStore, decisions, trades, null, null);
    }

    public void request(OrderCancellationRequestedEvent request) {
        validate(request);
        CancellationIntake intake = runtimeControls == null
                ? intake(request)
                : runtimeControls.withCancellationIntakeLock(() -> intake(request));
        if (intake.resolveNow()) {
            resolve(intake.decision());
        }
    }

    private CancellationIntake intake(OrderCancellationRequestedEvent request) {
        // PENDING makes an interruption recoverable. The shared PostgreSQL advisory
        // lock prevents activation from validating an earlier snapshot while this
        // durable fact and its READY-generation Redis intent are being established.
        OrderCancellationDecisionStore.Decision decision = decisions.begin(request, null);
        if (decision.complete()) {
            return new CancellationIntake(decision, false);
        }
        if (!runtimeReady()) {
            log.warn("Cancellation persisted but deferred while CDA order book is unavailable: cancellationId={}, orderId={}",
                    request.getCancellationId(), request.getOrderId());
            return new CancellationIntake(decision, false);
        }
        orderBook.recordCancellationIntent(request.getOrderId(), request.getCancellationId());
        return new CancellationIntake(decision, true);
    }

    public void resolveAdmissionBlockedByCancellationIntent(OrderAssetReservationSucceededEvent order) {
        requireRuntimeReady();
        OrderCancellationDecisionStore.Decision decision = decisions.findByOrderId(order.getOrderId());
        if (decision == null) {
            throw new IllegalStateException("Redis reported cancellation intent without a pending decision: orderId="
                    + order.getOrderId());
        }
        validateOwner(decision, order);
        if (decision.complete()) {
            if (OrderCancellationResultEvent.CANCELLED.equals(decision.status())) {
                validateStableIdentity(decision, order);
                return;
            }
            throw new IllegalStateException("Redis cancellation intent conflicts with completed decision: orderId="
                    + order.getOrderId() + ", outcome=" + decision.status());
        }
        decisions.complete(
                decision,
                OrderCancellationResultEvent.CANCELLED,
                null,
                order,
                decision.originalAmount());
        log.info("Order cancellation accepted before Match admission: cancellationId={}, orderId={}, amount={}",
                decision.cancellationId(), order.getOrderId(), order.getAmount());
    }

    @Scheduled(
            fixedDelayString = "${eap.match-engine.order-cancellation.reconcile-delay-ms:250}",
            initialDelayString = "${eap.match-engine.order-cancellation.reconcile-initial-delay-ms:1000}")
    public void reconcilePending() {
        if (!runtimeReady()) {
            return;
        }
        for (OrderCancellationDecisionStore.Decision decision :
                decisions.claimRetryable(reconcileBatchSize, reconcileOwner, reconcileLeaseMs)) {
            try {
                orderBook.recordCancellationIntent(decision.orderId(), decision.cancellationId());
                resolve(decision);
                OrderCancellationDecisionStore.Decision resolved = decisions.find(decision.cancellationId());
                if (!resolved.complete()) {
                    decisions.reschedule(
                            resolved,
                            reconcileOwner,
                            null,
                            retryDelayMs(resolved.attemptCount()));
                }
            } catch (RuntimeException e) {
                log.warn("Pending cancellation reconciliation failed: cancellationId={}, orderId={}",
                        decision.cancellationId(), decision.orderId(), e);
                try {
                    decisions.reschedule(
                            decision,
                            reconcileOwner,
                            e,
                            retryDelayMs(decision.attemptCount()));
                } catch (RuntimeException rescheduleFailure) {
                    log.error("Failed to reschedule pending cancellation: cancellationId={}",
                            decision.cancellationId(), rescheduleFailure);
                }
            }
        }
    }

    private void resolve(OrderCancellationDecisionStore.Decision pending) {
        OrderBookRuntimeGuard.Snapshot runtime = requireRuntimeReady();
        OrderCancellationDecisionStore.Decision decision = decisions.find(pending.cancellationId());
        if (decision.complete()) {
            return;
        }

        OrderAssetReservationSucceededEvent visibleOrder = orderBook.findOpenOrder(decision.orderId());
        if (visibleOrder != null) {
            decision = decisions.refreshSnapshot(decision.cancellationId(), visibleOrder);
            if (completeFromRedisArbitration(decision, visibleOrder)) {
                return;
            }
        } else if (decision.snapshot() != null
                && completeFromRedisArbitration(decision, decision.snapshot())) {
            // Replays the Lua arbitration so a Redis marker can heal a crash before
            // the PostgreSQL cancellation decision and outbox were committed.
            return;
        }

        if (processingStore.isReserved(decision.orderId())) {
            return;
        }

        // Matching can release a partially filled remainder immediately after the
        // first Lua arbitration lost. Re-read before classifying the order as closed.
        visibleOrder = orderBook.findOpenOrder(decision.orderId());
        if (visibleOrder != null) {
            decision = decisions.refreshSnapshot(decision.cancellationId(), visibleOrder);
            completeFromRedisArbitration(decision, visibleOrder);
            return;
        }
        if (processingStore.isReserved(decision.orderId())) {
            return;
        }

        IncomingOrderProcessingStore.State admissionState = processingStore.state(decision.orderId());
        if (admissionState != null
                && admissionState.status() == IncomingOrderProcessingStore.Status.PROCESSING) {
            return;
        }

        // A snapshot-free request may have been interrupted before the Redis intent.
        // Durable trade facts can classify it only after admission is no longer active.
        long matchedQuantity = durableMatchedQuantity(decision);
        if (matchedQuantity > 0) {
            verifyRuntimeUnchanged(runtime);
            completeRejected(
                    decision,
                    OrderCancellationResultEvent.ALREADY_MATCHED,
                    "Order has already participated in a durable trade and has no open remainder");
            return;
        }

        OrderAssetReservationSucceededEvent snapshot = decision.snapshot();
        if (snapshot == null) {
            return;
        }
        IncomingOrderProcessingStore.State state = processingStore.state(snapshot);
        if (state == null || state.status() == IncomingOrderProcessingStore.Status.PROCESSING) {
            return;
        }
        verifyRuntimeUnchanged(runtime);
        completeRejected(
                decision,
                OrderCancellationResultEvent.NOT_OPEN,
                "Completed Match admission has no visible order or durable trade");
    }

    private boolean completeFromRedisArbitration(
            OrderCancellationDecisionStore.Decision decision,
            OrderAssetReservationSucceededEvent candidate) {
        validateOwner(decision, candidate);
        RedisOrderBookService.CancellationArbitration arbitration =
                orderBook.arbitrateCancellation(candidate, decision.cancellationId());
        if (arbitration.outcome() != RedisOrderBookService.CancellationOutcome.CANCELLED
                && arbitration.outcome()
                != RedisOrderBookService.CancellationOutcome.ALREADY_CANCELLED_BY_REQUEST) {
            return false;
        }
        decisions.complete(
                decision,
                OrderCancellationResultEvent.CANCELLED,
                null,
                arbitration.cancelledOrder(),
                decision.originalAmount());
        log.info("Order cancellation accepted: cancellationId={}, orderId={}, amount={}",
                decision.cancellationId(), decision.orderId(), arbitration.cancelledOrder().getAmount());
        return true;
    }

    private void validateOwner(
            OrderCancellationDecisionStore.Decision decision,
            OrderAssetReservationSucceededEvent order) {
        if (!decision.userId().equals(order.getUserId())) {
            throw new IllegalArgumentException("Cancellation request user does not own Match order: orderId="
                    + order.getOrderId());
        }
    }

    private void validateStableIdentity(
            OrderCancellationDecisionStore.Decision decision,
            OrderAssetReservationSucceededEvent order) {
        OrderAssetReservationSucceededEvent snapshot = decision.snapshot();
        if (snapshot == null
                || !Objects.equals(snapshot.getMarketId(), order.getMarketId())
                || !Objects.equals(snapshot.getMarketSequence(), order.getMarketSequence())
                || !Objects.equals(snapshot.getOrderType(), order.getOrderType())
                || !Objects.equals(snapshot.getPrice(), order.getPrice())
                || !Objects.equals(decision.originalAmount(), order.getAmount())
                || !sameTimestampAtMicrosecondPrecision(snapshot.getCreatedAt(), order.getCreatedAt())) {
            throw new IllegalStateException("Completed cancellation conflicts with asset-reservation success identity: orderId="
                    + order.getOrderId());
        }
    }

    private boolean sameTimestampAtMicrosecondPrecision(LocalDateTime left, LocalDateTime right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.truncatedTo(ChronoUnit.MICROS).equals(right.truncatedTo(ChronoUnit.MICROS));
    }

    private void completeRejected(
            OrderCancellationDecisionStore.Decision decision,
            String outcome,
            String reason) {
        decisions.complete(decision, outcome, reason, null, null);
        log.info("Order cancellation rejected: cancellationId={}, orderId={}, outcome={}",
                decision.cancellationId(), decision.orderId(), outcome);
    }

    private void validate(OrderCancellationRequestedEvent request) {
        if (request == null || request.getCancellationId() == null
                || request.getOrderId() == null || request.getUserId() == null
                || request.getOriginalAmount() == null || request.getOriginalAmount() <= 0
                || request.getRequestedAt() == null) {
            throw new IllegalArgumentException(
                    "Cancellation request identifiers, positive originalAmount and requestedAt are required");
        }
    }

    private long durableMatchedQuantity(OrderCancellationDecisionStore.Decision decision) {
        if ("BUY".equalsIgnoreCase(decision.orderType())) {
            return trades.sumQuantityByBuyerOrderId(decision.orderId());
        }
        if ("SELL".equalsIgnoreCase(decision.orderType())) {
            return trades.sumQuantityBySellerOrderId(decision.orderId());
        }
        return trades.sumQuantityByBuyerOrderId(decision.orderId())
                + trades.sumQuantityBySellerOrderId(decision.orderId());
    }

    private record CancellationIntake(
            OrderCancellationDecisionStore.Decision decision,
            boolean resolveNow) {
    }

    private long retryDelayMs(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 7);
        return Math.min(30_000L, 250L << exponent);
    }

    private boolean runtimeReady() {
        return runtimeGuard == null || runtimeGuard.isReady();
    }

    private OrderBookRuntimeGuard.Snapshot requireRuntimeReady() {
        return runtimeGuard == null ? null : runtimeGuard.requireReady();
    }

    private void verifyRuntimeUnchanged(OrderBookRuntimeGuard.Snapshot runtime) {
        if (runtimeGuard != null) {
            runtimeGuard.verifyUnchanged(runtime);
        }
    }
}
