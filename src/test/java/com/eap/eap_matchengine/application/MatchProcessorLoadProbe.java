package com.eap.eap_matchengine.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.eap.common.event.OrderConfirmedEvent;
import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_matchengine.configuration.repository.TradeExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import liquibase.integration.spring.SpringLiquibase;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Isolated diagnostic for the combined MatchEngine order-processing path.
 */
public final class MatchProcessorLoadProbe {

    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://localhost:15434/eap_match_db";
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin123";

    private MatchProcessorLoadProbe() {
    }

    public static void main(String[] args) throws Exception {
        quietApplicationLogs();
        ProbeConfig config = ProbeConfig.from(args);
        String marketId = "MATCH-PROCESSOR-" + UUID.randomUUID();
        List<OrderConfirmedEvent> orders = orders(config, marketId);

        HikariDataSource dataSource = dataSource(config);
        LettuceConnectionFactory redisConnectionFactory =
                new LettuceConnectionFactory(config.redisHost(), config.redisPort());
        redisConnectionFactory.afterPropertiesSet();
        RedisTemplate<String, String> redisTemplate = redisTemplate(redisConnectionFactory);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        RedissonClient redisson = redisson(config);

        try {
            migrate(dataSource);
            requireCleanInputState(jdbc, redisTemplate, marketId);

            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            MatchingEngineMetrics matchingMetrics = new MatchingEngineMetrics(registry);
            ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
            RedisOrderBookService orderBookService =
                    new RedisOrderBookService(redisTemplate, objectMapper, matchingMetrics);
            orderBookService.init();

            JpaTradeExecutionRecorder jdbcRecorder =
                    new JpaTradeExecutionRecorder(jdbc, matchingMetrics, true);
            TransactionTemplate transaction =
                    new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            TradeExecutionRecorder recorder = transactionalRecorder(jdbcRecorder, transaction);
            MatchingEngineService matchingEngine =
                    new MatchingEngineService(orderBookService, redisson, recorder, matchingMetrics);
            IncomingOrderProcessingStore processingStore =
                    new IncomingOrderProcessingStore(redisTemplate);
            OrderConfirmedProcessor processor = new OrderConfirmedProcessor(
                    matchingEngine,
                    processingStore,
                    unusedRecoveryRepository(),
                    redisson,
                    30);

            ProcessingResult processing = runProcessing(config, processor, orders);
            PreCleanupState preCleanup = preCleanupState(
                    jdbc, redisTemplate, orderBookService, marketId, config);

            ReservationCleanupMetrics cleanupMetrics = new ReservationCleanupMetrics(registry);
            ReservationCleanupWorker cleanupWorker = new ReservationCleanupWorker(
                    jdbc,
                    orderBookService,
                    cleanupMetrics,
                    config.cleanupBatchSize(),
                    3,
                    1,
                    100,
                    30,
                    config.leaseChunkSize());
            CleanupResult cleanup = runCleanup(
                    cleanupWorker, jdbc, orderBookService, marketId, config.pairs());

            Map<String, Object> output = resultMap(
                    config, marketId, processing, preCleanup, cleanup, registry);
            writeResult(config, objectMapper, output);
            assertCorrect(config, processing, preCleanup, cleanup);
        } finally {
            cleanupRedis(redisTemplate, orders, marketId);
            cleanupDatabaseQuietly(jdbc, marketId);
            redisson.shutdown();
            redisConnectionFactory.destroy();
            dataSource.close();
        }
    }

    private static ProcessingResult runProcessing(
            ProbeConfig config,
            OrderConfirmedProcessor processor,
            List<OrderConfirmedEvent> orders) throws InterruptedException {
        AtomicInteger next = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(orders.size()));
        ExecutorService executor = Executors.newFixedThreadPool(config.workers());
        CountDownLatch ready = new CountDownLatch(config.workers());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(config.workers());

