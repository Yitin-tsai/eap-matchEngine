package com.eap.eap_matchengine.configuration.observability;

import com.eap.common.observability.DurableDebtSnapshot;
import com.eap.common.observability.DurableDebtSnapshotCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.eap.eap_matchengine.configuration.config.MatchEngineSchedulerConfig.DURABLE_DEBT_SCHEDULER;

@Component
@Slf4j
public class MatchDurableDebtSnapshotProvider {

    public static final List<String> WORK = List.of(
            "order_admission_inbox",
            "trade_outbox",
            "reservation_cleanup",
            "order_cancellation",
            "reservation_reconciliation");

    private static final String SQL = """
            SELECT work, total_count, retry_count, terminal_count,
                   oldest_unresolved_age_seconds
            FROM (
                SELECT 'order_admission_inbox' AS work, count(*) AS total_count,
                       count(*) FILTER (WHERE status IN ('PENDING_PREREQUISITE', 'FAILED_RETRYABLE')
                           OR (status = 'IN_PROGRESS' AND error_type IS NOT NULL)) AS retry_count,
                       count(*) FILTER (WHERE status = 'FAILED_PERMANENT'
                                            OR conflict_detected_at IS NOT NULL) AS terminal_count,
                       COALESCE(MAX(GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                           (CURRENT_TIMESTAMP - CASE
                               WHEN conflict_detected_at IS NOT NULL
                                   THEN LEAST(received_at, conflict_detected_at)
                               ELSE received_at END)))::bigint)), 0) AS oldest_unresolved_age_seconds
                FROM match_engine.order_admission_inbox
                WHERE status <> 'APPLIED' OR conflict_detected_at IS NOT NULL
                UNION ALL
                SELECT 'trade_outbox', count(*),
                       count(*) FILTER (WHERE status = 'PENDING' AND attempt_count > 0),
                       count(*) FILTER (WHERE status = 'FAILED'),
                       COALESCE(MAX(GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                           (CURRENT_TIMESTAMP - created_at)))::bigint)), 0)
                FROM match_engine.trade_outbox
                WHERE status <> 'SENT'
                UNION ALL
                SELECT 'reservation_cleanup', count(*),
                       count(*) FILTER (WHERE status IN ('PENDING', 'PROCESSING')
                                            AND attempt_count > 0),
                       count(*) FILTER (WHERE status = 'FAILED'),
                       COALESCE(MAX(GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                           (CURRENT_TIMESTAMP - created_at)))::bigint)), 0)
                FROM match_engine.reservation_cleanup_tasks
                WHERE status <> 'COMPLETED'
                UNION ALL
                SELECT 'order_cancellation', count(*),
                       count(*) FILTER (WHERE status IN ('PENDING', 'IN_PROGRESS')
                           AND (prerequisite_wait_count > 0 OR technical_attempt_count > 0)),
                       count(*) FILTER (WHERE status = 'FAILED_TERMINAL'),
                       COALESCE(MAX(GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                           (CURRENT_TIMESTAMP - created_at)))::bigint)), 0)
                FROM match_engine.order_cancellations
                WHERE status IN ('PENDING', 'IN_PROGRESS', 'FAILED_TERMINAL')
                UNION ALL
                SELECT 'reservation_reconciliation', count(*),
                       count(*) FILTER (WHERE status = 'RETRYABLE'),
                       count(*) FILTER (WHERE status = 'TERMINAL'),
                       COALESCE(MAX(GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
                           (CURRENT_TIMESTAMP - first_seen_at)))::bigint)), 0)
                FROM match_engine.reservation_reconciliation_issues
                WHERE status IN ('RETRYABLE', 'TERMINAL')
            ) debt
            """;

    private final JdbcTemplate jdbc;
    private final DurableDebtSnapshotCache cache =
            new DurableDebtSnapshotCache("eap-matchEngine", WORK);
    private final Counter refreshFailures;
    private final AtomicBoolean refreshFailureLogged = new AtomicBoolean();

    public MatchDurableDebtSnapshotProvider(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.refreshFailures = Counter.builder("eap_durable_debt_refresh_failures_total")
                .description("Durable-debt snapshot refresh failures")
                .tag("service", "eap-matchEngine")
                .register(registry);
        registerGauges(registry);
    }

    @PostConstruct
    void initialize() {
        refresh();
    }

    @Scheduled(
            fixedDelayString = "${eap.durable-debt.refresh-interval-ms:5000}",
            initialDelayString = "${eap.durable-debt.refresh-interval-ms:5000}",
            scheduler = DURABLE_DEBT_SCHEDULER)
    void refresh() {
        try {
            cache.recordSuccess(jdbc.query(SQL, (rs, rowNum) ->
                    new DurableDebtSnapshot.ComponentDebt(
                            rs.getString("work"),
                            rs.getLong("total_count"),
                            rs.getLong("retry_count"),
                            rs.getLong("terminal_count"),
                            rs.getLong("oldest_unresolved_age_seconds"))));
            if (refreshFailureLogged.getAndSet(false)) {
                log.info("Match durable-debt snapshot refresh recovered");
            }
        } catch (RuntimeException failure) {
            cache.recordFailure();
            refreshFailures.increment();
            if (refreshFailureLogged.compareAndSet(false, true)) {
                log.warn("Could not refresh Match durable-debt snapshot; retaining last successful values", failure);
            }
        }
    }

    public DurableDebtSnapshot snapshot() {
        return cache.snapshot();
    }

    private void registerGauges(MeterRegistry registry) {
        for (String work : WORK) {
            registerCountGauge(registry, work, "total");
            registerCountGauge(registry, work, "retry");
            registerCountGauge(registry, work, "terminal");
            Gauge.builder("eap_durable_debt_oldest_age_seconds", cache,
                            source -> source.component(work).oldestUnresolvedAgeSeconds())
                    .description("Age of the oldest unresolved durable work item")
                    .tags("service", "eap-matchEngine", "work", work)
                    .register(registry);
        }
        Gauge.builder("eap_durable_debt_observation_success", cache,
                        source -> source.snapshot().observationSuccess() ? 1 : 0)
                .description("Whether the latest durable-debt observation succeeded")
                .tag("service", "eap-matchEngine")
                .register(registry);
        Gauge.builder("eap_durable_debt_snapshot_age_seconds", cache,
                        source -> source.snapshot().snapshotAgeSeconds())
                .description("Age of the last successful durable-debt snapshot")
                .tag("service", "eap-matchEngine")
                .register(registry);
        Gauge.builder("eap_durable_debt_contract_version", cache,
                        source -> source.snapshot().contractVersion())
                .description("Durable-debt snapshot contract version")
                .tag("service", "eap-matchEngine")
                .register(registry);
    }

    private void registerCountGauge(MeterRegistry registry, String work, String debtClass) {
        Gauge.builder("eap_durable_debt_items", cache, source -> {
                    DurableDebtSnapshot.ComponentDebt debt = source.component(work);
                    return switch (debtClass) {
                        case "total" -> debt.totalCount();
                        case "retry" -> debt.retryCount();
                        case "terminal" -> debt.terminalCount();
                        default -> throw new IllegalStateException("unsupported durable-debt class " + debtClass);
                    };
                })
                .description("Current durable work items by semantic debt class")
                .tags("service", "eap-matchEngine", "work", work, "class", debtClass)
                .register(registry);
    }
}
