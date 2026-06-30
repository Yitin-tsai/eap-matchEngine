package com.eap.eap_matchengine.application;

import com.eap.common.constants.RabbitMQConstants;
import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_matchengine.configuration.observability.TradeOutboxMetrics;
import com.eap.eap_matchengine.configuration.repository.TradeOutboxRepository;
import com.eap.eap_matchengine.domain.entity.TradeOutboxEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Slf4j
@ConditionalOnProperty(
        name = "eap.match-engine.trade-outbox-relay.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class TradeOutboxRelay {

    private final TradeOutboxRepository tradeOutboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final TradeOutboxMetrics metrics;
    private final int batchSize;
    private final long confirmTimeoutMs;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    public TradeOutboxRelay(
            TradeOutboxRepository tradeOutboxRepository,
            RabbitTemplate rabbitTemplate,
            ObjectMapper objectMapper,
            TradeOutboxMetrics metrics,
            @Value("${eap.match-engine.trade-outbox-relay.batch-size:200}") int batchSize,
            @Value("${eap.match-engine.trade-outbox-relay.confirm-timeout-ms:5000}") long confirmTimeoutMs,
            @Value("${eap.match-engine.trade-outbox-relay.max-attempts:10}") int maxAttempts,
            @Value("${eap.match-engine.trade-outbox-relay.initial-backoff-ms:1000}") long initialBackoffMs,
            @Value("${eap.match-engine.trade-outbox-relay.max-backoff-ms:300000}") long maxBackoffMs) {
        this.tradeOutboxRepository = tradeOutboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    @Scheduled(fixedDelayString = "${eap.match-engine.trade-outbox-relay.poll-interval-ms:500}")
    public void pollAndPublish() {
        boolean continueDraining;
        do {
            List<TradeOutboxEntity> pending = tradeOutboxRepository
                    .findByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                            "PENDING",
                            LocalDateTime.now(),
                            PageRequest.of(0, batchSize));

            boolean batchSucceeded = true;
            List<PublishAttempt> attempts = new ArrayList<>(pending.size());

            for (TradeOutboxEntity entry : pending) {
                Instant startedAt = Instant.now();
                try {
                    TradeExecutedEvent event = deserialize(entry);
                    CorrelationData correlationData = new CorrelationData(entry.getId().toString());
                    rabbitTemplate.convertAndSend(
                            RabbitMQConstants.TRADE_EXCHANGE,
                            entry.getRoutingKey(),
                            event,
                            correlationData);
                    attempts.add(new PublishAttempt(entry, correlationData, startedAt));
                } catch (Exception e) {
                    batchSucceeded = false;
                    metrics.publishFailed();
                    recordFailure(entry, e);
                    metrics.recordPublish(Duration.between(startedAt, Instant.now()));
                }
            }

            long confirmationDeadlineNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(confirmTimeoutMs);
            List<PublishAttempt> confirmedAttempts = new ArrayList<>(attempts.size());

            for (PublishAttempt attempt : attempts) {
                TradeOutboxEntity entry = attempt.entry();
                try {
                    awaitBrokerConfirmation(entry, attempt.correlationData(), confirmationDeadlineNanos);
                    confirmedAttempts.add(attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    metrics.publishFailed();
                    log.warn("Trade outbox relay interrupted while waiting for broker confirmation: id={}", entry.getId());
                    return;
                } catch (Exception e) {
                    batchSucceeded = false;
                    metrics.publishFailed();
                    recordFailure(entry, e);
                    metrics.recordPublish(Duration.between(attempt.startedAt(), Instant.now()));
                }
            }

            if (!confirmedAttempts.isEmpty()) {
                try {
                    markConfirmedAsSent(confirmedAttempts);
                } catch (Exception e) {
                    batchSucceeded = false;
                    for (PublishAttempt attempt : confirmedAttempts) {
                        metrics.publishFailed();
                        recordFailure(attempt.entry(), e);
                        metrics.recordPublish(Duration.between(attempt.startedAt(), Instant.now()));
                    }
                }
            }

            continueDraining = batchSucceeded && pending.size() == batchSize;
        } while (continueDraining);
    }

    private TradeExecutedEvent deserialize(TradeOutboxEntity entry) throws Exception {
        if (!"TradeExecutedEvent".equals(entry.getEventType())) {
            throw new IllegalArgumentException("Unknown trade outbox event type: " + entry.getEventType());
        }
        return objectMapper.readValue(entry.getPayload(), TradeExecutedEvent.class);
    }

    private void awaitBrokerConfirmation(
            TradeOutboxEntity entry,
            CorrelationData correlationData,
            long confirmationDeadlineNanos) throws Exception {
        long remainingNanos = confirmationDeadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new TimeoutException("Trade outbox publisher confirm batch timed out");
        }
        CorrelationData.Confirm confirm = correlationData.getFuture().get(remainingNanos, TimeUnit.NANOSECONDS);
        if (!confirm.isAck()) {
            throw new AmqpException("RabbitMQ nack: " + confirm.getReason());
        }
        if (correlationData.getReturned() != null) {
            throw new AmqpException("Unroutable TradeExecutedEvent: id=" + entry.getId());
        }
    }

    private void markConfirmedAsSent(List<PublishAttempt> confirmedAttempts) {
        List<Long> ids = confirmedAttempts.stream()
                .map(attempt -> attempt.entry().getId())
                .toList();
        LocalDateTime updatedAt = LocalDateTime.now();
        int marked = tradeOutboxRepository.markPendingAsSent(ids, updatedAt);
        if (marked != ids.size()) {
            throw new IllegalStateException(
                    "Expected to mark " + ids.size() + " trade outbox records SENT, but updated " + marked);
        }

        Instant completedAt = Instant.now();
        for (PublishAttempt attempt : confirmedAttempts) {
            TradeOutboxEntity entry = attempt.entry();
            entry.setStatus("SENT");
            entry.setNextRetryAt(null);
            entry.setLastError(null);
            entry.setUpdatedAt(updatedAt);
            metrics.published();
            metrics.recordPublish(Duration.between(attempt.startedAt(), completedAt));
            log.debug("Trade outbox event published: id={}, aggregateId={}", entry.getId(), entry.getAggregateId());
        }
    }

    private void recordFailure(TradeOutboxEntity entry, Exception failure) {
        int attemptCount = entry.getAttemptCount() + 1;
        String error = failure.getClass().getSimpleName() + ": "
                + (failure.getMessage() == null ? "no message" : failure.getMessage());

        entry.setAttemptCount(attemptCount);
        entry.setLastError(error.substring(0, Math.min(error.length(), 1000)));
        entry.setUpdatedAt(LocalDateTime.now());

        if (attemptCount >= maxAttempts) {
            entry.setStatus("FAILED");
            entry.setNextRetryAt(null);
            log.error("Trade outbox event permanently failed: id={}, aggregateId={}, attempts={}, error={}",
                    entry.getId(), entry.getAggregateId(), attemptCount, entry.getLastError());
        } else {
            long backoffMs = calculateBackoffMs(attemptCount);
            entry.setNextRetryAt(LocalDateTime.now().plusNanos(TimeUnit.MILLISECONDS.toNanos(backoffMs)));
            metrics.retryScheduled();
            log.warn("Trade outbox publish failed; retry scheduled: id={}, aggregateId={}, attempt={}/{}, backoffMs={}, error={}",
                    entry.getId(), entry.getAggregateId(), attemptCount, maxAttempts, backoffMs, entry.getLastError());
        }
        tradeOutboxRepository.save(entry);
    }

    private long calculateBackoffMs(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 30);
        long multiplier = 1L << exponent;
        if (initialBackoffMs > maxBackoffMs / multiplier) {
            return maxBackoffMs;
        }
        return Math.min(initialBackoffMs * multiplier, maxBackoffMs);
    }

    private record PublishAttempt(
            TradeOutboxEntity entry,
            CorrelationData correlationData,
            Instant startedAt) {
    }
}
