package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderCancellationRequestedEvent;
import com.eap.common.event.OrderCancellationResultEvent;
import com.eap.common.event.OrderAssetReservationSucceededEvent;
import com.eap.eap_matchengine.configuration.repository.TradeExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.redisson.client.RedisException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
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

    @Value("${eap.match-engine.order-cancellation.max-technical-attempts:20}")
    private int maxTechnicalAttempts = 20;

    @Value("${eap.match-engine.order-cancellation.prerequisite-alert-after-attempts:40}")
    private int prerequisiteAlertAfterAttempts = 40;

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
                Resolution resolution = resolve(decision);
                if (resolution.waiting()) {
                    reschedulePrerequisite(decision, resolution);
                }
            } catch (RuntimeException e) {
                log.warn("Pending cancellation reconciliation failed: cancellationId={}, orderId={}",
                        decision.cancellationId(), decision.orderId(), e);
                try {
                    handleFailure(decision, e);
                } catch (RuntimeException rescheduleFailure) {
                    log.error("Failed to record pending cancellation failure: cancellationId={}",
                            decision.cancellationId(), rescheduleFailure);
                }
            }
        }
    }

    private Resolution resolve(OrderCancellationDecisionStore.Decision pending) {
        OrderBookRuntimeGuard.Snapshot runtime = requireRuntimeReady();
        OrderCancellationDecisionStore.Decision decision = decisions.find(pending.cancellationId());
        if (decision.complete()) {
            return Resolution.resolved();
        }

        OrderAssetReservationSucceededEvent visibleOrder = orderBook.findOpenOrder(decision.orderId());
        if (visibleOrder != null) {
            decision = decisions.refreshSnapshot(decision.cancellationId(), visibleOrder);
            if (completeFromRedisArbitration(decision, visibleOrder)) {
                return Resolution.resolved();
            }
        } else if (decision.snapshot() != null
                && completeFromRedisArbitration(decision, decision.snapshot())) {
            // Replays the Lua arbitration so a Redis marker can heal a crash before
            // the PostgreSQL cancellation decision and outbox were committed.
            return Resolution.resolved();
        }

        if (processingStore.isReserved(decision.orderId())) {
            return Resolution.waiting(
                    "PREREQUISITE_MATCH_RESERVATION",
                    "Waiting for the active Match reservation to converge");
        }

        // Matching can release a partially filled remainder immediately after the
        // first Lua arbitration lost. Re-read before classifying the order as closed.
        visibleOrder = orderBook.findOpenOrder(decision.orderId());
        if (visibleOrder != null) {
            decision = decisions.refreshSnapshot(decision.cancellationId(), visibleOrder);
            return completeFromRedisArbitration(decision, visibleOrder)
                    ? Resolution.resolved()
                    : Resolution.waiting(
                            "PREREQUISITE_CANCELLATION_ARBITRATION",
                            "Order is still participating in Match arbitration");
        }
        if (processingStore.isReserved(decision.orderId())) {
            return Resolution.waiting(
                    "PREREQUISITE_MATCH_RESERVATION",
                    "Waiting for the active Match reservation to converge");
        }

        IncomingOrderProcessingStore.State admissionState = processingStore.state(decision.orderId());
        if (admissionState != null
                && admissionState.status() == IncomingOrderProcessingStore.Status.PROCESSING) {
            return Resolution.waiting(
                    "PREREQUISITE_ORDER_ADMISSION",
                    "Waiting for Match order admission to leave PROCESSING");
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
            return Resolution.resolved();
        }

        OrderAssetReservationSucceededEvent snapshot = decision.snapshot();
        if (snapshot == null) {
            return Resolution.waiting(
                    "PREREQUISITE_ORDER_SNAPSHOT",
                    "Waiting for the asset-reservation order snapshot");
        }
        IncomingOrderProcessingStore.State state = processingStore.state(snapshot);
        if (state == null || state.status() == IncomingOrderProcessingStore.Status.PROCESSING) {
            return Resolution.waiting(
                    "PREREQUISITE_ORDER_ADMISSION",
                    "Waiting for Match order admission to become durably classifiable");
        }
        verifyRuntimeUnchanged(runtime);
        completeRejected(
                decision,
                OrderCancellationResultEvent.NOT_OPEN,
                "Completed Match admission has no visible order or durable trade");
        return Resolution.resolved();
    }

    private void reschedulePrerequisite(
            OrderCancellationDecisionStore.Decision decision,
            Resolution resolution) {
        int nextWait = decision.prerequisiteWaitCount() + 1;
        boolean updated = decisions.reschedulePrerequisite(
                decision,
                reconcileOwner,
                resolution.errorType(),
                resolution.detail(),
                retryDelayMs(nextWait));
        if (!updated) {
            log.warn("Lost cancellation reconciliation lease while waiting for prerequisite: cancellationId={}",
                    decision.cancellationId());
            return;
        }
        int alertEvery = Math.max(1, prerequisiteAlertAfterAttempts);
        if (nextWait == alertEvery || nextWait % alertEvery == 0) {
            log.error("Cancellation prerequisite remains unresolved: cancellationId={}, orderId={}, type={}, waits={}",
                    decision.cancellationId(), decision.orderId(), resolution.errorType(), nextWait);
        }
    }

    private void handleFailure(
            OrderCancellationDecisionStore.Decision decision,
            RuntimeException failure) {
        FailureClassification classification = classify(failure);
        if (classification.prerequisite()) {
            reschedulePrerequisite(decision, Resolution.waiting(
                    classification.errorType(), failure.toString()));
            return;
        }

        int nextTechnicalAttempt = decision.technicalAttemptCount() + 1;
        boolean terminal = classification.permanent()
                || nextTechnicalAttempt >= Math.max(1, maxTechnicalAttempts);
        if (terminal) {
            String terminalType = classification.permanent()
                    ? classification.errorType()
                    : "RETRY_EXHAUSTED_" + classification.errorType();
            if (!decisions.markTerminal(
                    decision, reconcileOwner, terminalType, failure)) {
                log.warn("Lost cancellation reconciliation lease while marking terminal: cancellationId={}",
                        decision.cancellationId());
                return;
            }
            log.error("Cancellation reconciliation requires intervention: cancellationId={}, orderId={}, type={}, attempts={}",
                    decision.cancellationId(), decision.orderId(), terminalType, nextTechnicalAttempt, failure);
            return;
        }

        if (!decisions.rescheduleTechnical(
                decision,
                reconcileOwner,
                classification.errorType(),
                failure,
                retryDelayMs(nextTechnicalAttempt))) {
            log.warn("Lost cancellation reconciliation lease while scheduling technical retry: cancellationId={}",
                    decision.cancellationId());
        }
    }

    private FailureClassification classify(RuntimeException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof OrderBookRuntimeUnavailableException) {
                return new FailureClassification(true, false, "PREREQUISITE_ORDER_BOOK_NOT_READY");
            }
            if (current instanceof OrderBookDataInvariantException
                    || current instanceof DataIntegrityViolationException
                    || current instanceof IllegalArgumentException
                    || current instanceof ArithmeticException) {
                return new FailureClassification(false, true, "PERMANENT_CANCELLATION_INVARIANT");
            }
            if (current instanceof DataAccessException) {
                return new FailureClassification(false, false, "TRANSIENT_DATA_STORE");
            }
            if (current instanceof RedisException) {
                return new FailureClassification(false, false, "TRANSIENT_REDIS");
            }
            current = current.getCause();
        }
        return new FailureClassification(false, false, "UNKNOWN_RETRYABLE");
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

    private record Resolution(boolean waiting, String errorType, String detail) {
        private static Resolution resolved() {
            return new Resolution(false, null, null);
        }

        private static Resolution waiting(String errorType, String detail) {
            return new Resolution(true, errorType, detail);
        }
    }

    private record FailureClassification(boolean prerequisite, boolean permanent, String errorType) {
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
