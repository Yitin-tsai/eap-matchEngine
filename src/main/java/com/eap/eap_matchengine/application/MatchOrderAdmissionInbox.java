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
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class MatchOrderAdmissionInbox {

    private static final int ERROR_LIMIT = 2_000;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ReceiveOutcome receive(OrderAssetReservationSucceededEvent event) {
        validateIdentity(event);
        String payload = serialize(event);
        String payloadHash = sha256(payload);
        MapSqlParameterSource identity = identity(event)
                .addValue("payload", payload)
                .addValue("payloadHash", payloadHash);
        int inserted = jdbc.update("""
                INSERT INTO match_engine.order_admission_inbox
                    (order_id, market_id, market_sequence, payload, payload_hash,
                     schema_version, status, attempt_count, next_retry_at,
                     received_at, updated_at)
                VALUES
                    (:orderId, :marketId, :marketSequence, :payload, :payloadHash,
                     1, 'PENDING', 0, CURRENT_TIMESTAMP,
                     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT DO NOTHING
                """, identity);
        if (inserted == 1) {
            return ReceiveOutcome.ACCEPTED;
        }

        ExistingIdentity existing = findExistingIdentity(event);
        if (existing != null
                && existing.orderId().equals(event.getOrderId())
                && existing.payloadHash().equals(payloadHash)) {
            return ReceiveOutcome.DUPLICATE;
        }

        if (existing == null) {
            throw new IllegalStateException("Match admission inbox conflict row disappeared: orderId="
                    + event.getOrderId());
        }
        String error = "Match admission identity conflict: incomingOrderId=" + event.getOrderId()
                + ", existingOrderId=" + existing.orderId()
                + ", marketId=" + event.getMarketId()
                + ", marketSequence=" + event.getMarketSequence();
        jdbc.update("""
                UPDATE match_engine.order_admission_inbox
                SET status = CASE WHEN status = 'APPLIED' THEN status ELSE 'FAILED_PERMANENT' END,
                    error_type = 'IDENTITY_CONFLICT',
                    last_error = :lastError,
                    conflict_detected_at = CURRENT_TIMESTAMP,
                    conflicting_payload = :payload,
                    claimed_by = CASE WHEN status = 'APPLIED' THEN claimed_by ELSE NULL END,
                    claim_until = CASE WHEN status = 'APPLIED' THEN claim_until ELSE NULL END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE order_id = :existingOrderId
                """, new MapSqlParameterSource()
                .addValue("existingOrderId", existing.orderId())
                .addValue("payload", payload)
                .addValue("lastError", error));
        return ReceiveOutcome.CONFLICT;
    }

    public List<InboxEntry> claimRetryable(int limit, String owner, long leaseMs) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT order_id
                    FROM match_engine.order_admission_inbox
                    WHERE (status IN ('PENDING', 'PENDING_PREREQUISITE', 'FAILED_RETRYABLE')
                               AND next_retry_at <= CURRENT_TIMESTAMP)
                       OR (status = 'IN_PROGRESS' AND claim_until <= CURRENT_TIMESTAMP)
                    ORDER BY next_retry_at, market_id, market_sequence, received_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT :limit
                )
                UPDATE match_engine.order_admission_inbox AS inbox
                SET status = 'IN_PROGRESS',
                    attempt_count = inbox.attempt_count + 1,
                    claimed_by = :owner,
                    claim_until = CURRENT_TIMESTAMP + (:leaseMs * INTERVAL '1 millisecond'),
                    updated_at = CURRENT_TIMESTAMP
                FROM candidates
                WHERE inbox.order_id = candidates.order_id
                RETURNING inbox.order_id, inbox.payload, inbox.attempt_count
                """, new MapSqlParameterSource()
                .addValue("limit", limit)
                .addValue("owner", owner)
                .addValue("leaseMs", leaseMs),
                (rs, rowNum) -> new InboxEntry(
                        rs.getObject("order_id", UUID.class),
                        deserialize(rs.getString("payload")),
                        rs.getInt("attempt_count")));
    }

    public boolean markApplied(InboxEntry entry, String owner) {
        return jdbc.update("""
                UPDATE match_engine.order_admission_inbox
                SET status = 'APPLIED', applied_at = CURRENT_TIMESTAMP,
                    claimed_by = NULL, claim_until = NULL,
                    error_type = NULL, last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE order_id = :orderId
                  AND status = 'IN_PROGRESS' AND claimed_by = :owner
                """, claimParams(entry, owner)) == 1;
    }

    public boolean reschedule(
            InboxEntry entry,
            String owner,
            String status,
            String errorType,
            Exception failure,
            long delayMs) {
        return jdbc.update("""
                UPDATE match_engine.order_admission_inbox
                SET status = :status,
                    next_retry_at = CURRENT_TIMESTAMP + (:delayMs * INTERVAL '1 millisecond'),
                    claimed_by = NULL, claim_until = NULL,
                    error_type = :errorType, last_error = :lastError,
                    updated_at = CURRENT_TIMESTAMP
                WHERE order_id = :orderId
                  AND status = 'IN_PROGRESS' AND claimed_by = :owner
                """, claimParams(entry, owner)
                .addValue("status", status)
                .addValue("delayMs", delayMs)
                .addValue("errorType", errorType)
                .addValue("lastError", truncate(failure.toString()))) == 1;
    }

    public boolean markPermanent(
            InboxEntry entry,
            String owner,
            String errorType,
            Exception failure) {
        return jdbc.update("""
                UPDATE match_engine.order_admission_inbox
                SET status = 'FAILED_PERMANENT',
                    claimed_by = NULL, claim_until = NULL,
                    error_type = :errorType, last_error = :lastError,
                    updated_at = CURRENT_TIMESTAMP
                WHERE order_id = :orderId
                  AND status = 'IN_PROGRESS' AND claimed_by = :owner
                """, claimParams(entry, owner)
                .addValue("errorType", errorType)
                .addValue("lastError", truncate(failure.toString()))) == 1;
    }

    public Map<String, Long> countByStatus() {
        return jdbc.query("""
                SELECT status, COUNT(*) AS rows
                FROM match_engine.order_admission_inbox
                GROUP BY status
                """, rs -> {
                    Map<String, Long> counts = new java.util.HashMap<>();
                    while (rs.next()) {
                        counts.put(rs.getString("status"), rs.getLong("rows"));
                    }
                    return Map.copyOf(counts);
                });
    }

    public long countConflicts() {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM match_engine.order_admission_inbox
                WHERE conflict_detected_at IS NOT NULL
                """, new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    public boolean retryExhaustedTechnicalFailure(UUID orderId) {
        return jdbc.update("""
                UPDATE match_engine.order_admission_inbox
                SET status = 'PENDING', next_retry_at = CURRENT_TIMESTAMP,
                    attempt_count = 0,
                    claimed_by = NULL, claim_until = NULL,
                    error_type = NULL, last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE order_id = :orderId
                  AND status = 'FAILED_PERMANENT'
                  AND error_type LIKE 'RETRY_EXHAUSTED_%'
                """, new MapSqlParameterSource("orderId", orderId)) == 1;
    }

    private ExistingIdentity findExistingIdentity(OrderAssetReservationSucceededEvent event) {
        List<ExistingIdentity> rows = jdbc.query("""
                SELECT order_id, payload_hash
                FROM match_engine.order_admission_inbox
                WHERE order_id = :orderId
                   OR (market_id = :marketId AND market_sequence = :marketSequence)
                ORDER BY CASE WHEN order_id = :orderId THEN 0 ELSE 1 END
                LIMIT 1
                """, identity(event),
                (rs, rowNum) -> new ExistingIdentity(
                        rs.getObject("order_id", UUID.class),
                        rs.getString("payload_hash")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private MapSqlParameterSource identity(OrderAssetReservationSucceededEvent event) {
        return new MapSqlParameterSource()
                .addValue("orderId", event.getOrderId())
                .addValue("marketId", event.getMarketId())
                .addValue("marketSequence", event.getMarketSequence());
    }

    private MapSqlParameterSource claimParams(InboxEntry entry, String owner) {
        return new MapSqlParameterSource()
                .addValue("orderId", entry.orderId())
                .addValue("owner", owner);
    }

    private void validateIdentity(OrderAssetReservationSucceededEvent event) {
        if (event == null || event.getOrderId() == null || event.getUserId() == null) {
            throw new IllegalArgumentException(
                    "OrderAssetReservationSucceededEvent orderId and userId are required");
        }
        if (event.getMarketId() == null || event.getMarketId().isBlank()
                || event.getMarketSequence() == null || event.getMarketSequence() <= 0) {
            throw new IllegalArgumentException(
                    "OrderAssetReservationSucceededEvent market identity is required");
        }
    }

    private String serialize(OrderAssetReservationSucceededEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize Match admission event", e);
        }
    }

    private OrderAssetReservationSucceededEvent deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, OrderAssetReservationSucceededEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot deserialize Match admission inbox payload", e);
        }
    }

    private String sha256(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String truncate(String value) {
        return value == null || value.length() <= ERROR_LIMIT
                ? value
                : value.substring(0, ERROR_LIMIT);
    }

    public enum ReceiveOutcome {
        ACCEPTED,
        DUPLICATE,
        CONFLICT
    }

    public record InboxEntry(
            UUID orderId,
            OrderAssetReservationSucceededEvent event,
            int attemptCount) {
    }

    private record ExistingIdentity(UUID orderId, String payloadHash) {
    }
}
