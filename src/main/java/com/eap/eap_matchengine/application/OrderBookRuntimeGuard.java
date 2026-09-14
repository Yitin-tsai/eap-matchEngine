package com.eap.eap_matchengine.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Component("orderBookRuntime")
@Slf4j
public class OrderBookRuntimeGuard implements HealthIndicator {

    public static final String SENTINEL_KEY = "match:orderbook:control";
    private static final DefaultRedisScript<Long> INVALIDATE_SENTINEL = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if current and string.sub(current, 1, string.len(ARGV[1])) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final OrderBookRuntimeControlStore controls;
    private final RedisTemplate<String, String> redis;
    private final long maxSnapshotAgeMillis;
    private final AtomicReference<Snapshot> snapshot =
            new AtomicReference<>(Snapshot.unavailable("STARTING", null, null));

    public OrderBookRuntimeGuard(
            OrderBookRuntimeControlStore controls,
            RedisTemplate<String, String> redis,
            @Value("${eap.match-engine.orderbook-runtime.max-snapshot-age-ms:1000}")
            long maxSnapshotAgeMillis) {
        this.controls = controls;
        this.redis = redis;
        this.maxSnapshotAgeMillis = Math.max(1, maxSnapshotAgeMillis);
    }

    @Scheduled(
            fixedDelayString = "${eap.match-engine.orderbook-runtime.monitor-interval-ms:250}",
            initialDelayString = "${eap.match-engine.orderbook-runtime.monitor-initial-delay-ms:0}")
    public void refresh() {
        try {
            OrderBookRuntimeControlStore.Control control = controls.find();
            if (control == null) {
                close("CONTROL_NOT_INITIALIZED", null, null);
                return;
            }
            if (control.state() != OrderBookRuntimeControlStore.State.READY) {
                close("CONTROL_" + control.state(), control, null);
                return;
            }

            String actualRunId = redisRunId();
            String actualSentinel = redis.opsForValue().get(SENTINEL_KEY);
            String expectedSentinel = sentinel(control, control.redisRunId());
            if (!control.redisRunId().equals(actualRunId)) {
                trip(control, sentinelPrefix(control),
                        "REDIS_RUN_ID_MISMATCH expected=" + control.redisRunId() + " actual=" + actualRunId);
                return;
            }
            if (!expectedSentinel.equals(actualSentinel)) {
                trip(control, sentinelPrefix(control),
                        "GENERATION_MISMATCH expected=" + expectedSentinel + " actual=" + actualSentinel);
                return;
            }
            snapshot.set(Snapshot.ready(control, expectedSentinel, actualRunId));
        } catch (Exception failure) {
            close("CONTROL_CHECK_FAILED", null, failure);
        }
    }

    public Snapshot requireReady() {
        Snapshot current = snapshot.get();
        if (current.ready()
                && Instant.now().toEpochMilli() - current.checkedAtEpochMillis() <= maxSnapshotAgeMillis) {
            return current;
        }
        refresh();
        current = snapshot.get();
        if (!current.ready()) {
            throw new OrderBookRuntimeUnavailableException(
                    "CDA order-book runtime is not ready: " + current.reason(), current.cause());
        }
        return current;
    }

    public boolean isReady() {
        try {
            requireReady();
            return true;
        } catch (OrderBookRuntimeUnavailableException unavailable) {
            return false;
        }
    }

    public Snapshot current() {
        return snapshot.get();
    }

    public String redisRunId() {
        Properties server = redis.execute((RedisCallback<Properties>) connection ->
                connection.serverCommands().info("server"));
        String runId = server == null ? null : server.getProperty("run_id");
        if (runId == null || runId.isBlank()) {
            throw new OrderBookRuntimeUnavailableException("Redis did not report run_id");
        }
        return runId;
    }

    public static String sentinel(OrderBookRuntimeControlStore.Control control, String redisRunId) {
        return sentinelPrefix(control) + redisRunId;
    }

