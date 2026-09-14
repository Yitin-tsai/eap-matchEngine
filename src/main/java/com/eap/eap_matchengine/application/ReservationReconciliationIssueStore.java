package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderAssetReservationSucceededEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ReservationReconciliationIssueStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public Set<String> findTerminalIssueIds(Set<String> issueIds) {
        if (issueIds.isEmpty()) {
            return Set.of();
        }
        List<String> rows = jdbc.query("""
                SELECT issue_id
                FROM match_engine.reservation_reconciliation_issues
                WHERE issue_id IN (:issueIds)
                  AND status = 'TERMINAL'
                """, new MapSqlParameterSource("issueIds", issueIds),
                (rs, rowNum) -> rs.getString("issue_id"));
        return Set.copyOf(rows);
    }

    public List<RetryableIssue> claimRetryableAbsenceChecks(int limit) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT issue_id
                    FROM match_engine.reservation_reconciliation_issues
                    WHERE status = 'RETRYABLE'
                    ORDER BY last_checked_at, issue_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                )
                UPDATE match_engine.reservation_reconciliation_issues issue
                SET last_checked_at = CURRENT_TIMESTAMP
                FROM candidates
                WHERE issue.issue_id = candidates.issue_id
                RETURNING issue.issue_id, issue.reservation_key
                """, new MapSqlParameterSource("limit", Math.max(1, limit)),
                (rs, rowNum) -> new RetryableIssue(
                        rs.getString("issue_id"), rs.getString("reservation_key")));
    }

    public void resolveAbsentRetryableIssues(Set<String> absentIssueIds) {
        if (absentIssueIds.isEmpty()) {
            return;
        }
        jdbc.update("""
                UPDATE match_engine.reservation_reconciliation_issues
                SET status = 'RESOLVED',
                    resolved_at = CURRENT_TIMESTAMP,
                    last_seen_at = CURRENT_TIMESTAMP
                WHERE issue_id IN (:issueIds)
                  AND status = 'RETRYABLE'
                """, new MapSqlParameterSource("issueIds", absentIssueIds));
    }

    public void recordTerminal(
            RedisOrderBookService.ReservationSnapshot reservation,
            String errorType,
            String error) {
        upsert(reservation, "TERMINAL", errorType, error, 1);
    }

    public FailureRecord recordTransientFailure(
            RedisOrderBookService.ReservationSnapshot reservation,
            String errorType,
            Exception failure,
            int maxAttempts) {
        String error = describe(failure);
        return jdbc.queryForObject("""
                INSERT INTO match_engine.reservation_reconciliation_issues
                    (issue_id, reservation_key, generation_identity, trade_id,
                     order_id, user_id, status, error_type,
                     payload, attempt_count, first_seen_at, last_seen_at, last_error)
                VALUES
                    (:issueId, :reservationKey, :generationIdentity, :tradeId, :orderId, :userId,
                     CASE WHEN :maxAttempts <= 1 THEN 'TERMINAL' ELSE 'RETRYABLE' END,
                     :errorType, :payload, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, :lastError)
                ON CONFLICT (issue_id) DO UPDATE
                SET trade_id = EXCLUDED.trade_id,
                    order_id = EXCLUDED.order_id,
                    user_id = EXCLUDED.user_id,
                    status = CASE
                        WHEN match_engine.reservation_reconciliation_issues.status = 'TERMINAL'
                            THEN 'TERMINAL'
                        WHEN match_engine.reservation_reconciliation_issues.status = 'RESOLVED'
                            THEN CASE WHEN :maxAttempts <= 1 THEN 'TERMINAL' ELSE 'RETRYABLE' END
                        WHEN match_engine.reservation_reconciliation_issues.attempt_count + 1 >= :maxAttempts
                            THEN 'TERMINAL'
                        ELSE 'RETRYABLE'
                    END,
                    error_type = EXCLUDED.error_type,
                    payload = EXCLUDED.payload,
                    attempt_count = CASE
                        WHEN match_engine.reservation_reconciliation_issues.status = 'RESOLVED' THEN 1
                        ELSE match_engine.reservation_reconciliation_issues.attempt_count + 1
                    END,
                    first_seen_at = CASE
                        WHEN match_engine.reservation_reconciliation_issues.status = 'RESOLVED'
                            THEN CURRENT_TIMESTAMP
                        ELSE match_engine.reservation_reconciliation_issues.first_seen_at
                    END,
                    last_seen_at = CURRENT_TIMESTAMP,
                    last_error = EXCLUDED.last_error,
                    resolved_at = NULL
                RETURNING status, attempt_count
                """, parameters(reservation, errorType, error)
                        .addValue("maxAttempts", Math.max(1, maxAttempts)),
                (rs, rowNum) -> new FailureRecord(
                        "TERMINAL".equals(rs.getString("status")),
                        rs.getInt("attempt_count")));
    }

    public void markResolved(String issueId) {
        jdbc.update("""
                UPDATE match_engine.reservation_reconciliation_issues
                SET status = 'RESOLVED',
                    resolved_at = CURRENT_TIMESTAMP,
                    last_seen_at = CURRENT_TIMESTAMP
                WHERE issue_id = :issueId
                  AND status = 'RETRYABLE'
                """, new MapSqlParameterSource("issueId", issueId));
    }

    private void upsert(
            RedisOrderBookService.ReservationSnapshot reservation,
            String status,
            String errorType,
            String error,
            int attemptCount) {
        jdbc.update("""
                INSERT INTO match_engine.reservation_reconciliation_issues
                    (issue_id, reservation_key, generation_identity, trade_id,
                     order_id, user_id, status, error_type,
                     payload, attempt_count, first_seen_at, last_seen_at, last_error)
                VALUES
                    (:issueId, :reservationKey, :generationIdentity, :tradeId,
                     :orderId, :userId, :status, :errorType,
                     :payload, :attemptCount, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, :lastError)
                ON CONFLICT (issue_id) DO UPDATE
                SET trade_id = EXCLUDED.trade_id,
                    order_id = EXCLUDED.order_id,
                    user_id = EXCLUDED.user_id,
                    status = EXCLUDED.status,
                    error_type = EXCLUDED.error_type,
                    payload = EXCLUDED.payload,
                    attempt_count = CASE
                        WHEN match_engine.reservation_reconciliation_issues.status = 'RESOLVED'
                            THEN EXCLUDED.attempt_count
                        ELSE match_engine.reservation_reconciliation_issues.attempt_count + 1
                    END,
                    first_seen_at = CASE
                        WHEN match_engine.reservation_reconciliation_issues.status = 'RESOLVED'
                            THEN CURRENT_TIMESTAMP
                        ELSE match_engine.reservation_reconciliation_issues.first_seen_at
                    END,
                    last_seen_at = CURRENT_TIMESTAMP,
                    last_error = EXCLUDED.last_error,
                    resolved_at = NULL
                """, parameters(reservation, errorType, error)
                .addValue("status", status)
                .addValue("attemptCount", attemptCount));
    }

    private MapSqlParameterSource parameters(
            RedisOrderBookService.ReservationSnapshot reservation,
            String errorType,
            String error) {
        OrderAssetReservationSucceededEvent order = reservation.order();
        return new MapSqlParameterSource()
                .addValue("issueId", fingerprint(reservation))
                .addValue("reservationKey", reservation.key())
                .addValue("generationIdentity", reservation.generationIdentity())
                .addValue("tradeId", reservation.tradeId())
                .addValue("orderId", order == null ? null : order.getOrderId())
                .addValue("userId", order == null ? null : order.getUserId())
                .addValue("errorType", errorType)
                .addValue("payload", payload(reservation))
                .addValue("lastError", truncate(error == null ? "no detail" : error));
    }

    public String fingerprint(RedisOrderBookService.ReservationSnapshot reservation) {
        OrderAssetReservationSucceededEvent order = reservation.order();
        String identity = String.join("\n",
                value(reservation.generationIdentity()),
                value(reservation.key()),
                value(reservation.tradeId()),
                Long.toString(reservation.reservedAtEpochMillis()),
                order == null ? "" : value(order.getOrderId()),
                order == null ? "" : value(order.getUserId()),
                order == null ? "" : value(order.getMarketId()),
                order == null ? "" : value(order.getMarketSequence()),
                order == null ? "" : value(order.getOrderType()),
                order == null ? "" : value(order.getPrice()),
                order == null ? "" : value(order.getAmount()),
                value(reservation.rawPayload()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private String payload(RedisOrderBookService.ReservationSnapshot reservation) {
        if (reservation.rawPayload() != null) {
            return truncate(reservation.rawPayload());
        }
        if (reservation.order() == null) {
            return null;
        }
        try {
            return truncate(objectMapper.writeValueAsString(reservation.order()));
        } catch (JsonProcessingException e) {
            return truncate(String.valueOf(reservation.order()));
        }
    }

    private String describe(Exception failure) {
        return truncate(failure.getClass().getSimpleName() + ": "
                + (failure.getMessage() == null ? "no message" : failure.getMessage()));
    }

    private String truncate(String value) {
        return value == null || value.length() <= 4_000 ? value : value.substring(0, 4_000);
    }

    public record FailureRecord(boolean terminal, int attemptCount) {
    }

    public record RetryableIssue(String issueId, String reservationKey) {
    }
}
