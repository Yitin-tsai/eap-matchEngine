package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderAssetReservationSucceededEvent;
import com.eap.common.event.OrderCancellationRequestedEvent;
import com.eap.common.event.OrderCancellationResultEvent;
import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_matchengine.configuration.repository.TradeExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.liquibase.enabled=true",
                "eap.match-engine.trade-outbox-relay.enabled=false",
                "eap.match-engine.trade-checkpoint-relay.enabled=false",
                "eap.match-engine.order-admission-inbox.enabled=false",
                "eap.match-engine.reservation-reconciler.enabled=false",
                "eap.match-engine.reservation-cleanup.enabled=false",
                "eap.match-engine.order-cancellation.reconcile-initial-delay-ms=3600000",
                "eap.match-engine.orderbook-runtime.monitor-initial-delay-ms=3600000",
                "eap.match-engine.orderbook-runtime.max-snapshot-age-ms=3600000"
        })
@EnabledIfSystemProperty(named = "eap.integration.crash-recovery", matches = "true")
class IncomingOrderCrashRecoveryPostgresRedisIT {

    private static final String MARKET_ID = "CRASH-RECOVERY-MARKET";
    private static final UUID INCOMING_ORDER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000501");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:14.6"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureInfrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private RedisOrderBookService orderBookService;
    @Autowired
    private IncomingOrderProcessingStore processingStore;
    @Autowired
    private TradeExecutionRepository tradeExecutionRepository;
    @Autowired
    private TradeExecutionRecorder durableRecorder;
    @Autowired
    private MatchingEngineMetrics matchingMetrics;
    @Autowired
    private ReservationCleanupMetrics cleanupMetrics;
    @Autowired
    private ReservationReconcilerMetrics reconcilerMetrics;
    @Autowired
    private ReservationCleanupTaskStore cleanupTaskStore;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private RedisConnectionFactory redisConnectionFactory;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OrderCancellationCoordinator cancellationCoordinator;
    @Autowired
    private OrderCancellationDecisionStore cancellationDecisions;
    @Autowired
    private MatchOrderAdmissionProcessor matchOrderAdmissionProcessor;
    @Autowired
    private MatchOrderAdmissionInbox matchOrderAdmissionInbox;
    @Autowired
    private OrderBookRuntimeAdminService orderBookRuntimeAdmin;
    @Autowired
    private OrderBookRuntimeGuard orderBookRuntimeGuard;
    @Autowired
    private OrderBookRuntimeControlStore orderBookRuntimeControls;
    @Autowired
    private RedisOrderBookManifestVerifier orderBookManifestVerifier;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetState() {
        jdbc.execute("""
                TRUNCATE TABLE
                    match_engine.order_admission_inbox,
                    match_engine.order_cancellations,
                    match_engine.reservation_cleanup_tasks,
                    match_engine.trade_outbox,
                    match_engine.trade_executions,
                    match_engine.order_book_runtime_control
                RESTART IDENTITY CASCADE
                """);
        try (var connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        orderBookRuntimeAdmin.initializeEmpty("integration-test", "fresh test fixture");
    }

    @Test
    void generationMismatch_shouldFailClosedBeforeAnyOrderBookMutation() throws Exception {
        OrderAssetReservationSucceededEvent order = order("BUY", 501, 1L, 3);
        redisTemplate.opsForValue().set(OrderBookRuntimeGuard.SENTINEL_KEY, "tampered-generation");

        assertThatThrownBy(() -> orderBookService.addOrder(order))
                .isInstanceOf(OrderBookRuntimeUnavailableException.class)
                .hasMessageContaining("generation");

        assertThat(redisTemplate.hasKey("order:" + order.getOrderId())).isFalse();
        assertThat(redisTemplate.opsForZSet().size("orderbook:" + MARKET_ID + ":buy")).isZero();
        assertThat(orderBookRuntimeGuard.isReady()).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT state FROM match_engine.order_book_runtime_control
                WHERE shard_id = 'CDA_GLOBAL'
                """, String.class)).isEqualTo("RECOVERING");
    }

    @Test
    void redisFullLoss_shouldKeepCancellationPendingAndContinueDurableInboxIntake() {
        try (var connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        orderBookRuntimeGuard.refresh();

        OrderAssetReservationSucceededEvent order = order("BUY", 501, 1L, 3);
        assertThat(matchOrderAdmissionInbox.receive(order))
                .isEqualTo(MatchOrderAdmissionInbox.ReceiveOutcome.ACCEPTED);

        UUID cancellationId = UUID.randomUUID();
        cancellationCoordinator.request(OrderCancellationRequestedEvent.builder()
                .cancellationId(cancellationId)
                .orderId(order.getOrderId())
                .userId(order.getUserId())
                .originalAmount(order.getAmount())
                .requestedAt(LocalDateTime.now())
                .build());

        assertThat(jdbc.queryForObject("""
                SELECT status FROM match_engine.order_cancellations
                WHERE cancellation_id = ?
                """, String.class, cancellationId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM match_engine.order_admission_inbox
                WHERE order_id = ?
                """, String.class, order.getOrderId())).isEqualTo("PENDING");
        assertThat(redisTemplate.hasKey("order:cancellation-intent:" + order.getOrderId())).isFalse();
    }

    @Test
    void redisRunIdMismatch_shouldPersistNewRecoveryGeneration() {
        UUID readyGeneration = jdbc.queryForObject("""
                SELECT generation FROM match_engine.order_book_runtime_control
                WHERE shard_id = 'CDA_GLOBAL'
                """, UUID.class);
        jdbc.update("""
                UPDATE match_engine.order_book_runtime_control
                SET redis_run_id = 'stale-run-id'
                WHERE shard_id = 'CDA_GLOBAL'
                """);

        orderBookRuntimeGuard.refresh();

        assertThat(orderBookRuntimeGuard.isReady()).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT state FROM match_engine.order_book_runtime_control
                WHERE shard_id = 'CDA_GLOBAL'
                """, String.class)).isEqualTo("RECOVERING");
        assertThat(jdbc.queryForObject("""
                SELECT generation FROM match_engine.order_book_runtime_control
                WHERE shard_id = 'CDA_GLOBAL'
                """, UUID.class)).isNotEqualTo(readyGeneration);
        assertThat(redisTemplate.hasKey(OrderBookRuntimeGuard.SENTINEL_KEY)).isFalse();
    }

    @Test
    void runtimeStatus_afterRedisLoss_shouldSynchronouslyReportNotReady() {
        assertThat(orderBookRuntimeAdmin.status()).containsEntry("localReady", true);
        try (var connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }

        assertThat(orderBookRuntimeAdmin.status()).containsEntry("localReady", false);
        assertThat(jdbc.queryForObject("""
                SELECT state FROM match_engine.order_book_runtime_control
                WHERE shard_id = 'CDA_GLOBAL'
                """, String.class)).isEqualTo("RECOVERING");
    }

    @Test
    void runtimeManifestInspection_withUntrustedCompletedBitmap_shouldReportWithoutClosingRuntime() {
        redisTemplate.opsForValue().setBit(
                "match:incoming-order:completed:" + MARKET_ID + ":0", 0L, true);

        Map<String, Object> status = orderBookRuntimeAdmin.inspectReadyManifest();

        assertThat(status).containsEntry("localReady", true);
        assertThat(status).containsKey("redisManifestError");
        assertThat(status.get("redisManifestError").toString())
                .contains("bitmap keys do not match");
        assertThat(redisTemplate.hasKey(OrderBookRuntimeGuard.SENTINEL_KEY)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT state FROM match_engine.order_book_runtime_control
                WHERE shard_id = 'CDA_GLOBAL'
                """, String.class)).isEqualTo("READY");
    }

    @Test
    void runtimeStatus_withActiveReservationWindow_shouldRemainReadyWithoutFullInspection() throws Exception {
        OrderAssetReservationSucceededEvent resting = order("SELL", 502, 1L, 1);
        OrderAssetReservationSucceededEvent incoming = order("BUY", 501, 2L, 1);
        orderBookService.addOrder(resting);
        assertThat(orderBookService.reserveBestMatchOrderLua(incoming)).isNotNull();

        Map<String, Object> status = orderBookRuntimeAdmin.status();

        assertThat(status).containsEntry("localReady", true);
        assertThat(status).doesNotContainKeys("redisManifest", "redisManifestError");
        assertThat(redisTemplate.hasKey(OrderBookRuntimeGuard.SENTINEL_KEY)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT state FROM match_engine.order_book_runtime_control
                WHERE shard_id = 'CDA_GLOBAL'
                """, String.class)).isEqualTo("READY");
        assertThat(orderBookRuntimeAdmin.inspectReadyManifest())
                .containsEntry("localReady", true)
                .containsKey("redisManifestError");
    }

    @Test
    void runtimeStatus_withCompletedBitmapBeforeInboxApplied_shouldRemainReady() {
        OrderAssetReservationSucceededEvent incoming = order("BUY", 501, 1L, 1);
        matchOrderAdmissionInbox.receive(incoming);
        redisTemplate.opsForValue().setBit(
                "match:incoming-order:completed:" + MARKET_ID + ":0",
                incoming.getMarketSequence() - 1,
                true);

        Map<String, Object> status = orderBookRuntimeAdmin.status();

        assertThat(status).containsEntry("localReady", true);
        assertThat(status).doesNotContainKeys("redisManifest", "redisManifestError");
        assertThat(orderBookRuntimeAdmin.inspectReadyManifest())
                .containsEntry("localReady", true)
                .containsKey("redisManifestError");
        assertThat(redisTemplate.hasKey(OrderBookRuntimeGuard.SENTINEL_KEY)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT state FROM match_engine.order_book_runtime_control
                WHERE shard_id = 'CDA_GLOBAL'
                """, String.class)).isEqualTo("READY");
    }

    @Test
    void recoveryActivation_withStrayCompletedBitmap_shouldRemainFailClosed() {
        enterRecovery();
        redisTemplate.opsForValue().setBit(
                "match:incoming-order:completed:" + MARKET_ID + ":0", 0L, true);

        assertThatThrownBy(() -> orderBookRuntimeAdmin.activateRebuilt(
                activationRequest(
                        "manifest-stray-completed-bit",
                        0L,
                        0L,
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                        "integration-test",
                        "reject unproven completed admission")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bitmap keys do not match");

        assertThat(orderBookRuntimeGuard.isReady()).isFalse();
        assertThat(redisTemplate.hasKey(OrderBookRuntimeGuard.SENTINEL_KEY)).isFalse();
    }

    @Test
    void recoveryActivation_withMissingCompletedBitmap_shouldRemainFailClosed() {
        OrderAssetReservationSucceededEvent incoming = order("BUY", 501, 1L, 7);
        matchOrderAdmissionInbox.receive(incoming);
        jdbc.update("""
                UPDATE match_engine.order_admission_inbox
                SET status = 'APPLIED', applied_at = CURRENT_TIMESTAMP
                WHERE order_id = ?
                """, incoming.getOrderId());
        enterRecovery();

        assertThatThrownBy(() -> orderBookRuntimeAdmin.activateRebuilt(
                activationRequest(
                        "manifest-missing-completed-bit",
                        0L,
                        0L,
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                        "integration-test",
                        "reject incomplete idempotency projection")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bitmap keys do not match");

        assertThat(orderBookRuntimeGuard.isReady()).isFalse();
        assertThat(redisTemplate.hasKey(OrderBookRuntimeGuard.SENTINEL_KEY)).isFalse();
    }

    @Test
    void recoveryActivation_withSameTokenConcurrently_shouldHaveOneWinner() throws Exception {
        enterRecovery();
        RedisOrderBookManifestVerifier.Manifest manifest =
                orderBookManifestVerifier.inspect(Map.of());
        OrderBookRuntimeAdminService.ActivationRequest request = activationRequest(
                "manifest-concurrent",
                manifest.openOrderCount(),
                manifest.openQuantity(),
                manifest.identityDigest(),
                "integration-test",
                "serialize concurrent activation");
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> activateAfter(start, request, successes, failures));
            var second = executor.submit(() -> activateAfter(start, request, successes, failures));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).getMessage())
                .containsAnyOf("not RECOVERING", "Stale order-book recovery token");
        assertThat(orderBookRuntimeGuard.isReady()).isTrue();
    }

    @Test
    void recoveryActivation_shouldWaitForCancellationDurableIntakeAndRejectMissingIntent() throws Exception {
        enterRecovery();
        RedisOrderBookManifestVerifier.Manifest manifest = orderBookManifestVerifier.inspect(Map.of());
        OrderBookRuntimeAdminService.ActivationRequest activation = activationRequest(
                "manifest-cancellation-intake-race",
                manifest.openOrderCount(),
                manifest.openQuantity(),
                manifest.identityDigest(),
                "integration-test",
                "serialize cancellation intake with activation");
        OrderCancellationRequestedEvent cancellation = cancellationRequest(UUID.randomUUID(), UUID.randomUUID());
        CountDownLatch cancellationCommitted = new CountDownLatch(1);
        CountDownLatch releaseCancellationIntake = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var intake = executor.submit(() -> orderBookRuntimeControls.withCancellationIntakeLock(() -> {
                cancellationDecisions.begin(cancellation, null);
                cancellationCommitted.countDown();
                await(releaseCancellationIntake, "release cancellation intake");
                return null;
            }));
            assertThat(cancellationCommitted.await(5, TimeUnit.SECONDS)).isTrue();

            var activationAttempt = executor.submit(() -> orderBookRuntimeAdmin.activateRebuilt(activation));
            awaitAdvisoryLockWaiter();
            assertThat(activationAttempt.isDone()).isFalse();

            releaseCancellationIntake.countDown();
            intake.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> activationAttempt.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Redis pending cancellation intent mismatch: orderId="
                            + cancellation.getOrderId());
        } finally {
            releaseCancellationIntake.countDown();
            executor.shutdownNow();
        }

        assertThat(orderBookRuntimeGuard.isReady()).isFalse();
        assertThat(redisTemplate.hasKey(OrderBookRuntimeGuard.SENTINEL_KEY)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT state FROM match_engine.order_book_runtime_control
                WHERE shard_id = 'CDA_GLOBAL'
                """, String.class)).isEqualTo("RECOVERING");
    }

    @Test
    void manifest_shouldRequireIntentForPendingDurableFactEvenWithoutPendingSnapshot() {
        enterRecovery();
        OrderCancellationRequestedEvent cancellation = cancellationRequest(UUID.randomUUID(), UUID.randomUUID());
        cancellationDecisions.begin(cancellation, null);

        assertThatThrownBy(() -> orderBookManifestVerifier.inspect(
                Map.of(),
                List.of(),
                orderBookRuntimeControls.cancellationFacts()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Redis pending cancellation intent mismatch: orderId=" + cancellation.getOrderId());
    }

    @Test
    void completedAdmissionBitmap_shouldExactlyMatchDurableAppliedInbox() {
        OrderAssetReservationSucceededEvent incoming = order("BUY", 501, 1L, 7);
        matchOrderAdmissionInbox.receive(incoming);
        MatchOrderAdmissionInbox.InboxEntry entry =
                matchOrderAdmissionInbox.claimRetryable(1, "worker-a", 30_000).get(0);
        matchOrderAdmissionProcessor.process(entry.event());
        assertThat(matchOrderAdmissionInbox.markApplied(entry, "worker-a")).isTrue();

        RedisOrderBookManifestVerifier.Manifest manifest = orderBookManifestVerifier.inspect(
                Map.of(),
                List.of(new OrderBookRuntimeControlStore.CompletedAdmission(
                        MARKET_ID, incoming.getMarketSequence())));

        assertThat(manifest.completedAdmissionCount()).isEqualTo(1);
        assertThat(orderBookRuntimeAdmin.status()).containsEntry("localReady", true);
    }

    @Test
    void cancellationMarker_withVisibleOrder_shouldFailStatusAndRecoveryActivation() throws Exception {
        OrderAssetReservationSucceededEvent open = order("BUY", 501, 1L, 7);
        orderBookService.addOrder(open);
        UUID cancellationId = UUID.randomUUID();
        cancellationCoordinator.request(OrderCancellationRequestedEvent.builder()
                .cancellationId(cancellationId)
                .orderId(open.getOrderId())
                .userId(open.getUserId())
                .originalAmount(open.getAmount())
                .requestedAt(LocalDateTime.now())
                .build());

        assertThat(orderBookRuntimeAdmin.status()).containsEntry("localReady", true);

        // Simulate a corrupt rebuild that restores an order already removed by the
        // durable cancellation marker.
        orderBookService.addOrder(open);
        Map<String, Object> rejectedStatus = orderBookRuntimeAdmin.inspectReadyManifest();

        assertThat(rejectedStatus).containsEntry("localReady", true);
        assertThat(rejectedStatus.get("redisManifestError").toString())
                .contains("cannot coexist with a visible order");
        assertThat(jdbc.queryForObject("""
                SELECT state FROM match_engine.order_book_runtime_control
                WHERE shard_id = 'CDA_GLOBAL'
                """, String.class)).isEqualTo("READY");

        orderBookRuntimeGuard.rejectCurrentGeneration("integration-test recovery", null);

        assertThatThrownBy(() -> orderBookRuntimeAdmin.activateRebuilt(
                activationRequest(
                        "manifest-marker-visible-order",
                        1L,
                        open.getAmount(),
                        "not-reached-because-marker-is-invalid",
                        "integration-test",
                        "reject marker and visible order")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot coexist with a visible order");
    }

    @Test
    void expiredCancellationMarker_withDurableCompletedDecisionAndVisibleOrder_shouldFailClosed() throws Exception {
        OrderAssetReservationSucceededEvent open = order("BUY", 501, 1L, 7);
        orderBookService.addOrder(open);
        UUID cancellationId = UUID.randomUUID();
        cancellationCoordinator.request(cancellationRequest(open, cancellationId));
        assertThat(cancellationDecisions.find(cancellationId).status())
                .isEqualTo(OrderCancellationResultEvent.CANCELLED);

        // Model TTL expiry: Redis no longer has a marker to expose the conflict, so
        // the durable completed decision itself must still fence a corrupt rebuild.
        redisTemplate.delete("order:cancellation:" + open.getOrderId());
        redisTemplate.delete("order:cancellation-intent:" + open.getOrderId());
        orderBookService.addOrder(open);

        Map<String, Object> status = orderBookRuntimeAdmin.inspectReadyManifest();

        assertThat(status).containsEntry("localReady", true);
        assertThat(status.get("redisManifestError").toString())
                .contains("Durable completed cancellation cannot coexist with a visible order")
                .contains(open.getOrderId().toString());
        assertThat(redisTemplate.hasKey(OrderBookRuntimeGuard.SENTINEL_KEY)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT state FROM match_engine.order_book_runtime_control
                WHERE shard_id = 'CDA_GLOBAL'
                """, String.class)).isEqualTo("READY");
    }

    @Test
    void stalePreResetControl_shouldNotDemoteReinitializedGeneration() {
        OrderBookRuntimeControlStore.Control stale = orderBookRuntimeControls.find();
        jdbc.execute("TRUNCATE TABLE match_engine.order_book_runtime_control");
        redisTemplate.delete(OrderBookRuntimeGuard.SENTINEL_KEY);

        orderBookRuntimeAdmin.initializeEmpty(
                "integration-test", "reinitialize after isolated load-test reset");
        OrderBookRuntimeControlStore.Control reinitialized = orderBookRuntimeControls.find();
        assertThat(reinitialized.version()).isEqualTo(stale.version());
        assertThat(reinitialized.generation()).isNotEqualTo(stale.generation());

        OrderBookRuntimeControlStore.Control afterStaleCas = orderBookRuntimeControls.beginRecovery(
                stale, UUID.randomUUID(), "stale process observed an old Redis generation");

        assertThat(afterStaleCas.state()).isEqualTo(OrderBookRuntimeControlStore.State.READY);
        assertThat(afterStaleCas.generation()).isEqualTo(reinitialized.generation());
        assertThat(afterStaleCas.version()).isEqualTo(reinitialized.version());
        assertThat(orderBookRuntimeGuard.isReady()).isTrue();
    }

    @Test
    void rebuildManifest_shouldRejectWrongMarketIndex() throws Exception {
        enterRecovery();
        OrderAssetReservationSucceededEvent rebuilt = order("SELL", 701, 7L, 4);
        seedRebuiltOrder(rebuilt, "WRONG-MARKET", "sell", scoreFor(rebuilt), rebuilt);

        assertThatThrownBy(() -> orderBookManifestVerifier.inspect(java.util.Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wrong market/side");
    }

    @Test
    void rebuildManifest_shouldRejectWrongSideIndex() throws Exception {
        enterRecovery();
        OrderAssetReservationSucceededEvent rebuilt = order("SELL", 701, 7L, 4);
        seedRebuiltOrder(rebuilt, MARKET_ID, "buy", scoreFor(rebuilt), rebuilt);

        assertThatThrownBy(() -> orderBookManifestVerifier.inspect(java.util.Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wrong market/side");
    }

    @Test
    void rebuildManifest_shouldRejectWrongCompositeScore() throws Exception {
        enterRecovery();
        OrderAssetReservationSucceededEvent rebuilt = order("BUY", 701, 7L, 4);
        seedRebuiltOrder(rebuilt, MARKET_ID, "buy", scoreFor(rebuilt) + 1, rebuilt);

        assertThatThrownBy(() -> orderBookManifestVerifier.inspect(java.util.Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("score mismatch");
    }

    @Test
    void rebuildManifest_shouldRejectMemberAndDetailOrderIdMismatch() throws Exception {
        enterRecovery();
        OrderAssetReservationSucceededEvent member = order("SELL", 701, 7L, 4);
        OrderAssetReservationSucceededEvent differentDetail = order("SELL", 702, 8L, 4);
        seedRebuiltOrder(member, MARKET_ID, "sell", scoreFor(member), differentDetail);

        assertThatThrownBy(() -> orderBookManifestVerifier.inspect(java.util.Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity mismatch");
    }

    @Test
    void recoveryActivation_withUnverifiedManifest_shouldRemainFailClosed() {
        try (var connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        orderBookRuntimeGuard.refresh();

        assertThatThrownBy(() -> orderBookRuntimeAdmin.activateRebuilt(
                activationRequest(
                        "manifest-stale",
                        1L,
                        3L,
                        "not-the-empty-digest",
                        "integration-test",
                        "reject stale rebuild")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manifest mismatch");

        assertThat(orderBookRuntimeGuard.isReady()).isFalse();
        assertThat(redisTemplate.hasKey(OrderBookRuntimeGuard.SENTINEL_KEY)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT state FROM match_engine.order_book_runtime_control
                WHERE shard_id = 'CDA_GLOBAL'
                """, String.class)).isEqualTo("RECOVERING");
    }

    @Test
    void recoveryActivation_withStaleOperatorToken_shouldRemainFailClosed() {
        enterRecovery();
        RedisOrderBookManifestVerifier.Manifest manifest =
                orderBookManifestVerifier.inspect(java.util.Map.of());
        OrderBookRuntimeAdminService.ActivationRequest stale = activationRequest(
                "manifest-stale-token",
                manifest.openOrderCount(),
                manifest.openQuantity(),
                manifest.identityDigest(),
                "integration-test",
                "stale token");
        jdbc.update("""
                UPDATE match_engine.order_book_runtime_control
                SET version = version + 1
                WHERE shard_id = 'CDA_GLOBAL'
                """);

        assertThatThrownBy(() -> orderBookRuntimeAdmin.activateRebuilt(stale))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stale order-book recovery token");
        assertThat(redisTemplate.hasKey(OrderBookRuntimeGuard.SENTINEL_KEY)).isFalse();
    }

    @Test
    void verifiedRebuildActivation_shouldOpenOnlyTheNewGeneration() throws Exception {
        try (var connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        orderBookRuntimeGuard.refresh();

        OrderAssetReservationSucceededEvent rebuilt = order("SELL", 701, 7L, 4);
        redisTemplate.opsForValue().set(
                "order:" + rebuilt.getOrderId(), objectMapper.writeValueAsString(rebuilt));
        redisTemplate.opsForZSet().add(
                "orderbook:" + MARKET_ID + ":sell",
                rebuilt.getOrderId().toString(),
                (100L * 1_000_000_000L) + rebuilt.getMarketSequence());
        redisTemplate.opsForSet().add(
                "user:" + rebuilt.getUserId() + ":orders", rebuilt.getOrderId().toString());
        RedisOrderBookManifestVerifier.Manifest manifest = orderBookManifestVerifier.inspect(java.util.Map.of());

        OrderBookRuntimeAdminService.Result result = orderBookRuntimeAdmin.activateRebuilt(
                activationRequest(
                        "manifest-verified-1",
                        manifest.openOrderCount(),
                        manifest.openQuantity(),
                        manifest.identityDigest(),
                        "integration-test",
                        "verified manual rebuild"));

        assertThat(result.ready()).isTrue();
        assertThat(orderBookRuntimeGuard.isReady()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT verification_manifest_id FROM match_engine.order_book_runtime_control
                WHERE shard_id = 'CDA_GLOBAL'
                """, String.class)).isEqualTo("manifest-verified-1");

        OrderAssetReservationSucceededEvent admitted = order("BUY", 501, 8L, 1);
        orderBookService.addOrder(admitted);
        assertThat(redisTemplate.hasKey("order:" + admitted.getOrderId())).isTrue();
    }

    private OrderBookRuntimeAdminService.ActivationRequest activationRequest(
            String manifestId,
            long expectedOpenOrderCount,
            long expectedOpenQuantity,
            String expectedIdentityDigest,
            String operator,
            String reason) {
        OrderBookRuntimeControlStore.Control control = jdbc.queryForObject("""
                SELECT shard_id, state, fence_epoch, generation, redis_run_id, version,
                       transition_reason, verification_manifest_id, verification_manifest,
                       verified_by, transitioned_at, updated_at
                FROM match_engine.order_book_runtime_control
                WHERE shard_id = 'CDA_GLOBAL'
                """, (rs, rowNum) -> new OrderBookRuntimeControlStore.Control(
                rs.getString("shard_id"),
                OrderBookRuntimeControlStore.State.valueOf(rs.getString("state")),
                rs.getLong("fence_epoch"),
                rs.getObject("generation", UUID.class),
                rs.getString("redis_run_id"),
                rs.getLong("version"),
                rs.getString("transition_reason"),
                rs.getString("verification_manifest_id"),
                rs.getString("verification_manifest"),
                rs.getString("verified_by"),
                rs.getObject("transitioned_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class)));
        return new OrderBookRuntimeAdminService.ActivationRequest(
                manifestId,
                control.fenceEpoch(),
                control.generation(),
                control.version(),
                0L,
                0L,
                expectedOpenOrderCount,
                expectedOpenQuantity,
                expectedIdentityDigest,
                operator,
                reason);
    }

    private void activateAfter(
            CountDownLatch start,
            OrderBookRuntimeAdminService.ActivationRequest request,
            AtomicInteger successes,
            List<Throwable> failures) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("activation start barrier timed out");
            }
            orderBookRuntimeAdmin.activateRebuilt(request);
            successes.incrementAndGet();
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    private void awaitAdvisoryLockWaiter() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiters = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_locks
                    WHERE locktype = 'advisory' AND granted = FALSE
                    """, Integer.class);
            if (waiters != null && waiters > 0) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("activation did not wait on the cancellation intake barrier");
    }

    private void await(CountDownLatch latch, String operation) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(operation + " timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " was interrupted", interrupted);
        }
    }

    private OrderCancellationRequestedEvent cancellationRequest(UUID cancellationId, UUID orderId) {
        return OrderCancellationRequestedEvent.builder()
                .cancellationId(cancellationId)
                .orderId(orderId)
                .userId(UUID.randomUUID())
                .originalAmount(5)
                .requestedAt(LocalDateTime.now())
                .build();
    }

    private void enterRecovery() {
        try (var connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        orderBookRuntimeGuard.refresh();
        assertThat(orderBookRuntimeGuard.isReady()).isFalse();
    }

    private void seedRebuiltOrder(
            OrderAssetReservationSucceededEvent member,
            String indexedMarket,
            String indexedSide,
            long score,
            OrderAssetReservationSucceededEvent detail) throws Exception {
        redisTemplate.opsForValue().set(
                "order:" + member.getOrderId(), objectMapper.writeValueAsString(detail));
        redisTemplate.opsForZSet().add(
                "orderbook:" + indexedMarket + ":" + indexedSide,
                member.getOrderId().toString(),
                score);
        redisTemplate.opsForSet().add(
                "user:" + detail.getUserId() + ":orders", member.getOrderId().toString());
    }

    private long scoreFor(OrderAssetReservationSucceededEvent order) {
        long sequence = Math.floorMod(order.getMarketSequence(), 1_000_000_000L);
        if ("BUY".equalsIgnoreCase(order.getOrderType())) {
            return ((long) order.getPrice() * 1_000_000_000L) + (1_000_000_000L - sequence);
        }
        return ((long) order.getPrice() * 1_000_000_000L) + sequence;
    }

    @Test
    void admissionInbox_duplicateAndIdentityConflict_shouldRemainAuditable() {
        OrderAssetReservationSucceededEvent original = order("BUY", 501, 1L, 7);

        assertThat(matchOrderAdmissionInbox.receive(original))
                .isEqualTo(MatchOrderAdmissionInbox.ReceiveOutcome.ACCEPTED);
        assertThat(matchOrderAdmissionInbox.receive(original))
                .isEqualTo(MatchOrderAdmissionInbox.ReceiveOutcome.DUPLICATE);

        OrderAssetReservationSucceededEvent conflicting = order("BUY", 501, 1L, 8);
        assertThat(matchOrderAdmissionInbox.receive(conflicting))
                .isEqualTo(MatchOrderAdmissionInbox.ReceiveOutcome.CONFLICT);
        assertThat(jdbc.queryForObject("""
                SELECT status
                FROM match_engine.order_admission_inbox
                WHERE order_id = ?
                """, String.class, INCOMING_ORDER_ID)).isEqualTo("FAILED_PERMANENT");
        assertThat(jdbc.queryForObject("""
                SELECT error_type
                FROM match_engine.order_admission_inbox
                WHERE order_id = ?
                """, String.class, INCOMING_ORDER_ID)).isEqualTo("IDENTITY_CONFLICT");
        assertThat(matchOrderAdmissionInbox.retryExhaustedTechnicalFailure(INCOMING_ORDER_ID)).isFalse();
    }

    @Test
    void admissionInbox_operatorRetry_shouldOnlyReopenExhaustedTechnicalFailure() {
        OrderAssetReservationSucceededEvent incoming = order("BUY", 501, 1L, 7);
        matchOrderAdmissionInbox.receive(incoming);
        jdbc.update("""
                UPDATE match_engine.order_admission_inbox
                SET status = 'FAILED_PERMANENT',
                    attempt_count = 20,
                    error_type = 'RETRY_EXHAUSTED_TRANSIENT_REDIS'
                WHERE order_id = ?
                """, INCOMING_ORDER_ID);

        assertThat(matchOrderAdmissionInbox.retryExhaustedTechnicalFailure(INCOMING_ORDER_ID)).isTrue();
        assertThat(jdbc.queryForMap("""
                SELECT status, attempt_count, error_type
                FROM match_engine.order_admission_inbox
                WHERE order_id = ?
                """, INCOMING_ORDER_ID))
                .containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 0)
                .containsEntry("error_type", null);
    }

    @Test
    void admissionInbox_crashAfterMatchBeforeAppliedMarker_shouldReclaimAndConvergeOnce() {
        OrderAssetReservationSucceededEvent incoming = order("BUY", 501, 1L, 7);
        assertThat(matchOrderAdmissionInbox.receive(incoming))
                .isEqualTo(MatchOrderAdmissionInbox.ReceiveOutcome.ACCEPTED);

        MatchOrderAdmissionInbox.InboxEntry first =
                matchOrderAdmissionInbox.claimRetryable(1, "worker-a", 30_000).get(0);
        matchOrderAdmissionProcessor.process(first.event());

        // Simulate process death after the Redis admission completed but before the inbox APPLIED update.
        jdbc.update("""
                UPDATE match_engine.order_admission_inbox
                SET claim_until = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE order_id = ?
                """, INCOMING_ORDER_ID);

        MatchOrderAdmissionInbox.InboxEntry reclaimed =
                matchOrderAdmissionInbox.claimRetryable(1, "worker-b", 30_000).get(0);
        assertThat(reclaimed.attemptCount()).isEqualTo(2);
        matchOrderAdmissionProcessor.process(reclaimed.event());
        assertThat(matchOrderAdmissionInbox.markApplied(reclaimed, "worker-b")).isTrue();

        assertThat(visibleAmount(incoming)).isEqualTo(7);
        assertThat(tradeCount()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT status
                FROM match_engine.order_admission_inbox
                WHERE order_id = ?
                """, String.class, INCOMING_ORDER_ID)).isEqualTo("APPLIED");
    }

    @Test
    void cancellationLua_shouldReturnExactRemovedOrderAndRemainIdempotent() throws Exception {
        OrderAssetReservationSucceededEvent open = order("BUY", 501, 1L, 7);
        UUID cancellationId = UUID.randomUUID();
        orderBookService.addOrder(open);

        RedisOrderBookService.CancellationArbitration first =
                orderBookService.arbitrateCancellation(open, cancellationId);
        RedisOrderBookService.CancellationArbitration retry =
                orderBookService.arbitrateCancellation(open, cancellationId);

        assertThat(first.outcome()).isEqualTo(RedisOrderBookService.CancellationOutcome.CANCELLED);
        assertThat(first.cancelledOrder().getAmount()).isEqualTo(7);
        assertThat(retry.outcome())
                .isEqualTo(RedisOrderBookService.CancellationOutcome.ALREADY_CANCELLED_BY_REQUEST);
        assertThat(retry.cancelledOrder().getAmount()).isEqualTo(7);
        assertThat(orderBookService.findOpenOrder(open.getOrderId())).isNull();
    }

    @Test
    void cancellationLua_shouldLoseWhenMatchingAlreadyReservedTheOrder() throws Exception {
        OrderAssetReservationSucceededEvent resting = order("SELL", 501, 1L, 7);
        OrderAssetReservationSucceededEvent incoming = order("BUY", 502, 2L, 1);
        orderBookService.addOrder(resting);
        RedisOrderBookService.ReservedMatch reserved =
                orderBookService.reserveBestMatchOrderWithSequenceLua(incoming);

        RedisOrderBookService.CancellationArbitration result =
                orderBookService.arbitrateCancellation(resting, UUID.randomUUID());

        assertThat(reserved.order().getOrderId()).isEqualTo(resting.getOrderId());
        assertThat(result.outcome()).isEqualTo(RedisOrderBookService.CancellationOutcome.NOT_OPEN);
        assertThat(processingStore.isReserved(resting.getOrderId())).isTrue();
        assertThat(orderBookService.findOpenOrder(resting.getOrderId())).isNotNull();
    }

    @Test
    void incomingBuy_shouldSkipOwnBestSellAndReserveNextEligibleSeller() throws Exception {
        OrderAssetReservationSucceededEvent incoming = order("BUY", 501, 3L, 1);
        OrderAssetReservationSucceededEvent ownBestSell = order("SELL", 502, 1L, 1);
        ownBestSell.setUserId(incoming.getUserId());
        OrderAssetReservationSucceededEvent eligibleSell = order("SELL", 503, 2L, 1);
        orderBookService.addOrder(ownBestSell);
        orderBookService.addOrder(eligibleSell);

        RedisOrderBookService.MatchOrAddResult result =
                orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(incoming);

        assertThat(result.reservedMatch().order().getOrderId()).isEqualTo(eligibleSell.getOrderId());
        assertThat(result.reservedMatch().order().getUserId()).isNotEqualTo(incoming.getUserId());
        assertThat(orderBookService.findOpenOrder(ownBestSell.getOrderId())).isNotNull();
        assertThat(processingStore.isReserved(ownBestSell.getOrderId())).isFalse();
    }

    @Test
    void incomingSell_withOnlyOwnCompatibleBuy_shouldEnterBookWithoutTrade() throws Exception {
        OrderAssetReservationSucceededEvent incoming = order("SELL", 501, 2L, 1);
        OrderAssetReservationSucceededEvent ownBuy = order("BUY", 502, 1L, 1);
        ownBuy.setUserId(incoming.getUserId());
        orderBookService.addOrder(ownBuy);

        RedisOrderBookService.MatchOrAddResult result =
                orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(incoming);

        assertThat(result.orderAdded()).isTrue();
        assertThat(result.reservedMatch()).isNull();
        assertThat(orderBookService.findOpenOrder(ownBuy.getOrderId())).isNotNull();
        assertThat(orderBookService.findOpenOrder(incoming.getOrderId())).isNotNull();
        assertThat(tradeCount()).isZero();
    }

    @Test
    void incomingSell_shouldScanPastFullPageOfOwnOrdersAndReserveNextEligibleBuyer() throws Exception {
        OrderAssetReservationSucceededEvent incoming = order("SELL", 501, 100L, 1);
        for (int index = 0; index < 32; index++) {
            OrderAssetReservationSucceededEvent ownBuy = order("BUY", 600 + index, index + 1L, 1);
            ownBuy.setUserId(incoming.getUserId());
            orderBookService.addOrder(ownBuy);
        }
        OrderAssetReservationSucceededEvent eligibleBuy = order("BUY", 632, 33L, 1);
        orderBookService.addOrder(eligibleBuy);

        RedisOrderBookService.MatchOrAddResult result =
                orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(incoming);

        assertThat(result.reservedMatch().order().getOrderId()).isEqualTo(eligibleBuy.getOrderId());
        assertThat(result.reservedMatch().order().getUserId()).isNotEqualTo(incoming.getUserId());
        assertThat(processingStore.isReserved(eligibleBuy.getOrderId())).isTrue();
    }

    @Test
    void incomingBuy_shouldScanPastFullPageOfOwnOrdersAndReserveNextEligibleSeller() throws Exception {
        OrderAssetReservationSucceededEvent incoming = order("BUY", 501, 100L, 1);
        for (int index = 0; index < 32; index++) {
            OrderAssetReservationSucceededEvent ownSell = order("SELL", 700 + index, index + 1L, 1);
            ownSell.setUserId(incoming.getUserId());
            orderBookService.addOrder(ownSell);
        }
        OrderAssetReservationSucceededEvent eligibleSell = order("SELL", 732, 33L, 1);
        orderBookService.addOrder(eligibleSell);

        RedisOrderBookService.MatchOrAddResult result =
                orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(incoming);

        assertThat(result.reservedMatch().order().getOrderId()).isEqualTo(eligibleSell.getOrderId());
        assertThat(result.reservedMatch().order().getUserId()).isNotEqualTo(incoming.getUserId());
        assertThat(processingStore.isReserved(eligibleSell.getOrderId())).isTrue();
    }

    @Test
    void legacyIncomingBuy_shouldSkipOwnBestSellAndReserveEligibleSeller() throws Exception {
        OrderAssetReservationSucceededEvent incoming = order("BUY", 501, 3L, 1);
        OrderAssetReservationSucceededEvent ownSell = order("SELL", 741, 1L, 1);
        ownSell.setUserId(incoming.getUserId());
        OrderAssetReservationSucceededEvent eligibleSell = order("SELL", 742, 2L, 1);
        orderBookService.addOrder(ownSell);
        orderBookService.addOrder(eligibleSell);

        OrderAssetReservationSucceededEvent reserved = orderBookService.reserveBestMatchOrderLua(incoming);

        assertThat(reserved.getOrderId()).isEqualTo(eligibleSell.getOrderId());
        assertThat(orderBookService.findOpenOrder(ownSell.getOrderId())).isNotNull();
        assertThat(processingStore.isReserved(eligibleSell.getOrderId())).isTrue();
    }

    @Test
    void legacyIncomingSell_shouldSkipOwnBestBuyAndReserveEligibleBuyer() throws Exception {
        OrderAssetReservationSucceededEvent incoming = order("SELL", 501, 3L, 1);
        OrderAssetReservationSucceededEvent ownBuy = order("BUY", 751, 1L, 1);
        ownBuy.setUserId(incoming.getUserId());
        OrderAssetReservationSucceededEvent eligibleBuy = order("BUY", 752, 2L, 1);
        orderBookService.addOrder(ownBuy);
        orderBookService.addOrder(eligibleBuy);

        OrderAssetReservationSucceededEvent reserved = orderBookService.reserveBestMatchOrderLua(incoming);

        assertThat(reserved.getOrderId()).isEqualTo(eligibleBuy.getOrderId());
        assertThat(orderBookService.findOpenOrder(ownBuy.getOrderId())).isNotNull();
        assertThat(processingStore.isReserved(eligibleBuy.getOrderId())).isTrue();
    }

    @Test
    void malformedJson_shouldFailClosedInAllReservationScriptFamilies() throws Exception {
        OrderAssetReservationSucceededEvent incomingBuy = order("BUY", 501, 3L, 1);
        OrderAssetReservationSucceededEvent malformedSell = order("SELL", 761, 1L, 1);
        orderBookService.addOrder(malformedSell);
        redisTemplate.opsForValue().set("order:" + malformedSell.getOrderId(), "{");

        assertThatThrownBy(() -> orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(incomingBuy))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> orderBookService.reserveBestMatchOrderLua(incomingBuy))
                .isInstanceOf(RuntimeException.class);
        assertThat(redisTemplate.opsForZSet().score(
                "orderbook:" + MARKET_ID + ":sell",
                malformedSell.getOrderId().toString())).isNotNull();

        OrderAssetReservationSucceededEvent incomingSell = order("SELL", 502, 3L, 1);
        OrderAssetReservationSucceededEvent malformedBuy = order("BUY", 762, 1L, 1);
        orderBookService.addOrder(malformedBuy);
        redisTemplate.opsForValue().set("order:" + malformedBuy.getOrderId(), "{");

        assertThatThrownBy(() -> orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(incomingSell))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> orderBookService.reserveBestMatchOrderLua(incomingSell))
                .isInstanceOf(RuntimeException.class);
        assertThat(redisTemplate.opsForZSet().score(
                "orderbook:" + MARKET_ID + ":buy",
                malformedBuy.getOrderId().toString())).isNotNull();
        assertThat(orderBookService.countActiveReservations()).isZero();
        assertThat(tradeCount()).isZero();
    }

    @Test
    void malformedRestingOrderWithoutOwner_shouldFailClosedWithoutReservation() throws Exception {
        OrderAssetReservationSucceededEvent incoming = order("BUY", 501, 2L, 1);
        OrderAssetReservationSucceededEvent malformedSell = order("SELL", 502, 1L, 1);
        orderBookService.addOrder(malformedSell);
        redisTemplate.opsForValue().set(
                "order:" + malformedSell.getOrderId(),
                "{\"i\":\"" + malformedSell.getOrderId()
                        + "\",\"m\":\"" + MARKET_ID
                        + "\",\"s\":1,\"p\":100,\"a\":1,\"t\":\"SELL\"}");

        assertThatThrownBy(() -> orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(incoming))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Redis orderbook owner missing")
                .hasMessageContaining(malformedSell.getOrderId().toString());

        assertThat(processingStore.isReserved(malformedSell.getOrderId())).isFalse();
        assertThat(orderBookService.findOpenOrder(malformedSell.getOrderId())).isNotNull();
    }

    @Test
    void cancellationBeforeOrderConfirmed_shouldPersistDecisionAndPreventAdmission() {
        OrderAssetReservationSucceededEvent order = order("BUY", 501, 1L, 7);
        UUID cancellationId = UUID.randomUUID();
        cancellationCoordinator.request(cancellationRequest(order, cancellationId));

        matchOrderAdmissionProcessor.process(order);

        OrderCancellationDecisionStore.Decision decision = cancellationDecisions.find(cancellationId);
        assertThat(decision.status()).isEqualTo(OrderCancellationResultEvent.CANCELLED);
        assertThat(decision.cancelledAmount()).isEqualTo(7);
        assertThat(orderBookService.findOpenOrder(order.getOrderId())).isNull();
        assertThat(processingStore.isCompleted(order)).isTrue();
        assertThat(tradeCount()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM match_engine.trade_outbox
                WHERE event_type = 'OrderCancellationResultEvent'
                  AND aggregate_id = ?
                """, Long.class, cancellationId.toString())).isEqualTo(1L);
    }

    @Test
    void pendingDecisionWithoutRedisIntent_whenMatchingWins_shouldConvergeAsAlreadyMatched() throws Exception {
        OrderAssetReservationSucceededEvent resting = order("SELL", 502, 1L, 7);
        OrderAssetReservationSucceededEvent incoming = order("BUY", 501, 2L, 7);
        UUID cancellationId = UUID.randomUUID();
        orderBookService.addOrder(resting);

        cancellationDecisions.begin(cancellationRequest(incoming, cancellationId), null);
        matchOrderAdmissionProcessor.process(incoming);
        cancellationCoordinator.reconcilePending();

        assertThat(tradeCount()).isEqualTo(1);
        assertThat(cancellationDecisions.find(cancellationId).status())
                .isEqualTo(OrderCancellationResultEvent.ALREADY_MATCHED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM match_engine.trade_outbox
                WHERE event_type = 'OrderCancellationResultEvent'
                  AND aggregate_id = ?
                """, Long.class, cancellationId.toString())).isEqualTo(1L);
    }

    @Test
    void crashAfterRedisCancellationBeforeDecisionCommit_shouldRecoverFromMarker() throws Exception {
        OrderAssetReservationSucceededEvent order = order("BUY", 501, 1L, 7);
        UUID cancellationId = UUID.randomUUID();
        OrderCancellationRequestedEvent request = cancellationRequest(order, cancellationId);
        orderBookService.addOrder(order);
        cancellationDecisions.begin(request, order);

        RedisOrderBookService.CancellationArbitration redisResult =
                orderBookService.arbitrateCancellation(order, cancellationId);

        assertThat(redisResult.outcome()).isEqualTo(RedisOrderBookService.CancellationOutcome.CANCELLED);
        assertThat(cancellationDecisions.find(cancellationId).status()).isEqualTo("PENDING");
        assertThat(orderBookService.findOpenOrder(order.getOrderId())).isNull();

        cancellationCoordinator.reconcilePending();

        assertThat(cancellationDecisions.find(cancellationId).status())
                .isEqualTo(OrderCancellationResultEvent.CANCELLED);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM match_engine.trade_outbox
                WHERE event_type = 'OrderCancellationResultEvent'
                  AND aggregate_id = ?
                """, Long.class, cancellationId.toString())).isEqualTo(1L);
    }

    @Test
    void crashAfterPreAdmissionDecisionBeforeCompletedMarker_shouldHealOnRedelivery() {
        OrderAssetReservationSucceededEvent order = order("BUY", 501, 1L, 7);
        UUID cancellationId = UUID.randomUUID();
        cancellationCoordinator.request(cancellationRequest(order, cancellationId));
        IncomingOrderProcessingStore markerFailingStore =
                new FailOnceCompletedStore(redisTemplate);
        MatchOrderAdmissionProcessor crashingProcessor = new MatchOrderAdmissionProcessor(
                matchingEngine(durableRecorder),
                markerFailingStore,
                tradeExecutionRepository,
                cancellationCoordinator,
                redissonClient,
                1);

        assertThatThrownBy(() -> crashingProcessor.process(order))
                .isInstanceOf(SimulatedCrash.class);

        assertThat(cancellationDecisions.find(cancellationId).status())
                .isEqualTo(OrderCancellationResultEvent.CANCELLED);
        assertThat(markerFailingStore.isCompleted(order)).isFalse();

        crashingProcessor.process(order);

        assertThat(markerFailingStore.isCompleted(order)).isTrue();
        assertThat(tradeCount()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM match_engine.trade_outbox
                WHERE event_type = 'OrderCancellationResultEvent'
                  AND aggregate_id = ?
                """, Long.class, cancellationId.toString())).isEqualTo(1L);
    }

    @Test
    void legacyCompletedHashRedelivery_shouldMigrateToBitmapWithoutMatchingAgain() throws Exception {
        OrderAssetReservationSucceededEvent resting = order("SELL", 901, 1L, 1);
        OrderAssetReservationSucceededEvent incoming = order("BUY", 501, 2L, 1);
        orderBookService.addOrder(resting);
        IncomingOrderProcessingStore.Claim claim = processingStore.newClaim(incoming);
        redisTemplate.opsForHash().put(
                claim.stateHashKey(),
                claim.orderIdField(),
                IncomingOrderProcessingStore.Status.COMPLETED.name());

        processor(matchingEngine(durableRecorder)).process(incoming);

        assertThat(tradeCount()).isZero();
        assertThat(visibleAmount(resting)).isEqualTo(1);
        assertThat(processingStore.state(INCOMING_ORDER_ID)).isNull();
        assertThat(processingStore.isCompleted(incoming)).isTrue();
    }

    @Test
    void noMatchAdd_shouldAtomicallyCompleteGuardWithoutSeparateMarkerWrite() {
        OrderAssetReservationSucceededEvent incoming = order("BUY", 501, 1L, 3);
        IncomingOrderProcessingStore markerFailingStore =
                new FailOnceCompletedStore(redisTemplate);
        MatchOrderAdmissionProcessor processor = new MatchOrderAdmissionProcessor(
                matchingEngine(durableRecorder),
                markerFailingStore,
                tradeExecutionRepository,
                cancellationCoordinator,
                redissonClient,
                1);

        processor.process(incoming);

        assertThat(visibleAmount(incoming)).isEqualTo(3);
        assertThat(processingStore.isCompleted(incoming)).isTrue();
        assertThat(processingStore.state(INCOMING_ORDER_ID)).isNull();
        assertThat(tradeCount()).isZero();

        processor.process(incoming);
        assertThat(visibleAmount(incoming)).isEqualTo(3);
        assertThat(tradeCount()).isZero();
    }

    @Test
    void staleCleanup_shouldNotDeleteNewerReservationForSameOrder() throws Exception {
        OrderAssetReservationSucceededEvent resting = order("BUY", 611, 1L, 1);
        OrderAssetReservationSucceededEvent firstIncoming = order("SELL", 612, 2L, 1);
        OrderAssetReservationSucceededEvent secondIncoming = order("SELL", 613, 3L, 1);
        orderBookService.addOrder(resting);

        RedisOrderBookService.ReservedMatch firstReservation =
                orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(firstIncoming).reservedMatch();
        String firstTradeId = MARKET_ID + "-" + firstReservation.matchId();
        orderBookService.releaseReservedOrder(resting, firstTradeId);

        RedisOrderBookService.ReservedMatch secondReservation =
                orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(secondIncoming).reservedMatch();
        String secondTradeId = MARKET_ID + "-" + secondReservation.matchId();
        assertThat(secondTradeId).isNotEqualTo(firstTradeId);
        assertThat(orderBookService.scanReservations(10).get(0).tradeId()).isEqualTo(secondTradeId);

        assertThat(orderBookService.completeReservedOrder(resting, firstTradeId))
                .isEqualTo(ReservationCompletionOutcome.NEWER_TRADE_OWNER);

        assertThat(orderBookService.countActiveReservations()).isEqualTo(1);
        assertThat(orderBookService.scanReservations(10).get(0).tradeId()).isEqualTo(secondTradeId);

        assertThat(orderBookService.completeReservedOrder(resting, secondTradeId))
                .isEqualTo(ReservationCompletionOutcome.COMPLETED);
        assertThat(orderBookService.countActiveReservations()).isZero();
        assertThat(orderBookService.completeReservedOrder(resting, secondTradeId))
                .isEqualTo(ReservationCompletionOutcome.ALREADY_COMPLETED);
    }

    @Test
    void cleanupWorker_whenNewerTradeOwnsReservation_shouldFailTaskWithoutDeletingReservation() throws Exception {
        OrderAssetReservationSucceededEvent resting = order("BUY", 621, 1L, 1);
        OrderAssetReservationSucceededEvent firstIncoming = order("SELL", 622, 2L, 1);
        OrderAssetReservationSucceededEvent secondIncoming = order("SELL", 623, 3L, 1);
        orderBookService.addOrder(resting);

        RedisOrderBookService.ReservedMatch firstReservation =
                orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(firstIncoming).reservedMatch();
        String firstTradeId = MARKET_ID + "-" + firstReservation.matchId();
        orderBookService.releaseReservedOrder(resting, firstTradeId);

        RedisOrderBookService.ReservedMatch secondReservation =
                orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(secondIncoming).reservedMatch();
        String secondTradeId = MARKET_ID + "-" + secondReservation.matchId();
        jdbc.update("""
                INSERT INTO match_engine.reservation_cleanup_tasks (
                    trade_id, order_id, user_id, status, attempt_count, next_retry_at)
                VALUES (?, ?, ?, 'PENDING', 0, CURRENT_TIMESTAMP)
                """, firstTradeId, resting.getOrderId(), resting.getUserId());

        assertThat(cleanupWorker().cleanupOnce()).isEqualTo(1);

        assertThat(jdbc.queryForObject("""
                SELECT status
                FROM match_engine.reservation_cleanup_tasks
                WHERE trade_id = ?
                """, String.class, firstTradeId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count
                FROM match_engine.reservation_cleanup_tasks
                WHERE trade_id = ?
                """, Integer.class, firstTradeId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT last_error
                FROM match_engine.reservation_cleanup_tasks
                WHERE trade_id = ?
                """, String.class, firstTradeId))
                .contains("NEWER_TRADE_OWNER")
                .contains(firstTradeId)
                .contains(resting.getOrderId().toString());
        assertThat(orderBookService.countActiveReservations()).isEqualTo(1);
        assertThat(orderBookService.scanReservations(10).get(0).tradeId()).isEqualTo(secondTradeId);
    }

    @Test
    void completeReservation_whenStoredOrderIdentityDiffers_shouldFailWithoutMutatingRedis() throws Exception {
        OrderAssetReservationSucceededEvent resting = order("BUY", 631, 1L, 1);
        OrderAssetReservationSucceededEvent incoming = order("SELL", 632, 2L, 1);
        orderBookService.addOrder(resting);
        RedisOrderBookService.ReservedMatch reservation =
                orderBookService.reserveBestMatchOrAddOrderWithSequenceLua(incoming).reservedMatch();
        String tradeId = MARKET_ID + "-" + reservation.matchId();
        String reservationKey = "order:reservation:" + resting.getOrderId();
        String orderKey = "order:" + resting.getOrderId();
        String userOrdersKey = "user:" + resting.getUserId() + ":orders";
        String originalReservation = redisTemplate.opsForValue().get(reservationKey);
        String originalOrderDetail = redisTemplate.opsForValue().get(orderKey);
        UUID differentOrderId = UUID.fromString("00000000-0000-0000-0000-000000009999");
        String corruptedReservation = originalReservation.replace(
                resting.getOrderId().toString(),
                differentOrderId.toString());
        redisTemplate.opsForValue().set(reservationKey, corruptedReservation);

        assertThat(orderBookService.completeReservedOrder(resting, tradeId))
                .isEqualTo(ReservationCompletionOutcome.ORDER_ID_MISMATCH);

        assertThat(redisTemplate.opsForValue().get(reservationKey)).isEqualTo(corruptedReservation);
        assertThat(redisTemplate.opsForValue().get(orderKey)).isEqualTo(originalOrderDetail);
        assertThat(redisTemplate.opsForSet().isMember(
                userOrdersKey,
                resting.getOrderId().toString())).isTrue();
    }

    @Test
    void crashAfterTradeCommitBeforeRedisCleanup_shouldResumeOnlyDurableRemainder() throws Exception {
        OrderAssetReservationSucceededEvent firstResting = order("SELL", 601, 1L, 2);
        OrderAssetReservationSucceededEvent secondResting = order("SELL", 602, 2L, 1);
        OrderAssetReservationSucceededEvent incoming = order("BUY", 501, 3L, 5);
        orderBookService.addOrder(firstResting);
        orderBookService.addOrder(secondResting);

        MatchingEngineService crashingEngine = matchingEngine(crashAfterCommitRecorder());
        MatchOrderAdmissionProcessor crashingProcessor = processor(crashingEngine);

        assertThatThrownBy(() -> crashingProcessor.process(incoming))
                .isInstanceOf(SimulatedCrash.class);

        assertThat(tradeCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(cleanupTaskCount("PENDING")).isEqualTo(1);
        assertThat(tradeExecutionRepository.sumQuantityByBuyerOrderId(INCOMING_ORDER_ID)).isEqualTo(2);
        assertThat(orderBookService.countActiveReservations()).isEqualTo(1);
        assertThat(processingStore.state(INCOMING_ORDER_ID).status())
                .isEqualTo(IncomingOrderProcessingStore.Status.PROCESSING);

        assertThat(reservationReconciler().reconcileOnce()).isZero();
        assertThat(orderBookService.countActiveReservations()).isEqualTo(1);

        cleanupWorker().cleanupOnce();
        assertThat(orderBookService.countActiveReservations()).isZero();
        backdateIncomingClaim();

        MatchOrderAdmissionProcessor recoveryProcessor = processor(matchingEngine(durableRecorder));
        recoveryProcessor.process(incoming);
        cleanupWorker().cleanupOnce();

        assertThat(tradeCount()).isEqualTo(2);
        assertThat(distinctTradeCount()).isEqualTo(2);
        assertThat(outboxCount()).isEqualTo(2);
        assertThat(cleanupTaskCount("COMPLETED")).isEqualTo(2);
        assertThat(tradeExecutionRepository.sumQuantityByBuyerOrderId(INCOMING_ORDER_ID)).isEqualTo(3);
        assertThat(tradesForRestingOrder(firstResting.getOrderId())).isEqualTo(1);
        assertThat(tradesForRestingOrder(secondResting.getOrderId())).isEqualTo(1);
        assertThat(visibleAmount(incoming)).isEqualTo(2);
        assertThat(tradeExecutionRepository.sumQuantityByBuyerOrderId(INCOMING_ORDER_ID)
                + visibleAmount(incoming)).isEqualTo(5);
        assertThat(orderBookService.countActiveReservations()).isZero();
        assertThat(processingStore.state(incoming).status())
                .isEqualTo(IncomingOrderProcessingStore.Status.COMPLETED);
        assertThat(processingStore.state(INCOMING_ORDER_ID)).isNull();

        recoveryProcessor.process(incoming);
        recoveryProcessor.process(incoming);

        assertThat(tradeCount()).isEqualTo(2);
        assertThat(outboxCount()).isEqualTo(2);
        assertThat(cleanupTaskCount("COMPLETED")).isEqualTo(2);
        assertThat(visibleAmount(incoming)).isEqualTo(2);
        assertThat(orderBookService.countActiveReservations()).isZero();
    }

    @Test
    void crashAfterLuaReservationBeforeTradeCommit_shouldReleaseOrphanAndMatchOnce() throws Exception {
        OrderAssetReservationSucceededEvent resting = order("SELL", 701, 1L, 2);
        OrderAssetReservationSucceededEvent incoming = order("BUY", 501, 2L, 2);
        orderBookService.addOrder(resting);

        MatchOrderAdmissionProcessor crashingProcessor =
                processor(matchingEngine(crashBeforeCommitRecorder()));

        assertThatThrownBy(() -> crashingProcessor.process(incoming))
                .isInstanceOf(SimulatedCrash.class);

        assertThat(tradeCount()).isZero();
        assertThat(outboxCount()).isZero();
        assertThat(cleanupTaskCount("PENDING")).isZero();
        assertThat(orderBookService.countActiveReservations()).isEqualTo(1);
        assertThat(processingStore.state(INCOMING_ORDER_ID).status())
                .isEqualTo(IncomingOrderProcessingStore.Status.PROCESSING);

        assertThat(reservationReconciler().reconcileOnce()).isEqualTo(1);
        assertThat(orderBookService.countActiveReservations()).isZero();
        assertThat(visibleAmount(resting)).isEqualTo(2);
        backdateIncomingClaim();

        MatchOrderAdmissionProcessor recoveryProcessor = processor(matchingEngine(durableRecorder));
        recoveryProcessor.process(incoming);
        cleanupWorker().cleanupOnce();

        assertThat(tradeCount()).isEqualTo(1);
        assertThat(distinctTradeCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(cleanupTaskCount("COMPLETED")).isEqualTo(1);
        assertThat(tradesForRestingOrder(resting.getOrderId())).isEqualTo(1);
        assertThat(tradeExecutionRepository.sumQuantityByBuyerOrderId(INCOMING_ORDER_ID)).isEqualTo(2);
        assertThat(orderBookService.countActiveReservations()).isZero();
        assertThat(processingStore.state(incoming).status())
                .isEqualTo(IncomingOrderProcessingStore.Status.COMPLETED);

        recoveryProcessor.process(incoming);
        recoveryProcessor.process(incoming);

        assertThat(tradeCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(cleanupTaskCount("COMPLETED")).isEqualTo(1);
    }

    @Test
    void crashBeforeCompletedMarker_shouldConvergeFromDurableTradeAndIgnoreRedelivery() throws Exception {
        OrderAssetReservationSucceededEvent resting = order("SELL", 801, 1L, 5);
        OrderAssetReservationSucceededEvent incoming = order("BUY", 501, 2L, 5);
        orderBookService.addOrder(resting);
        IncomingOrderProcessingStore markerFailingStore =
                new FailOnceCompletedStore(redisTemplate);

        MatchOrderAdmissionProcessor crashingProcessor = new MatchOrderAdmissionProcessor(
                matchingEngine(durableRecorder),
                markerFailingStore,
                tradeExecutionRepository,
                cancellationCoordinator,
                redissonClient,
                1);

        assertThatThrownBy(() -> crashingProcessor.process(incoming))
                .isInstanceOf(SimulatedCrash.class);

        assertThat(tradeCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(cleanupTaskCount("PENDING")).isEqualTo(1);
        assertThat(orderBookService.countActiveReservations()).isEqualTo(1);
        assertThat(processingStore.state(INCOMING_ORDER_ID).status())
                .isEqualTo(IncomingOrderProcessingStore.Status.PROCESSING);

        cleanupWorker().cleanupOnce();
        backdateIncomingClaim();

        MatchOrderAdmissionProcessor recoveryProcessor = processor(matchingEngine(durableRecorder));
        recoveryProcessor.process(incoming);

        assertThat(tradeCount()).isEqualTo(1);
        assertThat(distinctTradeCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(cleanupTaskCount("COMPLETED")).isEqualTo(1);
        assertThat(tradesForRestingOrder(resting.getOrderId())).isEqualTo(1);
        assertThat(tradeExecutionRepository.sumQuantityByBuyerOrderId(INCOMING_ORDER_ID)).isEqualTo(5);
        assertThat(orderBookService.countActiveReservations()).isZero();
        assertThat(processingStore.state(incoming).status())
                .isEqualTo(IncomingOrderProcessingStore.Status.COMPLETED);

        recoveryProcessor.process(incoming);
        recoveryProcessor.process(incoming);

        assertThat(tradeCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(cleanupTaskCount("COMPLETED")).isEqualTo(1);
    }

    private MatchingEngineService matchingEngine(TradeExecutionRecorder recorder) {
        return new MatchingEngineService(orderBookService, redissonClient, recorder, matchingMetrics);
    }

    private MatchOrderAdmissionProcessor processor(MatchingEngineService matchingEngineService) {
        return new MatchOrderAdmissionProcessor(
                matchingEngineService,
                processingStore,
                tradeExecutionRepository,
                cancellationCoordinator,
                redissonClient,
                1);
    }

    private TradeExecutionRecorder crashAfterCommitRecorder() {
        return new TradeExecutionRecorder() {
            @Override
            public void record(TradeExecutedEvent event) {
                durableRecorder.record(event);
                throw new SimulatedCrash();
            }

            @Override
            public boolean record(TradeExecutedEvent event, ReservationCleanupTask cleanupTask) {
                durableRecorder.record(event, cleanupTask);
                throw new SimulatedCrash();
            }
        };
    }

    private TradeExecutionRecorder crashBeforeCommitRecorder() {
        return new TradeExecutionRecorder() {
            @Override
            public void record(TradeExecutedEvent event) {
                throw new SimulatedCrash();
            }

            @Override
            public boolean record(TradeExecutedEvent event, ReservationCleanupTask cleanupTask) {
                throw new SimulatedCrash();
            }
        };
    }

    private ReservationCleanupWorker cleanupWorker() {
        return new ReservationCleanupWorker(
                jdbc,
                orderBookService,
                cleanupMetrics,
                100,
                10,
                1,
                1000,
                0,
                50);
    }

    private ReservationReconciler reservationReconciler() {
        return new ReservationReconciler(
                orderBookService,
                tradeExecutionRepository,
                cleanupTaskStore,
                reconcilerMetrics,
                0,
                100);
    }

    private void backdateIncomingClaim() {
        Set<String> stateKeys = redisTemplate.keys("match:incoming-order:states:*");
        assertThat(stateKeys).isNotNull().hasSize(1);
        String stateKey = stateKeys.iterator().next();
        IncomingOrderProcessingStore.State state = processingStore.state(INCOMING_ORDER_ID);
        redisTemplate.opsForHash().put(
                stateKey,
                INCOMING_ORDER_ID.toString(),
                "PROCESSING:" + state.token() + ":0");
    }

    private int visibleAmount(OrderAssetReservationSucceededEvent order) {
        return orderBookService.getOrderByUserId(order.getUserId()).stream()
                .filter(candidate -> candidate.getOrderId().equals(order.getOrderId()))
                .findFirst()
                .orElseThrow()
                .getAmount();
    }

    private long tradeCount() {
        return jdbc.queryForObject("SELECT count(*) FROM match_engine.trade_executions", Long.class);
    }

    private long distinctTradeCount() {
        return jdbc.queryForObject(
                "SELECT count(DISTINCT trade_id) FROM match_engine.trade_executions", Long.class);
    }

    private long outboxCount() {
        return jdbc.queryForObject("SELECT count(*) FROM match_engine.trade_outbox", Long.class);
    }

    private long cleanupTaskCount(String status) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM match_engine.reservation_cleanup_tasks WHERE status = ?",
                Long.class,
                status);
    }

    private long tradesForRestingOrder(UUID orderId) {
        return jdbc.queryForObject("""
                SELECT count(*)
                FROM match_engine.trade_executions
                WHERE buyer_order_id = ? OR seller_order_id = ?
                """, Long.class, orderId, orderId);
    }

    private OrderAssetReservationSucceededEvent order(String side, int suffix, long sequence, int amount) {
        UUID orderId = suffix == 501
                ? INCOMING_ORDER_ID
                : UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
        UUID userId = UUID.fromString("00000000-0000-0000-0001-%012d".formatted(suffix));
        return OrderAssetReservationSucceededEvent.builder()
                .orderId(orderId)
                .userId(userId)
                .marketId(MARKET_ID)
                .marketSequence(sequence)
                .price(100)
                .amount(amount)
                .orderType(side)
                .createdAt(LocalDateTime.of(2026, 8, 6, 12, 0).plusSeconds(sequence))
                .build();
    }

    private OrderCancellationRequestedEvent cancellationRequest(
            OrderAssetReservationSucceededEvent order,
            UUID cancellationId) {
        return OrderCancellationRequestedEvent.builder()
                .cancellationId(cancellationId)
                .orderId(order.getOrderId())
                .userId(order.getUserId())
                .originalAmount(order.getAmount())
                .requestedAt(LocalDateTime.now())
                .build();
    }

    private static final class SimulatedCrash extends Error {
    }

    private static final class FailOnceCompletedStore extends IncomingOrderProcessingStore {
        private boolean fail = true;

        private FailOnceCompletedStore(RedisTemplate<String, String> redisTemplate) {
            super(redisTemplate);
        }

        @Override
        void markCompleted(OrderAssetReservationSucceededEvent order) {
            if (fail) {
                fail = false;
                throw new SimulatedCrash();
            }
            super.markCompleted(order);
        }
    }
}
