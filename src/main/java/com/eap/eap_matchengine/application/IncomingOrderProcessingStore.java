package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderAssetReservationSucceededEvent;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.beans.factory.annotation.Autowired;
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
            local expected_run_id = string.match(ARGV[3], '|([^|]+)$')
            local actual_run_id = string.match(redis.call('INFO', 'server'), 'run_id:([^\\r\\n]+)')
            if redis.call('GET', KEYS[3]) ~= ARGV[3] or actual_run_id ~= expected_run_id then
                return -1
            end
            redis.call('SETBIT', KEYS[1], ARGV[1], 1)
            redis.call('HDEL', KEYS[2], ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> LEGACY_MARK_COMPLETED_SCRIPT = new DefaultRedisScript<>("""
            redis.call('SETBIT', KEYS[1], ARGV[1], 1)
            redis.call('HDEL', KEYS[2], ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REPLACE_WITH_CLAIM_SCRIPT = new DefaultRedisScript<>("""
            local expected_run_id = string.match(ARGV[3], '|([^|]+)$')
            local actual_run_id = string.match(redis.call('INFO', 'server'), 'run_id:([^\\r\\n]+)')
            if redis.call('GET', KEYS[2]) ~= ARGV[3] or actual_run_id ~= expected_run_id then
                return -1
            end
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            return 1
            """, Long.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final OrderBookRuntimeGuard runtimeGuard;

    public IncomingOrderProcessingStore(RedisTemplate<String, String> redisTemplate) {
        this(redisTemplate, null);
    }

    @Autowired
    public IncomingOrderProcessingStore(
            RedisTemplate<String, String> redisTemplate,
            OrderBookRuntimeGuard runtimeGuard) {
        this.redisTemplate = redisTemplate;
        this.runtimeGuard = runtimeGuard;
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

    State state(OrderAssetReservationSucceededEvent order) {
        if (isCompleted(order)) {
            return State.completed();
        }
        State legacyState = state(order.getOrderId());
        if (legacyState != null && legacyState.status() == Status.COMPLETED) {
            markCompleted(order);
        }
        return legacyState;
    }

    Claim newClaim(OrderAssetReservationSucceededEvent order) {
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
        if (runtimeGuard == null) {
            redisTemplate.opsForHash().put(
                    claim.stateHashKey(),
                    claim.orderIdField(),
                    serializedProcessingState(claim, System.currentTimeMillis()));
            return;
        }
        OrderBookRuntimeGuard.Snapshot runtime = runtimeGuard.requireReady();
        Long result = redisTemplate.execute(
                REPLACE_WITH_CLAIM_SCRIPT,
                List.of(claim.stateHashKey(), OrderBookRuntimeGuard.SENTINEL_KEY),
                claim.orderIdField(),
                serializedProcessingState(claim, System.currentTimeMillis()),
                runtime.expectedSentinel());
        requireFenceAccepted(result, "replace incoming-order processing claim");
    }

    void markCompleted(OrderAssetReservationSucceededEvent order) {
        if (runtimeGuard == null) {
            redisTemplate.execute(
                    LEGACY_MARK_COMPLETED_SCRIPT,
                    List.of(completedBitmapKey(order), stateHashKey(order.getOrderId())),
                    String.valueOf(completedBitOffset(order)),
                    order.getOrderId().toString());
            return;
        }
        OrderBookRuntimeGuard.Snapshot runtime = runtimeGuard.requireReady();
        Long result = redisTemplate.execute(
                MARK_COMPLETED_SCRIPT,
                List.of(
                        completedBitmapKey(order),
                        stateHashKey(order.getOrderId()),
                        OrderBookRuntimeGuard.SENTINEL_KEY),
                String.valueOf(completedBitOffset(order)),
                order.getOrderId().toString(),
                runtime.expectedSentinel());
        requireFenceAccepted(result, "complete incoming-order processing claim");
    }

    boolean isCompleted(OrderAssetReservationSucceededEvent order) {
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

    private String completedBitmapKey(OrderAssetReservationSucceededEvent order) {
        long sequence = requiredSequence(order);
        long shard = (sequence - 1) / COMPLETED_BITMAP_SHARD_SIZE;
        return COMPLETED_BITMAP_PREFIX + order.getMarketId() + ":" + shard;
    }

    private long completedBitOffset(OrderAssetReservationSucceededEvent order) {
        long sequence = requiredSequence(order);
        return (sequence - 1) % COMPLETED_BITMAP_SHARD_SIZE;
    }

    private long requiredSequence(OrderAssetReservationSucceededEvent order) {
        if (order == null || order.getMarketId() == null || order.getMarketId().isBlank()) {
            throw new IllegalArgumentException("OrderAssetReservationSucceededEvent must contain marketId");
        }
        if (order.getMarketSequence() == null || order.getMarketSequence() <= 0) {
            throw new IllegalArgumentException("OrderAssetReservationSucceededEvent marketSequence must be positive");
        }
        return order.getMarketSequence();
    }

    private String serializedProcessingState(Claim claim, long startedAtEpochMillis) {
        return Status.PROCESSING.name() + ":" + claim.token() + ":" + startedAtEpochMillis;
    }

    private void requireFenceAccepted(Long result, String operation) {
        if (!Long.valueOf(1L).equals(result)) {
            if (runtimeGuard != null) {
                runtimeGuard.refresh();
            }
            throw new OrderBookRuntimeUnavailableException(
                    "CDA order-book generation rejected operation: " + operation);
        }
    }
}
