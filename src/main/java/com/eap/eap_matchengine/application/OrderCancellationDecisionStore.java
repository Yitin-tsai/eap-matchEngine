package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderCancellationRequestedEvent;
import com.eap.common.event.OrderCancellationResultEvent;
import com.eap.common.event.OrderAssetReservationSucceededEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.eap.common.constants.RabbitMQConstants.ORDER_CANCELLATION_RESULT_KEY;

@Component
@RequiredArgsConstructor
public class OrderCancellationDecisionStore {

    private static final String DECISION_COLUMNS = """
            cancellation_id, order_id, user_id, status, reason, market_id,
            order_type, market_sequence, limit_price, original_amount, cancelled_amount,
            order_created_at, requested_at, decided_at, attempt_count
            """;
    private static final String RETURNED_DECISION_COLUMNS = """
            cancellation.cancellation_id, cancellation.order_id, cancellation.user_id,
            cancellation.status, cancellation.reason, cancellation.market_id,
            cancellation.order_type, cancellation.market_sequence, cancellation.limit_price,
            cancellation.original_amount, cancellation.cancelled_amount,
            cancellation.order_created_at, cancellation.requested_at,
            cancellation.decided_at, cancellation.attempt_count
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Transactional
    public Decision begin(
            OrderCancellationRequestedEvent request,
            OrderAssetReservationSucceededEvent openOrder) {
        jdbc.update("""
                INSERT INTO match_engine.order_cancellations
                    (cancellation_id, order_id, user_id, status, market_id, order_type,
                     market_sequence, limit_price, original_amount, cancelled_amount, order_created_at,
                     requested_at, created_at, updated_at)
                VALUES
                    (:cancellationId, :orderId, :userId, 'PENDING', :marketId, :orderType,
                     :marketSequence, :limitPrice, :originalAmount, :cancelledAmount,
                     :orderCreatedAt, :requestedAt,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (cancellation_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("cancellationId", request.getCancellationId())
                .addValue("orderId", request.getOrderId())
                .addValue("userId", request.getUserId())
                .addValue("marketId", openOrder == null ? null : openOrder.getMarketId())
                .addValue("orderType", openOrder == null ? null : openOrder.getOrderType())
                .addValue("marketSequence", openOrder == null ? null : openOrder.getMarketSequence())
                .addValue("limitPrice", openOrder == null ? null : openOrder.getPrice())
                .addValue("originalAmount", request.getOriginalAmount())
                .addValue("cancelledAmount", openOrder == null ? null : openOrder.getAmount())
                .addValue("orderCreatedAt", openOrder == null ? null : openOrder.getCreatedAt())
                .addValue("requestedAt", request.getRequestedAt()));
        if (openOrder != null) {
            refreshSnapshot(request.getCancellationId(), openOrder);
        }
        Decision decision = find(request.getCancellationId());
        if (!decision.orderId().equals(request.getOrderId())
                || !decision.userId().equals(request.getUserId())
                || !Objects.equals(decision.originalAmount(), request.getOriginalAmount())) {
            throw new IllegalStateException("Cancellation id is already bound to another request identity: "
                    + request.getCancellationId());
        }
        return decision;
    }

    public Decision find(UUID cancellationId) {
        List<Decision> rows = jdbc.query("""
                SELECT %s
                FROM match_engine.order_cancellations
                WHERE cancellation_id = :cancellationId
                """.formatted(DECISION_COLUMNS),
                new MapSqlParameterSource("cancellationId", cancellationId), this::mapDecision);
        if (rows.size() != 1) {
            throw new IllegalStateException("Cancellation decision not found: " + cancellationId);
        }
        return rows.get(0);
    }

