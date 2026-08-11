package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
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

    @BeforeEach
    void resetState() {
        jdbc.execute("""
                TRUNCATE TABLE
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
    void legacyCompletedHashRedelivery_shouldMigrateToBitmapWithoutMatchingAgain() throws Exception {
        OrderConfirmedEvent resting = order("SELL", 901, 1L, 1);
        OrderConfirmedEvent incoming = order("BUY", 501, 2L, 1);
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
    void staleCleanup_shouldNotDeleteNewerReservationForSameOrder() throws Exception {
        OrderConfirmedEvent resting = order("BUY", 611, 1L, 1);
        OrderConfirmedEvent firstIncoming = order("SELL", 612, 2L, 1);
        OrderConfirmedEvent secondIncoming = order("SELL", 613, 3L, 1);
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

        orderBookService.completeReservedOrder(resting, firstTradeId);

        assertThat(orderBookService.countActiveReservations()).isEqualTo(1);
        assertThat(orderBookService.scanReservations(10).get(0).tradeId()).isEqualTo(secondTradeId);

        orderBookService.completeReservedOrder(resting, secondTradeId);
        assertThat(orderBookService.countActiveReservations()).isZero();
    }

    @Test
    void crashAfterTradeCommitBeforeRedisCleanup_shouldResumeOnlyDurableRemainder() throws Exception {
        OrderConfirmedEvent firstResting = order("SELL", 601, 1L, 2);
        OrderConfirmedEvent secondResting = order("SELL", 602, 2L, 1);
        OrderConfirmedEvent incoming = order("BUY", 501, 3L, 5);
        orderBookService.addOrder(firstResting);
        orderBookService.addOrder(secondResting);

        MatchingEngineService crashingEngine = matchingEngine(crashAfterCommitRecorder());
        OrderConfirmedProcessor crashingProcessor = processor(crashingEngine);

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

        OrderConfirmedProcessor recoveryProcessor = processor(matchingEngine(durableRecorder));
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
        OrderConfirmedEvent resting = order("SELL", 701, 1L, 2);
        OrderConfirmedEvent incoming = order("BUY", 501, 2L, 2);
        orderBookService.addOrder(resting);

        OrderConfirmedProcessor crashingProcessor =
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

        OrderConfirmedProcessor recoveryProcessor = processor(matchingEngine(durableRecorder));
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
        OrderConfirmedEvent resting = order("SELL", 801, 1L, 5);
        OrderConfirmedEvent incoming = order("BUY", 501, 2L, 5);
        orderBookService.addOrder(resting);
        IncomingOrderProcessingStore markerFailingStore =
                new FailOnceCompletedStore(redisTemplate);

        OrderConfirmedProcessor crashingProcessor = new OrderConfirmedProcessor(
                matchingEngine(durableRecorder),
                markerFailingStore,
                tradeExecutionRepository,
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

        OrderConfirmedProcessor recoveryProcessor = processor(matchingEngine(durableRecorder));
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

    private OrderConfirmedProcessor processor(MatchingEngineService matchingEngineService) {
        return new OrderConfirmedProcessor(
                matchingEngineService,
                processingStore,
                tradeExecutionRepository,
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

    private int visibleAmount(OrderConfirmedEvent order) {
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

    private OrderConfirmedEvent order(String side, int suffix, long sequence, int amount) {
        UUID orderId = suffix == 501
                ? INCOMING_ORDER_ID
                : UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
        UUID userId = UUID.fromString("00000000-0000-0000-0001-%012d".formatted(suffix));
        return OrderConfirmedEvent.builder()
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

    private static final class SimulatedCrash extends Error {
    }

    private static final class FailOnceCompletedStore extends IncomingOrderProcessingStore {
        private boolean fail = true;

        private FailOnceCompletedStore(RedisTemplate<String, String> redisTemplate) {
            super(redisTemplate);
        }

        @Override
        void markCompleted(OrderConfirmedEvent order) {
            if (fail) {
                fail = false;
                throw new SimulatedCrash();
            }
            super.markCompleted(order);
        }
    }
}
