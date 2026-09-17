package com.eap.eap_matchengine.configuration.reliability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.eap.common.constants.RabbitMQConstants.MATCH_ENGINE_ORDER_ASSET_RESERVATION_SUCCEEDED_QUEUE;
import static com.eap.common.constants.RabbitMQConstants.MATCH_ENGINE_ORDER_CANCELLATION_REQUESTED_QUEUE;

/** Keeps pre-inbox deliveries on their source queues while the MatchEngine database is unavailable. */
@Component
@Slf4j
public class MatchCdaDatabaseOutageCircuitBreaker {

    public static final String RESERVATION_SUCCEEDED_LISTENER_ID = "match-cda-reservation-succeeded";
    public static final String CANCELLATION_REQUESTED_LISTENER_ID = "match-cda-cancellation-requested";

    private static final List<String> LISTENER_IDS = List.of(
            RESERVATION_SUCCEEDED_LISTENER_ID,
            CANCELLATION_REQUESTED_LISTENER_ID);
    private static final Set<String> SOURCE_QUEUES = Set.of(
            MATCH_ENGINE_ORDER_ASSET_RESERVATION_SUCCEEDED_QUEUE,
            MATCH_ENGINE_ORDER_CANCELLATION_REQUESTED_QUEUE);

    private final RabbitListenerEndpointRegistry listenerRegistry;
    private final DataSource dataSource;
    private final ScheduledExecutorService recoveryExecutor;
    private final long initialProbeDelayMs;
    private final long maximumProbeDelayMs;
    private final long resumeSpacingMs;
    private final int probeTimeoutSeconds;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicLong recoveryGeneration = new AtomicLong();
    private final AtomicInteger openGauge = new AtomicInteger();
    private final Counter openedCounter;
    private final Counter probeCounter;
    private final Counter probeFailureCounter;
    private final Set<String> listenerIdsPausedByCircuit = new LinkedHashSet<>();

