package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderAssetReservationSucceededEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.format.DateTimeFormatter;

/**
 * Redis-based implementation of an order book service for managing buy and sell orders.
 * Uses Redis Sorted Sets (ZSet) to maintain order books with price-based sorting.
 * All operations use Lua scripts to ensure atomicity and ACID compliance.
 */
@Service
@Slf4j
public class RedisOrderBookService {

    private static final String DEFAULT_MARKET_ID = "ENERGY-SPOT";
    private static final long SCORE_FACTOR = 1_000_000_000L;
    private static final String MATCH_ID_KEY = "match:id:sequence";
    private static final String MISSING_ORDER_DETAIL_PREFIX = "__MISSING_ORDER_DETAIL__:";
    private static final String INVALID_ORDER_DETAIL_PREFIX = "__INVALID_ORDER_DETAIL__:";
    private static final String RESERVATION_EXISTS_PREFIX = "__RESERVATION_EXISTS__:";
    private static final String RESERVATION_KEY_PATTERN = "order:reservation:*";
    private static final String RESERVATION_SCAN_STATE_KEY =
            "match:reservation-reconciler:scan-state";
    private static final String RESERVATION_SCAN_BUFFER_KEY =
            "match:reservation-reconciler:scan-buffer";
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> RESERVATION_SCAN_PAGE_SCRIPT =
            new DefaultRedisScript<>("""
                    local expected_generation = ARGV[3]
                    local stored_generation = redis.call('HGET', KEYS[1], 'generation')
                    if stored_generation ~= expected_generation then
                        redis.call('HSET', KEYS[1],
                            'generation', expected_generation,
                            'cursor', '0')
                        redis.call('DEL', KEYS[2])
                    end

                    local limit = math.max(1, tonumber(ARGV[2]))
                    local keys = {}
                    while #keys < limit do
                        local buffered = redis.call('LPOP', KEYS[2])
                        if not buffered then
                            break
                        end
                        table.insert(keys, buffered)
                    end

                    local cursor = redis.call('HGET', KEYS[1], 'cursor') or '0'
                    if #keys < limit then
                        local page = redis.call('SCAN', cursor, 'MATCH', ARGV[1], 'COUNT', limit)
                        cursor = page[1]
                        redis.call('HSET', KEYS[1], 'cursor', cursor)
                        for _, key in ipairs(page[2]) do
                            if #keys < limit then
                                table.insert(keys, key)
                            else
                                redis.call('RPUSH', KEYS[2], key)
                            end
                        end
                    end
                    return {cursor, keys}
                    """, List.class);
    private static final DefaultRedisScript<Long> UNLINK_USER_ORDER_SCRIPT = new DefaultRedisScript<>("""
            local expected_run_id = string.match(ARGV[2], '|([^|]+)$')
            local actual_run_id = string.match(redis.call('INFO', 'server'), 'run_id:([^\\r\\n]+)')
            if ARGV[2] ~= '' and (redis.call('GET', KEYS[2]) ~= ARGV[2] or actual_run_id ~= expected_run_id) then
                return -99
            end
            return redis.call('SREM', KEYS[1], ARGV[1])
            """, Long.class);
    private static final DefaultRedisScript<Long> RECORD_CANCELLATION_INTENT_SCRIPT = new DefaultRedisScript<>("""
            local expected_run_id = string.match(ARGV[3], '|([^|]+)$')
            local actual_run_id = string.match(redis.call('INFO', 'server'), 'run_id:([^\\r\\n]+)')
            if ARGV[3] ~= '' and (redis.call('GET', KEYS[2]) ~= ARGV[3] or actual_run_id ~= expected_run_id) then
                return -99
            end
            local existing = redis.call('GET', KEYS[1])
            if not existing then
                redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
                return 1
            end
            if existing == ARGV[1] then
                return 0
            end
            return -1
            """, Long.class);
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final MatchingEngineMetrics metrics;
    private final OrderBookRuntimeGuard runtimeGuard;
    private final boolean userOpenOrderIndexEnabled;

    // Lua scripts loaded from classpath
    private String addOrderLuaScript;
    private String reserveMatchOrderBuyLuaScript;
    private String reserveMatchOrderSellLuaScript;
    private String reserveOrAddOrderBuyLuaScript;
    private String reserveOrAddOrderSellLuaScript;
    private String releaseReservedOrderLuaScript;
    private String completeReservedOrderLuaScript;
    private String removeOrderLuaScript;
    private String cancelOrderRequestLuaScript;
    private String addOrderLuaSha;
    private String reserveMatchOrderBuyLuaSha;
    private String reserveMatchOrderSellLuaSha;
    private String reserveOrAddOrderBuyLuaSha;
    private String reserveOrAddOrderSellLuaSha;
    private String releaseReservedOrderLuaSha;
    private String completeReservedOrderLuaSha;
    private String removeOrderLuaSha;
    private String cancelOrderRequestLuaSha;

