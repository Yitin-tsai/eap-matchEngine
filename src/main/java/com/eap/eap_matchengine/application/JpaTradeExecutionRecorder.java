package com.eap.eap_matchengine.application;

import com.eap.common.constants.RabbitMQConstants;
import com.eap.common.event.TradeExecutedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;

@Component
@ConditionalOnProperty(
        name = "eap.match-engine.trade-persistence.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class JpaTradeExecutionRecorder implements TradeExecutionRecorder {

    private final JdbcTemplate jdbcTemplate;
    private final MatchingEngineMetrics metrics;
    private final boolean outboxWriteEnabled;

    public JpaTradeExecutionRecorder(
            JdbcTemplate jdbcTemplate,
            MatchingEngineMetrics metrics,
            @Value("${eap.match-engine.trade-outbox.write-enabled:true}") boolean outboxWriteEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.metrics = metrics;
        this.outboxWriteEnabled = outboxWriteEnabled;
    }

    @Override
    @Transactional
    public void record(TradeExecutedEvent event) {
        record(event, null);
    }

    @Override
    @Transactional
    public boolean record(TradeExecutedEvent event, ReservationCleanupTask reservationCleanupTask) {
        Instant transactionStartedAt = Instant.now();
        Instant[] transactionBodyFinishedAt = new Instant[1];
        registerTransactionCompletionMetrics(transactionStartedAt, transactionBodyFinishedAt);
        try {
            Instant serializeStartedAt = Instant.now();
            metrics.recordTradeRecordSerialize(Duration.between(serializeStartedAt, Instant.now()));
            Instant insertStartedAt = Instant.now();
            int insertedTrade;
            try {
                insertedTrade = outboxWriteEnabled ? insertTradeAndOutbox(event) : insertTradeOnly(event);
            } finally {
                metrics.recordTradeRecordInsert(Duration.between(insertStartedAt, Instant.now()));
            }
            if (insertedTrade == 0) {
                throw new IllegalStateException("Trade already executed: tradeId=" + event.getTradeId());
            }
            if (reservationCleanupTask != null) {
                insertReservationCleanupTask(reservationCleanupTask);
            }
        } finally {
            transactionBodyFinishedAt[0] = Instant.now();
            metrics.recordTradeRecordTransactionBody(Duration.between(transactionStartedAt, transactionBodyFinishedAt[0]));
            recordCompletionMetricsWithoutSpringTransaction(transactionStartedAt, transactionBodyFinishedAt[0]);
        }
        return reservationCleanupTask != null;
    }

    private int insertTradeAndOutbox(TradeExecutedEvent event) {
        return jdbcTemplate.update("""
            WITH inserted_trade AS (
                INSERT INTO match_engine.trade_executions
                    (trade_id, sequence, legacy_match_id, market_id,
                     buyer_id, seller_id, buyer_order_id, seller_order_id,
                     buyer_market_sequence, seller_market_sequence,
                     origin_buyer_price, origin_seller_price, deal_price, quantity, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (trade_id) DO NOTHING
                RETURNING trade_id
            )
            INSERT INTO match_engine.trade_outbox
                (event_type, aggregate_type, aggregate_id, routing_key)
            SELECT ?, ?, inserted_trade.trade_id, ?
            FROM inserted_trade
            """,
            event.getTradeId(),
            event.getSequence(),
            event.getLegacyMatchId().longValue(),
            event.getMarketId(),
            event.getBuyerId(),
            event.getSellerId(),
            event.getBuyerOrderId(),
            event.getSellerOrderId(),
            event.getBuyerMarketSequence(),
            event.getSellerMarketSequence(),
            event.getOriginBuyerPrice(),
            event.getOriginSellerPrice(),
            event.getDealPrice(),
            event.getQuantity(),
            event.getOccurredAt(),
            "TradeExecutedEvent",
            "TRADE",
            RabbitMQConstants.TRADE_EXECUTED_KEY);
    }

    private int insertTradeOnly(TradeExecutedEvent event) {
        return jdbcTemplate.update("""
            INSERT INTO match_engine.trade_executions
                (trade_id, sequence, legacy_match_id, market_id,
                 buyer_id, seller_id, buyer_order_id, seller_order_id,
                 buyer_market_sequence, seller_market_sequence,
                 origin_buyer_price, origin_seller_price, deal_price, quantity, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (trade_id) DO NOTHING
            """,
            event.getTradeId(),
            event.getSequence(),
            event.getLegacyMatchId().longValue(),
            event.getMarketId(),
            event.getBuyerId(),
            event.getSellerId(),
            event.getBuyerOrderId(),
            event.getSellerOrderId(),
            event.getBuyerMarketSequence(),
            event.getSellerMarketSequence(),
            event.getOriginBuyerPrice(),
            event.getOriginSellerPrice(),
            event.getDealPrice(),
            event.getQuantity(),
            event.getOccurredAt());
    }

    private void insertReservationCleanupTask(ReservationCleanupTask task) {
        jdbcTemplate.update("""
            INSERT INTO match_engine.reservation_cleanup_tasks
                (trade_id, order_id, user_id)
            VALUES (?, ?, ?)
            ON CONFLICT (trade_id) DO NOTHING
            """,
            task.tradeId(),
            task.orderId(),
            task.userId());
    }

    private void registerTransactionCompletionMetrics(Instant transactionStartedAt, Instant[] transactionBodyFinishedAt) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                Instant completedAt = Instant.now();
                metrics.recordTradeRecordTransactionTotal(Duration.between(transactionStartedAt, completedAt));
                Instant bodyFinishedAt = transactionBodyFinishedAt[0];
                if (bodyFinishedAt != null) {
                    metrics.recordTradeRecordCommitGap(Duration.between(bodyFinishedAt, completedAt));
                }
            }
        });
    }

    private void recordCompletionMetricsWithoutSpringTransaction(
            Instant transactionStartedAt,
            Instant transactionBodyFinishedAt) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        metrics.recordTradeRecordTransactionTotal(Duration.between(transactionStartedAt, transactionBodyFinishedAt));
        metrics.recordTradeRecordCommitGap(Duration.ZERO);
    }

}
