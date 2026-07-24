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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Slf4j
@ConditionalOnProperty(
        name = "eap.match-engine.trade-checkpoint-relay.enabled",
        havingValue = "true")
public class TradeExecutionCheckpointRelay {

    private static final String RELAY_NAME = "trade-executed-rabbitmq";

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final TradeOutboxMetrics metrics;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final boolean batchConfirmEnabled;
    private final long confirmTimeoutMs;

    public TradeExecutionCheckpointRelay(
            NamedParameterJdbcTemplate jdbcTemplate,
            RabbitTemplate rabbitTemplate,
            TradeOutboxMetrics metrics,
            ObjectMapper objectMapper,
            @Value("${eap.match-engine.trade-checkpoint-relay.batch-size:500}") int batchSize,
            @Value("${eap.match-engine.trade-checkpoint-relay.batch-confirm-enabled:false}") boolean batchConfirmEnabled,
            @Value("${eap.match-engine.trade-checkpoint-relay.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.batchConfirmEnabled = batchConfirmEnabled;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${eap.match-engine.trade-checkpoint-relay.poll-interval-ms:100}")
    public void pollAndPublish() {
        boolean continueDraining;
        do {
            Instant batchStartedAt = Instant.now();
            List<TradeExecutionRow> pending = selectPendingTrades();
            if (pending.isEmpty()) {
                return;
            }

            List<PublishAttempt> attempts = new ArrayList<>(pending.size());
            Instant publishStageStartedAt = Instant.now();
            boolean batchSucceeded = true;
            try {
                attempts.addAll(publishBatch(pending));
            } catch (Exception e) {
                batchSucceeded = false;
                recordFailure(pending, e);
            } finally {
                metrics.recordPublishStage(Duration.between(publishStageStartedAt, Instant.now()));
            }

            if (batchSucceeded && !batchConfirmEnabled) {
                long confirmationDeadlineNanos = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(confirmTimeoutMs);
                Instant confirmStageStartedAt = Instant.now();
                try {
                    for (PublishAttempt attempt : attempts) {
                        Instant confirmStartedAt = Instant.now();
                        awaitBrokerConfirmation(attempt, confirmationDeadlineNanos);
                        metrics.recordConfirm(Duration.between(confirmStartedAt, Instant.now()));
                    }
                } catch (Exception e) {
                    batchSucceeded = false;
                    recordFailure(pending, e);
                } finally {
                    metrics.recordConfirmWall(Duration.between(confirmStageStartedAt, Instant.now()));
                }
            }

            if (batchSucceeded) {
                metrics.recordPostConfirmMarkGap(Duration.ZERO);
                markCheckpoint(pending.get(pending.size() - 1));
                Instant completedAt = Instant.now();
                for (PublishAttempt attempt : attempts) {
                    metrics.published();
                    metrics.recordPublish(Duration.between(attempt.startedAt(), completedAt));
                }
            }

            continueDraining = batchSucceeded && pending.size() == batchSize;
            metrics.recordBatch(Duration.between(batchStartedAt, Instant.now()));
        } while (continueDraining);
    }

    private List<TradeExecutionRow> selectPendingTrades() {
        Instant selectStartedAt = Instant.now();
        try {
            return jdbcTemplate.query("""
                    WITH checkpoint AS (
                        SELECT last_created_at, last_trade_id
                        FROM match_engine.trade_publish_checkpoints
                        WHERE relay_name = :relayName
                    )
                    SELECT trade.trade_id, trade.sequence, trade.legacy_match_id, trade.market_id,
                           trade.buyer_id, trade.seller_id, trade.buyer_order_id, trade.seller_order_id,
                           trade.buyer_market_sequence, trade.seller_market_sequence,
                           trade.origin_buyer_price, trade.origin_seller_price,
                           trade.deal_price, trade.quantity, trade.occurred_at, trade.created_at
                    FROM match_engine.trade_executions trade
                    WHERE NOT EXISTS (SELECT 1 FROM checkpoint)
                       OR (trade.created_at, trade.trade_id) >
                          ((SELECT last_created_at FROM checkpoint), (SELECT last_trade_id FROM checkpoint))
                    ORDER BY trade.created_at, trade.trade_id
                    LIMIT :limit
                    """, new MapSqlParameterSource()
                    .addValue("relayName", RELAY_NAME)
                    .addValue("limit", batchSize), (rs, rowNum) -> new TradeExecutionRow(
                    rs.getString("trade_id"),
                    rs.getObject("sequence", Long.class),
                    rs.getObject("legacy_match_id", Long.class),
                    rs.getString("market_id"),
                    rs.getObject("buyer_id", UUID.class),
                    rs.getObject("seller_id", UUID.class),
                    rs.getObject("buyer_order_id", UUID.class),
                    rs.getObject("seller_order_id", UUID.class),
                    rs.getObject("buyer_market_sequence", Long.class),
                    rs.getObject("seller_market_sequence", Long.class),
                    rs.getObject("origin_buyer_price", Integer.class),
                    rs.getObject("origin_seller_price", Integer.class),
                    rs.getObject("deal_price", Integer.class),
                    rs.getObject("quantity", Integer.class),
                    rs.getObject("occurred_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class)));
        } finally {
            metrics.recordSelect(Duration.between(selectStartedAt, Instant.now()));
        }
    }

    private List<PublishAttempt> publishBatch(List<TradeExecutionRow> pending) {
        List<PublishAttempt> attempts = new ArrayList<>(pending.size());
        rabbitTemplate.invoke(operations -> {
            for (TradeExecutionRow row : pending) {
                attempts.add(publishOne(row, operations));
            }
            if (batchConfirmEnabled && !attempts.isEmpty()) {
                Instant confirmStartedAt = Instant.now();
                operations.waitForConfirmsOrDie(confirmTimeoutMs);
                Duration confirmDuration = Duration.between(confirmStartedAt, Instant.now());
                metrics.recordConfirmWall(confirmDuration);
                Duration perMessageDuration = confirmDuration.dividedBy(attempts.size());
                for (int i = 0; i < attempts.size(); i++) {
                    metrics.recordConfirm(perMessageDuration);
                }
                for (PublishAttempt attempt : attempts) {
                    if (attempt.correlationData().getReturned() != null) {
                        throw new AmqpException("Unroutable TradeExecutedEvent: tradeId=" + attempt.row().tradeId());
                    }
                }
            }
            return null;
        });
        return attempts;
    }

    private PublishAttempt publishOne(TradeExecutionRow row, RabbitOperations operations) {
        Instant startedAt = Instant.now();
        Instant enqueueStartedAt = Instant.now();
        try {
            CorrelationData correlationData = new CorrelationData(row.tradeId());
            operations.send(
                    RabbitMQConstants.TRADE_EXCHANGE,
                    RabbitMQConstants.TRADE_EXECUTED_KEY,
                    toJsonMessage(row),
                    correlationData);
            return new PublishAttempt(row, correlationData, startedAt);
        } finally {
            metrics.recordPublishEnqueue(Duration.between(enqueueStartedAt, Instant.now()));
        }
    }

    private Message toJsonMessage(TradeExecutionRow row) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return new Message(payload(row).getBytes(StandardCharsets.UTF_8), properties);
    }

    private String payload(TradeExecutionRow row) {
        try {
            return objectMapper.writeValueAsString(row.toEvent());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize checkpoint TradeExecutedEvent: tradeId="
                    + row.tradeId(), e);
        }
    }

    private void awaitBrokerConfirmation(PublishAttempt attempt, long confirmationDeadlineNanos) throws Exception {
        long remainingNanos = confirmationDeadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new TimeoutException("Trade checkpoint relay publisher confirm batch timed out");
        }
        CorrelationData.Confirm confirm =
                attempt.correlationData().getFuture().get(remainingNanos, TimeUnit.NANOSECONDS);
        if (!confirm.isAck()) {
            throw new AmqpException("RabbitMQ nack: " + confirm.getReason());
        }
        if (attempt.correlationData().getReturned() != null) {
            throw new AmqpException("Unroutable TradeExecutedEvent: tradeId=" + attempt.row().tradeId());
        }
    }

    private void markCheckpoint(TradeExecutionRow row) {
        Instant markStartedAt = Instant.now();
        try {
            jdbcTemplate.update("""
                    INSERT INTO match_engine.trade_publish_checkpoints
                        (relay_name, last_created_at, last_trade_id, attempt_count,
                         last_error, updated_at)
                    VALUES
                        (:relayName, :lastCreatedAt, :lastTradeId, 0, NULL, CURRENT_TIMESTAMP)
                    ON CONFLICT (relay_name) DO UPDATE
                    SET last_created_at = EXCLUDED.last_created_at,
                        last_trade_id = EXCLUDED.last_trade_id,
                        attempt_count = 0,
                        last_error = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    """, new MapSqlParameterSource()
                    .addValue("relayName", RELAY_NAME)
                    .addValue("lastCreatedAt", row.createdAt())
                    .addValue("lastTradeId", row.tradeId()));
        } finally {
            metrics.recordMarkSent(Duration.between(markStartedAt, Instant.now()));
        }
    }

    private void recordFailure(List<TradeExecutionRow> pending, Exception failure) {
        metrics.publishFailed();
        String error = failure.getClass().getSimpleName() + ": "
                + (failure.getMessage() == null ? "no message" : failure.getMessage());
        String truncatedError = error.substring(0, Math.min(error.length(), 1000));
        jdbcTemplate.update("""
                INSERT INTO match_engine.trade_publish_checkpoints
                    (relay_name, last_trade_id, attempt_count, last_error, updated_at)
                VALUES
                    (:relayName, '', 1, :lastError, CURRENT_TIMESTAMP)
                ON CONFLICT (relay_name) DO UPDATE
                SET attempt_count = trade_publish_checkpoints.attempt_count + 1,
                    last_error = EXCLUDED.last_error,
                    updated_at = CURRENT_TIMESTAMP
                """, new MapSqlParameterSource()
                .addValue("relayName", RELAY_NAME)
                .addValue("lastError", truncatedError));
        log.warn("Trade checkpoint relay publish failed; checkpoint not advanced: batchSize={}, error={}",
                pending.size(), truncatedError);
    }

    private record TradeExecutionRow(
            String tradeId,
            Long sequence,
            Long legacyMatchId,
            String marketId,
            UUID buyerId,
            UUID sellerId,
            UUID buyerOrderId,
            UUID sellerOrderId,
            Long buyerMarketSequence,
            Long sellerMarketSequence,
            Integer originBuyerPrice,
            Integer originSellerPrice,
            Integer dealPrice,
            Integer quantity,
            LocalDateTime occurredAt,
            LocalDateTime createdAt) {

        private TradeExecutedEvent toEvent() {
            return TradeExecutedEvent.builder()
                    .tradeId(tradeId)
                    .sequence(sequence)
                    .legacyMatchId(legacyMatchId == null ? null : legacyMatchId.intValue())
                    .marketId(marketId)
                    .buyerId(buyerId)
                    .sellerId(sellerId)
                    .buyerOrderId(buyerOrderId)
                    .sellerOrderId(sellerOrderId)
                    .buyerMarketSequence(buyerMarketSequence)
                    .sellerMarketSequence(sellerMarketSequence)
                    .originBuyerPrice(originBuyerPrice)
                    .originSellerPrice(originSellerPrice)
                    .dealPrice(dealPrice)
                    .quantity(quantity)
                    .occurredAt(occurredAt)
                    .build();
        }
    }

    private record PublishAttempt(
            TradeExecutionRow row,
            CorrelationData correlationData,
            Instant startedAt) {
    }
}
