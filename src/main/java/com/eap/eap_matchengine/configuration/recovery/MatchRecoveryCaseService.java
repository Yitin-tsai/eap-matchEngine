package com.eap.eap_matchengine.configuration.recovery;

import com.eap.common.recovery.RecoveryActionType;
import com.eap.common.recovery.RecoveryCaseDetail;
import com.eap.common.recovery.RecoveryCaseId;
import com.eap.common.recovery.RecoveryCaseSummary;
import com.eap.common.recovery.RecoveryDebtType;
import com.eap.common.recovery.RecoveryDryRunRequest;
import com.eap.common.recovery.RecoveryDryRunResult;
import com.eap.common.recovery.RecoveryExecuteRequest;
import com.eap.common.recovery.RecoveryExecuteResult;
import com.eap.common.recovery.RecoveryExecutionStatus;
import com.eap.common.recovery.RecoveryFailureClass;
import com.eap.common.recovery.RecoveryFingerprint;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

@Service
public class MatchRecoveryCaseService {

    static final String SERVICE = "eap-matchEngine";
    static final String ORDER_ADMISSION = "order_admission_inbox";
    static final String TRADE_OUTBOX = "trade_outbox";
    static final String RESERVATION_CLEANUP = "reservation_cleanup";
    static final String ORDER_CANCELLATION = "order_cancellation";
    static final String RESERVATION_RECONCILIATION = "reservation_reconciliation";

    private static final int MAX_LIMIT = 100;
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final List<String> WORK = List.of(
            ORDER_ADMISSION,
            TRADE_OUTBOX,
            RESERVATION_CLEANUP,
            ORDER_CANCELLATION,
            RESERVATION_RECONCILIATION);

