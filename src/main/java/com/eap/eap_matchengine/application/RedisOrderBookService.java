package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderCancelEvent;
import com.eap.common.event.OrderConfirmedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
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

    private final String BUY_ORDERBOOK_KEY = "orderbook:buy";
    private final String SELL_ORDERBOOK_KEY = "orderbook:sell";
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    // Lua scripts loaded from classpath
    private String addOrderLuaScript;
    private String getAndRemoveMatchOrderBuyLuaScript;
    private String getAndRemoveMatchOrderSellLuaScript;
    private String removeOrderLuaScript;

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
            getAndRemoveMatchOrderBuyLuaScript = loadLuaScript("lua/get_and_remove_match_order_buy.lua");
            getAndRemoveMatchOrderSellLuaScript = loadLuaScript("lua/get_and_remove_match_order_sell.lua");
            removeOrderLuaScript = loadLuaScript("lua/remove_order.lua");
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
        String orderbookKey = event.getOrderType().equalsIgnoreCase("BUY") ? BUY_ORDERBOOK_KEY : SELL_ORDERBOOK_KEY;
        String orderIdKey = "order:" + event.getOrderId();
        String userOrdersKey = "user:" + event.getUserId() + ":orders";
        String orderJson = objectMapper.writeValueAsString(event);

        List<String> keys = List.of(orderbookKey, orderIdKey, userOrdersKey);
        List<String> args = List.of(
            event.getOrderId().toString(),
            String.valueOf(event.getPrice()),
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

            Object res = connection.eval(
                addOrderLuaScript.getBytes(StandardCharsets.UTF_8),
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
        String orderbookKey = event.getOrderType().equalsIgnoreCase("BUY") ? BUY_ORDERBOOK_KEY : SELL_ORDERBOOK_KEY;
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

            Object res = connection.eval(
                removeOrderLuaScript.getBytes(StandardCharsets.UTF_8),
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
            String orderbookKey = order.getOrderType().equalsIgnoreCase("BUY") ? BUY_ORDERBOOK_KEY : SELL_ORDERBOOK_KEY;
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
     * Atomically finds the best matching order and removes it from orderbook.
     * Uses Lua script to ensure complete atomicity:
     * 1. Find best match by price
     * 2. Remove from orderbook ZSet
     * 3. Get order details
     * 4. Delete order details key
     *
     * This prevents race conditions where order is removed but details are not available.
     *
     * @param isBuy whether the incoming order is a buy order
     * @param price the price limit for matching
     * @return the matched order, or null if no match found
     */
    public OrderConfirmedEvent getAndRemoveBestMatchOrderLua(boolean isBuy, int price) {
        String orderbookKey = isBuy ? SELL_ORDERBOOK_KEY : BUY_ORDERBOOK_KEY;
        String luaScript = isBuy ? getAndRemoveMatchOrderBuyLuaScript : getAndRemoveMatchOrderSellLuaScript;

        List<String> keys = List.of(orderbookKey);
        List<String> args = List.of(String.valueOf(price));

        String orderJson = redisTemplate.execute((RedisCallback<String>) connection -> {
            // Flatten keys and args into single byte[] varargs array
            byte[][] keysBytes = keys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] argsBytes = args.stream().map(a -> a.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);

            // Combine keys and args into single varargs array
            byte[][] allParams = new byte[keysBytes.length + argsBytes.length][];
            System.arraycopy(keysBytes, 0, allParams, 0, keysBytes.length);
            System.arraycopy(argsBytes, 0, allParams, keysBytes.length, argsBytes.length);

            Object res = connection.eval(
                luaScript.getBytes(StandardCharsets.UTF_8),
                ReturnType.VALUE,
                keys.size(),
                allParams
            );
            return res != null ? new String((byte[]) res, StandardCharsets.UTF_8) : null;
        });

        if (orderJson == null) {
            log.debug("No matching order found for price {}, isBuy={}", price, isBuy);
            return null;
        }

        try {
            OrderConfirmedEvent matchedOrder = objectMapper.readValue(orderJson, OrderConfirmedEvent.class);
            log.debug("Successfully matched and removed order {} atomically", matchedOrder.getOrderId());
            return matchedOrder;
        } catch (Exception e) {
            log.error("Failed to deserialize matched order", e);
            return null;
        }
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
        String oppositeKey = isBuy ? SELL_ORDERBOOK_KEY : BUY_ORDERBOOK_KEY;

        Set<String> results;
        if (isBuy) {
            // Find sell orders with price <= buy price
            results = redisTemplate.opsForZSet().rangeByScore(oppositeKey, 0, incomingOrder.getPrice());
        } else {
            // Find buy orders with price >= sell price
            results = redisTemplate.opsForZSet().reverseRangeByScore(oppositeKey, incomingOrder.getPrice(), Double.POSITIVE_INFINITY);
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
}
