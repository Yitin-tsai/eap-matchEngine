package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderAssetReservationSucceededEvent;
import com.eap.common.event.OrderCancellationRequestedEvent;
import com.eap.common.event.OrderCancellationResultEvent;
import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_matchengine.configuration.repository.TradeExecutionRepository;
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
import java.util.Set;
import java.util.UUID;

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
                "eap.match-engine.reservation-cleanup.enabled=false"
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

    @BeforeEach
    void resetState() {
        jdbc.execute("""
                TRUNCATE TABLE
                    match_engine.order_admission_inbox,
                    match_engine.order_cancellations,
                    match_engine.reservation_cleanup_tasks,
                    match_engine.trade_outbox,
                    match_engine.trade_executions
                RESTART IDENTITY CASCADE
                """);
        try (var connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
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