    public RedisOrderBookService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this(redisTemplate, objectMapper, null, null, true);
    }

    @Autowired
    public RedisOrderBookService(
            RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            MatchingEngineMetrics metrics,
            OrderBookRuntimeGuard runtimeGuard,
            @Value("${eap.match-engine.orderbook.user-open-order-index-enabled:true}")
            boolean userOpenOrderIndexEnabled) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.runtimeGuard = runtimeGuard;
        this.userOpenOrderIndexEnabled = userOpenOrderIndexEnabled;
    }

    RedisOrderBookService(
            RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            MatchingEngineMetrics metrics) {
        this(redisTemplate, objectMapper, metrics, null, true);
    }

    RedisOrderBookService(
            RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            MatchingEngineMetrics metrics,
            boolean userOpenOrderIndexEnabled) {
        this(redisTemplate, objectMapper, metrics, null, userOpenOrderIndexEnabled);
    }

    /**
     * Load Lua scripts from classpath during initialization
     */
    @PostConstruct
    public void init() {
        try {
            addOrderLuaScript = loadLuaScript("lua/add_order.lua");
            reserveMatchOrderBuyLuaScript = loadLuaScript("lua/reserve_match_order_buy.lua");
            reserveMatchOrderSellLuaScript = loadLuaScript("lua/reserve_match_order_sell.lua");
            reserveOrAddOrderBuyLuaScript = loadLuaScript("lua/reserve_or_add_order_buy.lua");
            reserveOrAddOrderSellLuaScript = loadLuaScript("lua/reserve_or_add_order_sell.lua");
            releaseReservedOrderLuaScript = loadLuaScript("lua/release_reserved_order.lua");
            completeReservedOrderLuaScript = loadLuaScript("lua/complete_reserved_order.lua");
            removeOrderLuaScript = loadLuaScript("lua/remove_order.lua");
            cancelOrderRequestLuaScript = loadLuaScript("lua/cancel_order_request.lua");
            addOrderLuaSha = loadLuaScriptSha(addOrderLuaScript);
            reserveMatchOrderBuyLuaSha = loadLuaScriptSha(reserveMatchOrderBuyLuaScript);
            reserveMatchOrderSellLuaSha = loadLuaScriptSha(reserveMatchOrderSellLuaScript);
            reserveOrAddOrderBuyLuaSha = loadLuaScriptSha(reserveOrAddOrderBuyLuaScript);
            reserveOrAddOrderSellLuaSha = loadLuaScriptSha(reserveOrAddOrderSellLuaScript);
            releaseReservedOrderLuaSha = loadLuaScriptSha(releaseReservedOrderLuaScript);
            completeReservedOrderLuaSha = loadLuaScriptSha(completeReservedOrderLuaScript);
            removeOrderLuaSha = loadLuaScriptSha(removeOrderLuaScript);
            cancelOrderRequestLuaSha = loadLuaScriptSha(cancelOrderRequestLuaScript);
            log.info("Successfully loaded all Lua scripts for atomic Redis operations");
        } catch (IOException e) {
            log.error("Failed to load Lua scripts", e);
            throw new RuntimeException("Failed to initialize RedisOrderBookService", e);
        }
    }

    private String loadLuaScript(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }

    private String loadLuaScriptSha(String script) {
        return redisTemplate.execute((RedisCallback<String>) connection ->
                connection.scriptLoad(script.getBytes(StandardCharsets.UTF_8)));
    }

    private Object evalLoadedScript(
            RedisConnection connection,
            String scriptSha,
            String script,
            ReturnType returnType,
            int numKeys,
            byte[]... keysAndArgs) {
        try {
            return connection.evalSha(scriptSha, returnType, numKeys, keysAndArgs);
        } catch (RuntimeException e) {
            if (!isNoScript(e)) {
                throw e;
            }
            return connection.eval(script.getBytes(StandardCharsets.UTF_8), returnType, numKeys, keysAndArgs);
        }
    }

    private boolean isNoScript(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("NOSCRIPT")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private OrderBookRuntimeGuard.Snapshot runtimeSnapshot() {
        return runtimeGuard == null ? null : runtimeGuard.requireReady();
    }

    private String expectedSentinel(OrderBookRuntimeGuard.Snapshot runtime) {
        return runtime == null ? "" : runtime.expectedSentinel();
    }

    private void rejectGenerationMismatch(String operation) {
        if (runtimeGuard != null) {
            runtimeGuard.refresh();
        }
        throw new OrderBookRuntimeUnavailableException(
                "CDA order-book generation rejected Redis operation: " + operation);
    }

    private String serializeRedisOrder(OrderAssetReservationSucceededEvent event) throws JsonProcessingException {
        return objectMapper.writeValueAsString(RedisOrderEntry.from(event));
    }

    private OrderAssetReservationSucceededEvent deserializeRedisOrder(String value) throws JsonProcessingException {
        return deserializeRedisOrder(objectMapper.readTree(value));
    }

    private OrderAssetReservationSucceededEvent deserializeRedisOrder(JsonNode root) throws JsonProcessingException {
        if (root.has("i")) {
            return objectMapper.treeToValue(root, RedisOrderEntry.class).toEvent();
        }
        return objectMapper.treeToValue(root, OrderAssetReservationSucceededEvent.class);
    }

    /**
     * Atomically adds a new order to the appropriate order book (buy/sell).
     * Uses Lua script to ensure all three operations are atomic:
     * 1. Add to orderbook ZSet
     * 2. Store order details
     * 3. Add to user's order set
     *
     * @param event The order event to be added
     * @throws JsonProcessingException if the order cannot be serialized to JSON
     */
    public void addOrder(OrderAssetReservationSucceededEvent event) throws JsonProcessingException {
        OrderBookRuntimeGuard.Snapshot runtime = runtimeSnapshot();
        String orderbookKey = orderbookKey(event);
        String orderIdKey = "order:" + event.getOrderId();
        String userOrdersKey = "user:" + event.getUserId() + ":orders";
        String orderJson = serializeRedisOrder(event);
        double orderScore = scoreFor(event);

        List<String> keys = List.of(
                orderbookKey, orderIdKey, userOrdersKey, OrderBookRuntimeGuard.SENTINEL_KEY);
        List<String> args = List.of(
            event.getOrderId().toString(),
            String.valueOf(orderScore),
            orderJson,
            userOpenOrderIndexEnabledArg(),
            expectedSentinel(runtime)
        );

        Long result = redisTemplate.execute((RedisCallback<Long>) connection -> {
            // Flatten keys and args into single byte[] varargs array
            byte[][] keysBytes = keys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] argsBytes = args.stream().map(a -> a.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);

            // Combine keys and args into single varargs array
            byte[][] allParams = new byte[keysBytes.length + argsBytes.length][];
            System.arraycopy(keysBytes, 0, allParams, 0, keysBytes.length);
            System.arraycopy(argsBytes, 0, allParams, keysBytes.length, argsBytes.length);

            Object res = evalLoadedScript(
                connection,
                addOrderLuaSha,
                addOrderLuaScript,
                ReturnType.INTEGER,
                keys.size(),
                allParams
            );
            return res != null ? (Long) res : 0L;
        });

        if (result != null && result == -99L) {
            rejectGenerationMismatch("add order");
        }
        if (result != null && result == 1L) {
            log.debug("Successfully added order {} to orderbook atomically", event.getOrderId());
        } else {
            log.error("Failed to add order {} to orderbook", event.getOrderId());
            throw new RuntimeException("Failed to add order to Redis");
        }
    }

    /**
     * Atomically removes an order from its corresponding order book.
     * Uses Lua script to ensure all three operations are atomic:
     * 1. Remove from orderbook ZSet
     * 2. Delete order details
     * 3. Remove from user's order set
     *
     * @param event The order event to be removed
     */
    public void removeOrder(OrderAssetReservationSucceededEvent event) {
        OrderBookRuntimeGuard.Snapshot runtime = runtimeSnapshot();
        String orderbookKey = orderbookKey(event);
        String orderIdKey = "order:" + event.getOrderId();
        String userOrdersKey = "user:" + event.getUserId() + ":orders";

        List<String> keys = List.of(
                orderbookKey, orderIdKey, userOrdersKey, OrderBookRuntimeGuard.SENTINEL_KEY);
        List<String> args = List.of(
                event.getOrderId().toString(), userOpenOrderIndexEnabledArg(), expectedSentinel(runtime));

        Long result = redisTemplate.execute((RedisCallback<Long>) connection -> {
            // Flatten keys and args into single byte[] varargs array
            byte[][] keysBytes = keys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] argsBytes = args.stream().map(a -> a.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);

            // Combine keys and args into single varargs array
            byte[][] allParams = new byte[keysBytes.length + argsBytes.length][];
            System.arraycopy(keysBytes, 0, allParams, 0, keysBytes.length);
            System.arraycopy(argsBytes, 0, allParams, keysBytes.length, argsBytes.length);

            Object res = evalLoadedScript(
                connection,
                removeOrderLuaSha,
                removeOrderLuaScript,
                ReturnType.INTEGER,
                keys.size(),
                allParams
            );
            return res != null ? (Long) res : 0L;
        });

        if (result != null && result == -99L) {
            rejectGenerationMismatch("remove order");
        }
        if (result != null && result == 1L) {
            log.debug("Successfully removed order {} from orderbook atomically", event.getOrderId());
        } else {
            log.warn("Order {} was not found in orderbook (might have been matched already)", event.getOrderId());
        }
    }

    /**
     * Removes only the user's open-order reference.
     *
     * Matching Lua scripts already remove the resting order from the market orderbook and delete
     * the order detail. For a fully matched resting order, the only remaining cleanup is the
     * user:{userId}:orders set entry. Keeping this as a single Redis SREM avoids a redundant
     * remove_order.lua round trip on the trade hot path.
     */
    public void unlinkUserOrder(OrderAssetReservationSucceededEvent event) {
        if (!userOpenOrderIndexEnabled) {
            return;
        }
        OrderBookRuntimeGuard.Snapshot runtime = runtimeSnapshot();
        String userOrdersKey = "user:" + event.getUserId() + ":orders";
        Long removed = redisTemplate.execute(
                UNLINK_USER_ORDER_SCRIPT,
                List.of(userOrdersKey, OrderBookRuntimeGuard.SENTINEL_KEY),
                event.getOrderId().toString(),
                expectedSentinel(runtime));
        if (removed != null && removed == -99L) {
            rejectGenerationMismatch("unlink user order");
        }
        if (removed != null && removed > 0) {
            log.debug("Successfully unlinked order {} from user open orders", event.getOrderId());
        } else {
            log.warn("Order {} was not linked in user open orders", event.getOrderId());
        }
    }

    /**
     * Reserves the best matching resting order without deleting the order detail.
     *
     * The order is removed from the visible orderbook and written to a reservation key. This
     * prevents another incoming order from matching the same resting order while the durable
     * TradeExecuted fact is being persisted. If persistence fails, the reservation can be
     * released back to the orderbook with the original amount.
     */
    public OrderAssetReservationSucceededEvent reserveBestMatchOrderLua(OrderAssetReservationSucceededEvent incomingOrder) {
        OrderBookRuntimeGuard.Snapshot runtime = runtimeSnapshot();
        boolean isBuy = incomingOrder.getOrderType().equalsIgnoreCase("BUY");
        String orderbookKey = isBuy
                ? orderbookKey(marketId(incomingOrder), "sell")
                : orderbookKey(marketId(incomingOrder), "buy");
        String luaScript = isBuy ? reserveMatchOrderBuyLuaScript : reserveMatchOrderSellLuaScript;
        double priceBoundary = isBuy
                ? maxSellScore(incomingOrder.getPrice())
                : minBuyScore(incomingOrder.getPrice());

        List<String> keys = List.of(orderbookKey, OrderBookRuntimeGuard.SENTINEL_KEY);
        List<String> args = List.of(
                String.valueOf(priceBoundary),
                String.valueOf(Instant.now().toEpochMilli()),
                incomingOrder.getUserId().toString(),
                expectedSentinel(runtime));

        String orderJson = redisTemplate.execute((RedisCallback<String>) connection -> {
            byte[][] keysBytes = keys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] argsBytes = args.stream().map(a -> a.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] allParams = new byte[keysBytes.length + argsBytes.length][];
            System.arraycopy(keysBytes, 0, allParams, 0, keysBytes.length);
            System.arraycopy(argsBytes, 0, allParams, keysBytes.length, argsBytes.length);

            Object res = evalLoadedScript(
                    connection,
                    isBuy ? reserveMatchOrderBuyLuaSha : reserveMatchOrderSellLuaSha,
                    luaScript,
                    ReturnType.VALUE,
                    keys.size(),
                    allParams
            );
            return res != null ? new String((byte[]) res, StandardCharsets.UTF_8) : null;
        });

        if (orderJson == null) {
            log.debug("No matching order found for price {}, isBuy={}", incomingOrder.getPrice(), isBuy);
            return null;
        }
        if ("__GENERATION_MISMATCH__".equals(orderJson)) {
            rejectGenerationMismatch("reserve best match");
        }
        if (orderJson.startsWith(MISSING_ORDER_DETAIL_PREFIX)) {
            String missingOrderId = orderJson.substring(MISSING_ORDER_DETAIL_PREFIX.length());
            log.error("Redis orderbook is inconsistent: orderbook entry {} exists but order detail is missing",
                    missingOrderId);
            throw new OrderBookDataInvariantException("Redis orderbook detail missing for order " + missingOrderId);
        }
        if (orderJson.startsWith(INVALID_ORDER_DETAIL_PREFIX)) {
            String invalidOrderId = orderJson.substring(INVALID_ORDER_DETAIL_PREFIX.length());
            log.error("Redis orderbook is inconsistent: order {} has no owner", invalidOrderId);
            throw new OrderBookDataInvariantException("Redis orderbook owner missing for order " + invalidOrderId);
        }
        if (orderJson.startsWith(RESERVATION_EXISTS_PREFIX)) {
            String orderId = orderJson.substring(RESERVATION_EXISTS_PREFIX.length());
            log.error("Redis orderbook is inconsistent: order {} is visible but already reserved", orderId);
            throw new OrderBookDataInvariantException("Redis order already reserved for order " + orderId);
        }

        try {
            OrderAssetReservationSucceededEvent reservedOrder = deserializeRedisOrder(orderJson);
            log.debug("Successfully reserved order {} for matching", reservedOrder.getOrderId());
            return reservedOrder;
        } catch (Exception e) {
            log.error("Failed to deserialize reserved order", e);
            throw new OrderBookDataInvariantException("Failed to deserialize reserved Redis order", e);
        }
    }

    /**
     * Atomically reserves the best matching resting order and generates the match sequence.
     *
     * The match ID is generated inside the same Redis Lua script after the reservation is
     * created. No-match orders do not consume a sequence value.
     */
    public ReservedMatch reserveBestMatchOrderWithSequenceLua(OrderAssetReservationSucceededEvent incomingOrder) {
        OrderBookRuntimeGuard.Snapshot runtime = runtimeSnapshot();
        boolean isBuy = incomingOrder.getOrderType().equalsIgnoreCase("BUY");
        String orderbookKey = isBuy
                ? orderbookKey(marketId(incomingOrder), "sell")
                : orderbookKey(marketId(incomingOrder), "buy");
        String luaScript = isBuy ? reserveMatchOrderBuyLuaScript : reserveMatchOrderSellLuaScript;
        double priceBoundary = isBuy
                ? maxSellScore(incomingOrder.getPrice())
                : minBuyScore(incomingOrder.getPrice());

        List<String> keys = List.of(orderbookKey, MATCH_ID_KEY, OrderBookRuntimeGuard.SENTINEL_KEY);
        List<String> args = List.of(
                String.valueOf(priceBoundary),
                String.valueOf(Instant.now().toEpochMilli()),
                incomingOrder.getUserId().toString(),
                expectedSentinel(runtime));

        @SuppressWarnings("unchecked")
        List<byte[]> rawResult = redisTemplate.execute((RedisCallback<List<byte[]>>) connection -> {
            byte[][] keysBytes = keys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] argsBytes = args.stream().map(a -> a.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] allParams = new byte[keysBytes.length + argsBytes.length][];
            System.arraycopy(keysBytes, 0, allParams, 0, keysBytes.length);
            System.arraycopy(argsBytes, 0, allParams, keysBytes.length, argsBytes.length);

            Object res = evalLoadedScript(
                    connection,
                    isBuy ? reserveMatchOrderBuyLuaSha : reserveMatchOrderSellLuaSha,
                    luaScript,
                    ReturnType.MULTI,
                    keys.size(),
                    allParams
            );
            return (List<byte[]>) res;
        });

        if (rawResult == null || rawResult.isEmpty()) {
            log.debug("No matching order found for price {}, isBuy={}", incomingOrder.getPrice(), isBuy);
            return null;
        }

        String orderJson = new String(rawResult.get(0), StandardCharsets.UTF_8);
        if ("__GENERATION_MISMATCH__".equals(orderJson)) {
            rejectGenerationMismatch("reserve best match with sequence");
        }
        if (orderJson.startsWith(MISSING_ORDER_DETAIL_PREFIX)) {
            String missingOrderId = orderJson.substring(MISSING_ORDER_DETAIL_PREFIX.length());
            log.error("Redis orderbook is inconsistent: orderbook entry {} exists but order detail is missing",
                    missingOrderId);
            throw new OrderBookDataInvariantException("Redis orderbook detail missing for order " + missingOrderId);
        }
        if (orderJson.startsWith(INVALID_ORDER_DETAIL_PREFIX)) {
            String invalidOrderId = orderJson.substring(INVALID_ORDER_DETAIL_PREFIX.length());
            log.error("Redis orderbook is inconsistent: order {} has no owner", invalidOrderId);
            throw new OrderBookDataInvariantException("Redis orderbook owner missing for order " + invalidOrderId);
        }
        if (orderJson.startsWith(RESERVATION_EXISTS_PREFIX)) {
            String orderId = orderJson.substring(RESERVATION_EXISTS_PREFIX.length());
            log.error("Redis orderbook is inconsistent: order {} is visible but already reserved", orderId);
            throw new OrderBookDataInvariantException("Redis order already reserved for order " + orderId);
        }
        if (rawResult.size() < 2) {
            throw new OrderBookDataInvariantException("Redis reserve script did not return a match sequence");
        }

        try {
            OrderAssetReservationSucceededEvent reservedOrder = deserializeRedisOrder(orderJson);
            Long matchId = Long.valueOf(new String(rawResult.get(1), StandardCharsets.UTF_8));
            log.debug("Successfully reserved order {} for matching with matchId={}",
                    reservedOrder.getOrderId(), matchId);
            return new ReservedMatch(reservedOrder, matchId);
        } catch (Exception e) {
            log.error("Failed to deserialize reserved order or match sequence", e);
            throw new OrderBookDataInvariantException("Failed to deserialize reserved Redis order with sequence", e);
        }
    }

    /**
     * Atomically reserves the best matching resting order, or adds the incoming order to the
     * visible orderbook when no match exists.
     *
     * This removes the no-match hot-path round trip where Java first ran a reserve Lua script,
     * observed null, and then ran add_order.lua.
     */
    public MatchOrAddResult reserveBestMatchOrAddOrderWithSequenceLua(OrderAssetReservationSucceededEvent incomingOrder) {
        return reserveBestMatchOrAddOrderWithSequenceLua(incomingOrder, null);
    }

    MatchOrAddResult reserveBestMatchOrAddOrderWithSequenceLua(
            OrderAssetReservationSucceededEvent incomingOrder,
            IncomingOrderProcessingStore.Claim processingClaim) {
        OrderBookRuntimeGuard.Snapshot runtime = runtimeSnapshot();
        Instant prepareStartedAt = Instant.now();
        boolean isBuy = incomingOrder.getOrderType().equalsIgnoreCase("BUY");
        String oppositeOrderbookKey = isBuy
                ? orderbookKey(marketId(incomingOrder), "sell")
                : orderbookKey(marketId(incomingOrder), "buy");
        String ownOrderbookKey = orderbookKey(incomingOrder);
        String incomingOrderIdKey = "order:" + incomingOrder.getOrderId();
        String incomingUserOrdersKey = "user:" + incomingOrder.getUserId() + ":orders";
        String luaScript = isBuy ? reserveOrAddOrderBuyLuaScript : reserveOrAddOrderSellLuaScript;
        String scriptSha = isBuy ? reserveOrAddOrderBuyLuaSha : reserveOrAddOrderSellLuaSha;
        double priceBoundary = isBuy
                ? maxSellScore(incomingOrder.getPrice())
                : minBuyScore(incomingOrder.getPrice());
        recordReservePrepare(Duration.between(prepareStartedAt, Instant.now()));

        Instant serializeStartedAt = Instant.now();
        String incomingOrderJson;
        try {
            incomingOrderJson = serializeRedisOrder(incomingOrder);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize incoming Redis order", e);
        } finally {
            recordReserveSerializeIncoming(Duration.between(serializeStartedAt, Instant.now()));
        }

        List<String> keys = new ArrayList<>(8);
        keys.add(oppositeOrderbookKey);
        keys.add(ownOrderbookKey);
        keys.add(incomingOrderIdKey);
        keys.add(incomingUserOrdersKey);
        keys.add(MATCH_ID_KEY);
        List<String> args = new ArrayList<>(10);
        args.add(String.valueOf(priceBoundary));
        args.add(String.valueOf(Instant.now().toEpochMilli()));
        args.add(incomingOrder.getOrderId().toString());
        args.add(String.valueOf(scoreFor(incomingOrder)));
        args.add(incomingOrderJson);
        args.add(userOpenOrderIndexEnabledArg());
        if (processingClaim != null) {
            keys.add(processingClaim.stateHashKey());
            keys.add(processingClaim.completedBitmapKey());
            args.add(processingClaim.orderIdField());
            args.add(processingClaim.token());
            args.add(String.valueOf(processingClaim.completedBitOffset()));
        } else {
            keys.add("match:incoming-order:unused-state");
            keys.add("match:incoming-order:unused-completed");
            args.add("");
            args.add("");
            args.add("");
        }
        keys.add(cancellationIntentKey(incomingOrder.getOrderId()));
        args.add(marketId(incomingOrder));
        args.add(incomingOrder.getUserId().toString());
        keys.add(OrderBookRuntimeGuard.SENTINEL_KEY);
        args.add(expectedSentinel(runtime));

        @SuppressWarnings("unchecked")
        List<byte[]> rawResult = redisTemplate.execute((RedisCallback<List<byte[]>>) connection -> {
            Instant callbackPrepareStartedAt = Instant.now();
            byte[][] keysBytes = keys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] argsBytes = args.stream().map(a -> a.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] allParams = new byte[keysBytes.length + argsBytes.length][];
            System.arraycopy(keysBytes, 0, allParams, 0, keysBytes.length);
            System.arraycopy(argsBytes, 0, allParams, keysBytes.length, argsBytes.length);
            recordReserveCallbackPrepare(Duration.between(callbackPrepareStartedAt, Instant.now()));

            Instant redisEvalStartedAt = Instant.now();
            try {
                Object res = evalLoadedScript(
                        connection,
                        scriptSha,
                        luaScript,
                        ReturnType.MULTI,
                        keys.size(),
                        allParams
                );
                return (List<byte[]>) res;
            } finally {
                recordReserveRedisEval(Duration.between(redisEvalStartedAt, Instant.now()));
            }
        });

        Instant resultStartedAt = Instant.now();
        if (rawResult == null || rawResult.isEmpty()) {
            throw new IllegalStateException("Redis reserve-or-add script returned no result");
        }

        String status = new String(rawResult.get(0), StandardCharsets.UTF_8);
        if ("__GENERATION_MISMATCH__".equals(status)) {
            rejectGenerationMismatch("reserve or add incoming order");
        }
        if ("__ADDED__".equals(status)) {
            log.debug("No matching order found; added incoming order {} to orderbook", incomingOrder.getOrderId());
            return MatchOrAddResult.added();
        }
        if ("__ADDED_COMPLETED__".equals(status)) {
            log.debug("No matching order found; added and completed incoming order {}", incomingOrder.getOrderId());
            return MatchOrAddResult.addedAndCompleted();
        }
        if ("__DUPLICATE__".equals(status)) {
            return MatchOrAddResult.duplicate();
        }
        if ("__IN_PROGRESS__".equals(status)) {
            return MatchOrAddResult.inProgress();
        }
        if ("__CANCELLATION_PENDING__".equals(status)) {
            return MatchOrAddResult.cancellationPending();
        }
        if (status.startsWith(MISSING_ORDER_DETAIL_PREFIX)) {
            String missingOrderId = status.substring(MISSING_ORDER_DETAIL_PREFIX.length());
            log.error("Redis orderbook is inconsistent: orderbook entry {} exists but order detail is missing",
                    missingOrderId);
            throw new OrderBookDataInvariantException("Redis orderbook detail missing for order " + missingOrderId);
        }
        if (status.startsWith(INVALID_ORDER_DETAIL_PREFIX)) {
            String invalidOrderId = status.substring(INVALID_ORDER_DETAIL_PREFIX.length());
            log.error("Redis orderbook is inconsistent: order {} has no owner", invalidOrderId);
            throw new OrderBookDataInvariantException("Redis orderbook owner missing for order " + invalidOrderId);
        }
        if (status.startsWith(RESERVATION_EXISTS_PREFIX)) {
            String orderId = status.substring(RESERVATION_EXISTS_PREFIX.length());
            log.error("Redis orderbook is inconsistent: order {} is visible but already reserved", orderId);
            throw new OrderBookDataInvariantException("Redis order already reserved for order " + orderId);
        }
        if (!"__MATCH__".equals(status)) {
            throw new OrderBookDataInvariantException("Redis reserve-or-add script returned unknown status " + status);
        }
        if (rawResult.size() < 3) {
            throw new OrderBookDataInvariantException("Redis reserve-or-add script did not return reserved order and sequence");
        }
        recordReserveResult(Duration.between(resultStartedAt, Instant.now()));

        Instant deserializeStartedAt = Instant.now();
        try {
            String orderJson = new String(rawResult.get(1), StandardCharsets.UTF_8);
            OrderAssetReservationSucceededEvent reservedOrder = deserializeRedisOrder(orderJson);
            Long matchId = Long.valueOf(new String(rawResult.get(2), StandardCharsets.UTF_8));
            log.debug("Successfully reserved order {} for matching with matchId={}",
                    reservedOrder.getOrderId(), matchId);
            return MatchOrAddResult.matched(new ReservedMatch(reservedOrder, matchId));
        } catch (Exception e) {
            log.error("Failed to deserialize reserve-or-add result", e);
            throw new OrderBookDataInvariantException("Failed to deserialize reserve-or-add Redis result", e);
        } finally {
            recordReserveDeserializeResting(Duration.between(deserializeStartedAt, Instant.now()));
        }
    }

    public record ReservedMatch(OrderAssetReservationSucceededEvent order, Long matchId) {
    }

    public enum IncomingOrderAdmission {
        CLAIMED,
        COMPLETED,
        DUPLICATE,
        IN_PROGRESS,
        CANCELLATION_PENDING
    }

    public record MatchOrAddResult(
            boolean orderAdded,
            ReservedMatch reservedMatch,
            IncomingOrderAdmission incomingOrderAdmission) {
        public static MatchOrAddResult added() {
            return new MatchOrAddResult(true, null, IncomingOrderAdmission.CLAIMED);
        }

        public static MatchOrAddResult addedAndCompleted() {
            return new MatchOrAddResult(true, null, IncomingOrderAdmission.COMPLETED);
        }

        public static MatchOrAddResult matched(ReservedMatch reservedMatch) {
            return new MatchOrAddResult(false, reservedMatch, IncomingOrderAdmission.CLAIMED);
        }

        public static MatchOrAddResult duplicate() {
            return new MatchOrAddResult(false, null, IncomingOrderAdmission.DUPLICATE);
        }

        public static MatchOrAddResult inProgress() {
            return new MatchOrAddResult(false, null, IncomingOrderAdmission.IN_PROGRESS);
        }

        public static MatchOrAddResult cancellationPending() {
            return new MatchOrAddResult(false, null, IncomingOrderAdmission.CANCELLATION_PENDING);
        }
    }

    /**
     * Releases a reserved order back to the visible orderbook.
     */
    public void releaseReservedOrder(OrderAssetReservationSucceededEvent event, String expectedTradeId)
            throws JsonProcessingException {
        OrderBookRuntimeGuard.Snapshot runtime = runtimeSnapshot();
        String orderbookKey = orderbookKey(event);
        String orderIdKey = "order:" + event.getOrderId();
        String userOrdersKey = "user:" + event.getUserId() + ":orders";
        String reservationKey = reservationKey(event);
        String orderJson = serializeRedisOrder(event);
        double orderScore = scoreFor(event);

        List<String> keys = List.of(
                orderbookKey, orderIdKey, userOrdersKey, reservationKey,
                OrderBookRuntimeGuard.SENTINEL_KEY);
        List<String> args = List.of(
                event.getOrderId().toString(),
                String.valueOf(orderScore),
                orderJson,
                userOpenOrderIndexEnabledArg(),
                expectedTradeId == null ? "" : expectedTradeId,
                expectedSentinel(runtime)
        );

        Long result = redisTemplate.execute((RedisCallback<Long>) connection -> {
            byte[][] keysBytes = keys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] argsBytes = args.stream().map(a -> a.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] allParams = new byte[keysBytes.length + argsBytes.length][];
            System.arraycopy(keysBytes, 0, allParams, 0, keysBytes.length);
            System.arraycopy(argsBytes, 0, allParams, keysBytes.length, argsBytes.length);

            Object res = evalLoadedScript(
                    connection,
                    releaseReservedOrderLuaSha,
                    releaseReservedOrderLuaScript,
                    ReturnType.INTEGER,
                    keys.size(),
                    allParams
            );
            return res != null ? (Long) res : 0L;
        });

        if (result != null && result == -99L) {
            rejectGenerationMismatch("release reserved order");
        }
        if (result != null && result == 1L) {
            log.debug("Successfully released reserved order {} back to orderbook", event.getOrderId());
        } else {
            log.error("Failed to release reserved order {} back to orderbook for trade {}, result={}",
                    event.getOrderId(), expectedTradeId, result);
            throw new RuntimeException("Failed to release reserved order to Redis");
        }
    }

    /**
     * Completes a reserved order after its corresponding TradeExecuted fact is durable.
     */
    public ReservationCompletionOutcome completeReservedOrder(
            OrderAssetReservationSucceededEvent event,
            String expectedTradeId) {
        OrderBookRuntimeGuard.Snapshot runtime = runtimeSnapshot();
        Instant prepareStartedAt = Instant.now();
        String orderIdKey = "order:" + event.getOrderId();
        String userOrdersKey = "user:" + event.getUserId() + ":orders";
        String reservationKey = reservationKey(event);

        List<String> keys = List.of(
                orderIdKey, userOrdersKey, reservationKey, OrderBookRuntimeGuard.SENTINEL_KEY);
        List<String> args = List.of(
                event.getOrderId().toString(),
                userOpenOrderIndexEnabledArg(),
                expectedTradeId == null ? "" : expectedTradeId,
                expectedSentinel(runtime));
        recordCompleteReservationPrepare(Duration.between(prepareStartedAt, Instant.now()));

        Long result = redisTemplate.execute((RedisCallback<Long>) connection -> {
            byte[][] keysBytes = keys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] argsBytes = args.stream().map(a -> a.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] allParams = new byte[keysBytes.length + argsBytes.length][];
            System.arraycopy(keysBytes, 0, allParams, 0, keysBytes.length);
            System.arraycopy(argsBytes, 0, allParams, keysBytes.length, argsBytes.length);

            Instant redisEvalStartedAt = Instant.now();
            Object res;
            try {
                res = evalLoadedScript(
                        connection,
                        completeReservedOrderLuaSha,
                        completeReservedOrderLuaScript,
                        ReturnType.INTEGER,
                        keys.size(),
                        allParams
                );
            } finally {
                recordCompleteReservationRedisEval(Duration.between(redisEvalStartedAt, Instant.now()));
            }
            return res == null ? null : ((Number) res).longValue();
        });

        Instant resultStartedAt = Instant.now();
        try {
            if (result == null) {
                throw new IllegalStateException("Redis returned no reservation completion result for order "
                        + event.getOrderId());
            }
            if (result == -99L) {
                rejectGenerationMismatch("complete reserved order");
            }
            ReservationCompletionOutcome outcome = ReservationCompletionOutcome.fromRedisCode(result);
            if (outcome == ReservationCompletionOutcome.COMPLETED) {
                log.debug("Successfully completed reserved order {}", event.getOrderId());
            } else if (outcome == ReservationCompletionOutcome.ALREADY_COMPLETED) {
                log.debug("Reserved order {} was already completed for trade {}",
                        event.getOrderId(), expectedTradeId);
            } else {
                log.error("Reservation completion ownership conflict: orderId={}, expectedTradeId={}, outcome={}, redisCode={}",
                        event.getOrderId(), expectedTradeId, outcome, outcome.redisCode());
            }
            return outcome;
        } finally {
            recordCompleteReservationResult(Duration.between(resultStartedAt, Instant.now()));
        }
    }

    private void recordCompleteReservationPrepare(Duration duration) {
        if (metrics != null) {
            metrics.recordCompleteReservationPrepare(duration);
        }
    }

    private void recordReservePrepare(Duration duration) {
        if (metrics != null) {
            metrics.recordReservePrepare(duration);
        }
    }

    private void recordReserveCallbackPrepare(Duration duration) {
        if (metrics != null) {
            metrics.recordReserveCallbackPrepare(duration);
        }
    }

    private void recordReserveSerializeIncoming(Duration duration) {
        if (metrics != null) {
            metrics.recordReserveSerializeIncoming(duration);
        }
    }

    private void recordReserveRedisEval(Duration duration) {
        if (metrics != null) {
            metrics.recordReserveRedisEval(duration);
        }
    }

    private void recordReserveDeserializeResting(Duration duration) {
        if (metrics != null) {
            metrics.recordReserveDeserializeResting(duration);
        }
    }

    private void recordReserveResult(Duration duration) {
        if (metrics != null) {
            metrics.recordReserveResult(duration);
        }
    }

    private void recordCompleteReservationRedisEval(Duration duration) {
        if (metrics != null) {
            metrics.recordCompleteReservationRedisEval(duration);
        }
    }

    private void recordCompleteReservationResult(Duration duration) {
        if (metrics != null) {
            metrics.recordCompleteReservationResult(duration);
        }
    }

    public long countActiveReservations() {
        return redisTemplate.execute((RedisCallback<Long>) connection -> {
            long count = 0;
            ScanOptions options = ScanOptions.scanOptions()
                    .match(RESERVATION_KEY_PATTERN)
                    .count(1_000)
                    .build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    cursor.next();
                    count++;
                }
            } catch (RuntimeException e) {
                throw new IllegalStateException("Failed to scan Redis reservations", e);
            }
            return count;
        });
    }

    /**
     * Reads one bounded, rotating page of the reservation keyspace. Redis SCAN's cursor is kept
     * between polls so keys behind terminal or fresh reservations still receive a turn without
     * forcing an O(keyspace) scan on the single-threaded Redis server every few seconds.
     */
    public List<ReservationSnapshot> scanReservations(int limit) {
        OrderBookRuntimeGuard.Snapshot runtime = runtimeSnapshot();
        String generationIdentity = runtime == null ? null : runtime.expectedSentinel();
        List<String> keys = scanReservationKeys(Math.max(1, limit), generationIdentity);
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<ReservationSnapshot> snapshots = new ArrayList<>(keys.size());
        for (String key : keys) {
            String value = redisTemplate.opsForValue().get(key);
            ReservationSnapshot snapshot = parseReservationSnapshot(
                    key,
                    value,
                    generationIdentity);
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }
        if (runtime != null) {
            runtimeGuard.verifyUnchanged(runtime);
        }
        return snapshots;
    }

    public ReservationSnapshot readReservation(String key) {
        OrderBookRuntimeGuard.Snapshot runtime = runtimeSnapshot();
        String value = redisTemplate.opsForValue().get(key);
        ReservationSnapshot snapshot = parseReservationSnapshot(
                key,
                value,
                runtime == null ? null : runtime.expectedSentinel());
        if (runtime != null) {
            runtimeGuard.verifyUnchanged(runtime);
        }
        return snapshot;
    }

    private List<String> scanReservationKeys(int count, String generationIdentity) {
        Object raw;
        try {
            raw = redisTemplate.execute(
                    RESERVATION_SCAN_PAGE_SCRIPT,
                    List.of(RESERVATION_SCAN_STATE_KEY, RESERVATION_SCAN_BUFFER_KEY),
                    RESERVATION_KEY_PATTERN,
                    Integer.toString(count),
                    generationIdentity == null ? "" : generationIdentity);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to scan Redis reservations", e);
        }
        if (!(raw instanceof List<?> result) || result.size() != 2) {
            throw new IllegalStateException("Redis SCAN returned an invalid reservation page");
        }
        redisString(result.get(0));
        if (!(result.get(1) instanceof Collection<?> rawKeys)) {
            throw new IllegalStateException("Redis SCAN returned an invalid reservation key list");
        }
        return rawKeys.stream()
                .map(this::redisString)
                .toList();
    }

    private String redisString(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalStateException("Redis returned an unsupported SCAN value type");
    }

    private ReservationSnapshot parseReservationSnapshot(
            String key,
            String value,
            String generationIdentity) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            return ReservationSnapshot.invalid(
                    key, "missing reservation value", value, generationIdentity);
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            if (root.has("order")) {
                OrderAssetReservationSucceededEvent order = deserializeRedisOrder(root.get("order"));
                long reservedAtEpochMillis = root.path("reservedAtEpochMillis").asLong(0L);
                String tradeId = root.path("tradeId").asText(null);
                return ReservationSnapshot.valid(
                        key, order, reservedAtEpochMillis, tradeId, value, generationIdentity);
            }
            if (root.has("orderId")) {
                String orderId = root.path("orderId").asText();
                String orderJson = redisTemplate.opsForValue().get("order:" + orderId);
                if (orderJson == null || orderJson.isBlank()) {
                    return ReservationSnapshot.invalid(
                            key, "missing reserved order detail " + orderId, value, generationIdentity);
                }
                OrderAssetReservationSucceededEvent order = deserializeRedisOrder(orderJson);
                long reservedAtEpochMillis = root.path("reservedAtEpochMillis").asLong(0L);
                String tradeId = root.path("tradeId").asText(null);
                return ReservationSnapshot.valid(
                        key, order, reservedAtEpochMillis, tradeId, value, generationIdentity);
            }

            // Backward compatibility for pre-TPS-59 reservation values that stored only order JSON.
            OrderAssetReservationSucceededEvent order = deserializeRedisOrder(root);
            return ReservationSnapshot.valid(key, order, 0L, null, value, generationIdentity);
        } catch (Exception e) {
            return ReservationSnapshot.invalid(key, e.getMessage(), value, generationIdentity);
        }
    }

    public OrderAssetReservationSucceededEvent findOpenOrder(UUID orderId) {
        OrderBookRuntimeGuard.Snapshot runtime = runtimeSnapshot();
        String orderJson = redisTemplate.opsForValue().get("order:" + orderId);
        if (runtime != null) {
            runtimeGuard.verifyUnchanged(runtime);
        }
        if (orderJson == null) {
            return null;
        }
        try {
            return deserializeRedisOrder(orderJson);
        } catch (JsonProcessingException e) {
            throw new OrderBookDataInvariantException(
                    "Cannot deserialize open order for cancellation: orderId=" + orderId, e);
        }
    }

    public void recordCancellationIntent(UUID orderId, UUID cancellationId) {
        OrderBookRuntimeGuard.Snapshot runtime = runtimeSnapshot();
        String key = cancellationIntentKey(orderId);
        if (runtime != null) {
            Long result = redisTemplate.execute(
                    RECORD_CANCELLATION_INTENT_SCRIPT,
                    List.of(key, OrderBookRuntimeGuard.SENTINEL_KEY),
                    cancellationId.toString(),
                    Long.toString(Duration.ofDays(7).toSeconds()),
                    runtime.expectedSentinel());
            if (result != null && result == -99L) {
                rejectGenerationMismatch("record cancellation intent");
            }
            if (result != null && result == -1L) {
                throw new IllegalStateException(
                        "Order already has another cancellation intent: orderId=" + orderId);
            }
            if (result == null) {
                throw new IllegalStateException("Redis cancellation intent script returned no result");
            }
            return;
        }
        Boolean inserted = redisTemplate.opsForValue().setIfAbsent(
                key, cancellationId.toString(), Duration.ofDays(7));
        if (Boolean.TRUE.equals(inserted)) {
            return;
        }
        String existing = redisTemplate.opsForValue().get(key);
        if (!cancellationId.toString().equals(existing)) {
            throw new IllegalStateException("Order already has another cancellation intent: orderId=" + orderId);
        }
    }

    public CancellationArbitration arbitrateCancellation(
            OrderAssetReservationSucceededEvent order,
            UUID cancellationId) {
        OrderBookRuntimeGuard.Snapshot runtime = runtimeSnapshot();
        String orderbookKey = orderbookKey(order);
        String orderIdKey = "order:" + order.getOrderId();
        String userOrdersKey = "user:" + order.getUserId() + ":orders";
        String markerKey = "order:cancellation:" + order.getOrderId();
        List<String> keys = List.of(
                orderbookKey, orderIdKey, userOrdersKey, markerKey,
                OrderBookRuntimeGuard.SENTINEL_KEY);
        List<String> args = List.of(
                order.getOrderId().toString(),
                userOpenOrderIndexEnabledArg(),
                cancellationId.toString(),
                expectedSentinel(runtime));

        @SuppressWarnings("unchecked")
        List<byte[]> result = redisTemplate.execute((RedisCallback<List<byte[]>>) connection -> {
            byte[][] keysBytes = keys.stream()
                    .map(key -> key.getBytes(StandardCharsets.UTF_8))
                    .toArray(byte[][]::new);
            byte[][] argsBytes = args.stream()
                    .map(arg -> arg.getBytes(StandardCharsets.UTF_8))
                    .toArray(byte[][]::new);
            byte[][] params = new byte[keysBytes.length + argsBytes.length][];
            System.arraycopy(keysBytes, 0, params, 0, keysBytes.length);
            System.arraycopy(argsBytes, 0, params, keysBytes.length, argsBytes.length);
            Object response = evalLoadedScript(
                    connection,
                    cancelOrderRequestLuaSha,
                    cancelOrderRequestLuaScript,
                    ReturnType.MULTI,
                    keys.size(),
                    params);
            return (List<byte[]>) response;
        });

        if (result == null || result.isEmpty()) {
            throw new IllegalStateException("Redis cancellation script returned no result: orderId="
                    + order.getOrderId());
        }
        String status = new String(result.get(0), StandardCharsets.UTF_8);
        if ("__GENERATION_MISMATCH__".equals(status)) {
            rejectGenerationMismatch("arbitrate cancellation");
        }
        if ("__NOT_OPEN__".equals(status)) {
            return CancellationArbitration.notOpen();
        }
        if (("__CANCELLED__".equals(status) || "__DUPLICATE__".equals(status))
                && result.size() == 2 && result.get(1) != null) {
            try {
                OrderAssetReservationSucceededEvent cancelledOrder = deserializeRedisOrder(
                        new String(result.get(1), StandardCharsets.UTF_8));
                return "__CANCELLED__".equals(status)
                        ? CancellationArbitration.cancelled(cancelledOrder)
                        : CancellationArbitration.duplicate(cancelledOrder);
            } catch (JsonProcessingException e) {
                throw new OrderBookDataInvariantException("Cannot deserialize atomically cancelled order: orderId="
                        + order.getOrderId(), e);
            }
        }
        throw new OrderBookDataInvariantException(
                "Redis cancellation script returned unknown result: " + status);
    }

    private String cancellationIntentKey(UUID orderId) {
        return "order:cancellation-intent:" + orderId;
    }

    public enum CancellationOutcome {
        CANCELLED,
        ALREADY_CANCELLED_BY_REQUEST,
        NOT_OPEN
    }

    public record CancellationArbitration(
            CancellationOutcome outcome,
            OrderAssetReservationSucceededEvent cancelledOrder) {

        static CancellationArbitration cancelled(OrderAssetReservationSucceededEvent order) {
            return new CancellationArbitration(CancellationOutcome.CANCELLED, order);
        }

        static CancellationArbitration duplicate(OrderAssetReservationSucceededEvent order) {
            return new CancellationArbitration(CancellationOutcome.ALREADY_CANCELLED_BY_REQUEST, order);
        }

        static CancellationArbitration notOpen() {
            return new CancellationArbitration(CancellationOutcome.NOT_OPEN, null);
        }
    }

    /**
     * Retrieves all orders for a specific user
     *
     * @param userId The user ID
     * @return List of orders for the user
     */
    public List<OrderAssetReservationSucceededEvent> getOrderByUserId(UUID userId) {
        OrderBookRuntimeGuard.Snapshot runtime = runtimeSnapshot();
        if (!userOpenOrderIndexEnabled) {
            verifyRuntimeUnchanged(runtime);
            return List.of();
        }
        String userOrdersKey = "user:" + userId + ":orders";
        Set<String> orderIds = redisTemplate.opsForSet().members(userOrdersKey);
        if (orderIds == null || orderIds.isEmpty()) {
            verifyRuntimeUnchanged(runtime);
            return List.of();
        }
        List<OrderAssetReservationSucceededEvent> orders = orderIds.stream()
                .map(orderId -> {
                    String orderJson = redisTemplate.opsForValue().get("order:" + orderId);
                    if (orderJson != null) {
                        try {
                            return deserializeRedisOrder(orderJson);
                        } catch (Exception e) {
                            log.error("Failed to deserialize order {}", orderId, e);
                        }
                    }
                    return null;
                })
                .filter(o -> o != null)
                .collect(Collectors.toList());
        if (runtime != null) {
            runtimeGuard.verifyUnchanged(runtime);
        }
        return orders;
    }

    /**
     * Retrieves matchable orders for an incoming order based on price matching rules:
     * - For buy orders: finds sell orders with prices less than or equal to the buy price
     * - For sell orders: finds buy orders with prices greater than or equal to the sell price
     *
     * Note: This is a read-only query operation and doesn't modify the orderbook.
     *
     * @param incomingOrder The order to find matches for
     * @return List of matching orders sorted by best price (lowest for sells, highest for buys)
     */
    public List<OrderAssetReservationSucceededEvent> getMatchableOrders(OrderAssetReservationSucceededEvent incomingOrder) {
        OrderBookRuntimeGuard.Snapshot runtime = runtimeSnapshot();
        boolean isBuy = incomingOrder.getOrderType().equalsIgnoreCase("BUY");
        String oppositeKey = isBuy
                ? orderbookKey(marketId(incomingOrder), "sell")
                : orderbookKey(marketId(incomingOrder), "buy");

        Set<String> results;
        if (isBuy) {
            // Find sell orders with price <= buy price
            results = redisTemplate.opsForZSet().rangeByScore(oppositeKey, 0, maxSellScore(incomingOrder.getPrice()));
        } else {
            // Find buy orders with price >= sell price
            results = redisTemplate.opsForZSet().reverseRangeByScore(oppositeKey, minBuyScore(incomingOrder.getPrice()), Double.POSITIVE_INFINITY);
        }

        if (results == null || results.isEmpty()) {
            verifyRuntimeUnchanged(runtime);
            return List.of();
        }

        List<OrderAssetReservationSucceededEvent> orders = results.stream()
                .map(orderIdStr -> {
                    try {
                        String orderJson = redisTemplate.opsForValue().get("order:" + orderIdStr);
                        if (orderJson != null) {
                            return deserializeRedisOrder(orderJson);
                        }
                    } catch (Exception e) {
                        log.error("Failed to deserialize order {}", orderIdStr, e);
                    }
                    return null;
                })
                .filter(event -> event != null)
                .collect(Collectors.toList());
        if (runtime != null) {
            runtimeGuard.verifyUnchanged(runtime);
        }
        return orders;
    }

    private String orderbookKey(OrderAssetReservationSucceededEvent event) {
        String side = event.getOrderType().equalsIgnoreCase("BUY") ? "buy" : "sell";
        return orderbookKey(marketId(event), side);
    }

    private void verifyRuntimeUnchanged(OrderBookRuntimeGuard.Snapshot runtime) {
        if (runtime != null) {
            runtimeGuard.verifyUnchanged(runtime);
        }
    }

    private String orderbookKey(String marketId, String side) {
        return "orderbook:" + marketId + ":" + side;
    }

    private String reservationKey(OrderAssetReservationSucceededEvent event) {
        return "order:reservation:" + event.getOrderId();
    }

    private String userOpenOrderIndexEnabledArg() {
        return userOpenOrderIndexEnabled ? "1" : "0";
    }

    private String marketId(OrderAssetReservationSucceededEvent event) {
        return event.getMarketId() == null || event.getMarketId().isBlank()
                ? DEFAULT_MARKET_ID
                : event.getMarketId();
    }

    private double scoreFor(OrderAssetReservationSucceededEvent event) {
        long sequence = event.getMarketSequence() == null ? 0L : event.getMarketSequence();
        long boundedSequence = Math.floorMod(sequence, SCORE_FACTOR);
        if (event.getOrderType().equalsIgnoreCase("BUY")) {
            return ((long) event.getPrice() * SCORE_FACTOR) + (SCORE_FACTOR - boundedSequence);
        }
        return ((long) event.getPrice() * SCORE_FACTOR) + boundedSequence;
    }

    private double maxSellScore(int buyLimitPrice) {
        return ((long) buyLimitPrice * SCORE_FACTOR) + (SCORE_FACTOR - 1);
    }

    private double minBuyScore(int sellLimitPrice) {
        return (long) sellLimitPrice * SCORE_FACTOR;
    }

    private record RedisOrderEntry(
            UUID i,
            UUID u,
            String m,
            Long s,
            Integer p,
            Integer a,
            String t,
            String c) {

        static RedisOrderEntry from(OrderAssetReservationSucceededEvent event) {
            return new RedisOrderEntry(
                    event.getOrderId(),
                    event.getUserId(),
                    event.getMarketId(),
                    event.getMarketSequence(),
                    event.getPrice(),
                    event.getAmount(),
                    event.getOrderType(),
                    event.getCreatedAt() == null ? null : event.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }

        OrderAssetReservationSucceededEvent toEvent() {
            return OrderAssetReservationSucceededEvent.builder()
                    .orderId(i)
                    .userId(u)
                    .marketId(m)
                    .marketSequence(s)
                    .price(p)
                    .amount(a)
                    .orderType(t)
                    .createdAt(c == null || c.isBlank() ? null : LocalDateTime.parse(c, DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .build();
        }
    }

    public record ReservationSnapshot(
            String key,
            OrderAssetReservationSucceededEvent order,
            long reservedAtEpochMillis,
            String tradeId,
            boolean valid,
            String invalidReason,
            String rawPayload,
            String generationIdentity) {

        static ReservationSnapshot valid(String key, OrderAssetReservationSucceededEvent order, long reservedAtEpochMillis) {
            return valid(key, order, reservedAtEpochMillis, null);
        }

        static ReservationSnapshot valid(
                String key,
                OrderAssetReservationSucceededEvent order,
                long reservedAtEpochMillis,
                String tradeId) {
            return valid(key, order, reservedAtEpochMillis, tradeId, null);
        }

        static ReservationSnapshot valid(
                String key,
                OrderAssetReservationSucceededEvent order,
                long reservedAtEpochMillis,
                String tradeId,
                String rawPayload) {
            return valid(key, order, reservedAtEpochMillis, tradeId, rawPayload, null);
        }

        static ReservationSnapshot valid(
                String key,
                OrderAssetReservationSucceededEvent order,
                long reservedAtEpochMillis,
                String tradeId,
                String rawPayload,
                String generationIdentity) {
            return new ReservationSnapshot(
                    key, order, reservedAtEpochMillis, tradeId, true, null,
                    rawPayload, generationIdentity);
        }

        static ReservationSnapshot invalid(String key, String invalidReason) {
            return invalid(key, invalidReason, null);
        }

        static ReservationSnapshot invalid(String key, String invalidReason, String rawPayload) {
            return invalid(key, invalidReason, rawPayload, null);
        }

        static ReservationSnapshot invalid(
                String key,
                String invalidReason,
                String rawPayload,
                String generationIdentity) {
            return new ReservationSnapshot(
                    key, null, 0L, null, false, invalidReason, rawPayload, generationIdentity);
        }
    }

}
