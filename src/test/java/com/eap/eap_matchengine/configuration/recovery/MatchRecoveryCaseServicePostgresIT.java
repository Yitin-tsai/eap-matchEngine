package com.eap.eap_matchengine.configuration.recovery;

import com.eap.common.recovery.RecoveryActionType;
import com.eap.common.recovery.RecoveryExecuteRequest;
import com.eap.common.recovery.RecoveryExecutionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@EnabledIfSystemProperty(named = "eap.integration.crash-recovery", matches = "true")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "eap.match-engine.trade-outbox-relay.enabled=false",
                "eap.match-engine.trade-checkpoint-relay.enabled=false",
                "eap.match-engine.order-admission-inbox.enabled=false",
                "eap.match-engine.reservation-reconciler.enabled=false",
                "eap.match-engine.reservation-cleanup.enabled=false",
                "eap.match-engine.order-cancellation.reconcile-initial-delay-ms=3600000",
                "eap.match-engine.orderbook-runtime.monitor-initial-delay-ms=3600000"
        })
class MatchRecoveryCaseServicePostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:14.6"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired MatchRecoveryCaseService service;
    @Autowired JdbcTemplate jdbc;

    private Long taskId;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM match_engine.recovery_source_actions");
        if (taskId != null) {
            jdbc.update("DELETE FROM match_engine.reservation_cleanup_tasks WHERE id = ?", taskId);
        }
    }

    @Test
    void exhaustedCleanupShouldBeSafelyReturnedToItsOwnedWorker() {
        taskId = jdbc.queryForObject("""
                INSERT INTO match_engine.reservation_cleanup_tasks
                    (trade_id, order_id, user_id, status, attempt_count,
                     error_type, last_error)
                VALUES (?, ?, ?, 'FAILED', 10,
                        'RETRY_EXHAUSTED_TECHNICAL_FAILURE', 'redis unavailable')
                RETURNING id
                """, Long.class, "trade-" + UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var terminal = service.list(10).stream()
                .filter(item -> item.sourceId().equals(taskId.toString()))
                .findFirst().orElseThrow();
        UUID actionId = UUID.randomUUID();

        var result = service.execute(terminal.caseId(), new RecoveryExecuteRequest(
                actionId, RecoveryActionType.REPLAY, terminal.fingerprint()));
        var duplicate = service.execute(terminal.caseId(), new RecoveryExecuteRequest(
                actionId, RecoveryActionType.REPLAY, terminal.fingerprint()));

        assertThat(result.status()).isEqualTo(RecoveryExecutionStatus.APPLIED);
        assertThat(duplicate).isEqualTo(result);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM match_engine.reservation_cleanup_tasks WHERE id = ?
                """, String.class, taskId)).isEqualTo("PENDING");
    }
}
