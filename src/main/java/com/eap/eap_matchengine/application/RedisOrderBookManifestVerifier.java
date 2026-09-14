package com.eap.eap_matchengine.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class RedisOrderBookManifestVerifier {

    private static final long SCORE_FACTOR = 1_000_000_000L;
    private static final String ORDERBOOK_PREFIX = "orderbook:";
    private static final String USER_PREFIX = "user:";
    private static final String USER_SUFFIX = ":orders";
    private static final String COMPLETED_BITMAP_PREFIX = "match:incoming-order:completed:";
    private static final long COMPLETED_BITMAP_SHARD_SIZE = 10_000_000L;
    private static final Pattern ORDER_DETAIL_KEY =
            Pattern.compile("order:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final List<String> CDA_KEY_PATTERNS = List.of(
            "orderbook:*",
            "order:*",
            "user:*:orders",
            "match:incoming-order:*",
            "match:id:sequence",
            "lock:incoming-order:*");

    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;
    private final boolean userOpenOrderIndexEnabled;

    public RedisOrderBookManifestVerifier(
            RedisTemplate<String, String> redis,
            ObjectMapper objectMapper,
            @Value("${eap.match-engine.orderbook.user-open-order-index-enabled:true}")
            boolean userOpenOrderIndexEnabled) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.userOpenOrderIndexEnabled = userOpenOrderIndexEnabled;
    }

    public long cdaKeyCountExcludingControl() {
        Set<String> keys = new HashSet<>();
        for (String pattern : CDA_KEY_PATTERNS) {
            keys.addAll(scan(pattern));
        }
        keys.remove(OrderBookRuntimeGuard.SENTINEL_KEY);
        return keys.size();
    }

    public Manifest inspect(Map<UUID, UUID> expectedPendingCancellations) {
        return inspect(expectedPendingCancellations, List.of(), List.of());
    }

    public Manifest inspect(
            Map<UUID, UUID> expectedPendingCancellations,
            List<OrderBookRuntimeControlStore.CompletedAdmission> expectedCompletedAdmissions) {
        return inspect(expectedPendingCancellations, expectedCompletedAdmissions, List.of());
    }

    public Manifest inspect(
            Map<UUID, UUID> expectedPendingCancellations,
            List<OrderBookRuntimeControlStore.CompletedAdmission> expectedCompletedAdmissions,
            List<OrderBookRuntimeControlStore.CancellationFact> cancellationFacts) {
        Set<String> detailKeys = scan("order:*");
        detailKeys.removeIf(key -> !ORDER_DETAIL_KEY.matcher(key).matches());

        Set<String> visibleIds = new HashSet<>();
        Map<String, Set<String>> expectedUserIndex = new HashMap<>();
        List<String> identities = new ArrayList<>(detailKeys.size());
        long openQuantity = 0L;
        for (String orderBookKey : scan("orderbook:*")) {
            BookIdentity book = parseOrderBookKey(orderBookKey);
            Set<ZSetOperations.TypedTuple<String>> members =
                    redis.opsForZSet().rangeWithScores(orderBookKey, 0, -1);
            if (members == null) {
                continue;
            }
            for (ZSetOperations.TypedTuple<String> tuple : members) {
                String member = tuple.getValue();
                if (member == null || tuple.getScore() == null) {
                    throw new IllegalStateException("Redis order book contains a null member/score: " + orderBookKey);
                }
                UUID memberId = parseUuid(member, "order-book member");
                if (!visibleIds.add(member)) {
                    throw new IllegalStateException(
                            "Order is present in more than one Redis order book: " + member);
                }
                String detailKey = "order:" + member;
                if (!detailKeys.contains(detailKey)) {
                    throw new IllegalStateException("Redis order-book member has no exact detail key: " + member);
                }
                JsonNode root = parseDetail(detailKey);
                String detailOrderId = requiredText(root, "i", "orderId");
                if (!memberId.equals(parseUuid(detailOrderId, "detail orderId"))) {
                    throw new IllegalStateException("Redis order-book member/detail identity mismatch: member="
                            + member + ", detailOrderId=" + detailOrderId);
                }
                String marketId = requiredText(root, "m", "marketId");
                String orderType = requiredText(root, "t", "orderType").toUpperCase(Locale.ROOT);
                if (!book.marketId().equals(marketId) || !book.side().equals(orderType.toLowerCase())) {
                    throw new IllegalStateException("Redis order is indexed under the wrong market/side: orderId="
                            + member + ", key=" + orderBookKey + ", detail=" + marketId + "/" + orderType);
                }
                long marketSequence = requiredLong(root, "s", "marketSequence");
                long price = requiredLong(root, "p", "price");
                long expectedScore = scoreFor(orderType, price, marketSequence);
                if (Double.compare(tuple.getScore(), (double) expectedScore) != 0) {
                    throw new IllegalStateException("Redis order score mismatch: orderId=" + member
                            + ", expected=" + expectedScore + ", actual=" + tuple.getScore());
                }
                String userId = parseUuid(requiredText(root, "u", "userId"), "detail userId").toString();
                long amount = requiredLong(root, "a", "amount");
                if (amount <= 0) {
                    throw new IllegalStateException("Redis open order amount must be positive: " + member);
                }
                openQuantity = Math.addExact(openQuantity, amount);
                expectedUserIndex.computeIfAbsent(userId, ignored -> new HashSet<>()).add(member);
                identities.add(String.join("|",
                        marketId,
                        Long.toString(marketSequence),
                        member,
                        userId,
                        orderType,
                        Long.toString(price),
                        Long.toString(amount),
                        optionalText(root, "c", "createdAt")));
            }
        }

        Set<String> visibleDetailKeys = visibleIds.stream()
                .map(id -> "order:" + id)
                .collect(java.util.stream.Collectors.toSet());
        if (!visibleDetailKeys.equals(detailKeys)) {
            Set<String> unindexedDetails = new HashSet<>(detailKeys);
            unindexedDetails.removeAll(visibleDetailKeys);
            throw new IllegalStateException("Redis order-book/detail mismatch: unindexedDetails="
                    + unindexedDetails);
        }
        verifyUserIndex(expectedUserIndex);

        long reservations = scan("order:reservation:*").size();
        long processingClaims = scan("match:incoming-order:states:*").stream()
                .mapToLong(key -> {
                    Long size = redis.opsForHash().size(key);
                    return size == null ? 0L : size;
                })
                .sum();
        long cancellationIntentCount =
                verifyCancellationIntents(expectedPendingCancellations, cancellationFacts);
        long cancellationMarkerCount = verifyCancellationMarkers(cancellationFacts, visibleIds);
        long durablePendingCancellationCount = cancellationFacts.stream()
                .filter(fact -> "PENDING".equals(fact.status()) || "IN_PROGRESS".equals(fact.status()))
                .count();
        long completedAdmissionCount = verifyCompletedAdmissionBitmaps(expectedCompletedAdmissions);

        String sequenceValue = redis.opsForValue().get("match:id:sequence");
        long matchSequence = sequenceValue == null ? 0L : Long.parseLong(sequenceValue);
        identities.sort(Comparator.naturalOrder());
        return new Manifest(
                identities.size(),
                openQuantity,
                sha256(String.join("\n", identities)),
                reservations,
                processingClaims,
                durablePendingCancellationCount,
                cancellationIntentCount,
                cancellationMarkerCount,
                completedAdmissionCount,
                matchSequence);
    }

    private long verifyCancellationMarkers(
            List<OrderBookRuntimeControlStore.CancellationFact> durableFacts,
            Set<String> visibleOrderIds) {
        Map<UUID, OrderBookRuntimeControlStore.CancellationFact> factsByOrder = new HashMap<>();
        for (OrderBookRuntimeControlStore.CancellationFact fact : durableFacts) {
            if (factsByOrder.put(fact.orderId(), fact) != null) {
                throw new IllegalStateException(
                        "PostgreSQL contains more than one cancellation marker fact for orderId="
                                + fact.orderId());
            }
        }

        Set<String> markerKeys = scan("order:cancellation:*");
        for (String markerKey : markerKeys) {
            UUID orderId = parseUuid(
                    markerKey.substring("order:cancellation:".length()),
                    "cancellation marker key");
            if (visibleOrderIds.contains(orderId.toString())) {
                throw new IllegalStateException(
                        "Redis cancellation marker cannot coexist with a visible order: orderId=" + orderId);
            }
            OrderBookRuntimeControlStore.CancellationFact durable = factsByOrder.get(orderId);
            if (durable == null) {
                throw new IllegalStateException(
                        "Redis cancellation marker has no durable Match decision: orderId=" + orderId);
            }
            Object cancellationIdValue = redis.opsForHash().get(markerKey, "cancellationId");
            UUID cancellationId = parseUuid(
                    cancellationIdValue == null ? "" : cancellationIdValue.toString(),
                    "cancellation marker cancellationId");
            if (!durable.cancellationId().equals(cancellationId)) {
                throw new IllegalStateException(
                        "Redis cancellation marker identity does not match durable decision: orderId=" + orderId);
            }
            if (!"PENDING".equals(durable.status())
                    && !"IN_PROGRESS".equals(durable.status())
                    && !"CANCELLED".equals(durable.status())) {
                throw new IllegalStateException(
                        "Redis cancellation marker conflicts with durable decision outcome: orderId="
                                + orderId + ", status=" + durable.status());
            }
            Object orderValue = redis.opsForHash().get(markerKey, "order");
            if (orderValue == null) {
                throw new IllegalStateException(
                        "Redis cancellation marker has no removed-order snapshot: orderId=" + orderId);
            }
            verifyCancellationMarkerSnapshot(durable, parseJson(orderValue.toString(), markerKey));
        }
        for (OrderBookRuntimeControlStore.CancellationFact fact : durableFacts) {
            if (("CANCELLED".equals(fact.status())
                    || "ALREADY_MATCHED".equals(fact.status())
                    || "NOT_OPEN".equals(fact.status()))
                    && visibleOrderIds.contains(fact.orderId().toString())) {
                throw new IllegalStateException(
                        "Durable completed cancellation cannot coexist with a visible order: orderId="
                                + fact.orderId() + ", status=" + fact.status());
            }
        }
        return markerKeys.size();
    }

    private void verifyCancellationMarkerSnapshot(
            OrderBookRuntimeControlStore.CancellationFact durable,
            JsonNode snapshot) {
        UUID orderId = parseUuid(requiredText(snapshot, "i", "orderId"), "cancellation marker orderId");
        UUID userId = parseUuid(requiredText(snapshot, "u", "userId"), "cancellation marker userId");
        long marketSequence = requiredLong(snapshot, "s", "marketSequence");
        int price = Math.toIntExact(requiredLong(snapshot, "p", "price"));
        int amount = Math.toIntExact(requiredLong(snapshot, "a", "amount"));
        String marketId = requiredText(snapshot, "m", "marketId");
        String orderType = requiredText(snapshot, "t", "orderType").toUpperCase(Locale.ROOT);
        LocalDateTime createdAt = parseTimestamp(
                optionalText(snapshot, "c", "createdAt"), "cancellation marker createdAt");
        if (!durable.orderId().equals(orderId)
                || !durable.userId().equals(userId)
                || !java.util.Objects.equals(durable.marketId(), marketId)
                || !java.util.Objects.equals(durable.marketSequence(), marketSequence)
                || !java.util.Objects.equals(durable.orderType(), orderType)
                || !java.util.Objects.equals(durable.limitPrice(), price)
                || !java.util.Objects.equals(durable.cancelledAmount(), amount)
                || !sameTimestamp(durable.orderCreatedAt(), createdAt)) {
            throw new IllegalStateException(
                    "Redis cancellation marker snapshot does not match durable decision: orderId="
                            + durable.orderId());
        }
    }

    private JsonNode parseJson(String json, String source) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid Redis JSON during verification: " + source, failure);
        }
    }

    private LocalDateTime parseTimestamp(String value, String field) {
        if (value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid Redis " + field + ": " + value, failure);
        }
    }

    private boolean sameTimestamp(LocalDateTime left, LocalDateTime right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.truncatedTo(ChronoUnit.MICROS).equals(right.truncatedTo(ChronoUnit.MICROS));
    }

    private long verifyCompletedAdmissionBitmaps(
            List<OrderBookRuntimeControlStore.CompletedAdmission> completedAdmissions) {
        Map<String, Set<Long>> expected = new HashMap<>();
        for (OrderBookRuntimeControlStore.CompletedAdmission admission : completedAdmissions) {
            if (admission.marketId() == null || admission.marketId().isBlank()
                    || admission.marketSequence() <= 0) {
                throw new IllegalStateException(
                        "PostgreSQL contains an invalid APPLIED admission identity: " + admission);
            }
            long zeroBased = admission.marketSequence() - 1;
            long shard = zeroBased / COMPLETED_BITMAP_SHARD_SIZE;
            long offset = zeroBased % COMPLETED_BITMAP_SHARD_SIZE;
            String key = COMPLETED_BITMAP_PREFIX + admission.marketId() + ":" + shard;
            if (!expected.computeIfAbsent(key, ignored -> new HashSet<>()).add(offset)) {
                throw new IllegalStateException(
                        "PostgreSQL contains duplicate APPLIED admission marker: " + admission);
            }
        }

        Set<String> actualKeys = scan(COMPLETED_BITMAP_PREFIX + "*");
        if (!actualKeys.equals(expected.keySet())) {
            Set<String> missing = new HashSet<>(expected.keySet());
            missing.removeAll(actualKeys);
            Set<String> unexpected = new HashSet<>(actualKeys);
            unexpected.removeAll(expected.keySet());
            throw new IllegalStateException(
                    "Redis completed-admission bitmap keys do not match PostgreSQL APPLIED facts: missing="
                            + missing + ", unexpected=" + unexpected);
        }

        long verified = 0L;
        for (Map.Entry<String, Set<Long>> bitmap : expected.entrySet()) {
            Long actualCount = redis.execute((RedisCallback<Long>) connection ->
                    connection.stringCommands().bitCount(
                            bitmap.getKey().getBytes(StandardCharsets.UTF_8)));
            long expectedCount = bitmap.getValue().size();
            if (actualCount == null || actualCount != expectedCount) {
                throw new IllegalStateException(
                        "Redis completed-admission bitmap count does not match PostgreSQL APPLIED facts: key="
                                + bitmap.getKey() + ", expected=" + expectedCount + ", actual=" + actualCount);
            }
            for (long offset : bitmap.getValue()) {
                Boolean present = redis.opsForValue().getBit(bitmap.getKey(), offset);
                if (!Boolean.TRUE.equals(present)) {
                    throw new IllegalStateException(
                            "Redis completed-admission bitmap is missing PostgreSQL APPLIED fact: key="
                                    + bitmap.getKey() + ", offset=" + offset);
                }
            }
            verified = Math.addExact(verified, expectedCount);
        }
        return verified;
    }

    private JsonNode parseDetail(String key) {
        String json = redis.opsForValue().get(key);
        if (json == null) {
            throw new IllegalStateException("Redis order detail disappeared during verification: " + key);
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid Redis order detail during verification: " + key, e);
        }
    }

    private BookIdentity parseOrderBookKey(String key) {
        if (!key.startsWith(ORDERBOOK_PREFIX)) {
            throw new IllegalStateException("Invalid Redis order-book key: " + key);
        }
        int sideSeparator = key.lastIndexOf(':');
        if (sideSeparator <= ORDERBOOK_PREFIX.length()) {
            throw new IllegalStateException("Invalid Redis order-book key: " + key);
        }
        String marketId = key.substring(ORDERBOOK_PREFIX.length(), sideSeparator);
        String side = key.substring(sideSeparator + 1).toLowerCase(Locale.ROOT);
        if (!"buy".equals(side) && !"sell".equals(side)) {
            throw new IllegalStateException("Invalid Redis order-book side: " + key);
        }
        return new BookIdentity(marketId, side);
    }

    private void verifyUserIndex(Map<String, Set<String>> expected) {
        Map<String, Set<String>> actual = new HashMap<>();
        for (String key : scan("user:*:orders")) {
            if (!key.startsWith(USER_PREFIX) || !key.endsWith(USER_SUFFIX)) {
                throw new IllegalStateException("Invalid Redis user order-index key: " + key);
            }
            String userId = key.substring(USER_PREFIX.length(), key.length() - USER_SUFFIX.length());
            parseUuid(userId, "user order-index key");
            Set<String> members = redis.opsForSet().members(key);
            Set<String> normalized = members == null ? Set.of() : Set.copyOf(members);
            for (String member : normalized) {
                parseUuid(member, "user order-index member");
            }
            actual.put(userId, normalized);
        }
        Map<String, Set<String>> required = userOpenOrderIndexEnabled ? expected : Map.of();
        if (!actual.equals(required)) {
            throw new IllegalStateException("Redis user order-index mismatch: expected="
                    + required + ", actual=" + actual);
        }
    }

    private long scoreFor(String orderType, long price, long marketSequence) {
        long boundedSequence = Math.floorMod(marketSequence, SCORE_FACTOR);
        if ("BUY".equals(orderType)) {
            return Math.addExact(Math.multiplyExact(price, SCORE_FACTOR), SCORE_FACTOR - boundedSequence);
        }
        if ("SELL".equals(orderType)) {
            return Math.addExact(Math.multiplyExact(price, SCORE_FACTOR), boundedSequence);
        }
        throw new IllegalStateException("Redis order type must be BUY or SELL: " + orderType);
    }

    private UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Invalid UUID in Redis " + field + ": " + value, failure);
        }
    }

    private long verifyCancellationIntents(
            Map<UUID, UUID> requiredPending,
            List<OrderBookRuntimeControlStore.CancellationFact> durableFacts) {
        Set<String> actualKeys = scan("order:cancellation-intent:*");
        Map<UUID, UUID> durableIdentities = durableFacts.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        OrderBookRuntimeControlStore.CancellationFact::orderId,
                        OrderBookRuntimeControlStore.CancellationFact::cancellationId));
        Map<UUID, UUID> requiredIntents = new HashMap<>(requiredPending);
        durableFacts.stream()
                .filter(fact -> "PENDING".equals(fact.status()) || "IN_PROGRESS".equals(fact.status()))
                .forEach(fact -> {
                    UUID prior = requiredIntents.putIfAbsent(fact.orderId(), fact.cancellationId());
                    if (prior != null && !prior.equals(fact.cancellationId())) {
                        throw new IllegalStateException(
                                "Durable pending cancellation snapshots disagree: orderId=" + fact.orderId());
                    }
                });
        for (Map.Entry<UUID, UUID> entry : requiredIntents.entrySet()) {
            String value = redis.opsForValue().get("order:cancellation-intent:" + entry.getKey());
            if (!entry.getValue().toString().equals(value)) {
                throw new IllegalStateException("Redis pending cancellation intent mismatch: orderId="
                        + entry.getKey());
            }
        }
        for (String key : actualKeys) {
            UUID orderId = parseUuid(
                    key.substring("order:cancellation-intent:".length()),
                    "cancellation intent key");
            UUID durableCancellationId = durableIdentities.get(orderId);
            String actualCancellationId = redis.opsForValue().get(key);
            if (durableCancellationId == null
                    || !durableCancellationId.toString().equals(actualCancellationId)) {
                throw new IllegalStateException(
                        "Redis cancellation intent has no matching durable decision: orderId=" + orderId);
            }
        }
        return actualKeys.size();
    }

    private Set<String> scan(String pattern) {
        Set<String> keys = redis.execute((RedisCallback<Set<String>>) connection -> {
            Set<String> found = new HashSet<>();
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(
                    ScanOptions.scanOptions().match(pattern).count(1_000).build())) {
                while (cursor.hasNext()) {
                    found.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return found;
        });
        return keys == null ? Set.of() : keys;
    }

    private String requiredText(JsonNode root, String compact, String legacy) {
        String value = optionalText(root, compact, legacy);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing " + legacy);
        }
        return value;
    }

    private String optionalText(JsonNode root, String compact, String legacy) {
        JsonNode node = root.has(compact) ? root.get(compact) : root.get(legacy);
        return node == null || node.isNull() ? "" : node.asText();
    }

    private long requiredLong(JsonNode root, String compact, String legacy) {
        JsonNode node = root.has(compact) ? root.get(compact) : root.get(legacy);
        if (node == null || !node.canConvertToLong()) {
            throw new IllegalArgumentException("Missing or invalid " + legacy);
        }
        return node.longValue();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record Manifest(
            long openOrderCount,
            long openQuantity,
            String identityDigest,
            long activeReservationCount,
            long activeProcessingClaimCount,
            long pendingCancellationCount,
            long cancellationIntentCount,
            long cancellationMarkerCount,
            long completedAdmissionCount,
            long matchSequence) {
    }

    private record BookIdentity(String marketId, String side) {
    }
}
