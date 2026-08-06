package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class IncomingOrderProcessingStore {

    enum Status {
        PROCESSING,
        COMPLETED
    }

    record State(Status status, String token, long processingStartedAtEpochMillis) {
        static State processing(String token, long startedAtEpochMillis) {
            return new State(Status.PROCESSING, token, startedAtEpochMillis);
        }

        static State completed() {
            return new State(Status.COMPLETED, null, 0L);
        }
    }

    record Claim(
            String stateHashKey,
            String orderIdField,
            String token,
            String completedBitmapKey,
            long completedBitOffset) {
    }

    private static final String STATE_HASH_PREFIX = "match:incoming-order:states:";
    private static final String COMPLETED_BITMAP_PREFIX = "match:incoming-order:completed:";
    private static final long COMPLETED_BITMAP_SHARD_SIZE = 10_000_000L;
    private static final int STATE_BUCKET_MASK = 0xff;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final String ORDER_PREFIX = "order:";
    private static final String RESERVATION_PREFIX = "order:reservation:";
    private static final DefaultRedisScript<Long> MARK_COMPLETED_SCRIPT = new DefaultRedisScript<>("""
            redis.call('SETBIT', KEYS[1], ARGV[1], 1)
            redis.call('HDEL', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    private final RedisTemplate<String, String> redisTemplate;

    public IncomingOrderProcessingStore(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    State state(UUID orderId) {
        Object value = redisTemplate.opsForHash().get(stateHashKey(orderId), orderId.toString());
        if (value == null) {
            return null;
        }
        String serialized = value.toString();
        if (Status.COMPLETED.name().equals(serialized)) {
            return State.completed();
        }
        String processingPrefix = Status.PROCESSING.name() + ":";
        if (serialized.startsWith(processingPrefix)) {
            int timestampSeparator = serialized.lastIndexOf(':');
            if (timestampSeparator <= processingPrefix.length()) {
                throw new IllegalStateException("Invalid incoming order processing state: " + serialized);
            }
            String token = serialized.substring(processingPrefix.length(), timestampSeparator);
            long startedAt = Long.parseLong(serialized.substring(timestampSeparator + 1));
            return State.processing(token, startedAt);
        }
        throw new IllegalStateException("Unknown incoming order processing state: " + serialized);
    }

    State state(OrderConfirmedEvent order) {
        if (isCompleted(order)) {
            return State.completed();
        }
        State legacyState = state(order.getOrderId());
        if (legacyState != null && legacyState.status() == Status.COMPLETED) {
            markCompleted(order);
        }
        return legacyState;
    }

    Claim newClaim(OrderConfirmedEvent order) {
        UUID orderId = order.getOrderId();
        String token = UUID.randomUUID().toString();
        return new Claim(
                stateHashKey(orderId),
                orderId.toString(),
                token,
                completedBitmapKey(order),
                completedBitOffset(order));
    }

    void replaceWithClaim(Claim claim) {
        redisTemplate.opsForHash().put(
                claim.stateHashKey(),
                claim.orderIdField(),
                serializedProcessingState(claim, System.currentTimeMillis()));
    }

    void markCompleted(OrderConfirmedEvent order) {
        redisTemplate.execute(
                MARK_COMPLETED_SCRIPT,
                List.of(completedBitmapKey(order), stateHashKey(order.getOrderId())),
                String.valueOf(completedBitOffset(order)),
                order.getOrderId().toString());
    }

    boolean isCompleted(OrderConfirmedEvent order) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().getBit(
                completedBitmapKey(order), completedBitOffset(order)));
    }

    boolean isVisible(UUID orderId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(ORDER_PREFIX + orderId));
    }

    boolean isReserved(UUID orderId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(RESERVATION_PREFIX + orderId));
    }

    private String stateHashKey(UUID orderId) {
        int bucket = orderId.hashCode() & STATE_BUCKET_MASK;
        return STATE_HASH_PREFIX + HEX[bucket >>> 4] + HEX[bucket & 0x0f];
    }

    private String completedBitmapKey(OrderConfirmedEvent order) {
        long sequence = requiredSequence(order);
        long shard = (sequence - 1) / COMPLETED_BITMAP_SHARD_SIZE;
        return COMPLETED_BITMAP_PREFIX + order.getMarketId() + ":" + shard;
    }

    private long completedBitOffset(OrderConfirmedEvent order) {
        long sequence = requiredSequence(order);
        return (sequence - 1) % COMPLETED_BITMAP_SHARD_SIZE;
    }

    private long requiredSequence(OrderConfirmedEvent order) {
        if (order == null || order.getMarketId() == null || order.getMarketId().isBlank()) {
            throw new IllegalArgumentException("OrderConfirmedEvent must contain marketId");
        }
        if (order.getMarketSequence() == null || order.getMarketSequence() <= 0) {
            throw new IllegalArgumentException("OrderConfirmedEvent marketSequence must be positive");
        }
        return order.getMarketSequence();
    }

    private String serializedProcessingState(Claim claim, long startedAtEpochMillis) {
        return Status.PROCESSING.name() + ":" + claim.token() + ":" + startedAtEpochMillis;
    }
}
