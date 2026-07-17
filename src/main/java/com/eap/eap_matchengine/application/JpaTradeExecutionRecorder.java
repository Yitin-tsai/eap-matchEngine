package com.eap.eap_matchengine.application;

import com.eap.common.constants.RabbitMQConstants;
import com.eap.common.event.TradeExecutedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "eap.match-engine.trade-persistence.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class JpaTradeExecutionRecorder implements TradeExecutionRecorder {

    private final JdbcTemplate jdbcTemplate;
    private final TradeCompletionService tradeCompletionService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void record(TradeExecutedEvent event) {
        int insertedOutbox = jdbcTemplate.update("""
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
                    (event_type, aggregate_type, aggregate_id, routing_key, payload)
                SELECT ?, ?, inserted_trade.trade_id, ?, ?
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
                RabbitMQConstants.TRADE_EXECUTED_KEY,
                serialize(event));
        if (insertedOutbox == 0) {
            throw new IllegalStateException("Trade already executed: tradeId=" + event.getTradeId());
        }
        tradeCompletionService.markTradeExecuted(event);
    }

    private String serialize(TradeExecutedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize TradeExecutedEvent: tradeId=" + event.getTradeId(), e);
        }
    }
}
