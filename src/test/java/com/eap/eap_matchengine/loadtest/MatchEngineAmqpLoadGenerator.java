package com.eap.eap_matchengine.loadtest;

import com.eap.common.event.OrderConfirmedEvent;
import com.eap.common.event.OrderMatchedEvent;
import com.eap.eap_matchengine.application.RedisOrderBookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.core.Message;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import static com.eap.common.constants.RabbitMQConstants.MATCH_ENGINE_ORDER_CONFIRMED_QUEUE;
import static com.eap.common.constants.RabbitMQConstants.ORDER_ORDER_CONFIRMED_QUEUE;
import static com.eap.common.constants.RabbitMQConstants.ORDER_ORDER_MATCHED_QUEUE;
import static com.eap.common.constants.RabbitMQConstants.ORDER_CONFIRMED_KEY;
import static com.eap.common.constants.RabbitMQConstants.ORDER_EXCHANGE;
import static com.eap.common.constants.RabbitMQConstants.ORDER_MATCHED_KEY;
import static com.eap.common.constants.RabbitMQConstants.WALLET_ORDER_MATCHED_QUEUE;

public class MatchEngineAmqpLoadGenerator {

    private static final String DEFAULT_REDIS_HOST = "localhost";
    private static final int DEFAULT_REDIS_PORT = 6379;
    private static final String DEFAULT_RABBIT_HOST = "localhost";
    private static final int DEFAULT_RABBIT_PORT = 5672;
    private static final String DEFAULT_RABBIT_USER = "admin";
    private static final String DEFAULT_RABBIT_PASSWORD = "admin123";
    private static final String DEFAULT_MARKET_PREFIX = "MATCH_AMQP_LOAD";
    private static final Set<String> GENERATED_REDIS_KEYS = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) throws Exception {
        LoadConfig config = LoadConfig.from(args);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        LettuceConnectionFactory redisConnectionFactory = redisConnectionFactory(config);
        RedisTemplate<String, String> redisTemplate = redisTemplate(redisConnectionFactory);
        RedisOrderBookService orderBookService = new RedisOrderBookService(redisTemplate, objectMapper);
        orderBookService.init();

        CachingConnectionFactory rabbitConnectionFactory =
                new CachingConnectionFactory(config.rabbitHost(), config.rabbitPort());
        rabbitConnectionFactory.setUsername(config.rabbitUser());
        rabbitConnectionFactory.setPassword(config.rabbitPassword());

        RabbitTemplate rabbitTemplate = new RabbitTemplate(rabbitConnectionFactory);
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter(objectMapper));
        rabbitTemplate.setMandatory(true);
        RabbitAdmin rabbitAdmin = new RabbitAdmin(rabbitConnectionFactory);

        String marketId = DEFAULT_MARKET_PREFIX + "_" + UUID.randomUUID();
        String captureQueueName = "matchEngine.amqpLoad.capture." + UUID.randomUUID();

        try {
            declareCaptureQueue(rabbitAdmin, captureQueueName);
            prepareRabbitQueues(rabbitAdmin, captureQueueName);
            preloadRestingOrders(config, orderBookService, marketId);

            List<OrderConfirmedEvent> incomingBuys = buildIncomingBuys(config.events(), marketId);
            long endToEndStarted = System.nanoTime();
            PublishResult publishResult = publishConfirmedOrders(config, rabbitTemplate, incomingBuys);
            CaptureResult captureResult = captureMatchedEvents(config, rabbitTemplate, objectMapper, captureQueueName);
            double endToEndElapsedSeconds = (System.nanoTime() - endToEndStarted) / 1_000_000_000.0;

            long remainingSellOrders = zsetSize(redisTemplate, "orderbook:" + marketId + ":sell");
            long remainingBuyOrders = zsetSize(redisTemplate, "orderbook:" + marketId + ":buy");
            printResult(config, publishResult, captureResult, endToEndElapsedSeconds, remainingSellOrders, remainingBuyOrders);

            require(publishResult.failures() == 0, "publisher should have no failures");
            require(captureResult.matchedEvents() == config.events(), "captured matched events should equal published orders");
            require(captureResult.matchedAmount() == config.events(), "matched amount should equal published orders");
            require(remainingSellOrders == 0, "all preloaded SELL orders should be consumed");
            require(remainingBuyOrders == 0, "all incoming BUY orders should be fully matched");
        } finally {
            rabbitAdmin.deleteQueue(captureQueueName);
            if (config.purgeCollateralQueues()) {
                purgeIfPresent(rabbitAdmin, ORDER_ORDER_CONFIRMED_QUEUE);
                purgeIfPresent(rabbitAdmin, ORDER_ORDER_MATCHED_QUEUE);
                purgeIfPresent(rabbitAdmin, WALLET_ORDER_MATCHED_QUEUE);
            }
            cleanupGeneratedRedisKeys(redisTemplate);
            redisConnectionFactory.destroy();
            rabbitConnectionFactory.destroy();
        }
    }

    private static LettuceConnectionFactory redisConnectionFactory(LoadConfig config) {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(config.redisHost(), config.redisPort());
        connectionFactory.afterPropertiesSet();
        return connectionFactory;
    }

    private static RedisTemplate<String, String> redisTemplate(LettuceConnectionFactory connectionFactory) {
        RedisTemplate<String, String> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new StringRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(new StringRedisSerializer());
        redisTemplate.afterPropertiesSet();
        return redisTemplate;
    }

    private static void declareCaptureQueue(RabbitAdmin rabbitAdmin, String captureQueueName) {
        Queue captureQueue = QueueBuilder.nonDurable(captureQueueName)
                .build();
        rabbitAdmin.declareQueue(captureQueue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(captureQueue)
                .to(new TopicExchange(ORDER_EXCHANGE))
                .with(ORDER_MATCHED_KEY));
    }

    private static void prepareRabbitQueues(RabbitAdmin rabbitAdmin, String captureQueueName) {
        purgeIfPresent(rabbitAdmin, MATCH_ENGINE_ORDER_CONFIRMED_QUEUE);
        purgeIfPresent(rabbitAdmin, captureQueueName);
    }

    private static void purgeIfPresent(RabbitAdmin rabbitAdmin, String queueName) {
        try {
            rabbitAdmin.purgeQueue(queueName, true);
        } catch (Exception ignored) {
            // Queue may not exist in narrow test topologies.
        }
    }

    private static void preloadRestingOrders(
            LoadConfig config,
            RedisOrderBookService orderBookService,
            String marketId) throws Exception {
        System.out.printf("preloading %d resting SELL orders, marketId=%s%n", config.events(), marketId);
        for (int i = 0; i < config.events(); i++) {
            orderBookService.addOrder(order(marketId, "SELL", 100, 1, i + 1L));
        }
    }

    private static List<OrderConfirmedEvent> buildIncomingBuys(int events, String marketId) {
        List<OrderConfirmedEvent> incomingBuys = new ArrayList<>(events);
        for (int i = 0; i < events; i++) {
            incomingBuys.add(order(marketId, "BUY", 100, 1, events + i + 1L));
        }
        return incomingBuys;
    }

    private static PublishResult publishConfirmedOrders(
            LoadConfig config,
            RabbitTemplate rabbitTemplate,
            List<OrderConfirmedEvent> events) throws InterruptedException {
        AtomicInteger published = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(events.size());
        ExecutorService executor = Executors.newFixedThreadPool(config.publishers());
        Semaphore inFlight = new Semaphore(config.publishers() * 2);

        long started = System.nanoTime();
        for (OrderConfirmedEvent event : events) {
            inFlight.acquire();
            executor.execute(() -> {
                try {
                    rabbitTemplate.convertAndSend(ORDER_EXCHANGE, ORDER_CONFIRMED_KEY, event);
                    published.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                    if (failures.get() <= 10) {
                        System.err.printf("publish failed: orderId=%s, error=%s%n",
                                event.getOrderId(), e.getMessage());
                    }
                } finally {
                    inFlight.release();
                    done.countDown();
                }
            });
        }

        done.await();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        double elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
        return new PublishResult(published.get(), failures.get(), elapsedSeconds);
    }

    private static CaptureResult captureMatchedEvents(
            LoadConfig config,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            String captureQueueName) throws Exception {
        AtomicInteger matchedEvents = new AtomicInteger();
        AtomicInteger matchedAmount = new AtomicInteger();
        AtomicInteger receiveFailures = new AtomicInteger();
        List<Long> receiveLatenciesNanos = Collections.synchronizedList(new ArrayList<>(config.events()));
        ExecutorService executor = Executors.newFixedThreadPool(config.captureWorkers());
        CountDownLatch workersDone = new CountDownLatch(config.captureWorkers());
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(config.timeoutSeconds());

        for (int i = 0; i < config.captureWorkers(); i++) {
            executor.execute(() -> {
                try {
                    while (matchedEvents.get() < config.events() && System.nanoTime() < deadline) {
                        long receiveStarted = System.nanoTime();
                        Message message = rabbitTemplate.receive(captureQueueName, 50);
                        if (message == null) {
                            continue;
                        }
                        receiveLatenciesNanos.add(System.nanoTime() - receiveStarted);
                        OrderMatchedEvent event = objectMapper.readValue(
                                new String(message.getBody(), StandardCharsets.UTF_8),
                                OrderMatchedEvent.class);
                        matchedAmount.addAndGet(event.getAmount() == null ? 0 : event.getAmount());
                        matchedEvents.incrementAndGet();
                    }
                } catch (Exception e) {
                    receiveFailures.incrementAndGet();
                    if (receiveFailures.get() <= 10) {
                        System.err.printf("capture failed: %s%n", e.getMessage());
                    }
                } finally {
                    workersDone.countDown();
                }
            });
        }

        workersDone.await();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        double elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
        List<Long> sortedReceiveLatencies = new ArrayList<>(receiveLatenciesNanos);
        Collections.sort(sortedReceiveLatencies);
        return new CaptureResult(
                matchedEvents.get(),
                matchedAmount.get(),
                elapsedSeconds,
                percentileMillis(sortedReceiveLatencies, 0.50),
                percentileMillis(sortedReceiveLatencies, 0.95),
                percentileMillis(sortedReceiveLatencies, 0.99));
    }

    private static OrderConfirmedEvent order(String marketId, String side, int price, int amount, long sequence) {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        GENERATED_REDIS_KEYS.add("order:" + orderId);
        GENERATED_REDIS_KEYS.add("user:" + userId + ":orders");

        return OrderConfirmedEvent.builder()
                .orderId(orderId)
                .userId(userId)
                .marketId(marketId)
                .marketSequence(sequence)
                .price(price)
                .amount(amount)
                .orderType(side)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private static long zsetSize(RedisTemplate<String, String> redisTemplate, String key) {
        Long size = redisTemplate.opsForZSet().size(key);
        return size == null ? 0L : size;
    }

    private static void cleanupGeneratedRedisKeys(RedisTemplate<String, String> redisTemplate) {
        if (!GENERATED_REDIS_KEYS.isEmpty()) {
            redisTemplate.delete(GENERATED_REDIS_KEYS);
            GENERATED_REDIS_KEYS.clear();
        }
    }

    private static double percentileMillis(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))) / 1_000_000.0;
    }

    private static void printResult(
            LoadConfig config,
            PublishResult publishResult,
            CaptureResult captureResult,
            double endToEndElapsedSeconds,
            long remainingSellOrders,
            long remainingBuyOrders) {
        System.out.println("{");
        System.out.printf("  \"mode\": \"matchEngineAmqp\",%n");
        System.out.printf("  \"events\": %d,%n", config.events());
        System.out.printf("  \"publishers\": %d,%n", config.publishers());
        System.out.printf("  \"captureWorkers\": %d,%n", config.captureWorkers());
        System.out.printf("  \"published\": %d,%n", publishResult.published());
        System.out.printf("  \"publishFailures\": %d,%n", publishResult.failures());
        System.out.printf("  \"publishElapsedSeconds\": %.2f,%n", publishResult.elapsedSeconds());
        System.out.printf("  \"publishTps\": %.2f,%n",
                publishResult.published() / Math.max(publishResult.elapsedSeconds(), 0.001));
        System.out.printf("  \"matchedEvents\": %d,%n", captureResult.matchedEvents());
        System.out.printf("  \"matchedAmount\": %d,%n", captureResult.matchedAmount());
        System.out.printf("  \"captureElapsedSeconds\": %.2f,%n", captureResult.elapsedSeconds());
        System.out.printf("  \"matchedTps\": %.2f,%n",
                captureResult.matchedEvents() / Math.max(captureResult.elapsedSeconds(), 0.001));
        System.out.printf("  \"endToEndElapsedSeconds\": %.2f,%n", endToEndElapsedSeconds);
        System.out.printf("  \"endToEndMatchedTps\": %.2f,%n",
                captureResult.matchedEvents() / Math.max(endToEndElapsedSeconds, 0.001));
        System.out.printf("  \"receiveP50Ms\": %.2f,%n", captureResult.p50Ms());
        System.out.printf("  \"receiveP95Ms\": %.2f,%n", captureResult.p95Ms());
        System.out.printf("  \"receiveP99Ms\": %.2f,%n", captureResult.p99Ms());
        System.out.printf("  \"remainingSellOrders\": %d,%n", remainingSellOrders);
        System.out.printf("  \"remainingBuyOrders\": %d%n", remainingBuyOrders);
        System.out.println("}");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record PublishResult(int published, int failures, double elapsedSeconds) {
    }

    private record CaptureResult(
            int matchedEvents,
            int matchedAmount,
            double elapsedSeconds,
            double p50Ms,
            double p95Ms,
            double p99Ms) {
    }

    private record LoadConfig(
            String redisHost,
            int redisPort,
            String rabbitHost,
            int rabbitPort,
            String rabbitUser,
            String rabbitPassword,
            int events,
            int publishers,
            int captureWorkers,
            int timeoutSeconds,
            boolean purgeCollateralQueues) {

        private static LoadConfig from(String[] args) {
            return new LoadConfig(
                    stringArg(args, "--redis-host", DEFAULT_REDIS_HOST),
                    intArg(args, "--redis-port", DEFAULT_REDIS_PORT),
                    stringArg(args, "--rabbit-host", DEFAULT_RABBIT_HOST),
                    intArg(args, "--rabbit-port", DEFAULT_RABBIT_PORT),
                    stringArg(args, "--rabbit-user", DEFAULT_RABBIT_USER),
                    stringArg(args, "--rabbit-pass", DEFAULT_RABBIT_PASSWORD),
                    intArg(args, "--events", 10_000),
                    intArg(args, "--publishers", 32),
                    intArg(args, "--capture-workers", 16),
                    intArg(args, "--timeout-seconds", 120),
                    booleanArg(args, "--purge-collateral-queues", true));
        }

        private static int intArg(String[] args, String name, int defaultValue) {
            return Integer.parseInt(stringArg(args, name, String.valueOf(defaultValue)));
        }

        private static String stringArg(String[] args, String name, String defaultValue) {
            for (int i = 0; i < args.length - 1; i++) {
                if (name.equals(args[i])) {
                    return args[i + 1];
                }
            }
            return defaultValue;
        }

        private static boolean booleanArg(String[] args, String name, boolean defaultValue) {
            return Boolean.parseBoolean(stringArg(args, name, String.valueOf(defaultValue)));
        }
    }
}
