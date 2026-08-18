package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
import com.eap.eap_matchengine.EapMatchengineApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static com.eap.common.constants.RabbitMQConstants.DEAD_LETTER_QUEUE;
import static com.eap.common.constants.RabbitMQConstants.MATCH_ENGINE_ORDER_CONFIRMED_QUEUE;
import static com.eap.common.constants.RabbitMQConstants.ORDER_CONFIRMED_KEY;
import static com.eap.common.constants.RabbitMQConstants.ORDER_EXCHANGE;

/**
 * Isolates the real RabbitMQ listener-to-MatchEngine boundary from Order and Wallet.
 */
public final class RabbitMatchIntakeProbe {

    private RabbitMatchIntakeProbe() {
    }

    public static void main(String[] args) throws Exception {
        ProbeConfig config = ProbeConfig.from(args);
        String marketId = "RABBIT-MATCH-" + UUID.randomUUID();
        List<OrderConfirmedEvent> orders = orders(config, marketId);

        ConfigurableApplicationContext context = SpringApplication.run(
                EapMatchengineApplication.class,
                "--spring.profiles.active=loadtest",
                "--spring.main.banner-mode=off",
                "--server.port=0",
                "--eap.match-engine.trade-outbox-relay.enabled=false",
                "--eap.match-engine.trade-checkpoint-relay.enabled=false");

        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
        RabbitTemplate rabbit = context.getBean(RabbitTemplate.class);
        AmqpAdmin rabbitAdmin = context.getBean(AmqpAdmin.class);
        MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
        ObjectMapper objectMapper = context.getBean(ObjectMapper.class);

        try (QueueMonitor monitor = new QueueMonitor(config, objectMapper)) {
            requireCleanInputState(jdbc, redis, marketId);
            rabbitAdmin.purgeQueue(MATCH_ENGINE_ORDER_CONFIRMED_QUEUE, false);
            rabbitAdmin.purgeQueue(DEAD_LETTER_QUEUE, false);
            waitForConsumers(monitor, config.timeoutSeconds());
            monitor.start();

            PublishResult publish = publish(config, rabbit, orders);
            ConvergenceResult convergence = awaitConvergence(
                    config, jdbc, redis, marketId, orders, publish.startedAtNanos());
            waitForQueueDrain(monitor, config.timeoutSeconds());
            QueueStats finalQueue = monitor.read(MATCH_ENGINE_ORDER_CONFIRMED_QUEUE);
            QueueStats finalDlq = monitor.read(DEAD_LETTER_QUEUE);

            Map<String, Object> result = resultMap(
                    config, marketId, publish, convergence, monitor, finalQueue, finalDlq, meterRegistry);
            writeResult(config, objectMapper, result);
            require(correctness(config, publish, convergence, finalQueue, finalDlq),
                    "Rabbit-to-Match probe failed its correctness gate");
        } finally {
            cleanupRedis(redis, orders, marketId);
            cleanupDatabaseQuietly(jdbc, marketId);
            context.close();
        }
    }

    private static PublishResult publish(
            ProbeConfig config,
            RabbitTemplate rabbit,
            List<OrderConfirmedEvent> orders) throws Exception {
        List<CorrelationData> confirmations = new ArrayList<>(orders.size());
        long intervalNanos = Math.max(1L, 1_000_000_000L / config.targetOrderTps());
        long startedAt = System.nanoTime();

        for (int index = 0; index < orders.size(); index++) {
            long deadline = startedAt + intervalNanos * index;
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                LockSupport.parkNanos(remaining);
            }
            CorrelationData correlation = new CorrelationData(orders.get(index).getOrderId().toString());
            confirmations.add(correlation);
            rabbit.convertAndSend(ORDER_EXCHANGE, ORDER_CONFIRMED_KEY, orders.get(index), correlation);
        }
        double offerSeconds = elapsedSeconds(startedAt);