    private static final Map<String, String> TERMINAL_SQL = Map.of(
            ORDER_ADMISSION, """
                    SELECT order_id::text AS source_id, status, attempt_count,
                           COALESCE(LEAST(received_at, conflict_detected_at), received_at) AS first_seen_at,
                           updated_at AS last_updated_at,
                           COALESCE(error_type,
                               CASE WHEN conflict_detected_at IS NOT NULL THEN 'IDENTITY_CONFLICT' END)
                               AS error_type,
                           last_error AS error_summary, payload,
                           json_build_object('orderId', order_id::text,
                                             'marketId', market_id,
                                             'marketSequence', market_sequence,
                                             'payloadHash', payload_hash)::text AS identity_json
                    FROM match_engine.order_admission_inbox
                    WHERE (status = 'FAILED_PERMANENT' OR conflict_detected_at IS NOT NULL)
                      AND (CAST(:sourceId AS text) IS NULL OR order_id::text = :sourceId)
                    ORDER BY first_seen_at, order_id
                    LIMIT :limit
                    """,
            TRADE_OUTBOX, """
                    SELECT id::text AS source_id, status, attempt_count,
                           created_at AS first_seen_at, updated_at AS last_updated_at,
                           'PUBLISH_RETRY_EXHAUSTED' AS error_type,
                           last_error AS error_summary, payload,
                           json_build_object('outboxId', id::text,
                                             'eventType', event_type,
                                             'aggregateType', aggregate_type,
                                             'aggregateId', aggregate_id,
                                             'routingKey', routing_key)::text AS identity_json
                    FROM match_engine.trade_outbox
                    WHERE status = 'FAILED'
                      AND (CAST(:sourceId AS text) IS NULL OR id::text = :sourceId)
                    ORDER BY created_at, id
                    LIMIT :limit
                    """,
            RESERVATION_CLEANUP, """
                    SELECT id::text AS source_id, status, attempt_count,
                           created_at AS first_seen_at, updated_at AS last_updated_at,
                           error_type, last_error AS error_summary,
                           json_build_object('tradeId', trade_id,
                                             'orderId', order_id::text,
                                             'userId', user_id::text)::text AS payload,
                           json_build_object('taskId', id::text,
                                             'tradeId', trade_id,
                                             'orderId', order_id::text,
                                             'userId', user_id::text)::text AS identity_json
                    FROM match_engine.reservation_cleanup_tasks
                    WHERE status = 'FAILED'
                      AND (CAST(:sourceId AS text) IS NULL OR id::text = :sourceId)
                    ORDER BY created_at, id
                    LIMIT :limit
                    """,
            ORDER_CANCELLATION, """
                    SELECT cancellation_id::text AS source_id, status,
                           technical_attempt_count AS attempt_count,
                           created_at AS first_seen_at, updated_at AS last_updated_at,
                           error_type, last_error AS error_summary,
                           json_build_object('cancellationId', cancellation_id::text,
                                             'orderId', order_id::text,
                                             'userId', user_id::text,
                                             'requestedAt', requested_at)::text AS payload,
                           json_build_object('cancellationId', cancellation_id::text,
                                             'orderId', order_id::text,
                                             'userId', user_id::text)::text AS identity_json
                    FROM match_engine.order_cancellations
                    WHERE status = 'FAILED_TERMINAL'
                      AND (CAST(:sourceId AS text) IS NULL OR cancellation_id::text = :sourceId)
                    ORDER BY created_at, cancellation_id
                    LIMIT :limit
                    """,
            RESERVATION_RECONCILIATION, """
                    SELECT issue_id AS source_id, status, attempt_count,
                           first_seen_at, last_seen_at AS last_updated_at,
                           error_type, last_error AS error_summary, payload,
                           json_build_object('issueId', issue_id,
                                             'reservationKey', reservation_key,
                                             'generationIdentity', generation_identity,
                                             'tradeId', trade_id,
                                             'orderId', order_id::text,
                                             'userId', user_id::text)::text AS identity_json
                    FROM match_engine.reservation_reconciliation_issues
                    WHERE status = 'TERMINAL'
                      AND (CAST(:sourceId AS text) IS NULL OR issue_id = :sourceId)
                    ORDER BY first_seen_at, issue_id
                    LIMIT :limit
                    """);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MatchRecoveryCaseService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<RecoveryCaseSummary> list(int requestedLimit) {
        int limit = boundedLimit(requestedLimit);
        List<RecoveryCaseDetail> cases = new ArrayList<>();
        WORK.forEach(work -> cases.addAll(query(work, null, limit)));
        return cases.stream()
                .sorted(Comparator.comparing(detail -> detail.summary().firstSeenAt()))
                .limit(limit)
                .map(RecoveryCaseDetail::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public RecoveryCaseDetail detail(String caseId) {
        RecoveryCaseId.Parts parts = requireMatchCase(caseId);
        return query(parts.work(), parts.sourceId(), 1).stream()
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Recovery case not found: " + caseId));
    }

    @Transactional(readOnly = true)
    public RecoveryDryRunResult dryRun(String caseId, RecoveryDryRunRequest request) {
        RecoveryCaseDetail detail = detail(caseId);
        boolean fingerprintMatches = detail.summary().fingerprint().equals(request.expectedFingerprint());
        boolean actionAllowed = detail.summary().allowedActions().contains(request.action());
        boolean allowed = fingerprintMatches && actionAllowed;
        String reason = !fingerprintMatches
                ? "Source changed after inspection; refresh the case before acting"
                : actionAllowed
                        ? "Action is allowed by the current service-owned recovery policy"
                        : "Action is not allowed for this failure class";
        return new RecoveryDryRunResult(
                caseId, request.action(), allowed, reason,
                detail.summary().fingerprint(), detail.summary());
    }

    @Transactional
    public RecoveryExecuteResult execute(String caseId, RecoveryExecuteRequest request) {
        if (request.action() != RecoveryActionType.REPLAY) {
            throw new IllegalArgumentException("Match source only executes REPLAY; PARK/RESOLVE belong to control plane");
        }
        lockAction(request);
        RecoveryExecuteResult existing = storedAction(caseId, request);
        if (existing != null) {
            return existing;
        }
        RecoveryCaseId.Parts parts = requireMatchCase(caseId);
        RecoveryCaseDetail before = detail(caseId);
        RecoveryDryRunResult check = dryRun(caseId,
                new RecoveryDryRunRequest(request.action(), request.expectedFingerprint()));
        if (!check.allowed()) {
            return remember(caseId, request, rejected(request, before, check.reason()));
        }

        lock(parts.work(), parts.sourceId());
        before = detail(caseId);
        check = dryRun(caseId,
                new RecoveryDryRunRequest(request.action(), request.expectedFingerprint()));
        if (!check.allowed()) {
            return remember(caseId, request, rejected(request, before, check.reason()));
        }

        int updated = replay(parts.work(), parts.sourceId());
        if (updated != 1) {
            return remember(caseId, request, new RecoveryExecuteResult(
                    request.actionId(), caseId, request.action(),
                    RecoveryExecutionStatus.NO_LONGER_ELIGIBLE,
                    "Recovery source no longer matches the replay precondition",
                    before.summary(), currentOrNull(caseId)));
        }
        return remember(caseId, request, new RecoveryExecuteResult(
                request.actionId(), caseId, request.action(), RecoveryExecutionStatus.APPLIED,
                "Terminal work was moved back to its service-owned retry state",
                before.summary(), currentOrNull(caseId)));
    }

    private List<RecoveryCaseDetail> query(String work, String sourceId, int limit) {
        String sql = TERMINAL_SQL.get(work);
        if (sql == null) {
            throw new IllegalArgumentException("Unsupported Match recovery work: " + work);
        }
        return jdbc.query(sql, new MapSqlParameterSource()
                        .addValue("sourceId", sourceId)
                        .addValue("limit", limit),
                (rs, rowNum) -> map(work, rs));
    }

    private RecoveryCaseDetail map(String work, ResultSet rs) throws SQLException {
        RecoveryDebtType debtType = debtType(work);
        String sourceId = rs.getString("source_id");
        String status = rs.getString("status");
        String errorType = rs.getString("error_type");
        String payload = rs.getString("payload");
        int attemptCount = rs.getInt("attempt_count");
        Instant firstSeen = rs.getTimestamp("first_seen_at").toInstant();
        Instant updated = rs.getTimestamp("last_updated_at").toInstant();
        RecoveryFailureClass failureClass = classify(errorType, debtType);
        String caseId = RecoveryCaseId.encode(SERVICE, debtType, work, sourceId);
        RecoveryCaseSummary summary = new RecoveryCaseSummary(
                caseId, SERVICE, debtType, work, sourceId, status, failureClass,
                attemptCount, firstSeen, updated, errorType,
                rs.getString("error_summary"),
                readIdentity(rs.getString("identity_json")),
                allowedActions(work, failureClass),
                RecoveryFingerprint.sha256(
                        caseId, status, Integer.toString(attemptCount), errorType, payload, updated.toString()));
        return new RecoveryCaseDetail(summary, payload, Map.of("source", "postgresql"));
    }

    private RecoveryDebtType debtType(String work) {
        return switch (work) {
            case TRADE_OUTBOX -> RecoveryDebtType.OUTBOX_TERMINAL;
            case RESERVATION_CLEANUP, RESERVATION_RECONCILIATION -> RecoveryDebtType.CLEANUP_TERMINAL;
            default -> RecoveryDebtType.INBOX_TERMINAL;
        };
    }

    private Set<RecoveryActionType> allowedActions(String work, RecoveryFailureClass failureClass) {
        EnumSet<RecoveryActionType> actions = EnumSet.of(
                RecoveryActionType.PARK, RecoveryActionType.RESOLVE);
        if (TRADE_OUTBOX.equals(work) || failureClass == RecoveryFailureClass.TRANSIENT) {
            actions.add(RecoveryActionType.REPLAY);
        }
        return actions;
    }

    private RecoveryFailureClass classify(String errorType, RecoveryDebtType debtType) {
        if (debtType == RecoveryDebtType.OUTBOX_TERMINAL) {
            return RecoveryFailureClass.TRANSIENT;
        }
        if (errorType == null) {
            return RecoveryFailureClass.UNKNOWN;
        }
        String normalized = errorType.toUpperCase(Locale.ROOT);
        if (normalized.startsWith("RETRY_EXHAUSTED_") || normalized.contains("TRANSIENT")) {
            return RecoveryFailureClass.TRANSIENT;
        }
        if (normalized.contains("IDENTITY") || normalized.contains("CONFLICT")) {
            return RecoveryFailureClass.IDENTITY;
        }
        if (normalized.contains("SCHEMA") || normalized.contains("DESERIAL")
                || normalized.contains("PAYLOAD")) {
            return RecoveryFailureClass.SCHEMA;
        }
        if (normalized.contains("INVARIANT") || normalized.contains("OWNERSHIP")) {
            return RecoveryFailureClass.INVARIANT;
        }
        if (normalized.contains("PREREQUISITE")) {
            return RecoveryFailureClass.PREREQUISITE;
        }
        return RecoveryFailureClass.PERMANENT;
    }

    private RecoveryCaseId.Parts requireMatchCase(String caseId) {
        RecoveryCaseId.Parts parts = RecoveryCaseId.decode(caseId);
        if (!SERVICE.equals(parts.service()) || !WORK.contains(parts.work())
                || parts.debtType() != debtType(parts.work())) {
            throw new IllegalArgumentException("Recovery case is not owned by MatchEngine");
        }
        return parts;
    }

    private void lock(String work, String sourceId) {
        String sql = switch (work) {
            case ORDER_ADMISSION -> "SELECT 1 FROM match_engine.order_admission_inbox WHERE order_id::text = :sourceId FOR UPDATE";
            case TRADE_OUTBOX -> "SELECT 1 FROM match_engine.trade_outbox WHERE id::text = :sourceId FOR UPDATE";
            case RESERVATION_CLEANUP -> "SELECT 1 FROM match_engine.reservation_cleanup_tasks WHERE id::text = :sourceId FOR UPDATE";
            case ORDER_CANCELLATION -> "SELECT 1 FROM match_engine.order_cancellations WHERE cancellation_id::text = :sourceId FOR UPDATE";
            case RESERVATION_RECONCILIATION -> "SELECT 1 FROM match_engine.reservation_reconciliation_issues WHERE issue_id = :sourceId FOR UPDATE";
            default -> throw new IllegalArgumentException("Unsupported Match recovery work: " + work);
        };
        if (jdbc.queryForList(sql, Map.of("sourceId", sourceId)).isEmpty()) {
            throw new NoSuchElementException("Recovery source no longer exists");
        }
    }

    private int replay(String work, String sourceId) {
        return switch (work) {
            case ORDER_ADMISSION -> jdbc.update("""
                    UPDATE match_engine.order_admission_inbox
                    SET status = 'PENDING', next_retry_at = CURRENT_TIMESTAMP,
                        attempt_count = 0, claimed_by = NULL, claim_until = NULL,
                        error_type = NULL, last_error = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE order_id::text = :sourceId
                      AND status = 'FAILED_PERMANENT'
                      AND conflict_detected_at IS NULL
                      AND error_type LIKE 'RETRY_EXHAUSTED_%'
                    """, Map.of("sourceId", sourceId));
            case TRADE_OUTBOX -> jdbc.update("""
                    UPDATE match_engine.trade_outbox
                    SET status = 'PENDING', attempt_count = 0,
                        next_retry_at = CURRENT_TIMESTAMP, last_error = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id::text = :sourceId AND status = 'FAILED'
                    """, Map.of("sourceId", sourceId));
            case RESERVATION_CLEANUP -> jdbc.update("""
                    UPDATE match_engine.reservation_cleanup_tasks
                    SET status = 'PENDING', attempt_count = 0,
                        next_retry_at = CURRENT_TIMESTAMP, error_type = NULL, last_error = NULL,
                        claim_owner = NULL, claim_token = NULL, claim_until = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id::text = :sourceId AND status = 'FAILED'
                      AND error_type LIKE 'RETRY_EXHAUSTED_%'
                    """, Map.of("sourceId", sourceId));
            case ORDER_CANCELLATION -> jdbc.update("""
                    UPDATE match_engine.order_cancellations
                    SET status = 'PENDING', technical_attempt_count = 0,
                        next_retry_at = CURRENT_TIMESTAMP, error_type = NULL, last_error = NULL,
                        claimed_by = NULL, claim_until = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE cancellation_id::text = :sourceId AND status = 'FAILED_TERMINAL'
                      AND error_type LIKE 'RETRY_EXHAUSTED_%'
                    """, Map.of("sourceId", sourceId));
            case RESERVATION_RECONCILIATION -> jdbc.update("""
                    UPDATE match_engine.reservation_reconciliation_issues
                    SET status = 'RETRYABLE', attempt_count = 1,
                        last_checked_at = CURRENT_TIMESTAMP, last_seen_at = CURRENT_TIMESTAMP,
                        resolved_at = NULL
                    WHERE issue_id = :sourceId AND status = 'TERMINAL'
                      AND (error_type LIKE 'RETRY_EXHAUSTED_%' OR error_type LIKE '%TRANSIENT%')
                    """, Map.of("sourceId", sourceId));
            default -> 0;
        };
    }

    private RecoveryExecuteResult rejected(
            RecoveryExecuteRequest request,
            RecoveryCaseDetail before,
            String reason) {
        return new RecoveryExecuteResult(
                request.actionId(), before.summary().caseId(), request.action(),
                RecoveryExecutionStatus.REJECTED, reason, before.summary(), before.summary());
    }

    private RecoveryCaseSummary currentOrNull(String caseId) {
        try {
            return detail(caseId).summary();
        } catch (NoSuchElementException resolved) {
            return null;
        }
    }

    private void lockAction(RecoveryExecuteRequest request) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(:actionId))",
                Map.of("actionId", request.actionId().toString()), rs -> {
                    rs.next();
                    return Boolean.TRUE;
                });
    }

    private RecoveryExecuteResult storedAction(String caseId, RecoveryExecuteRequest request) {
        return jdbc.query("""
                SELECT case_id, action_type, expected_fingerprint, result_json
                FROM match_engine.recovery_source_actions
                WHERE action_id = :actionId
                """, Map.of("actionId", request.actionId()), rs -> {
            if (!rs.next()) {
                return null;
            }
            if (!caseId.equals(rs.getString("case_id"))
                    || !request.action().name().equals(rs.getString("action_type"))
                    || !request.expectedFingerprint().equals(rs.getString("expected_fingerprint"))) {
                throw new IllegalArgumentException("Recovery actionId was already used for another request");
            }
            try {
                return objectMapper.readValue(rs.getString("result_json"), RecoveryExecuteResult.class);
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot deserialize stored recovery result", failure);
            }
        });
    }

    private RecoveryExecuteResult remember(
            String caseId,
            RecoveryExecuteRequest request,
            RecoveryExecuteResult result) {
        try {
            jdbc.update("""
                    INSERT INTO match_engine.recovery_source_actions
                        (action_id, case_id, action_type, expected_fingerprint, result_json)
                    VALUES (:actionId, :caseId, :actionType, :fingerprint, :resultJson)
                    """, new MapSqlParameterSource()
                    .addValue("actionId", request.actionId())
                    .addValue("caseId", caseId)
                    .addValue("actionType", request.action().name())
                    .addValue("fingerprint", request.expectedFingerprint())
                    .addValue("resultJson", objectMapper.writeValueAsString(result)));
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize recovery result", failure);
        }
    }

    private Map<String, String> readIdentity(String json) {
        try {
            return objectMapper.readValue(json, STRING_MAP);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot deserialize recovery identity", failure);
        }
    }

    private int boundedLimit(int requestedLimit) {
        return Math.min(Math.max(requestedLimit, 1), MAX_LIMIT);
    }
}