    public Decision findByOrderId(UUID orderId) {
        List<Decision> rows = jdbc.query("""
                SELECT %s
                FROM match_engine.order_cancellations
                WHERE order_id = :orderId
                """.formatted(DECISION_COLUMNS),
                new MapSqlParameterSource("orderId", orderId), this::mapDecision);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Decision> claimRetryable(int limit, String owner, long leaseMs) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT cancellation_id
                    FROM match_engine.order_cancellations
                    WHERE (status = 'PENDING' AND next_retry_at <= CURRENT_TIMESTAMP)
                       OR (status = 'IN_PROGRESS' AND claim_until <= CURRENT_TIMESTAMP)
                    ORDER BY next_retry_at, created_at, cancellation_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                )
                UPDATE match_engine.order_cancellations AS cancellation
                SET status = 'IN_PROGRESS',
                    attempt_count = cancellation.attempt_count + 1,
                    claimed_by = :owner,
                    claim_until = CURRENT_TIMESTAMP + (:leaseMs * INTERVAL '1 millisecond'),
                    updated_at = CURRENT_TIMESTAMP
                FROM candidates
                WHERE cancellation.cancellation_id = candidates.cancellation_id
                RETURNING %s
                """.formatted(RETURNED_DECISION_COLUMNS),
                new MapSqlParameterSource()
                        .addValue("limit", limit)
                        .addValue("owner", owner)
                        .addValue("leaseMs", leaseMs),
                this::mapDecision);
    }

    public Decision refreshSnapshot(UUID cancellationId, OrderAssetReservationSucceededEvent order) {
        jdbc.update("""
                UPDATE match_engine.order_cancellations
                SET market_id = :marketId,
                    market_sequence = :marketSequence,
                    order_type = :orderType,
                    limit_price = :limitPrice,
                    cancelled_amount = :cancelledAmount,
                    order_created_at = :orderCreatedAt,
                    updated_at = CURRENT_TIMESTAMP
                WHERE cancellation_id = :cancellationId
                  AND status IN ('PENDING', 'IN_PROGRESS')
                """, new MapSqlParameterSource()
                .addValue("cancellationId", cancellationId)
                .addValue("marketId", order.getMarketId())
                .addValue("marketSequence", order.getMarketSequence())
                .addValue("orderType", order.getOrderType())
                .addValue("limitPrice", order.getPrice())
                .addValue("cancelledAmount", order.getAmount())
                .addValue("orderCreatedAt", order.getCreatedAt()));
        return find(cancellationId);
    }

    @Transactional
    public Decision complete(
            Decision pending,
            String outcome,
            String reason,
            OrderAssetReservationSucceededEvent cancelledOrder,
            Integer originalAmount) {
        if (pending.complete()) {
            return pending;
        }
        if (OrderCancellationResultEvent.CANCELLED.equals(outcome) && cancelledOrder == null) {
            throw new IllegalArgumentException("Cancelled decision requires the atomically removed order snapshot");
        }
        if (OrderCancellationResultEvent.CANCELLED.equals(outcome)
                && (originalAmount == null || originalAmount <= 0
                || cancelledOrder.getAmount() > originalAmount)) {
            throw new IllegalArgumentException(
                    "Cancelled decision requires a valid immutable original amount");
        }
        Decision resultSource = cancelledOrder == null
                ? pending
                : refreshSnapshot(pending.cancellationId(), cancelledOrder, originalAmount);
        LocalDateTime decidedAt = LocalDateTime.now();
        OrderCancellationResultEvent result = OrderCancellationResultEvent.builder()
                .cancellationId(resultSource.cancellationId())
                .orderId(resultSource.orderId())
                .userId(resultSource.userId())
                .outcome(outcome)
                .reason(reason)
                .orderType(resultSource.orderType())
                .limitPrice(resultSource.limitPrice())
                .cancelledAmount(resultSource.cancelledAmount())
                .decidedAt(decidedAt)
                .build();
        String payload = serialize(result);

        jdbc.update("""
                WITH decided AS (
                    UPDATE match_engine.order_cancellations
                    SET status = :outcome,
                        reason = :reason,
                        decided_at = :decidedAt,
                        claimed_by = NULL,
                        claim_until = NULL,
                        last_error = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE cancellation_id = :cancellationId
                      AND status IN ('PENDING', 'IN_PROGRESS')
                    RETURNING cancellation_id
                )
                INSERT INTO match_engine.trade_outbox
                    (event_type, aggregate_type, aggregate_id, routing_key, payload,
                     status, attempt_count, next_retry_at, created_at, updated_at)
                SELECT 'OrderCancellationResultEvent', 'OrderCancellation',
                       cancellation_id::text, :routingKey, :payload,
                       'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                FROM decided
                """, new MapSqlParameterSource()
                .addValue("outcome", outcome)
                .addValue("reason", reason)
                .addValue("decidedAt", decidedAt)
                .addValue("cancellationId", pending.cancellationId())
                .addValue("routingKey", ORDER_CANCELLATION_RESULT_KEY)
                .addValue("payload", payload));
        return find(pending.cancellationId());
    }

    public void reschedule(
            Decision decision,
            String owner,
            RuntimeException failure,
            long delayMs) {
        jdbc.update("""
                UPDATE match_engine.order_cancellations
                SET status = 'PENDING',
                    next_retry_at = CURRENT_TIMESTAMP + (:delayMs * INTERVAL '1 millisecond'),
                    claimed_by = NULL,
                    claim_until = NULL,
                    last_error = :lastError,
                    updated_at = CURRENT_TIMESTAMP
                WHERE cancellation_id = :cancellationId
                  AND status = 'IN_PROGRESS'
                  AND claimed_by = :owner
                """, new MapSqlParameterSource()
                .addValue("cancellationId", decision.cancellationId())
                .addValue("owner", owner)
                .addValue("delayMs", delayMs)
                .addValue("lastError", failure == null ? null : truncate(failure.toString())));
    }

    private Decision refreshSnapshot(
            UUID cancellationId,
            OrderAssetReservationSucceededEvent order,
            Integer originalAmount) {
        jdbc.update("""
                UPDATE match_engine.order_cancellations
                SET original_amount = COALESCE(original_amount, :originalAmount)
                WHERE cancellation_id = :cancellationId
                  AND status IN ('PENDING', 'IN_PROGRESS')
                  AND (original_amount IS NULL OR original_amount = :originalAmount)
                """, new MapSqlParameterSource()
                .addValue("cancellationId", cancellationId)
                .addValue("originalAmount", originalAmount));
        Decision refreshed = refreshSnapshot(cancellationId, order);
        if (!Objects.equals(refreshed.originalAmount(), originalAmount)) {
            throw new IllegalStateException("Cancellation original amount conflicts with persisted identity: "
                    + cancellationId);
        }
        return refreshed;
    }

    private String truncate(String value) {
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    private String serialize(OrderCancellationResultEvent result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize cancellation result: "
                    + result.getCancellationId(), e);
        }
    }

    private Decision mapDecision(ResultSet rs, int rowNum) throws SQLException {
        return new Decision(
                rs.getObject("cancellation_id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getString("market_id"),
                rs.getString("order_type"),
                rs.getObject("market_sequence", Long.class),
                rs.getObject("limit_price", Integer.class),
                rs.getObject("original_amount", Integer.class),
                rs.getObject("cancelled_amount", Integer.class),
                rs.getObject("order_created_at", LocalDateTime.class),
                rs.getObject("requested_at", LocalDateTime.class),
                rs.getObject("decided_at", LocalDateTime.class),
                rs.getInt("attempt_count"));
    }

    public record Decision(
            UUID cancellationId,
            UUID orderId,
            UUID userId,
            String status,
            String reason,
            String marketId,
            String orderType,
            Long marketSequence,
            Integer limitPrice,
            Integer originalAmount,
            Integer cancelledAmount,
            LocalDateTime orderCreatedAt,
            LocalDateTime requestedAt,
            LocalDateTime decidedAt,
            int attemptCount) {

        public boolean complete() {
            return OrderCancellationResultEvent.CANCELLED.equals(status)
                    || OrderCancellationResultEvent.ALREADY_MATCHED.equals(status)
                    || OrderCancellationResultEvent.NOT_OPEN.equals(status);
        }

        public OrderAssetReservationSucceededEvent snapshot() {
            if (orderType == null || limitPrice == null || cancelledAmount == null) {
                return null;
            }
            return OrderAssetReservationSucceededEvent.builder()
                    .orderId(orderId)
                    .userId(userId)
                    .marketId(marketId)
                    .marketSequence(marketSequence)
                    .orderType(orderType)
                    .price(limitPrice)
                    .amount(cancelledAmount)
                    .createdAt(orderCreatedAt)
                    .build();
        }
    }
}
