package com.eap.eap_matchengine.application;

import com.eap.common.constants.RabbitMQConstants;
import com.eap.common.event.TradeExecutedEvent;
import com.eap.eap_matchengine.configuration.repository.TradeExecutionRepository;
import com.eap.eap_matchengine.configuration.repository.TradeOutboxRepository;
import com.eap.eap_matchengine.domain.entity.TradeExecutionEntity;
import com.eap.eap_matchengine.domain.entity.TradeOutboxEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "eap.match-engine.trade-persistence.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class JpaTradeExecutionRecorder implements TradeExecutionRecorder {

    private final TradeExecutionRepository tradeExecutionRepository;
    private final TradeOutboxRepository tradeOutboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void record(TradeExecutedEvent event) {
        tradeExecutionRepository.findByTradeId(event.getTradeId())
                .ifPresent(existing -> {
                    throw new IllegalStateException("Trade already executed: tradeId=" + event.getTradeId());
                });

        tradeExecutionRepository.save(new TradeExecutionEntity(
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
                event.getOccurredAt()));

        tradeOutboxRepository.save(new TradeOutboxEntity(
                "TradeExecutedEvent",
                "TRADE",
                event.getTradeId(),
                RabbitMQConstants.TRADE_EXECUTED_KEY,
                serialize(event)));
    }

    private String serialize(TradeExecutedEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize TradeExecutedEvent: tradeId=" + event.getTradeId(), e);
        }
    }
}