        int nacks = 0;
        int returns = 0;
        long confirmationDeadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(config.confirmTimeoutSeconds());
        for (CorrelationData correlation : confirmations) {
            long remaining = confirmationDeadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IllegalStateException("publisher confirms timed out");
            }
            CorrelationData.Confirm confirm = correlation.getFuture().get(remaining, TimeUnit.NANOSECONDS);
            if (!confirm.isAck()) {
                nacks++;
            }
            if (correlation.getReturned() != null) {
                returns++;
            }
        }
        return new PublishResult(startedAt, offerSeconds, elapsedSeconds(startedAt), nacks, returns);
    }

    private static ConvergenceResult awaitConvergence(
            ProbeConfig config,
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            String marketId,
            List<OrderConfirmedEvent> orders,
            long startedAt) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.timeoutSeconds());
        ProbeState state = state(jdbc, redis, marketId, config.totalOrders(), false, orders);
        double matchPersistenceSeconds = 0;
        while (System.nanoTime() < deadline) {
            state = state(jdbc, redis, marketId, config.totalOrders(), false, orders);
            if (matchPersistenceSeconds == 0
                    && state.matchPersistenceComplete(config.pairs(), config.totalOrders())) {
                matchPersistenceSeconds = elapsedSeconds(startedAt);
            }
            if (state.cleanupComplete(config.pairs(), config.totalOrders())) {
                double fullCleanupSeconds = elapsedSeconds(startedAt);
                ProbeState finalState = state(jdbc, redis, marketId, config.totalOrders(), true, orders);
                return new ConvergenceResult(matchPersistenceSeconds, fullCleanupSeconds, finalState);
            }
            Thread.sleep(config.pollIntervalMs());
        }
        ProbeState finalState = state(jdbc, redis, marketId, config.totalOrders(), true, orders);
        return new ConvergenceResult(matchPersistenceSeconds, elapsedSeconds(startedAt), finalState);
    }

    private static ProbeState state(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            String marketId,
            int totalOrders,
            boolean includeReservationScan,
            List<OrderConfirmedEvent> orders) {
        Map<String, Object> trade = jdbc.queryForMap("""
                SELECT count(*) AS rows,
                       count(DISTINCT trade_id) AS distinct_ids,
                       coalesce(sum(quantity), 0) AS quantity
                FROM match_engine.trade_executions
                WHERE market_id = ?
                """, marketId);
        Map<String, Object> cleanup = jdbc.queryForMap("""
                SELECT count(*) AS rows,
                       count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                       count(*) FILTER (WHERE status != 'COMPLETED') AS non_completed
                FROM match_engine.reservation_cleanup_tasks
                WHERE trade_id LIKE ?
                """, marketId + "-%");
        long outboxRows = count(jdbc,
                "SELECT count(*) FROM match_engine.trade_outbox WHERE aggregate_id LIKE ?",
                marketId + "-%");
        return new ProbeState(
                number(trade, "rows"),
                number(trade, "distinct_ids"),
                number(trade, "quantity"),
                outboxRows,
                number(cleanup, "rows"),
                number(cleanup, "completed"),
                number(cleanup, "non_completed"),
                completedMarkerCount(redis, marketId, totalOrders),
                zsetSize(redis, "orderbook:" + marketId + ":buy"),
                zsetSize(redis, "orderbook:" + marketId + ":sell"),
                includeReservationScan ? workloadReservationCount(redis, orders) : -1);
    }

    private static Map<String, Object> resultMap(
            ProbeConfig config,
            String marketId,
            PublishResult publish,
            ConvergenceResult convergence,
            QueueMonitor monitor,
            QueueStats finalQueue,
            QueueStats finalDlq,
            MeterRegistry registry) {
        ProbeState state = convergence.state();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "rabbitToMatchIntakeIsolated");
        result.put("evidenceClass", "isolated-diagnostic");
        result.put("marketId", marketId);
        result.put("arrivalPattern", "paced-shuffled-mixed");
        result.put("seed", config.seed());
        result.put("pairs", config.pairs());
        result.put("totalOrders", config.totalOrders());
        result.put("targetOrderTps", config.targetOrderTps());
        result.put("publisherOfferSeconds", round(publish.offerSeconds()));
        result.put("publisherConfirmedSeconds", round(publish.confirmedSeconds()));
        result.put("offeredOrdersPerSecond", round(config.totalOrders() / publish.offerSeconds()));
        result.put("confirmedOrdersPerSecond", round(config.totalOrders() / publish.confirmedSeconds()));
        result.put("rabbitToMatchPersistenceSeconds", round(convergence.matchPersistenceSeconds()));
        result.put("persistedOrdersPerSecond", rate(config.totalOrders(), convergence.matchPersistenceSeconds()));
        result.put("persistedTradesPerSecond", rate(state.tradeRows(), convergence.matchPersistenceSeconds()));
        result.put("fullCleanupConvergenceSeconds", round(convergence.fullCleanupSeconds()));
        result.put("cleanupConvergedOrdersPerSecond", rate(config.totalOrders(), convergence.fullCleanupSeconds()));
        result.put("cleanupConvergedTradesPerSecond", rate(state.tradeRows(), convergence.fullCleanupSeconds()));
        result.put("publisherNacks", publish.nacks());
        result.put("publisherReturns", publish.returns());
        result.put("queueSamples", monitor.samples());
        result.put("queueSampleFailures", monitor.failures());
        result.put("queueStatisticsSource", "RabbitMQ management API sampled at 100ms; broker statistics may refresh more slowly");
        result.put("queuePeakMayBeUndercounted", true);
        result.put("queueMaxReady", monitor.maxReady());
        result.put("queueMaxUnacked", monitor.maxUnacked());
        result.put("queueFinalReady", finalQueue.ready());
        result.put("queueFinalUnacked", finalQueue.unacked());
        result.put("queueConsumers", finalQueue.consumers());
        result.put("dlqFinalReady", finalDlq.ready());
        result.put("dlqFinalUnacked", finalDlq.unacked());
        result.put("tradeRows", state.tradeRows());
        result.put("distinctTradeIds", state.distinctTradeIds());
        result.put("matchedQuantity", state.matchedQuantity());
        result.put("outboxRows", state.outboxRows());
        result.put("cleanupTaskRows", state.cleanupRows());
        result.put("completedCleanupTasks", state.completedCleanup());
        result.put("nonCompletedCleanupTasks", state.nonCompletedCleanup());
        result.put("completedIncomingMarkers", state.completedMarkers());
        result.put("remainingBuyOrders", state.remainingBuyOrders());
        result.put("remainingSellOrders", state.remainingSellOrders());
        result.put("remainingWorkloadReservations", state.remainingReservations());
        result.put("listenerCount", timerCount(registry, "match_engine_order_confirmed_listener_duration"));
        result.put("listenerMeanMs", round(timerMeanMillis(registry, "match_engine_order_confirmed_listener_duration")));
        result.put("listenerMaxMs", round(timerMaxMillis(registry, "match_engine_order_confirmed_listener_duration")));
        result.put("tradeTransactionMeanMs", round(timerMeanMillis(
                registry, "match_engine_trade_record_phase_duration", "phase", "transaction_total")));
        result.put("tradeTransactionMaxMs", round(timerMaxMillis(
                registry, "match_engine_trade_record_phase_duration", "phase", "transaction_total")));
        result.put("correctness", correctness(config, publish, convergence, finalQueue, finalDlq) ? "PASS" : "FAIL");
        result.put("capacityClaimAllowed", false);
        return result;
    }

    private static boolean correctness(
            ProbeConfig config,
            PublishResult publish,
            ConvergenceResult convergence,
            QueueStats finalQueue,
            QueueStats finalDlq) {
        ProbeState state = convergence.state();
        return publish.nacks() == 0
                && publish.returns() == 0
                && state.complete(config.pairs(), config.totalOrders())
                && finalQueue.ready() == 0
                && finalQueue.unacked() == 0
                && finalDlq.ready() == 0
                && finalDlq.unacked() == 0;
    }

    private static void waitForConsumers(QueueMonitor monitor, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            QueueStats stats = monitor.read(MATCH_ENGINE_ORDER_CONFIRMED_QUEUE);
            if (stats.consumers() > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("MatchEngine Rabbit listener did not start");
    }

    private static void waitForQueueDrain(QueueMonitor monitor, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            QueueStats stats = monitor.read(MATCH_ENGINE_ORDER_CONFIRMED_QUEUE);
            if (stats.ready() == 0 && stats.unacked() == 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Rabbit queue did not report drained before timeout");
    }

    private static void requireCleanInputState(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            String marketId) {
        long claimable = count(jdbc, """
                SELECT count(*)
                FROM match_engine.reservation_cleanup_tasks
                WHERE status IN ('PENDING', 'PROCESSING')
                """);
        require(claimable == 0, "dedicated MatchEngine database contains claimable cleanup tasks");
        require(!Boolean.TRUE.equals(redis.hasKey("orderbook:" + marketId + ":buy")),
                "probe BUY orderbook already exists");
        require(!Boolean.TRUE.equals(redis.hasKey("orderbook:" + marketId + ":sell")),
                "probe SELL orderbook already exists");
    }

    private static List<OrderConfirmedEvent> orders(ProbeConfig config, String marketId) {
        List<OrderConfirmedEvent> orders = new ArrayList<>(config.totalOrders());
        for (int index = 0; index < config.pairs(); index++) {
            long sellSequence = index * 2L + 1;
            orders.add(order(marketId, "SELL", sellSequence));
            orders.add(order(marketId, "BUY", sellSequence + 1));
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

    private static long completedMarkerCount(
            StringRedisTemplate redis,
            String marketId,
            int totalOrders) {
        long lastShard = (totalOrders - 1L) / 10_000_000L;
        long count = 0;
        for (long shard = 0; shard <= lastShard; shard++) {
            long currentShard = shard;
            Long shardCount = redis.execute((RedisCallback<Long>) connection ->
                    connection.stringCommands().bitCount(bytes(
                            "match:incoming-order:completed:" + marketId + ":" + currentShard)));
            count += shardCount == null ? 0 : shardCount;
        }
        return count;
    }

    private static long workloadReservationCount(
            StringRedisTemplate redis,
            List<OrderConfirmedEvent> orders) {
        return redis.execute((RedisCallback<Long>) connection -> {
            long count = 0;
            for (OrderConfirmedEvent order : orders) {
                if (connection.keyCommands().exists(bytes("order:reservation:" + order.getOrderId()))) {
                    count++;
                }
            }
            return count;
        });
    }

    private static long zsetSize(StringRedisTemplate redis, String key) {
        Long size = redis.opsForZSet().size(key);
        return size == null ? 0 : size;
    }

    private static long count(JdbcTemplate jdbc, String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static void cleanupDatabaseQuietly(JdbcTemplate jdbc, String marketId) {
        try {
            jdbc.update("DELETE FROM match_engine.reservation_cleanup_tasks WHERE trade_id LIKE ?", marketId + "-%");
            jdbc.update("DELETE FROM match_engine.trade_outbox WHERE aggregate_id LIKE ?", marketId + "-%");
            jdbc.update("DELETE FROM match_engine.trade_executions WHERE market_id = ?", marketId);
        } catch (RuntimeException ignored) {
            // Preserve the original probe failure.
        }
    }

    private static void cleanupRedis(
            StringRedisTemplate redis,
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
        keys.add("match:incoming-order:completed:" + marketId + ":0");
        redis.delete(keys);
    }

    private static void writeResult(
            ProbeConfig config,
            ObjectMapper objectMapper,
            Map<String, Object> result) throws Exception {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        System.out.println(json);
        if (config.output() == null) {
            return;
        }
        File output = new File(config.output());
        File parent = output.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output, result);
    }

    private static long timerCount(MeterRegistry registry, String name, String... tags) {
        Timer timer = registry.find(name).tags(tags).timer();
        return timer == null ? 0 : timer.count();
    }

    private static double timerMeanMillis(MeterRegistry registry, String name, String... tags) {
        Timer timer = registry.find(name).tags(tags).timer();
        return timer == null ? 0 : timer.mean(TimeUnit.MILLISECONDS);
    }

    private static double timerMaxMillis(MeterRegistry registry, String name, String... tags) {
        Timer timer = registry.find(name).tags(tags).timer();
        return timer == null ? 0 : timer.max(TimeUnit.MILLISECONDS);
    }

    private static double elapsedSeconds(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000_000.0;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double rate(long count, double seconds) {
        return seconds <= 0 ? 0 : round(count / seconds);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record PublishResult(
            long startedAtNanos,
            double offerSeconds,
            double confirmedSeconds,
            int nacks,
            int returns) {
    }

    private record ConvergenceResult(
            double matchPersistenceSeconds,
            double fullCleanupSeconds,
            ProbeState state) {
    }

    private record ProbeState(
            long tradeRows,
            long distinctTradeIds,
            long matchedQuantity,
            long outboxRows,
            long cleanupRows,
            long completedCleanup,
            long nonCompletedCleanup,
            long completedMarkers,
            long remainingBuyOrders,
            long remainingSellOrders,
            long remainingReservations) {

        boolean complete(int pairs, int totalOrders) {
            return cleanupComplete(pairs, totalOrders)
                    && remainingReservations == 0;
        }

        boolean cleanupComplete(int pairs, int totalOrders) {
            return matchPersistenceComplete(pairs, totalOrders)
                    && completedCleanup == pairs
                    && nonCompletedCleanup == 0;
        }

        boolean matchPersistenceComplete(int pairs, int totalOrders) {
            return tradeRows == pairs
                    && distinctTradeIds == pairs
                    && matchedQuantity == pairs
                    && outboxRows == pairs
                    && cleanupRows == pairs
                    && completedMarkers == totalOrders
                    && remainingBuyOrders == 0
                    && remainingSellOrders == 0;
        }
    }

    private record QueueStats(long ready, long unacked, long consumers) {
    }

    private static final class QueueMonitor implements AutoCloseable {
        private final ProbeConfig config;
        private final ObjectMapper objectMapper;
        private final HttpClient client;
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicLong maxReady = new AtomicLong();
        private final AtomicLong maxUnacked = new AtomicLong();
        private final AtomicLong samples = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private Thread thread;

        private QueueMonitor(ProbeConfig config, ObjectMapper objectMapper) {
            this.config = config;
            this.objectMapper = objectMapper;
            this.client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .authenticator(new Authenticator() {
                        @Override
                        protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(
                                    config.rabbitUsername(), config.rabbitPassword().toCharArray());
                        }
                    })
                    .build();
        }

        private void start() {
            running.set(true);
            thread = new Thread(this::sampleLoop, "rabbit-match-queue-monitor");
            thread.setDaemon(true);
            thread.start();
        }

        private void sampleLoop() {
            while (running.get()) {
                try {
                    QueueStats stats = read(MATCH_ENGINE_ORDER_CONFIRMED_QUEUE);
                    maxReady.accumulateAndGet(stats.ready(), Math::max);
                    maxUnacked.accumulateAndGet(stats.unacked(), Math::max);
                    samples.incrementAndGet();
                } catch (Exception failure) {
                    failures.incrementAndGet();
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(config.queuePollIntervalMs()));
            }
        }

        private QueueStats read(String queue) throws Exception {
            String encodedQueue = java.net.URLEncoder.encode(queue, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.rabbitManagementUrl() + "/api/queues/%2F/" + encodedQueue))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Rabbit management returned " + response.statusCode()
                        + " for queue " + queue);
            }
            JsonNode json = objectMapper.readTree(response.body());
            return new QueueStats(
                    json.path("messages_ready").asLong(),
                    json.path("messages_unacknowledged").asLong(),
                    json.path("consumers").asLong());
        }

        private long maxReady() {
            return maxReady.get();
        }

        private long maxUnacked() {
            return maxUnacked.get();
        }

        private long samples() {
            return samples.get();
        }

        private long failures() {
            return failures.get();
        }

        @Override
        public void close() throws InterruptedException {
            running.set(false);
            if (thread != null) {
                thread.join(2_000);
            }
        }
    }

    private record ProbeConfig(
            int pairs,
            int targetOrderTps,
            long seed,
            int timeoutSeconds,
            int confirmTimeoutSeconds,
            int pollIntervalMs,
            int queuePollIntervalMs,
            String rabbitManagementUrl,
            String rabbitUsername,
            String rabbitPassword,
            String output) {

        int totalOrders() {
            return Math.multiplyExact(pairs, 2);
        }

        private static ProbeConfig from(String[] args) {
            return new ProbeConfig(
                    positiveInt(args, "--pairs", 10_000),
                    positiveInt(args, "--target-order-tps", 2_000),
                    longArg(args, "--seed", 20260814L),
                    positiveInt(args, "--timeout-seconds", 120),
                    positiveInt(args, "--confirm-timeout-seconds", 60),
                    positiveInt(args, "--poll-interval-ms", 200),
                    positiveInt(args, "--queue-poll-interval-ms", 100),
                    stringArg(args, "--rabbit-management-url", "http://localhost:15672"),
                    stringArg(args, "--rabbit-username", "admin"),
                    stringArg(args, "--rabbit-password", "admin123"),
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
