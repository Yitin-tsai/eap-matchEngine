package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderCancelEvent;
import com.eap.common.event.OrderConfirmedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import jakarta.annotation.PostConstruct;

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
    private static final String RESERVATION_EXISTS_PREFIX = "__RESERVATION_EXISTS__:";
    private static final String RESERVATION_KEY_PATTERN = "order:reservation:*";
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    // Lua scripts loaded from classpath
    private String addOrderLuaScript;
    private String reserveMatchOrderBuyLuaScript;
    private String reserveMatchOrderSellLuaScript;
    private String reserveOrAddOrderBuyLuaScript;
    private String reserveOrAddOrderSellLuaScript;
    private String releaseReservedOrderLuaScript;
    private String completeReservedOrderLuaScript;
    private String removeOrderLuaScript;
    private String addOrderLuaSha;
    private String reserveMatchOrderBuyLuaSha;
    private String reserveMatchOrderSellLuaSha;
    private String reserveOrAddOrderBuyLuaSha;
    private String reserveOrAddOrderSellLuaSha;
    private String releaseReservedOrderLuaSha;
    private String completeReservedOrderLuaSha;
    private String removeOrderLuaSha;

    public RedisOrderBookService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
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
            addOrderLuaSha = loadLuaScriptSha(addOrderLuaScript);
            reserveMatchOrderBuyLuaSha = loadLuaScriptSha(reserveMatchOrderBuyLuaScript);
            reserveMatchOrderSellLuaSha = loadLuaScriptSha(reserveMatchOrderSellLuaScript);
            reserveOrAddOrderBuyLuaSha = loadLuaScriptSha(reserveOrAddOrderBuyLuaScript);
            reserveOrAddOrderSellLuaSha = loadLuaScriptSha(reserveOrAddOrderSellLuaScript);
            releaseReservedOrderLuaSha = loadLuaScriptSha(releaseReservedOrderLuaScript);
            completeReservedOrderLuaSha = loadLuaScriptSha(completeReservedOrderLuaScript);
            removeOrderLuaSha = loadLuaScriptSha(removeOrderLuaScript);
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
    public void addOrder(OrderConfirmedEvent event) throws JsonProcessingException {
        String orderbookKey = orderbookKey(event);
        String orderIdKey = "order:" + event.getOrderId();
        String userOrdersKey = "user:" + event.getUserId() + ":orders";
        String orderJson = objectMapper.writeValueAsString(event);
        double orderScore = scoreFor(event);

        List<String> keys = List.of(orderbookKey, orderIdKey, userOrdersKey);
        List<String> args = List.of(
            event.getOrderId().toString(),
            String.valueOf(orderScore),
            orderJson
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
    public void removeOrder(OrderConfirmedEvent event) {
        String orderbookKey = orderbookKey(event);
        String orderIdKey = "order:" + event.getOrderId();
        String userOrdersKey = "user:" + event.getUserId() + ":orders";

        List<String> keys = List.of(orderbookKey, orderIdKey, userOrdersKey);
        List<String> args = List.of(event.getOrderId().toString());

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
    public void unlinkUserOrder(OrderConfirmedEvent event) {
        String userOrdersKey = "user:" + event.getUserId() + ":orders";
        Long removed = redisTemplate.opsForSet().remove(userOrdersKey, event.getOrderId().toString());
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
    public OrderConfirmedEvent reserveBestMatchOrderLua(OrderConfirmedEvent incomingOrder) {
        boolean isBuy = incomingOrder.getOrderType().equalsIgnoreCase("BUY");
        String orderbookKey = isBuy
                ? orderbookKey(marketId(incomingOrder), "sell")
                : orderbookKey(marketId(incomingOrder), "buy");
        String luaScript = isBuy ? reserveMatchOrderBuyLuaScript : reserveMatchOrderSellLuaScript;
        double priceBoundary = isBuy
                ? maxSellScore(incomingOrder.getPrice())
                : minBuyScore(incomingOrder.getPrice());

        List<String> keys = List.of(orderbookKey);
        List<String> args = List.of(
                String.valueOf(priceBoundary),
                String.valueOf(Instant.now().toEpochMilli()));

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
        if (orderJson.startsWith(MISSING_ORDER_DETAIL_PREFIX)) {
            String missingOrderId = orderJson.substring(MISSING_ORDER_DETAIL_PREFIX.length());
            log.error("Redis orderbook is inconsistent: orderbook entry {} exists but order detail is missing",
                    missingOrderId);
            throw new IllegalStateException("Redis orderbook detail missing for order " + missingOrderId);
        }
        if (orderJson.startsWith(RESERVATION_EXISTS_PREFIX)) {
            String orderId = orderJson.substring(RESERVATION_EXISTS_PREFIX.length());
            log.error("Redis orderbook is inconsistent: order {} is visible but already reserved", orderId);
            throw new IllegalStateException("Redis order already reserved for order " + orderId);
        }

        try {
            OrderConfirmedEvent reservedOrder = objectMapper.readValue(orderJson, OrderConfirmedEvent.class);
            log.debug("Successfully reserved order {} for matching", reservedOrder.getOrderId());
            return reservedOrder;
        } catch (Exception e) {
            log.error("Failed to deserialize reserved order", e);
            throw new IllegalStateException("Failed to deserialize reserved Redis order", e);
        }
    }

    /**
     * Atomically reserves the best matching resting order and generates the match sequence.
     *
     * The match ID is generated inside the same Redis Lua script after the reservation is
     * created. No-match orders do not consume a sequence value.
     */
    public ReservedMatch reserveBestMatchOrderWithSequenceLua(OrderConfirmedEvent incomingOrder) {
        boolean isBuy = incomingOrder.getOrderType().equalsIgnoreCase("BUY");
        String orderbookKey = isBuy
                ? orderbookKey(marketId(incomingOrder), "sell")
                : orderbookKey(marketId(incomingOrder), "buy");
        String luaScript = isBuy ? reserveMatchOrderBuyLuaScript : reserveMatchOrderSellLuaScript;
        double priceBoundary = isBuy
                ? maxSellScore(incomingOrder.getPrice())
                : minBuyScore(incomingOrder.getPrice());

        List<String> keys = List.of(orderbookKey, MATCH_ID_KEY);
        List<String> args = List.of(
                String.valueOf(priceBoundary),
                String.valueOf(Instant.now().toEpochMilli()));

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
        if (orderJson.startsWith(MISSING_ORDER_DETAIL_PREFIX)) {
            String missingOrderId = orderJson.substring(MISSING_ORDER_DETAIL_PREFIX.length());
            log.error("Redis orderbook is inconsistent: orderbook entry {} exists but order detail is missing",
                    missingOrderId);
            throw new IllegalStateException("Redis orderbook detail missing for order " + missingOrderId);
        }
        if (orderJson.startsWith(RESERVATION_EXISTS_PREFIX)) {
            String orderId = orderJson.substring(RESERVATION_EXISTS_PREFIX.length());
            log.error("Redis orderbook is inconsistent: order {} is visible but already reserved", orderId);
            throw new IllegalStateException("Redis order already reserved for order " + orderId);
        }
        if (rawResult.size() < 2) {
            throw new IllegalStateException("Redis reserve script did not return a match sequence");
        }

        try {
            OrderConfirmedEvent reservedOrder = objectMapper.readValue(orderJson, OrderConfirmedEvent.class);
            Long matchId = Long.valueOf(new String(rawResult.get(1), StandardCharsets.UTF_8));
            log.debug("Successfully reserved order {} for matching with matchId={}",
                    reservedOrder.getOrderId(), matchId);
            return new ReservedMatch(reservedOrder, matchId);
        } catch (Exception e) {
            log.error("Failed to deserialize reserved order or match sequence", e);
            throw new IllegalStateException("Failed to deserialize reserved Redis order with sequence", e);
        }
    }

    /**
     * Atomically reserves the best matching resting order, or adds the incoming order to the
     * visible orderbook when no match exists.
     *
     * This removes the no-match hot-path round trip where Java first ran a reserve Lua script,
     * observed null, and then ran add_order.lua.
     */
    public MatchOrAddResult reserveBestMatchOrAddOrderWithSequenceLua(OrderConfirmedEvent incomingOrder) {
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

        String incomingOrderJson;
        try {
            incomingOrderJson = objectMapper.writeValueAsString(incomingOrder);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize incoming Redis order", e);
        }

        List<String> keys = List.of(
                oppositeOrderbookKey,
                ownOrderbookKey,
                incomingOrderIdKey,
                incomingUserOrdersKey,
                MATCH_ID_KEY);
        List<String> args = List.of(
                String.valueOf(priceBoundary),
                String.valueOf(Instant.now().toEpochMilli()),
                incomingOrder.getOrderId().toString(),
                String.valueOf(scoreFor(incomingOrder)),
                incomingOrderJson);

        @SuppressWarnings("unchecked")
        List<byte[]> rawResult = redisTemplate.execute((RedisCallback<List<byte[]>>) connection -> {
            byte[][] keysBytes = keys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] argsBytes = args.stream().map(a -> a.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] allParams = new byte[keysBytes.length + argsBytes.length][];
            System.arraycopy(keysBytes, 0, allParams, 0, keysBytes.length);
            System.arraycopy(argsBytes, 0, allParams, keysBytes.length, argsBytes.length);

            Object res = evalLoadedScript(
                    connection,
                    scriptSha,
                    luaScript,
                    ReturnType.MULTI,
                    keys.size(),
                    allParams
            );
            return (List<byte[]>) res;
        });

        if (rawResult == null || rawResult.isEmpty()) {
            throw new IllegalStateException("Redis reserve-or-add script returned no result");
        }

        String status = new String(rawResult.get(0), StandardCharsets.UTF_8);
        if ("__ADDED__".equals(status)) {
            log.debug("No matching order found; added incoming order {} to orderbook", incomingOrder.getOrderId());
            return MatchOrAddResult.added();
        }
        if (status.startsWith(MISSING_ORDER_DETAIL_PREFIX)) {
            String missingOrderId = status.substring(MISSING_ORDER_DETAIL_PREFIX.length());
            log.error("Redis orderbook is inconsistent: orderbook entry {} exists but order detail is missing",
                    missingOrderId);
            throw new IllegalStateException("Redis orderbook detail missing for order " + missingOrderId);
        }
        if (status.startsWith(RESERVATION_EXISTS_PREFIX)) {
            String orderId = status.substring(RESERVATION_EXISTS_PREFIX.length());
            log.error("Redis orderbook is inconsistent: order {} is visible but already reserved", orderId);
            throw new IllegalStateException("Redis order already reserved for order " + orderId);
        }
        if (!"__MATCH__".equals(status)) {
            throw new IllegalStateException("Redis reserve-or-add script returned unknown status " + status);
        }
        if (rawResult.size() < 3) {
            throw new IllegalStateException("Redis reserve-or-add script did not return reserved order and sequence");
        }

        try {
            String orderJson = new String(rawResult.get(1), StandardCharsets.UTF_8);
            OrderConfirmedEvent reservedOrder = objectMapper.readValue(orderJson, OrderConfirmedEvent.class);
            Long matchId = Long.valueOf(new String(rawResult.get(2), StandardCharsets.UTF_8));
            log.debug("Successfully reserved order {} for matching with matchId={}",
                    reservedOrder.getOrderId(), matchId);
            return MatchOrAddResult.matched(new ReservedMatch(reservedOrder, matchId));
        } catch (Exception e) {
            log.error("Failed to deserialize reserve-or-add result", e);
            throw new IllegalStateException("Failed to deserialize reserve-or-add Redis result", e);
        }
    }

    public record ReservedMatch(OrderConfirmedEvent order, Long matchId) {
    }

    public record MatchOrAddResult(boolean orderAdded, ReservedMatch reservedMatch) {
        public static MatchOrAddResult added() {
            return new MatchOrAddResult(true, null);
        }

        public static MatchOrAddResult matched(ReservedMatch reservedMatch) {
            return new MatchOrAddResult(false, reservedMatch);
        }
    }

    /**
     * Releases a reserved order back to the visible orderbook.
     */
    public void releaseReservedOrder(OrderConfirmedEvent event) throws JsonProcessingException {
        String orderbookKey = orderbookKey(event);
        String orderIdKey = "order:" + event.getOrderId();
        String userOrdersKey = "user:" + event.getUserId() + ":orders";
        String reservationKey = reservationKey(event);
        String orderJson = objectMapper.writeValueAsString(event);
        double orderScore = scoreFor(event);

        List<String> keys = List.of(orderbookKey, orderIdKey, userOrdersKey, reservationKey);
        List<String> args = List.of(
                event.getOrderId().toString(),
                String.valueOf(orderScore),
                orderJson
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

        if (result != null && result == 1L) {
            log.debug("Successfully released reserved order {} back to orderbook", event.getOrderId());
        } else {
            log.error("Failed to release reserved order {} back to orderbook, result={}", event.getOrderId(), result);
            throw new RuntimeException("Failed to release reserved order to Redis");
        }
    }

    /**
     * Completes a reserved order after its corresponding TradeExecuted fact is durable.
     */
    public void completeReservedOrder(OrderConfirmedEvent event) {
        String orderIdKey = "order:" + event.getOrderId();
        String userOrdersKey = "user:" + event.getUserId() + ":orders";
        String reservationKey = reservationKey(event);

        List<String> keys = List.of(orderIdKey, userOrdersKey, reservationKey);
        List<String> args = List.of(event.getOrderId().toString());

        Long result = redisTemplate.execute((RedisCallback<Long>) connection -> {
            byte[][] keysBytes = keys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] argsBytes = args.stream().map(a -> a.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] allParams = new byte[keysBytes.length + argsBytes.length][];
            System.arraycopy(keysBytes, 0, allParams, 0, keysBytes.length);
            System.arraycopy(argsBytes, 0, allParams, keysBytes.length, argsBytes.length);

            Object res = evalLoadedScript(
                    connection,
                    completeReservedOrderLuaSha,
                    completeReservedOrderLuaScript,
                    ReturnType.INTEGER,
                    keys.size(),
                    allParams
            );
            return res != null ? (Long) res : 0L;
        });

        if (result != null && result == 1L) {
            log.debug("Successfully completed reserved order {}", event.getOrderId());
        } else {
            log.warn("Reserved order {} had no matching Redis reservation to complete, result={}",
                    event.getOrderId(), result);
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

    public List<ReservationSnapshot> scanReservations(int limit) {
        List<String> keys = redisTemplate.execute((RedisCallback<List<String>>) connection -> {
            List<String> scanned = new ArrayList<>();
            ScanOptions options = ScanOptions.scanOptions()
                    .match(RESERVATION_KEY_PATTERN)
                    .count(Math.max(limit, 1))
                    .build();
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext() && scanned.size() < limit) {
                    scanned.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            } catch (RuntimeException e) {
                throw new IllegalStateException("Failed to scan Redis reservations", e);
            }
            return scanned;
        });
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<ReservationSnapshot> snapshots = new ArrayList<>(keys.size());
        for (String key : keys) {
            String value = redisTemplate.opsForValue().get(key);
            ReservationSnapshot snapshot = parseReservationSnapshot(key, value);
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
        }
        return snapshots;
    }

    private ReservationSnapshot parseReservationSnapshot(String key, String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            return ReservationSnapshot.invalid(key, "missing reservation value");
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            if (root.has("order")) {
                OrderConfirmedEvent order = objectMapper.treeToValue(root.get("order"), OrderConfirmedEvent.class);
                long reservedAtEpochMillis = root.path("reservedAtEpochMillis").asLong(0L);
                return ReservationSnapshot.valid(key, order, reservedAtEpochMillis);
            }

            // Backward compatibility for pre-TPS-59 reservation values that stored only order JSON.
            OrderConfirmedEvent order = objectMapper.treeToValue(root, OrderConfirmedEvent.class);
            return ReservationSnapshot.valid(key, order, 0L);
        } catch (Exception e) {
            return ReservationSnapshot.invalid(key, e.getMessage());
        }
    }

    /**
     * Atomically cancels an order.
     * First retrieves order details, then uses Lua script to remove atomically.
     *
     * @param event The order cancel event
     * @return true if order was cancelled, false if not found
     */
    public boolean cancelOrder(OrderCancelEvent event) {
        String orderIdKey = "order:" + event.getOrderId();

        // First get the order to know which orderbook to remove from
        String orderJson = redisTemplate.opsForValue().get(orderIdKey);
        if (orderJson == null) {
            log.warn("Order {} not found for cancellation", event.getOrderId());
            return false;
        }

        try {
            OrderConfirmedEvent order = objectMapper.readValue(orderJson, OrderConfirmedEvent.class);

            // Use Lua script to atomically remove
            String orderbookKey = orderbookKey(order);
            String userOrdersKey = "user:" + order.getUserId() + ":orders";

            List<String> keys = List.of(orderbookKey, orderIdKey, userOrdersKey);
            List<String> args = List.of(event.getOrderId().toString());

            Long result = redisTemplate.execute((RedisCallback<Long>) connection -> {
                // Flatten keys and args into single byte[] varargs array
                byte[][] keysBytes = keys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
                byte[][] argsBytes = args.stream().map(a -> a.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);

                // Combine keys and args into single varargs array
                byte[][] allParams = new byte[keysBytes.length + argsBytes.length][];
                System.arraycopy(keysBytes, 0, allParams, 0, keysBytes.length);
                System.arraycopy(argsBytes, 0, allParams, keysBytes.length, argsBytes.length);

                Object res = connection.eval(
                    removeOrderLuaScript.getBytes(StandardCharsets.UTF_8),
                    ReturnType.INTEGER,
                    keys.size(),
                    allParams
                );
                return res != null ? (Long) res : 0L;
            });

            boolean removed = result != null && result == 1L;
            if (removed) {
                log.info("Successfully cancelled order {}", event.getOrderId());
            } else {
                log.warn("Order {} was not in orderbook (might have been matched)", event.getOrderId());
            }
            return removed;
        } catch (Exception e) {
            log.error("Failed to cancel order {}", event.getOrderId(), e);
            return false;
        }
    }

    /**
     * Retrieves all orders for a specific user
     *
     * @param userId The user ID
     * @return List of orders for the user
     */
    public List<OrderConfirmedEvent> getOrderByUserId(UUID userId) {
        String userOrdersKey = "user:" + userId + ":orders";
        Set<String> orderIds = redisTemplate.opsForSet().members(userOrdersKey);
        if (orderIds == null || orderIds.isEmpty()) {
            return List.of();
        }
        return orderIds.stream()
                .map(orderId -> {
                    String orderJson = redisTemplate.opsForValue().get("order:" + orderId);
                    if (orderJson != null) {
                        try {
                            return objectMapper.readValue(orderJson, OrderConfirmedEvent.class);
                        } catch (Exception e) {
                            log.error("Failed to deserialize order {}", orderId, e);
                        }
                    }
                    return null;
                })
                .filter(o -> o != null)
                .collect(Collectors.toList());
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
    public List<OrderConfirmedEvent> getMatchableOrders(OrderConfirmedEvent incomingOrder) {
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
            return List.of();
        }

        return results.stream()
                .map(orderIdStr -> {
                    try {
                        String orderJson = redisTemplate.opsForValue().get("order:" + orderIdStr);
                        if (orderJson != null) {
                            return objectMapper.readValue(orderJson, OrderConfirmedEvent.class);
                        }
                    } catch (Exception e) {
                        log.error("Failed to deserialize order {}", orderIdStr, e);
                    }
                    return null;
                })
                .filter(event -> event != null)
                .collect(Collectors.toList());
    }

    private String orderbookKey(OrderConfirmedEvent event) {
        String side = event.getOrderType().equalsIgnoreCase("BUY") ? "buy" : "sell";
        return orderbookKey(marketId(event), side);
    }

    private String orderbookKey(String marketId, String side) {
        return "orderbook:" + marketId + ":" + side;
    }

    private String reservationKey(OrderConfirmedEvent event) {
        return "order:reservation:" + event.getOrderId();
    }

    private String marketId(OrderConfirmedEvent event) {
        return event.getMarketId() == null || event.getMarketId().isBlank()
                ? DEFAULT_MARKET_ID
                : event.getMarketId();
    }

    private double scoreFor(OrderConfirmedEvent event) {
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

    public record ReservationSnapshot(
            String key,
            OrderConfirmedEvent order,
            long reservedAtEpochMillis,
            boolean valid,
            String invalidReason) {

        static ReservationSnapshot valid(String key, OrderConfirmedEvent order, long reservedAtEpochMillis) {
            return new ReservationSnapshot(key, order, reservedAtEpochMillis, true, null);
        }

        static ReservationSnapshot invalid(String key, String invalidReason) {
            return new ReservationSnapshot(key, null, 0L, false, invalidReason);
        }
    }
}
