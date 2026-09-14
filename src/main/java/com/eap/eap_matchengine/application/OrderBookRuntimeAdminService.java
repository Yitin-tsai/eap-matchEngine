package com.eap.eap_matchengine.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderBookRuntimeAdminService {

    private static final DefaultRedisScript<Long> STAGE_READY_SENTINEL = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current and current ~= ARGV[1] then
                return 0
            end
            redis.call('SET', KEYS[1], ARGV[1])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> REMOVE_SENTINEL_IF_EQUAL = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final OrderBookRuntimeControlStore controls;
    private final OrderBookRuntimeGuard guard;
    private final RedisOrderBookManifestVerifier verifier;
    private final RedisTemplate<String, String> redis;
    private final ObjectMapper objectMapper;

    public Result initializeEmpty(String operator, String reason) {
        requireOperator(operator, reason);
        return controls.withActivationLock(() -> initializeEmptyLocked(operator, reason));
    }

    private Result initializeEmptyLocked(String operator, String reason) {
        OrderBookRuntimeControlStore.Control control = controls.find();
        if (control != null && control.state() == OrderBookRuntimeControlStore.State.READY) {
            guard.refreshAfterOperatorAction();
            if (guard.current().ready()) {
                return result(control, guard.current(), null);
            }
            control = controls.find();
        }
        if (controls.durableFactCount() != 0) {
            throw new IllegalStateException(
                    "Empty initialization rejected because Match durable facts or debt already exist");
        }
        if (verifier.cdaKeyCountExcludingControl() != 0) {
            throw new IllegalStateException(
                    "Empty initialization rejected because CDA Redis keys already exist");
        }

        if (control == null) {
            control = controls.createRecoveringIfAbsent(UUID.randomUUID(),
                    "EMPTY_INITIALIZATION_STARTED: " + reason);
        }
        String runId = guard.redisRunId();
        RedisOrderBookManifestVerifier.Manifest manifest =
                verifier.inspect(Map.of(), List.of(), List.of());
        ActivationRequest request = new ActivationRequest(
                "EMPTY-" + control.fenceEpoch(),
                control.fenceEpoch(),
                control.generation(),
                control.version(),
                0L,
                0L,
                0L,
                0L,
                manifest.identityDigest(),
                operator,
                "EMPTY_INITIALIZATION: " + reason);
        return activate(
                control,
                new RecoveryProof(runId, manifest, Map.of(), 0L, 0L),
                request);
    }

    public Result activateRebuilt(ActivationRequest request) {
        requireOperator(request.operator(), request.reason());
        requireValue(request.manifestId(), "manifestId");
        if (request.sourceOrderWatermark() == null || request.sourceOrderWatermark() < 0
                || request.sourceTradeWatermark() == null || request.sourceTradeWatermark() < 0
                || request.expectedOpenOrderCount() == null || request.expectedOpenOrderCount() < 0
                || request.expectedOpenQuantity() == null || request.expectedOpenQuantity() < 0) {
            throw new IllegalArgumentException("Recovery watermarks, open-order count and quantity must be non-negative");
        }
        requireValue(request.expectedIdentityDigest(), "expectedIdentityDigest");
        if (request.expectedFenceEpoch() == null || request.expectedFenceEpoch() <= 0
                || request.expectedGeneration() == null
                || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new IllegalArgumentException(
                    "expectedFenceEpoch, expectedGeneration and expectedVersion are required recovery tokens");
        }

        return controls.withActivationLock(() -> activateRebuiltLocked(request));
    }

    private Result activateRebuiltLocked(ActivationRequest request) {
        OrderBookRuntimeControlStore.Control control = controls.find();
        requireRecoveryToken(control, request);

        RecoveryProof first = verifyRecovery(control, request);
        RecoveryProof second = verifyRecovery(control, request);
        if (!first.equals(second)) {
            throw new IllegalStateException("Redis rebuild changed while its activation manifest was verified");
        }

        return activate(control, second, request);
    }

    private RecoveryProof verifyRecovery(
            OrderBookRuntimeControlStore.Control expectedControl,
            ActivationRequest request) {
        requireRecoveryToken(controls.find(), request);
        String runIdBefore = guard.redisRunId();
        Map<UUID, UUID> pendingCancellations = controls.pendingCancellations();
        List<OrderBookRuntimeControlStore.CompletedAdmission> completedAdmissions =
                controls.completedAdmissions();
        RedisOrderBookManifestVerifier.Manifest manifest = verifier.inspect(
                pendingCancellations,
                completedAdmissions,
                        controls.cancellationFacts());
        long maxReceivedOrderSequence = controls.maxReceivedOrderSequence();
        if (request.sourceOrderWatermark() < maxReceivedOrderSequence) {
            throw new IllegalStateException("Order rebuild watermark is behind Match durable inbox: supplied="
                    + request.sourceOrderWatermark() + ", minimum=" + maxReceivedOrderSequence);
        }
        long maxDurableTradeSequence = controls.maxDurableTradeSequence();
        if (request.sourceTradeWatermark() != maxDurableTradeSequence) {
            throw new IllegalStateException("Trade rebuild watermark does not equal Match durable trades: supplied="
                    + request.sourceTradeWatermark() + ", durable=" + maxDurableTradeSequence);
        }
        if (manifest.openOrderCount() != request.expectedOpenOrderCount()
                || manifest.openQuantity() != request.expectedOpenQuantity()
                || !manifest.identityDigest().equalsIgnoreCase(request.expectedIdentityDigest())) {
            throw new IllegalStateException("Redis rebuild manifest mismatch: actual=" + manifest);
        }
        if (manifest.activeReservationCount() != 0 || manifest.activeProcessingClaimCount() != 0) {
            throw new IllegalStateException("Redis rebuild still has active reservation/processing debt: " + manifest);
        }
        if (manifest.matchSequence() < maxDurableTradeSequence) {
            throw new IllegalStateException("Redis match sequence is behind durable trades: redis="
                    + manifest.matchSequence() + ", durable=" + maxDurableTradeSequence);
        }
        String runIdAfter = guard.redisRunId();
        if (!runIdBefore.equals(runIdAfter)) {
            throw new IllegalStateException("Redis restarted while its rebuild manifest was verified");
        }
        requireRecoveryToken(controls.find(), request);
        if (expectedControl.fenceEpoch() != request.expectedFenceEpoch()
                || !expectedControl.generation().equals(request.expectedGeneration())
                || expectedControl.version() != request.expectedVersion()) {
            throw new IllegalStateException("Activation request does not belong to the locked recovery generation");
        }
        return new RecoveryProof(
                runIdAfter,
                manifest,
                Map.copyOf(pendingCancellations),
                maxReceivedOrderSequence,
                maxDurableTradeSequence);
    }

    public Map<String, Object> status() {
        guard.refresh();
        return runtimeStatus(guard.current());
    }

    /**
     * Performs a non-destructive diagnostic inspection. Callers must first prove the
     * business pipeline is quiescent; a live match has legitimate intermediate Redis
     * shapes that are not a rebuild manifest. Only RECOVERING activation may use a
     * manifest failure to prevent promotion.
     */
    public Map<String, Object> inspectReadyManifest() {
        guard.refresh();
        OrderBookRuntimeGuard.Snapshot inspectedGeneration = guard.current();
        Map<String, Object> result = new LinkedHashMap<>();
        if (inspectedGeneration.ready()) {
            try {
                result.put("redisManifest", verifier.inspect(
                        controls.pendingCancellations(),
                        controls.completedAdmissions(),
                        controls.cancellationFacts()));
                guard.verifyUnchanged(inspectedGeneration);
            } catch (RuntimeException inspectionFailure) {
                result.put("redisManifestError", inspectionFailure.getMessage());
            }
        }
        // Re-check only the generation identity. A manifest error can be a legitimate
        // in-flight shape and must never demote READY or delete the sentinel.
        guard.refresh();
        result.putAll(runtimeStatus(guard.current()));
        return result;
    }

    private Map<String, Object> runtimeStatus(OrderBookRuntimeGuard.Snapshot current) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("localReady", current.ready());
        result.put("localReason", current.reason());
        OrderBookRuntimeControlStore.Control control = controls.find();
        result.put("control", control == null ? Map.of() : control);
        return result;
    }

    private Result activate(
            OrderBookRuntimeControlStore.Control control,
            RecoveryProof verifiedProof,
            ActivationRequest request) {
        String runId = verifiedProof.redisRunId();
        RedisOrderBookManifestVerifier.Manifest manifest = verifiedProof.manifest();
        String sentinel = OrderBookRuntimeGuard.sentinel(control, runId);
        Long staged = redis.execute(
                STAGE_READY_SENTINEL,
                List.of(OrderBookRuntimeGuard.SENTINEL_KEY),
                sentinel);
        if (!Long.valueOf(1L).equals(staged)) {
            throw new IllegalStateException("A different Redis order-book sentinel already exists");
        }
        String serializedManifest;
        try {
            RecoveryProof finalProof = verifyRecovery(control, request);
            if (!verifiedProof.equals(finalProof)) {
                throw new IllegalStateException(
                        "Redis rebuild changed after the READY sentinel was staged");
            }
            serializedManifest = serializeManifest(request, finalProof.manifest());
        } catch (RuntimeException failure) {
            removeSentinel(sentinel);
            throw failure;
        }
        boolean markedReady;
        try {
            markedReady = controls.markReady(
                    control,
                    runId,
                    request.reason(),
                    request.manifestId(),
                    serializedManifest,
                    request.operator());
        } catch (RuntimeException failure) {
            removeSentinel(sentinel);
            throw failure;
        }
        if (!markedReady) {
            removeSentinel(sentinel);
            throw new IllegalStateException("Order-book activation lost its PostgreSQL recovery CAS");
        }
        guard.refreshAfterOperatorAction();
        if (!guard.current().ready()) {
            throw new IllegalStateException(
                    "Order-book activation did not survive the final sentinel/run-id verification");
        }
        return result(controls.find(), guard.current(), manifest);
    }

    private void requireRecoveryToken(
            OrderBookRuntimeControlStore.Control control,
            ActivationRequest request) {
        if (control == null || control.state() != OrderBookRuntimeControlStore.State.RECOVERING) {
            throw new IllegalStateException("CDA order-book control is not RECOVERING");
        }
        if (control.fenceEpoch() != request.expectedFenceEpoch()
                || !control.generation().equals(request.expectedGeneration())
                || control.version() != request.expectedVersion()) {
            throw new IllegalStateException("Stale order-book recovery token");
        }
    }

    private Result result(
            OrderBookRuntimeControlStore.Control control,
            OrderBookRuntimeGuard.Snapshot snapshot,
            RedisOrderBookManifestVerifier.Manifest manifest) {
        return new Result(
                snapshot.ready(),
                snapshot.reason(),
                control.fenceEpoch(),
                control.generation(),
                control.version(),
                manifest);
    }

    private String serializeManifest(
            ActivationRequest request,
            RedisOrderBookManifestVerifier.Manifest manifest) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "request", request,
                    "actualRedis", manifest));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize order-book verification manifest", e);
        }
    }

    private void removeSentinel(String sentinel) {
        try {
            redis.execute(
                    REMOVE_SENTINEL_IF_EQUAL,
                    List.of(OrderBookRuntimeGuard.SENTINEL_KEY),
                    sentinel);
        } catch (RuntimeException ignored) {
            // A missing sentinel keeps the service fail closed; the DB row is still RECOVERING.
        }
    }

    private void requireOperator(String operator, String reason) {
        requireValue(operator, "operator");
        requireValue(reason, "reason");
    }

    private void requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    public record ActivationRequest(
            String manifestId,
            Long expectedFenceEpoch,
            UUID expectedGeneration,
            Long expectedVersion,
            Long sourceOrderWatermark,
            Long sourceTradeWatermark,
            Long expectedOpenOrderCount,
            Long expectedOpenQuantity,
            String expectedIdentityDigest,
            String operator,
            String reason) {
    }

    private record RecoveryProof(
            String redisRunId,
            RedisOrderBookManifestVerifier.Manifest manifest,
            Map<UUID, UUID> pendingCancellations,
            long maxReceivedOrderSequence,
            long maxDurableTradeSequence) {
    }

    public record Result(
            boolean ready,
            String reason,
            long fenceEpoch,
            UUID generation,
            long version,
            RedisOrderBookManifestVerifier.Manifest manifest) {
    }
}