    private static String sentinelPrefix(OrderBookRuntimeControlStore.Control control) {
        return "READY|" + control.fenceEpoch() + "|" + control.generation() + "|";
    }

    public void verifyUnchanged(Snapshot expected) {
        String actualRunId = redisRunId();
        String actual = redis.opsForValue().get(SENTINEL_KEY);
        if (!expected.redisRunId().equals(actualRunId)
                || !expected.expectedSentinel().equals(actual)) {
            refresh();
            throw new OrderBookRuntimeUnavailableException(
                    "CDA order-book generation changed during operation");
        }
    }

    void refreshAfterOperatorAction() {
        refresh();
    }

    void rejectCurrentGeneration(String reason, Throwable cause) {
        Snapshot current = snapshot.get();
        OrderBookRuntimeControlStore.Control control = current.control();
        if (control == null || control.state() != OrderBookRuntimeControlStore.State.READY) {
            control = controls.find();
        }
        if (control == null || control.state() != OrderBookRuntimeControlStore.State.READY) {
            close(reason, control, cause);
            return;
        }
        trip(control, sentinelPrefix(control), reason, cause);
    }

    private void trip(
            OrderBookRuntimeControlStore.Control control,
            String oldGenerationPrefix,
            String reason) {
        trip(control, oldGenerationPrefix, reason, null);
    }

    private void trip(
            OrderBookRuntimeControlStore.Control control,
            String oldGenerationPrefix,
            String reason,
            Throwable cause) {
        close(reason, control, cause);
        try {
            redis.execute(INVALIDATE_SENTINEL, List.of(SENTINEL_KEY), oldGenerationPrefix);
        } catch (RuntimeException invalidationFailure) {
            log.warn("Could not invalidate stale CDA order-book sentinel", invalidationFailure);
        }
        try {
            OrderBookRuntimeControlStore.Control recovering =
                    controls.beginRecovery(control, UUID.randomUUID(), reason);
            close("CONTROL_" + recovering.state() + ": " + reason, recovering, cause);
        } catch (RuntimeException persistenceFailure) {
            log.error("Could not persist CDA order-book RECOVERING state", persistenceFailure);
        }
    }

    private void close(
            String reason,
            OrderBookRuntimeControlStore.Control control,
            Throwable cause) {
        Snapshot previous = snapshot.getAndSet(Snapshot.unavailable(reason, control, cause));
        if (previous.ready() || !previous.reason().equals(reason)) {
            log.warn("CDA order-book runtime closed: reason={}", reason, cause);
        }
    }

    @Override
    public Health health() {
        Snapshot current = snapshot.get();
        if (Instant.now().toEpochMilli() - current.checkedAtEpochMillis() > maxSnapshotAgeMillis) {
            refresh();
            current = snapshot.get();
        }
        Health.Builder health = current.ready() ? Health.up() : Health.outOfService();
        health.withDetail("ready", current.ready())
                .withDetail("reason", current.reason())
                .withDetail("checkedAtEpochMillis", current.checkedAtEpochMillis());
        if (current.control() != null) {
            health.withDetail("state", current.control().state())
                    .withDetail("fenceEpoch", current.control().fenceEpoch())
                    .withDetail("generation", current.control().generation())
                    .withDetail("version", current.control().version());
        }
        return health.build();
    }

    public record Snapshot(
            boolean ready,
            String reason,
            OrderBookRuntimeControlStore.Control control,
            String expectedSentinel,
            String redisRunId,
            long checkedAtEpochMillis,
            Throwable cause) {

        static Snapshot ready(
                OrderBookRuntimeControlStore.Control control,
                String sentinel,
                String redisRunId) {
            return new Snapshot(true, "READY", control, sentinel, redisRunId,
                    Instant.now().toEpochMilli(), null);
        }

        static Snapshot unavailable(
                String reason,
                OrderBookRuntimeControlStore.Control control,
                Throwable cause) {
            return new Snapshot(false, reason, control, null, null,
                    Instant.now().toEpochMilli(), cause);
        }
    }
}
