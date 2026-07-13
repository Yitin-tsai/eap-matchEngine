package com.eap.eap_matchengine.application;

import com.eap.common.constants.RabbitMQConstants;
import com.eap.eap_matchengine.configuration.observability.TradeOutboxMetrics;
import com.eap.eap_matchengine.configuration.repository.TradeOutboxRepository;
import com.eap.eap_matchengine.domain.entity.TradeOutboxEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
@ConditionalOnProperty(
        name = "eap.match-engine.trade-outbox-relay.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class TradeOutboxRelay {

    private final TradeOutboxRepository tradeOutboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final TradeOutboxMetrics metrics;
    private final int batchSize;
    private final int publishConcurrency;
    private final ExecutorService publishExecutor;
    private final long confirmTimeoutMs;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    public TradeOutboxRelay(
            TradeOutboxRepository tradeOutboxRepository,
            RabbitTemplate rabbitTemplate,
            TradeOutboxMetrics metrics,
            @Value("${eap.match-engine.trade-outbox-relay.batch-size:200}") int batchSize,
            @Value("${eap.match-engine.trade-outbox-relay.publish-concurrency:1}") int publishConcurrency,
            @Value("${eap.match-engine.trade-outbox-relay.confirm-timeout-ms:5000}") long confirmTimeoutMs,
            @Value("${eap.match-engine.trade-outbox-relay.max-attempts:10}") int maxAttempts,
            @Value("${eap.match-engine.trade-outbox-relay.initial-backoff-ms:1000}") long initialBackoffMs,
            @Value("${eap.match-engine.trade-outbox-relay.max-backoff-ms:300000}") long maxBackoffMs) {
        this.tradeOutboxRepository = tradeOutboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.publishConcurrency = Math.max(1, publishConcurrency);
        this.publishExecutor = this.publishConcurrency > 1
                ? Executors.newFixedThreadPool(this.publishConcurrency, new TradeOutboxPublishThreadFactory())
                : null;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    @PreDestroy
    public void shutdown() {
        if (publishExecutor != null) {
            publishExecutor.shutdown();
        }
    }

    @Scheduled(fixedDelayString = "${eap.match-engine.trade-outbox-relay.poll-interval-ms:500}")
    public void pollAndPublish() {
        boolean continueDraining;
        do {
            Instant batchStartedAt = Instant.now();
            Instant selectStartedAt = Instant.now();
            List<TradeOutboxEntity> pending;
            try {
                pending = tradeOutboxRepository
                        .findByStatusAndNextRetryAtLessThanEqualOrderByCreatedAtAsc(
                                "PENDING",
                                LocalDateTime.now(),
                                PageRequest.of(0, batchSize));
            } finally {
                metrics.recordSelect(Duration.between(selectStartedAt, Instant.now()));
            }
            if (pending.isEmpty()) {
                return;
            }

            boolean batchSucceeded = true;
            List<PublishAttempt> attempts = new ArrayList<>(pending.size());

            List<PublishResult> publishResults = publishBatch(pending);
            for (PublishResult result : publishResults) {
                if (result.succeeded()) {
                    attempts.add(new PublishAttempt(result.entry(), result.correlationData(), result.startedAt()));
                } else {
                    batchSucceeded = false;
                    metrics.publishFailed();
                    recordFailure(result.entry(), result.failure());
                    metrics.recordPublish(Duration.between(result.startedAt(), Instant.now()));
                }
            }

            long confirmationDeadlineNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(confirmTimeoutMs);
            List<PublishAttempt> confirmedAttempts = new ArrayList<>(attempts.size());

            for (PublishAttempt attempt : attempts) {
                TradeOutboxEntity entry = attempt.entry();
                Instant confirmStartedAt = Instant.now();
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
                } finally {
                    metrics.recordConfirm(Duration.between(confirmStartedAt, Instant.now()));
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
            metrics.recordBatch(Duration.between(batchStartedAt, Instant.now()));
        } while (continueDraining);
    }

    private List<PublishResult> publishBatch(List<TradeOutboxEntity> pending) {
        if (publishConcurrency == 1 || pending.size() <= 1) {
            List<PublishResult> results = new ArrayList<>(pending.size());
            for (TradeOutboxEntity entry : pending) {
                results.add(publishOne(entry, rabbitTemplate));
            }
            return results;
        }

        List<List<TradeOutboxEntity>> chunks = partition(pending, publishConcurrency);
        List<CompletableFuture<List<PublishResult>>> futures = chunks.stream()
                .map(chunk -> CompletableFuture.supplyAsync(() -> publishChunk(chunk), publishExecutor))
                .toList();
        return futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .toList();
    }

    private List<PublishResult> publishChunk(List<TradeOutboxEntity> chunk) {
        List<PublishResult> results = new ArrayList<>(chunk.size());
        try {
            rabbitTemplate.invoke(operations -> {
                for (TradeOutboxEntity entry : chunk) {
                    results.add(publishOne(entry, operations));
                }
                return null;
            });
        } catch (Exception e) {
            int publishedOrFailed = results.size();
            for (int i = publishedOrFailed; i < chunk.size(); i++) {
                results.add(PublishResult.failure(chunk.get(i), Instant.now(), e));
            }
        }
        return results;
    }

    private List<List<TradeOutboxEntity>> partition(List<TradeOutboxEntity> pending, int maxChunks) {
        int chunkCount = Math.min(maxChunks, pending.size());
        int chunkSize = (int) Math.ceil(pending.size() / (double) chunkCount);
        List<List<TradeOutboxEntity>> chunks = new ArrayList<>(chunkCount);
        for (int start = 0; start < pending.size(); start += chunkSize) {
            chunks.add(pending.subList(start, Math.min(start + chunkSize, pending.size())));
        }
        return chunks;
    }

    private PublishResult publishOne(TradeOutboxEntity entry, RabbitOperations operations) {
        Instant startedAt = Instant.now();
        Instant enqueueStartedAt = Instant.now();
        try {
            CorrelationData correlationData = new CorrelationData(entry.getId().toString());
            operations.send(
                    RabbitMQConstants.TRADE_EXCHANGE,
                    entry.getRoutingKey(),
                    toJsonMessage(entry),
                    correlationData);
            return PublishResult.success(entry, correlationData, startedAt);
        } catch (Exception e) {
            return PublishResult.failure(entry, startedAt, e);
        } finally {
            metrics.recordPublishEnqueue(Duration.between(enqueueStartedAt, Instant.now()));
        }
    }

    private Message toJsonMessage(TradeOutboxEntity entry) {
        if (!"TradeExecutedEvent".equals(entry.getEventType())) {
            throw new IllegalArgumentException("Unknown trade outbox event type: " + entry.getEventType());
        }
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return new Message(entry.getPayload().getBytes(StandardCharsets.UTF_8), properties);
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
        Instant markStartedAt = Instant.now();
        int marked;
        try {
            marked = tradeOutboxRepository.markPendingAsSent(ids, updatedAt);
        } finally {
            metrics.recordMarkSent(Duration.between(markStartedAt, Instant.now()));
        }
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

    private record PublishResult(
            TradeOutboxEntity entry,
            CorrelationData correlationData,
            Instant startedAt,
            Exception failure) {

        static PublishResult success(TradeOutboxEntity entry, CorrelationData correlationData, Instant startedAt) {
            return new PublishResult(entry, correlationData, startedAt, null);
        }

        static PublishResult failure(TradeOutboxEntity entry, Instant startedAt, Exception failure) {
            return new PublishResult(entry, null, startedAt, failure);
        }

        boolean succeeded() {
            return failure == null;
        }
    }

    private static class TradeOutboxPublishThreadFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "trade-outbox-publisher-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