    public MatchCdaDatabaseOutageCircuitBreaker(
            RabbitListenerEndpointRegistry listenerRegistry,
            DataSource dataSource,
            MeterRegistry meterRegistry,
            @Value("${eap.rabbit.db-outage.initial-probe-delay-ms:2000}") long initialProbeDelayMs,
            @Value("${eap.rabbit.db-outage.maximum-probe-delay-ms:30000}") long maximumProbeDelayMs,
            @Value("${eap.rabbit.db-outage.resume-spacing-ms:2000}") long resumeSpacingMs,
            @Value("${eap.rabbit.db-outage.probe-timeout-seconds:2}") int probeTimeoutSeconds) {
        this.listenerRegistry = listenerRegistry;
        this.dataSource = dataSource;
        this.initialProbeDelayMs = Math.max(100, initialProbeDelayMs);
        this.maximumProbeDelayMs = Math.max(this.initialProbeDelayMs, maximumProbeDelayMs);
        this.resumeSpacingMs = Math.max(0, resumeSpacingMs);
        this.probeTimeoutSeconds = Math.max(1, probeTimeoutSeconds);
        this.recoveryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "match-cda-db-recovery");
            thread.setDaemon(true);
            return thread;
        });
        Gauge.builder("eap.rabbit.cda.db.circuit.open", openGauge, AtomicInteger::get)
                .tag("service", "match-engine")
                .description("One while the service has paused CDA consumers for a database outage")
                .register(meterRegistry);
        this.openedCounter = counter(meterRegistry, "eap.rabbit.cda.db.circuit.opened");
        this.probeCounter = counter(meterRegistry, "eap.rabbit.cda.db.probe");
        this.probeFailureCounter = counter(meterRegistry, "eap.rabbit.cda.db.probe.failure");
    }

    public boolean ownsQueue(String queue) {
        return queue != null && SOURCE_QUEUES.contains(queue);
    }

    public void open(Throwable cause) {
        State observed = state.get();
        while (observed == State.CLOSED || observed == State.RESUMING) {
            if (state.compareAndSet(observed, State.OPEN)) {
                boolean freshCircuit = observed == State.CLOSED;
                openGauge.set(1);
                openedCounter.increment();
                long generation = recoveryGeneration.incrementAndGet();
                log.warn("Opening MatchEngine CDA database-outage circuit; source messages will remain queued", cause);
                recoveryExecutor.execute(() -> beginRecovery(generation, freshCircuit));
                return;
            }
            observed = state.get();
        }
    }

    public boolean isOpen() {
        return state.get() != State.CLOSED;
    }

    private void beginRecovery(long generation, boolean resetOwnership) {
        if (generation != recoveryGeneration.get() || state.get() == State.CLOSED) {
            return;
        }
        if (!pauseListeners(resetOwnership)) {
            recoveryExecutor.schedule(
                    () -> beginRecovery(generation, false), initialProbeDelayMs, TimeUnit.MILLISECONDS);
            return;
        }
        scheduleProbe(generation, initialProbeDelayMs, 0);
    }

    private boolean pauseListeners(boolean resetOwnership) {
        if (resetOwnership) {
            listenerIdsPausedByCircuit.clear();
        }
        boolean allPaused = true;
        for (String listenerId : LISTENER_IDS) {
            try {
                MessageListenerContainer container = listenerRegistry.getListenerContainer(listenerId);
                if (container == null) {
                    allPaused = false;
                    log.error("Cannot pause missing MatchEngine CDA listener container: id={}", listenerId);
                } else if (container.isRunning()) {
                    listenerIdsPausedByCircuit.add(listenerId);
                    container.stop();
                }
            } catch (RuntimeException failure) {
                allPaused = false;
                log.warn("Failed to pause MatchEngine CDA listener container: id={}", listenerId, failure);
            }
        }
        return allPaused;
    }

    private void scheduleProbe(long generation, long delayMs, int consecutiveSuccesses) {
        recoveryExecutor.schedule(
                () -> probe(generation, delayMs, consecutiveSuccesses),
                jitter(delayMs),
                TimeUnit.MILLISECONDS);
    }

    private void probe(long generation, long previousDelayMs, int consecutiveSuccesses) {
        if (generation != recoveryGeneration.get() || state.get() == State.CLOSED) {
            return;
        }
        state.compareAndSet(State.OPEN, State.PROBING);
        probeCounter.increment();
        if (databaseIsReady()) {
            int successes = consecutiveSuccesses + 1;
            if (successes >= 2) {
                state.set(State.RESUMING);
                resumeListener(generation, 0);
            } else {
                scheduleProbe(generation, 1000, successes);
            }
            return;
        }
        probeFailureCounter.increment();
        state.set(State.OPEN);
        long nextDelayMs = Math.min(maximumProbeDelayMs, Math.max(initialProbeDelayMs, previousDelayMs * 2));
        scheduleProbe(generation, nextDelayMs, 0);
    }

    private boolean databaseIsReady() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
            statement.setQueryTimeout(probeTimeoutSeconds);
            return statement.execute();
        } catch (Exception failure) {
            log.info("MatchEngine database recovery probe failed: {}", failure.getMessage());
            return false;
        }
    }

    private void resumeListener(long generation, int index) {
        if (generation != recoveryGeneration.get() || state.get() != State.RESUMING) {
            return;
        }
        List<String> ownedListenerIds = new ArrayList<>(listenerIdsPausedByCircuit);
        if (index >= ownedListenerIds.size()) {
            if (generation == recoveryGeneration.get() && state.compareAndSet(State.RESUMING, State.CLOSED)) {
                openGauge.set(0);
                listenerIdsPausedByCircuit.clear();
                log.info("MatchEngine database recovered; all circuit-owned CDA listener containers resumed");
            }
            return;
        }
        String listenerId = ownedListenerIds.get(index);
        try {
            MessageListenerContainer container = listenerRegistry.getListenerContainer(listenerId);
            if (container == null) {
                throw new IllegalStateException("Missing listener container: " + listenerId);
            }
            if (!container.isRunning()) {
                container.start();
            }
        } catch (RuntimeException failure) {
            log.warn("Failed to resume MatchEngine CDA listener container: id={}", listenerId, failure);
            state.compareAndSet(State.RESUMING, State.OPEN);
            scheduleProbe(generation, initialProbeDelayMs, 0);
            return;
        }
        recoveryExecutor.schedule(
                () -> resumeListener(generation, index + 1),
                resumeSpacingMs,
                TimeUnit.MILLISECONDS);
    }

    private long jitter(long delayMs) {
        long spread = Math.max(1, delayMs / 5);
        return Math.max(1, delayMs + ThreadLocalRandom.current().nextLong(-spread, spread + 1));
    }

    private Counter counter(MeterRegistry meterRegistry, String name) {
        return Counter.builder(name).tag("service", "match-engine").register(meterRegistry);
    }

    @PreDestroy
    void shutdown() {
        recoveryExecutor.shutdownNow();
    }

    private enum State {
        CLOSED,
        OPEN,
        PROBING,
        RESUMING
    }
}
