package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderTradeAppliedEvent;
import com.eap.common.event.WalletTradeSettledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.eap.common.constants.RabbitMQConstants.MATCH_ENGINE_ORDER_TRADE_APPLIED_QUEUE;
import static com.eap.common.constants.RabbitMQConstants.MATCH_ENGINE_WALLET_TRADE_SETTLED_QUEUE;

@Component
@RequiredArgsConstructor
@Slf4j
public class TradeCompletionListener {

    private final TradeCompletionService tradeCompletionService;

    @RabbitListener(
            queues = MATCH_ENGINE_ORDER_TRADE_APPLIED_QUEUE,
            containerFactory = "tradeCompletionBatchListenerContainerFactory",
            concurrency = "${eap.match-engine.listeners.order-trade-applied.concurrency:2}")
    public void handleOrderTradeApplied(List<OrderTradeAppliedEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        tradeCompletionService.markOrderAppliedBatch(events);
        log.debug("Order trade applied marker batch consumed: size={}", events.size());
    }

    @RabbitListener(
            queues = MATCH_ENGINE_WALLET_TRADE_SETTLED_QUEUE,
            containerFactory = "tradeCompletionBatchListenerContainerFactory",
            concurrency = "${eap.match-engine.listeners.wallet-trade-settled.concurrency:2}")
    public void handleWalletTradeSettled(List<WalletTradeSettledEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        tradeCompletionService.markWalletSettledBatch(events);
        log.debug("Wallet trade settled marker batch consumed: size={}", events.size());
    }
}
