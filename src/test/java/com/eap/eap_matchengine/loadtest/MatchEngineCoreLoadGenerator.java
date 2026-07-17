package com.eap.eap_matchengine.loadtest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.eap.common.event.OrderConfirmedEvent;
import com.eap.common.event.OrderMatchedEvent;
import com.eap.eap_matchengine.application.MatchingEngineMetrics;
import com.eap.eap_matchengine.application.MatchingEngineService;
import com.eap.eap_matchengine.application.NoopTradeExecutionRecorder;
import com.eap.eap_matchengine.application.RedisOrderBookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.Mockito;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

public class MatchEngineCoreLoadGenerator {

    private static final String DEFAULT_REDIS_HOST = "localhost";
    private static final int DEFAULT_REDIS_PORT = 6379;
    private static final String DEFAULT_MARKET_PREFIX = "MATCH_CORE_LOAD";
    private static final Set<String> GENERATED_REDIS_KEYS = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) throws Exception {
        quietApplicationLogs();
        LoadConfig config = LoadConfig.from(args);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(config.redisHost(), config.redisPort());
        connectionFactory.afterPropertiesSet();

        RedisTemplate<String, String> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new StringRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(new StringRedisSerializer());
        redisTemplate.afterPropertiesSet();

        Config redissonConfig = new Config();
        redissonConfig.useSingleServer()
                .setAddress("redis://" + config.redisHost() + ":" + config.redisPort());
        RedissonClient redissonClient = Redisson.create(redissonConfig);

        List<OrderMatchedEvent> publishedEvents = Collections.synchronizedList(new ArrayList<>());
        RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            Object payload = invocation.getArgument(2);
            if (payload instanceof OrderMatchedEvent matchedEvent) {
                publishedEvents.add(matchedEvent);
            }
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        RedisOrderBookService orderBookService = new RedisOrderBookService(redisTemplate, objectMapper);
        orderBookService.init();
        MatchingEngineService matchingEngineService = new MatchingEngineService(
                orderBookService,
                rabbitTemplate,
                redisTemplate,
                redissonClient,
                new NoopTradeExecutionRecorder(),
                new MatchingEngineMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(matchingEngineService, "legacyOrderMatchedPublishEnabled", true);

        try {
            runCorrectnessChecks(matchingEngineService, orderBookService, redisTemplate, publishedEvents);
            runBenchmark(config, matchingEngineService, redisTemplate, publishedEvents);
        } finally {
            cleanupGeneratedKeys(redisTemplate);
            redissonClient.shutdown();
            connectionFactory.destroy();
        }
    }

    private static void quietApplicationLogs() {
        setLogLevel("com.eap.eap_matchengine.application.MatchingEngineService", Level.ERROR);
        setLogLevel("com.eap.eap_matchengine.application.RedisOrderBookService", Level.ERROR);
    }

    private static void setLogLevel(String loggerName, Level level) {
        org.slf4j.Logger logger = LoggerFactory.getLogger(loggerName);
        if (logger instanceof Logger logbackLogger) {
            logbackLogger.setLevel(level);
        }
    }

    private static void runCorrectnessChecks(
            MatchingEngineService service,
            RedisOrderBookService orderBookService,
            RedisTemplate<String, String> redisTemplate,
            List<OrderMatchedEvent> publishedEvents) {
        verifyPricePriority(service, redisTemplate, publishedEvents);
        verifyTimePriority(service, redisTemplate, publishedEvents);
        verifyPartialFill(service, orderBookService, redisTemplate, publishedEvents);
        verifyNoCrossingOrdersRemainOpen(service, orderBookService, redisTemplate, publishedEvents);
        System.out.println("correctness: PASS");
    }

    private static void verifyPricePriority(
            MatchingEngineService service,
            RedisTemplate<String, String> redisTemplate,
            List<OrderMatchedEvent> publishedEvents) {
        String marketId = uniqueMarket("PRICE");
        cleanMarket(redisTemplate, marketId);
        publishedEvents.clear();

        OrderConfirmedEvent firstSell = order(marketId, "SELL", 100, 1, 1);
        OrderConfirmedEvent secondSell = order(marketId, "SELL", 99, 1, 2);
        OrderConfirmedEvent buy = order(marketId, "BUY", 100, 2, 3);
        service.tryMatch(firstSell);
        service.tryMatch(secondSell);
        service.tryMatch(buy);

        require(publishedEvents.size() == 2, "price priority should produce 2 matches");
        require(publishedEvents.get(0).getDealPrice() == 99, "lowest sell price should match first");
        require(publishedEvents.get(1).getDealPrice() == 100, "higher sell price should match second");
        require(totalMatchedAmount(publishedEvents) == 2, "price priority matched amount should conserve quantity");
        require(userOrderSetSize(redisTemplate, firstSell) == 0, "fully matched first sell should leave no user order reference");
        require(userOrderSetSize(redisTemplate, secondSell) == 0, "fully matched second sell should leave no user order reference");
        require(userOrderSetSize(redisTemplate, buy) == 0, "fully matched incoming buy should leave no user order reference");
    }

    private static void verifyTimePriority(
            MatchingEngineService service,
            RedisTemplate<String, String> redisTemplate,
            List<OrderMatchedEvent> publishedEvents) {
        String marketId = uniqueMarket("TIME");
        cleanMarket(redisTemplate, marketId);
        publishedEvents.clear();

        service.tryMatch(order(marketId, "SELL", 100, 1, 1));
        service.tryMatch(order(marketId, "SELL", 100, 1, 2));
        service.tryMatch(order(marketId, "BUY", 100, 2, 3));

        require(publishedEvents.size() == 2, "time priority should produce 2 matches");
        require(publishedEvents.get(0).getSellerMarketSequence() == 1L, "earlier sell sequence should match first");
        require(publishedEvents.get(1).getSellerMarketSequence() == 2L, "later sell sequence should match second");
    }

    private static void verifyPartialFill(
            MatchingEngineService service,
            RedisOrderBookService orderBookService,
            RedisTemplate<String, String> redisTemplate,
            List<OrderMatchedEvent> publishedEvents) {
        String marketId = uniqueMarket("PARTIAL");
        cleanMarket(redisTemplate, marketId);
        publishedEvents.clear();

        OrderConfirmedEvent sell = order(marketId, "SELL", 100, 5, 1);
        service.tryMatch(sell);
        service.tryMatch(order(marketId, "BUY", 100, 2, 2));

        require(publishedEvents.size() == 1, "partial fill should produce 1 match");
        require(publishedEvents.get(0).getAmount() == 2, "partial fill should match incoming buy amount");

        List<OrderConfirmedEvent> sellerOrders = orderBookService.getOrderByUserId(sell.getUserId());
        require(sellerOrders.size() == 1, "partial resting order should remain in seller open orders");
        require(sellerOrders.get(0).getAmount() == 3, "partial resting order should keep remaining amount");
        require(userOrderSetSize(redisTemplate, sell) == 1, "partial resting order should keep one user order reference");
    }

    private static void verifyNoCrossingOrdersRemainOpen(
            MatchingEngineService service,
            RedisOrderBookService orderBookService,
            RedisTemplate<String, String> redisTemplate,
            List<OrderMatchedEvent> publishedEvents) {
        String marketId = uniqueMarket("NO_CROSS");
        cleanMarket(redisTemplate, marketId);
        publishedEvents.clear();

        OrderConfirmedEvent sell = order(marketId, "SELL", 110, 1, 1);
        OrderConfirmedEvent buy = order(marketId, "BUY", 100, 1, 2);
        service.tryMatch(sell);
        service.tryMatch(buy);

        require(publishedEvents.isEmpty(), "non-crossing orders should not match");
        require(orderBookService.getOrderByUserId(sell.getUserId()).size() == 1, "non-crossing sell should remain open");
        require(orderBookService.getOrderByUserId(buy.getUserId()).size() == 1, "non-crossing buy should remain open");
    }

    private static void runBenchmark(
            LoadConfig config,
            MatchingEngineService service,
            RedisTemplate<String, String> redisTemplate,
            List<OrderMatchedEvent> publishedEvents) throws InterruptedException {
        String marketId = uniqueMarket("BENCH");
        cleanMarket(redisTemplate, marketId);
        publishedEvents.clear();

        int restingSellOrders = config.events();
        System.out.printf(
                "preloading %d resting SELL orders into Redis order book, marketId=%s%n",
                restingSellOrders,
                marketId);
        for (int i = 0; i < restingSellOrders; i++) {
            service.tryMatch(order(marketId, "SELL", 100, 1, i + 1L));
        }
        publishedEvents.clear();

        ExecutorService executor = Executors.newFixedThreadPool(config.workers());
        CountDownLatch done = new CountDownLatch(config.events());
        Semaphore inFlight = new Semaphore(config.workers() * 2);
        AtomicInteger failures = new AtomicInteger();
        List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>(config.events()));

        long started = System.nanoTime();
        for (int i = 0; i < config.events(); i++) {
            int index = i;
            inFlight.acquire();
            executor.execute(() -> {
                try {
                    long requestStarted = System.nanoTime();
                    service.tryMatch(order(marketId, "BUY", 100, 1, restingSellOrders + index + 1L));
                    latenciesNanos.add(System.nanoTime() - requestStarted);
                } catch (Exception e) {
                    failures.incrementAndGet();
                    if (failures.get() <= 10) {
                        System.err.printf("match failed: %s%n", e.getMessage());
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
        List<Long> sortedLatencies = new ArrayList<>(latenciesNanos);
        Collections.sort(sortedLatencies);

        int matchedEvents = publishedEvents.size();
        int matchedAmount = totalMatchedAmount(publishedEvents);
        long remainingSellOrders = zsetSize(redisTemplate, "orderbook:" + marketId + ":sell");
        long remainingBuyOrders = zsetSize(redisTemplate, "orderbook:" + marketId + ":buy");

        System.out.println("{");
        System.out.printf("  \"mode\": \"matchEngineCore\",%n");
        System.out.printf("  \"events\": %d,%n", config.events());
        System.out.printf("  \"workers\": %d,%n", config.workers());
        System.out.printf("  \"matchedEvents\": %d,%n", matchedEvents);
        System.out.printf("  \"matchedAmount\": %d,%n", matchedAmount);
        System.out.printf("  \"failures\": %d,%n", failures.get());
        System.out.printf("  \"remainingSellOrders\": %d,%n", remainingSellOrders);
        System.out.printf("  \"remainingBuyOrders\": %d,%n", remainingBuyOrders);
        System.out.printf("  \"elapsedSeconds\": %.2f,%n", elapsedSeconds);
        System.out.printf("  \"actualTps\": %.2f,%n", matchedEvents / Math.max(elapsedSeconds, 0.001));
        System.out.printf("  \"p50Ms\": %.2f,%n", percentileMillis(sortedLatencies, 0.50));
        System.out.printf("  \"p95Ms\": %.2f,%n", percentileMillis(sortedLatencies, 0.95));
        System.out.printf("  \"p99Ms\": %.2f%n", percentileMillis(sortedLatencies, 0.99));
        System.out.println("}");

        require(failures.get() == 0, "benchmark should have no failures");
        require(matchedEvents == config.events(), "every incoming BUY should match one resting SELL");
        require(matchedAmount == config.events(), "matched amount should equal incoming amount");
        require(remainingSellOrders == 0, "all resting SELL orders should be consumed");
        require(remainingBuyOrders == 0, "all incoming BUY orders should be fully matched");
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

    private static String uniqueMarket(String suffix) {
        return DEFAULT_MARKET_PREFIX + "_" + suffix + "_" + UUID.randomUUID();
    }

    private static void cleanMarket(RedisTemplate<String, String> redisTemplate, String marketId) {
        redisTemplate.delete("orderbook:" + marketId + ":buy");
        redisTemplate.delete("orderbook:" + marketId + ":sell");
    }

    private static void cleanupGeneratedKeys(RedisTemplate<String, String> redisTemplate) {
        if (!GENERATED_REDIS_KEYS.isEmpty()) {
            redisTemplate.delete(GENERATED_REDIS_KEYS);
            GENERATED_REDIS_KEYS.clear();
        }
    }

    private static long zsetSize(RedisTemplate<String, String> redisTemplate, String key) {
        Long size = redisTemplate.opsForZSet().size(key);
        return size == null ? 0L : size;
    }

    private static long userOrderSetSize(RedisTemplate<String, String> redisTemplate, OrderConfirmedEvent order) {
        Long size = redisTemplate.opsForSet().size("user:" + order.getUserId() + ":orders");
        return size == null ? 0L : size;
    }

    private static int totalMatchedAmount(List<OrderMatchedEvent> events) {
        return events.stream()
                .map(OrderMatchedEvent::getAmount)
                .filter(amount -> amount != null)
                .mapToInt(Integer::intValue)
                .sum();
    }

    private static double percentileMillis(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1))) / 1_000_000.0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record LoadConfig(
            String redisHost,
            int redisPort,
            int events,
            int workers) {

        private static LoadConfig from(String[] args) {
            return new LoadConfig(
                    stringArg(args, "--redis-host", DEFAULT_REDIS_HOST),
                    intArg(args, "--redis-port", DEFAULT_REDIS_PORT),
                    intArg(args, "--events", 50_000),
                    intArg(args, "--workers", 64));
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
    }
}
