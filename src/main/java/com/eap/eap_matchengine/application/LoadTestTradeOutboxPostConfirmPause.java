package com.eap.eap_matchengine.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Load-test-only pause used to make the publisher-confirm/local-commit ambiguity
 * window deterministic. The marker is written only after publisher confirms have
 * completed and before the relay can mark those rows {@code SENT}.
 */
@Component
@Profile("loadtest")
@ConditionalOnProperty(
        name = "eap.match-engine.trade-outbox-relay.failure-injection.post-confirm-pause-enabled",
        havingValue = "true")
@Slf4j
public class LoadTestTradeOutboxPostConfirmPause implements TradeOutboxPostConfirmProbe {

    private static final long MAX_PAUSE_MS = TimeUnit.MINUTES.toMillis(5);

    private final long pauseMs;
    private final Path markerPath;
    private final AtomicBoolean fired = new AtomicBoolean();

    public LoadTestTradeOutboxPostConfirmPause(
            @Value("${eap.match-engine.trade-outbox-relay.failure-injection.post-confirm-pause-ms:60000}")
            long pauseMs,
            @Value("${eap.match-engine.trade-outbox-relay.failure-injection.marker-path:}")
            String markerPath) {
        if (pauseMs <= 0 || pauseMs > MAX_PAUSE_MS) {
            throw new IllegalArgumentException(
                    "post-confirm failure-injection pause must be between 1 and " + MAX_PAUSE_MS + " ms");
        }
        if (markerPath == null || markerPath.isBlank()) {
            throw new IllegalArgumentException("post-confirm failure-injection marker-path is required");
        }
        this.pauseMs = pauseMs;
        this.markerPath = Path.of(markerPath).toAbsolutePath().normalize();
    }

    @Override
    public void afterBrokerConfirmationBeforeMarkSent(List<Long> outboxIds) {
        if (!fired.compareAndSet(false, true)) {
            return;
        }
        if (outboxIds == null || outboxIds.isEmpty()) {
            throw new IllegalArgumentException("confirmed outbox ids are required");
        }
        writeMarker(outboxIds);
        log.warn(
                "REL-107 failure injection armed after broker confirm and before mark-SENT: count={}, marker={}, pauseMs={}",
                outboxIds.size(), markerPath, pauseMs);
        try {
            Thread.sleep(pauseMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("post-confirm failure-injection pause interrupted", interrupted);
        }
    }

    private void writeMarker(List<Long> outboxIds) {
        Path parent = markerPath.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("failure-injection marker-path must have a parent directory");
        }
        Path temporary = markerPath.resolveSibling(markerPath.getFileName() + ".tmp");
        String ids = outboxIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        String marker = "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"processId\": " + ProcessHandle.current().pid() + ",\n"
                + "  \"confirmedCount\": " + outboxIds.size() + ",\n"
                + "  \"confirmedOutboxIds\": [" + ids + "],\n"
                + "  \"observedAt\": \"" + Instant.now() + "\"\n"
                + "}\n";
        try {
            Files.createDirectories(parent);
            Files.writeString(temporary, marker, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, markerPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, markerPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("could not write failure-injection marker " + markerPath, failure);
        }
    }
}
