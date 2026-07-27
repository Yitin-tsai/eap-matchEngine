package com.eap.eap_matchengine.application;

import com.eap.common.constants.RabbitMQConstants;
import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_matchengine.configuration.observability.TradeOutboxMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final TradeOutboxMetrics metrics;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final int publishConcurrency;
    private final boolean batchConfirmEnabled;
    private final ExecutorService publishExecutor;
    private final long confirmTimeoutMs;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    public TradeOutboxRelay(
            JdbcTemplate jdbcTemplate,
            NamedParameterJdbcTemplate namedJdbcTemplate,
            RabbitTemplate rabbitTemplate,
            TradeOutboxMetrics metrics,
            ObjectMapper objectMapper,
            @Value("${eap.match-engine.trade-outbox-relay.batch-size:200}") int batchSize,
            @Value("${eap.match-engine.trade-outbox-relay.publish-concurrency:1}") int publishConcurrency,
            @Value("${eap.match-engine.trade-outbox-relay.batch-confirm-enabled:false}") boolean batchConfirmEnabled,
            @Value("${eap.match-engine.trade-outbox-relay.confirm-timeout-ms:5000}") long confirmTimeoutMs,
            @Value("${eap.match-engine.trade-outbox-relay.max-attempts:10}") int maxAttempts,
            @Value("${eap.match-engine.trade-outbox-relay.initial-backoff-ms:1000}") long initialBackoffMs,
            @Value("${eap.match-engine.trade-outbox-relay.max-backoff-ms:300000}") long maxBackoffMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.publishConcurrency = Math.max(1, publishConcurrency);
        this.batchConfirmEnabled = batchConfirmEnabled;
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
            List<OutboxRow> pending;
            try {
                pending = jdbcTemplate.query("""
                                SELECT outbox.id, outbox.event_type, outbox.aggregate_id,
                                       outbox.routing_key, outbox.payload, outbox.attempt_count,
                                       trade.sequence, trade.legacy_match_id, trade.market_id,
                                       trade.buyer_id, trade.seller_id,
                                       trade.buyer_order_id, trade.seller_order_id,
                                       trade.buyer_market_sequence, trade.seller_market_sequence,
                                       trade.origin_buyer_price, trade.origin_seller_price,
                                       trade.deal_price, trade.quantity, trade.occurred_at
                                FROM match_engine.trade_outbox outbox
                                LEFT JOIN match_engine.trade_executions trade
                                  ON trade.trade_id = outbox.aggregate_id
                                 AND outbox.event_type = 'TradeExecutedEvent'
                                WHERE outbox.status = 'PENDING'
                                  AND outbox.next_retry_at <= CURRENT_TIMESTAMP
                                ORDER BY outbox.created_at, outbox.id
                                LIMIT ?
                                """,
                        (rs, rowNum) -> {
                            Instant rowMappingStartedAt = Instant.now();
                            try {
                                String payload = rs.getString("payload");
                                return new OutboxRow(
                                        rs.getLong("id"),
                                        rs.getString("event_type"),
                                        rs.getString("aggregate_id"),
                                        rs.getString("routing_key"),
                                        payload,
                                        payload == null || payload.isBlank() ? tradeExecutedEvent(rs) : null,
                                        rs.getInt("attempt_count"));
                            } finally {
                                metrics.recordRowMapping(Duration.between(rowMappingStartedAt, Instant.now()));
                            }
                        },
                        batchSize);
            } finally {
                metrics.recordSelect(Duration.between(selectStartedAt, Instant.now()));
            }
            if (pending.isEmpty()) {
                return;
            }
            metrics.recordBatchSize(pending.size());

            boolean batchSucceeded = true;
            List<PublishAttempt> attempts = new ArrayList<>(pending.size());

            Instant publishStageStartedAt = Instant.now();
            List<PublishResult> publishResults;
            try {
                publishResults = publishBatch(pending);
            } finally {
                metrics.recordPublishStage(Duration.between(publishStageStartedAt, Instant.now()));
            }
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
            Instant confirmStageStartedAt = Instant.now();

            if (batchConfirmEnabled) {
                confirmedAttempts.addAll(attempts);
            } else {
                boolean firstConfirm = true;
                for (PublishAttempt attempt : attempts) {
                    OutboxRow entry = attempt.entry();
                    Instant confirmStartedAt = Instant.now();
                    Duration confirmDuration;
                    try {
                        awaitBrokerConfirmation(entry, attempt.correlationData(), confirmationDeadlineNanos);
                        confirmedAttempts.add(attempt);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        metrics.publishFailed();
                        log.warn("Trade outbox relay interrupted while waiting for broker confirmation: id={}", entry.id());
                        return;
                    } catch (Exception e) {
                        batchSucceeded = false;
                        metrics.publishFailed();
                        recordFailure(entry, e);
                        metrics.recordPublish(Duration.between(attempt.startedAt(), Instant.now()));
                    } finally {
                        confirmDuration = Duration.between(confirmStartedAt, Instant.now());
                        metrics.recordConfirm(confirmDuration);
                        if (firstConfirm) {
                            metrics.recordFirstConfirm(confirmDuration);
                            firstConfirm = false;
                        } else {
                            metrics.recordRemainingConfirm(confirmDuration);
                        }
                    }
                }
            }
            Instant confirmStageCompletedAt = Instant.now();
            if (!batchConfirmEnabled) {
                metrics.recordConfirmWall(Duration.between(confirmStageStartedAt, confirmStageCompletedAt));
            }

            if (!confirmedAttempts.isEmpty()) {
                metrics.recordPostConfirmMarkGap(Duration.between(confirmStageCompletedAt, Instant.now()));
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

    private List<PublishResult> publishBatch(List<OutboxRow> pending) {
        if (publishConcurrency == 1 || pending.size() <= 1) {
            return publishChunk(pending);
        }

        List<List<OutboxRow>> chunks = partition(pending, publishConcurrency);
        List<CompletableFuture<List<PublishResult>>> futures = chunks.stream()
                .map(chunk -> CompletableFuture.supplyAsync(() -> publishChunk(chunk), publishExecutor))
                .toList();
        return futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .toList();
    }

    private List<PublishResult> publishChunk(List<OutboxRow> chunk) {
        List<PublishResult> results = new ArrayList<>(chunk.size());
        try {
            rabbitTemplate.invoke(operations -> {
                for (OutboxRow entry : chunk) {
                    results.add(publishOne(entry, operations));
                }
                if (batchConfirmEnabled && !results.isEmpty()) {
                    Instant confirmStartedAt = Instant.now();
                    operations.waitForConfirmsOrDie(confirmTimeoutMs);
                    Duration confirmDuration = Duration.between(confirmStartedAt, Instant.now());
                    recordBatchConfirm(results.size(), confirmDuration);
                    for (PublishResult result : results) {
                        if (result.correlationData().getReturned() != null) {
                            throw new AmqpException(
                                    "Unroutable TradeExecutedEvent: id=" + result.entry().id());
                        }
                    }
                }
                return null;
            });
        } catch (Exception e) {
            if (batchConfirmEnabled && !results.isEmpty()) {
                results.clear();
                for (OutboxRow entry : chunk) {
                    results.add(PublishResult.failure(entry, Instant.now(), e));
                }
                return results;
            }
            int publishedOrFailed = results.size();
            for (int i = publishedOrFailed; i < chunk.size(); i++) {
                results.add(PublishResult.failure(chunk.get(i), Instant.now(), e));
            }
        }
        return results;
    }

    private void recordBatchConfirm(int confirmedCount, Duration confirmDuration) {
        if (confirmedCount <= 0) {
            return;
        }
        Duration perMessageDuration = confirmDuration.dividedBy(confirmedCount);
        metrics.recordConfirmWall(confirmDuration);
        metrics.recordFirstConfirm(confirmDuration);
        for (int i = 0; i < confirmedCount; i++) {
            metrics.recordConfirm(perMessageDuration);
        }
    }

    private List<List<OutboxRow>> partition(List<OutboxRow> pending, int maxChunks) {
        int chunkCount = Math.min(maxChunks, pending.size());
        int chunkSize = (int) Math.ceil(pending.size() / (double) chunkCount);
        List<List<OutboxRow>> chunks = new ArrayList<>(chunkCount);
        for (int start = 0; start < pending.size(); start += chunkSize) {
            chunks.add(pending.subList(start, Math.min(start + chunkSize, pending.size())));
        }
        return chunks;
    }

    private PublishResult publishOne(OutboxRow entry, RabbitOperations operations) {
        Instant startedAt = Instant.now();
        Instant enqueueStartedAt = Instant.now();
        try {
            CorrelationData correlationData = new CorrelationData(Long.toString(entry.id()));
            operations.send(
                    RabbitMQConstants.TRADE_EXCHANGE,
                    entry.routingKey(),
                    toJsonMessage(entry),
                    correlationData);
            return PublishResult.success(entry, correlationData, startedAt);
        } catch (Exception e) {
            return PublishResult.failure(entry, startedAt, e);
        } finally {
            metrics.recordPublishEnqueue(Duration.between(enqueueStartedAt, Instant.now()));
        }
    }

    private Message toJsonMessage(OutboxRow entry) {
        Instant startedAt = Instant.now();
        try {
            if (!"TradeExecutedEvent".equals(entry.eventType())) {
                throw new IllegalArgumentException("Unknown trade outbox event type: " + entry.eventType());
            }
            MessageProperties properties = new MessageProperties();
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding(StandardCharsets.UTF_8.name());
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return new Message(payload(entry).getBytes(StandardCharsets.UTF_8), properties);
        } finally {
            metrics.recordMessageBuild(Duration.between(startedAt, Instant.now()));
        }
    }

    private String payload(OutboxRow entry) {
        if (entry.payload() != null && !entry.payload().isBlank()) {
            return entry.payload();
        }
        if (entry.tradeExecutedEvent() == null) {
            throw new IllegalStateException("Trade outbox row cannot be published without payload or trade fact: id="
                    + entry.id() + ", aggregateId=" + entry.aggregateId());
        }
        Instant rebuildStartedAt = Instant.now();
        try {
            return objectMapper.writeValueAsString(entry.tradeExecutedEvent());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to rebuild TradeExecutedEvent payload: id="
                    + entry.id() + ", aggregateId=" + entry.aggregateId(), e);
        } finally {
            metrics.recordPayloadRebuild(Duration.between(rebuildStartedAt, Instant.now()));
        }
    }

    private TradeExecutedEvent tradeExecutedEvent(java.sql.ResultSet rs) throws java.sql.SQLException {
        Long sequence = rs.getObject("sequence", Long.class);
        if (sequence == null) {
            return null;
        }
        Long legacyMatchId = rs.getObject("legacy_match_id", Long.class);
        return TradeExecutedEvent.builder()
                .tradeId(rs.getString("aggregate_id"))
                .sequence(sequence)
                .legacyMatchId(legacyMatchId == null ? null : legacyMatchId.intValue())
                .marketId(rs.getString("market_id"))
                .buyerId(rs.getObject("buyer_id", UUID.class))
                .sellerId(rs.getObject("seller_id", UUID.class))
                .buyerOrderId(rs.getObject("buyer_order_id", UUID.class))
                .sellerOrderId(rs.getObject("seller_order_id", UUID.class))
                .buyerMarketSequence(rs.getObject("buyer_market_sequence", Long.class))
                .sellerMarketSequence(rs.getObject("seller_market_sequence", Long.class))
                .originBuyerPrice(rs.getObject("origin_buyer_price", Integer.class))
                .originSellerPrice(rs.getObject("origin_seller_price", Integer.class))
                .dealPrice(rs.getObject("deal_price", Integer.class))
                .quantity(rs.getObject("quantity", Integer.class))
                .occurredAt(rs.getObject("occurred_at", LocalDateTime.class))
                .build();
    }

    private void awaitBrokerConfirmation(
            OutboxRow entry,
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
            throw new AmqpException("Unroutable TradeExecutedEvent: id=" + entry.id());
        }
    }

    private void markConfirmedAsSent(List<PublishAttempt> confirmedAttempts) {
        List<Long> ids = confirmedAttempts.stream()
                .map(attempt -> attempt.entry().id())
                .toList();
        LocalDateTime updatedAt = LocalDateTime.now();
        Instant markStartedAt = Instant.now();
        int marked;
        try {
            marked = namedJdbcTemplate.update("""
                    UPDATE match_engine.trade_outbox
                    SET status = 'SENT',
                        next_retry_at = NULL,
                        last_error = NULL,
                        updated_at = :updatedAt
                    WHERE id IN (:ids)
                      AND status = 'PENDING'
                    """, new MapSqlParameterSource()
                    .addValue("updatedAt", updatedAt)
                    .addValue("ids", ids));
        } finally {
            metrics.recordMarkSent(Duration.between(markStartedAt, Instant.now()));
        }
        if (marked != ids.size()) {
            throw new IllegalStateException(
                    "Expected to mark " + ids.size() + " trade outbox records SENT, but updated " + marked);
        }
        metrics.recordConfirmedBatchSize(ids.size());

        Instant completedAt = Instant.now();
        for (PublishAttempt attempt : confirmedAttempts) {
            metrics.published();
            metrics.recordPublish(Duration.between(attempt.startedAt(), completedAt));
            log.debug("Trade outbox event published: id={}, aggregateId={}",
                    attempt.entry().id(), attempt.entry().aggregateId());
        }
    }

    private void recordFailure(OutboxRow entry, Exception failure) {
        int attemptCount = entry.attemptCount() + 1;
        String error = failure.getClass().getSimpleName() + ": "
                + (failure.getMessage() == null ? "no message" : failure.getMessage());
        String truncatedError = error.substring(0, Math.min(error.length(), 1000));
        LocalDateTime updatedAt = LocalDateTime.now();

        if (attemptCount >= maxAttempts) {
            log.error("Trade outbox event permanently failed: id={}, aggregateId={}, attempts={}, error={}",
                    entry.id(), entry.aggregateId(), attemptCount, truncatedError);
            namedJdbcTemplate.update("""
                    UPDATE match_engine.trade_outbox
                    SET attempt_count = :attemptCount,
                        status = 'FAILED',
                        next_retry_at = NULL,
                        last_error = :lastError,
                        updated_at = :updatedAt
                    WHERE id = :id
                      AND status = 'PENDING'
                    """, new MapSqlParameterSource()
                    .addValue("attemptCount", attemptCount)
                    .addValue("lastError", truncatedError)
                    .addValue("updatedAt", updatedAt)
                    .addValue("id", entry.id()));
        } else {
            long backoffMs = calculateBackoffMs(attemptCount);
            LocalDateTime nextRetryAt = updatedAt.plusNanos(TimeUnit.MILLISECONDS.toNanos(backoffMs));
            namedJdbcTemplate.update("""
                    UPDATE match_engine.trade_outbox
                    SET attempt_count = :attemptCount,
                        status = 'PENDING',
                        next_retry_at = :nextRetryAt,
                        last_error = :lastError,
                        updated_at = :updatedAt
                    WHERE id = :id
                      AND status = 'PENDING'
                    """, new MapSqlParameterSource()
                    .addValue("attemptCount", attemptCount)
                    .addValue("nextRetryAt", nextRetryAt)
                    .addValue("lastError", truncatedError)
                    .addValue("updatedAt", updatedAt)
                    .addValue("id", entry.id()));
            metrics.retryScheduled();
            log.warn("Trade outbox publish failed; retry scheduled: id={}, aggregateId={}, attempt={}/{}, backoffMs={}, error={}",
                    entry.id(), entry.aggregateId(), attemptCount, maxAttempts, backoffMs, truncatedError);
        }
    }

    private long calculateBackoffMs(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 30);
        long multiplier = 1L << exponent;
        if (initialBackoffMs > maxBackoffMs / multiplier) {
            return maxBackoffMs;
        }
        return Math.min(initialBackoffMs * multiplier, maxBackoffMs);
    }

    private record OutboxRow(
            long id,
            String eventType,
            String aggregateId,
            String routingKey,
            String payload,
            TradeExecutedEvent tradeExecutedEvent,
            int attemptCount) {
    }

    private record PublishAttempt(
            OutboxRow entry,
            CorrelationData correlationData,
            Instant startedAt) {
    }

    private record PublishResult(
            OutboxRow entry,
            CorrelationData correlationData,
            Instant startedAt,
            Exception failure) {

        static PublishResult success(OutboxRow entry, CorrelationData correlationData, Instant startedAt) {
            return new PublishResult(entry, correlationData, startedAt, null);
        }

        static PublishResult failure(OutboxRow entry, Instant startedAt, Exception failure) {
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