        for (int worker = 0; worker < config.workers(); worker++) {
            executor.execute(() -> {
                ready.countDown();
                try {
                    start.await();
                    while (true) {
                        int index = next.getAndIncrement();
                        if (index >= orders.size()) {
                            return;
                        }
                        long startedAt = System.nanoTime();
                        try {
                            processor.process(orders.get(index));
                        } catch (RuntimeException failure) {
                            int failureCount = failures.incrementAndGet();
                            if (failureCount <= 10) {
                                System.err.printf("processor failure index=%d: %s%n", index, failure.getMessage());
                            }
                        } finally {
                            latencies.add(System.nanoTime() - startedAt);
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        long startedAt = System.nanoTime();
        start.countDown();
        done.await();
        double elapsedSeconds = elapsedSeconds(startedAt);
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        return new ProcessingResult(
                failures.get(),
                elapsedSeconds,
                percentileMillis(sorted, 0.50),
                percentileMillis(sorted, 0.95),
                percentileMillis(sorted, 0.99));
    }

    private static PreCleanupState preCleanupState(
            JdbcTemplate jdbc,
            RedisTemplate<String, String> redisTemplate,
            RedisOrderBookService orderBookService,
            String marketId,
            ProbeConfig config) {
        return new PreCleanupState(
                count(jdbc, "SELECT count(*) FROM match_engine.trade_executions WHERE market_id = ?", marketId),
                count(jdbc, "SELECT count(DISTINCT trade_id) FROM match_engine.trade_executions WHERE market_id = ?",
                        marketId),
                count(jdbc, "SELECT coalesce(sum(quantity), 0) FROM match_engine.trade_executions WHERE market_id = ?",
                        marketId),
                count(jdbc, "SELECT count(*) FROM match_engine.trade_outbox WHERE aggregate_id LIKE ?", marketId + "-%"),
                count(jdbc, "SELECT count(*) FROM match_engine.reservation_cleanup_tasks WHERE trade_id LIKE ?",
                        marketId + "-%"),
                count(jdbc, "SELECT count(*) FROM match_engine.reservation_cleanup_tasks WHERE trade_id LIKE ? AND status = 'PENDING'",
                        marketId + "-%"),
                orderBookService.countActiveReservations(),
                zsetSize(redisTemplate, "orderbook:" + marketId + ":buy"),
                zsetSize(redisTemplate, "orderbook:" + marketId + ":sell"),
                completedMarkerCount(redisTemplate, marketId, config.totalOrders()));
    }

    private static CleanupResult runCleanup(
            ReservationCleanupWorker worker,
            JdbcTemplate jdbc,
            RedisOrderBookService orderBookService,
            String marketId,
            int expectedTasks) {
        int calls = 0;
        int claimed = 0;
        long startedAt = System.nanoTime();
        while (claimed < expectedTasks) {
            int batchClaimed = worker.cleanupOnce();
            if (batchClaimed == 0) {
                break;
            }
            calls++;
            claimed += batchClaimed;
        }
        double elapsedSeconds = elapsedSeconds(startedAt);
        return new CleanupResult(
                calls,
                claimed,
                count(jdbc, "SELECT count(*) FROM match_engine.reservation_cleanup_tasks WHERE trade_id LIKE ? AND status = 'COMPLETED'",
                        marketId + "-%"),
                count(jdbc, "SELECT count(*) FROM match_engine.reservation_cleanup_tasks WHERE trade_id LIKE ? AND status != 'COMPLETED'",
                        marketId + "-%"),
                orderBookService.countActiveReservations(),
                elapsedSeconds);
    }

    private static Map<String, Object> resultMap(
            ProbeConfig config,
            String marketId,
            ProcessingResult processing,
            PreCleanupState preCleanup,
            CleanupResult cleanup,
            SimpleMeterRegistry registry) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mode", "matchProcessorCombinedIsolated");
        output.put("evidenceClass", "isolated-diagnostic");
        output.put("marketId", marketId);
        output.put("arrivalPattern", "shuffled-mixed");
        output.put("seed", config.seed());
        output.put("pairs", config.pairs());
        output.put("totalOrders", config.totalOrders());
        output.put("workers", config.workers());
        output.put("dbPoolSize", config.dbPoolSize());
        output.put("cleanupBatchSize", config.cleanupBatchSize());
        output.put("processingFailures", processing.failures());
        output.put("processingSeconds", round(processing.elapsedSeconds()));
        output.put("processedOrdersPerSecond",
                round(config.totalOrders() / Math.max(processing.elapsedSeconds(), 0.001)));
        output.put("persistedTradesPerSecond",
                round(preCleanup.tradeRows() / Math.max(processing.elapsedSeconds(), 0.001)));
        output.put("orderLatencyP50Ms", round(processing.p50Ms()));
        output.put("orderLatencyP95Ms", round(processing.p95Ms()));
        output.put("orderLatencyP99Ms", round(processing.p99Ms()));
        output.put("tradeRows", preCleanup.tradeRows());
        output.put("distinctTradeIds", preCleanup.distinctTradeIds());
        output.put("matchedQuantity", preCleanup.matchedQuantity());
        output.put("outboxRows", preCleanup.outboxRows());
        output.put("cleanupTaskRows", preCleanup.cleanupTaskRows());
        output.put("pendingCleanupTasksBeforeDrain", preCleanup.pendingCleanupTasks());
        output.put("activeReservationsBeforeDrain", preCleanup.activeReservations());
        output.put("remainingBuyOrders", preCleanup.remainingBuyOrders());
        output.put("remainingSellOrders", preCleanup.remainingSellOrders());
        output.put("completedIncomingMarkers", preCleanup.completedIncomingMarkers());
        output.put("cleanupCalls", cleanup.calls());
        output.put("cleanupClaimed", cleanup.claimed());
        output.put("cleanupSeconds", round(cleanup.elapsedSeconds()));
        output.put("cleanupTasksPerSecond",
                round(cleanup.completed() / Math.max(cleanup.elapsedSeconds(), 0.001)));
        output.put("completedCleanupTasks", cleanup.completed());
        output.put("nonCompletedCleanupTasks", cleanup.nonCompleted());
        output.put("activeReservationsAfterDrain", cleanup.activeReservations());
        output.put("tryMatchMeanMs", round(timerMeanMillis(registry, "match_engine_try_match_duration")));
        output.put("tryMatchMaxMs", round(timerMaxMillis(registry, "match_engine_try_match_duration")));
        output.put("tradeTransactionMeanMs",
                round(timerMeanMillis(registry, "match_engine_trade_record_phase_duration", "phase", "transaction_total")));
        output.put("tradeTransactionMaxMs",
                round(timerMaxMillis(registry, "match_engine_trade_record_phase_duration", "phase", "transaction_total")));
        output.put("correctness", correctness(config, processing, preCleanup, cleanup) ? "PASS" : "FAIL");
        output.put("capacityClaimAllowed", false);
        return output;
    }

    private static void assertCorrect(
            ProbeConfig config,
            ProcessingResult processing,
            PreCleanupState preCleanup,
            CleanupResult cleanup) {
        require(correctness(config, processing, preCleanup, cleanup),
                "combined MatchEngine probe failed its correctness gate");
    }

    private static boolean correctness(
            ProbeConfig config,
            ProcessingResult processing,
            PreCleanupState preCleanup,
            CleanupResult cleanup) {
        return processing.failures() == 0
                && preCleanup.tradeRows() == config.pairs()
                && preCleanup.distinctTradeIds() == config.pairs()
                && preCleanup.matchedQuantity() == config.pairs()
                && preCleanup.outboxRows() == config.pairs()
                && preCleanup.cleanupTaskRows() == config.pairs()
                && preCleanup.pendingCleanupTasks() == config.pairs()
                && preCleanup.activeReservations() == config.pairs()
                && preCleanup.remainingBuyOrders() == 0
                && preCleanup.remainingSellOrders() == 0
                && preCleanup.completedIncomingMarkers() == config.totalOrders()
                && cleanup.claimed() == config.pairs()
                && cleanup.completed() == config.pairs()
                && cleanup.nonCompleted() == 0
                && cleanup.activeReservations() == 0;
    }

    private static TradeExecutionRecorder transactionalRecorder(
            JpaTradeExecutionRecorder delegate,
            TransactionTemplate transaction) {
        return new TradeExecutionRecorder() {
            @Override
            public void record(TradeExecutedEvent event) {
                transaction.executeWithoutResult(status -> delegate.record(event));
            }

            @Override
            public boolean record(TradeExecutedEvent event, ReservationCleanupTask cleanupTask) {
                Boolean result = transaction.execute(status -> delegate.record(event, cleanupTask));
                return Boolean.TRUE.equals(result);
            }
        };
    }

    private static TradeExecutionRepository unusedRecoveryRepository() {
        return (TradeExecutionRepository) Proxy.newProxyInstance(
                MatchProcessorLoadProbe.class.getClassLoader(),
                new Class<?>[]{TradeExecutionRepository.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "UnusedTradeExecutionRepository";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    throw new IllegalStateException("Recovery repository should not be used by a clean probe");
                });
    }

    private static List<OrderConfirmedEvent> orders(ProbeConfig config, String marketId) {
        List<OrderConfirmedEvent> orders = new ArrayList<>(config.totalOrders());
        for (int index = 0; index < config.pairs(); index++) {
            long sellSequence = index * 2L + 1;
            long buySequence = sellSequence + 1;
            orders.add(order(marketId, "SELL", sellSequence));
            orders.add(order(marketId, "BUY", buySequence));
        }
        Collections.shuffle(orders, new Random(config.seed()));
        return orders;
    }

    private static OrderConfirmedEvent order(String marketId, String side, long sequence) {
        return OrderConfirmedEvent.builder()
                .orderId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .marketId(marketId)
                .marketSequence(sequence)
                .price(100)
                .amount(1)
                .orderType(side)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private static HikariDataSource dataSource(ProbeConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.dbPoolSize());
        hikari.setMinimumIdle(Math.min(2, config.dbPoolSize()));
        hikari.setPoolName("match-processor-probe");
        return new HikariDataSource(hikari);
    }

    private static RedisTemplate<String, String> redisTemplate(LettuceConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    private static RedissonClient redisson(ProbeConfig config) {
        Config redissonConfig = new Config();
        redissonConfig.useSingleServer()
                .setAddress("redis://" + config.redisHost() + ":" + config.redisPort());
        return Redisson.create(redissonConfig);
    }

    private static void migrate(HikariDataSource dataSource) throws Exception {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.xml");
        liquibase.afterPropertiesSet();
    }

    private static void requireCleanInputState(
            JdbcTemplate jdbc,
            RedisTemplate<String, String> redisTemplate,
            String marketId) {
        long claimable = count(jdbc, """
                SELECT count(*)
                FROM match_engine.reservation_cleanup_tasks
                WHERE status IN ('PENDING', 'PROCESSING')
                """);
        require(claimable == 0, "dedicated MatchEngine database contains claimable cleanup tasks");
        require(!Boolean.TRUE.equals(redisTemplate.hasKey("orderbook:" + marketId + ":buy")),
                "probe BUY orderbook already exists");
        require(!Boolean.TRUE.equals(redisTemplate.hasKey("orderbook:" + marketId + ":sell")),
                "probe SELL orderbook already exists");
    }

    private static long completedMarkerCount(
            RedisTemplate<String, String> redisTemplate,
            String marketId,
            int totalOrders) {
        long lastShard = (totalOrders - 1L) / 10_000_000L;
        long count = 0;
        for (long shard = 0; shard <= lastShard; shard++) {
            long currentShard = shard;
            Long shardCount = redisTemplate.execute((RedisCallback<Long>) connection ->
                    connection.stringCommands().bitCount(
                            bytes("match:incoming-order:completed:" + marketId + ":" + currentShard)));
            count += shardCount == null ? 0 : shardCount;
        }
        return count;
    }

    private static long zsetSize(RedisTemplate<String, String> redisTemplate, String key) {
        Long size = redisTemplate.opsForZSet().size(key);
        return size == null ? 0 : size;
    }

    private static long count(JdbcTemplate jdbc, String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private static void cleanupDatabaseQuietly(JdbcTemplate jdbc, String marketId) {
        try {
            jdbc.update("DELETE FROM match_engine.reservation_cleanup_tasks WHERE trade_id LIKE ?", marketId + "-%");
            jdbc.update("DELETE FROM match_engine.trade_outbox WHERE aggregate_id LIKE ?", marketId + "-%");
            jdbc.update("DELETE FROM match_engine.trade_executions WHERE market_id = ?", marketId);
        } catch (RuntimeException ignored) {
            // Preserve the original setup or probe failure when the schema is unavailable.
        }
    }

    private static void cleanupRedis(
            RedisTemplate<String, String> redisTemplate,
            List<OrderConfirmedEvent> orders,
            String marketId) {
        List<String> keys = new ArrayList<>(orders.size() * 3 + 3);
        for (OrderConfirmedEvent order : orders) {
            keys.add("order:" + order.getOrderId());
            keys.add("user:" + order.getUserId() + ":orders");
            keys.add("order:reservation:" + order.getOrderId());
        }
        keys.add("orderbook:" + marketId + ":buy");
        keys.add("orderbook:" + marketId + ":sell");
        long lastShard = (orders.size() - 1L) / 10_000_000L;
        for (long shard = 0; shard <= lastShard; shard++) {
            keys.add("match:incoming-order:completed:" + marketId + ":" + shard);
        }
        redisTemplate.delete(keys);
    }

    private static void writeResult(
            ProbeConfig config,
            ObjectMapper objectMapper,
            Map<String, Object> output) throws Exception {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        System.out.println(json);
        if (config.output() == null) {
            return;
        }
        File outputFile = new File(config.output());
        File parent = outputFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, output);
    }

    private static void quietApplicationLogs() {
        setLogLevel("com.eap.eap_matchengine.application.MatchingEngineService", Level.ERROR);
        setLogLevel("com.eap.eap_matchengine.application.RedisOrderBookService", Level.ERROR);
        setLogLevel("org.redisson", Level.WARN);
    }

    private static void setLogLevel(String name, Level level) {
        org.slf4j.Logger logger = LoggerFactory.getLogger(name);
        if (logger instanceof Logger logbackLogger) {
            logbackLogger.setLevel(level);
        }
    }

    private static double timerMeanMillis(SimpleMeterRegistry registry, String name, String... tags) {
        Timer timer = registry.find(name).tags(tags).timer();
        return timer == null ? 0.0 : timer.mean(TimeUnit.MILLISECONDS);
    }

    private static double timerMaxMillis(SimpleMeterRegistry registry, String name, String... tags) {
        Timer timer = registry.find(name).tags(tags).timer();
        return timer == null ? 0.0 : timer.max(TimeUnit.MILLISECONDS);
    }

    private static double percentileMillis(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))) / 1_000_000.0;
    }

    private static double elapsedSeconds(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000_000.0;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record ProcessingResult(
            int failures,
            double elapsedSeconds,
            double p50Ms,
            double p95Ms,
            double p99Ms) {
    }

    private record PreCleanupState(
            long tradeRows,
            long distinctTradeIds,
            long matchedQuantity,
            long outboxRows,
            long cleanupTaskRows,
            long pendingCleanupTasks,
            long activeReservations,
            long remainingBuyOrders,
            long remainingSellOrders,
            long completedIncomingMarkers) {
    }

    private record CleanupResult(
            int calls,
            int claimed,
            long completed,
            long nonCompleted,
            long activeReservations,
            double elapsedSeconds) {
    }

    private record ProbeConfig(
            String jdbcUrl,
            String username,
            String password,
            String redisHost,
            int redisPort,
            int pairs,
            int workers,
            int dbPoolSize,
            int cleanupBatchSize,
            int leaseChunkSize,
            long seed,
            String output) {

        int totalOrders() {
            return Math.multiplyExact(pairs, 2);
        }

        private static ProbeConfig from(String[] args) {
            return new ProbeConfig(
                    stringArg(args, "--jdbc-url", DEFAULT_JDBC_URL),
                    stringArg(args, "--username", DEFAULT_USERNAME),
                    stringArg(args, "--password", DEFAULT_PASSWORD),
                    stringArg(args, "--redis-host", "localhost"),
                    positiveInt(args, "--redis-port", 6379),
                    positiveInt(args, "--pairs", 10_000),
                    positiveInt(args, "--workers", 12),
                    positiveInt(args, "--db-pool-size", 35),
                    positiveInt(args, "--cleanup-batch-size", 1000),
                    positiveInt(args, "--lease-chunk-size", 50),
                    longArg(args, "--seed", 20260814L),
                    nullableArg(args, "--output"));
        }

        private static int positiveInt(String[] args, String name, int defaultValue) {
            int value = Integer.parseInt(stringArg(args, name, String.valueOf(defaultValue)));
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }

        private static long longArg(String[] args, String name, long defaultValue) {
            return Long.parseLong(stringArg(args, name, String.valueOf(defaultValue)));
        }

        private static String stringArg(String[] args, String name, String defaultValue) {
            String value = nullableArg(args, name);
            return value == null ? defaultValue : value;
        }

        private static String nullableArg(String[] args, String name) {
            for (int index = 0; index < args.length - 1; index++) {
                if (name.equals(args[index])) {
                    return args[index + 1];
                }
            }
            return null;
        }
    }
}
