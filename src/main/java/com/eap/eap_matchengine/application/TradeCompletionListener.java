package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderTradeAppliedEvent;
import com.eap.common.event.WalletTradeSettledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static com.eap.common.constants.RabbitMQConstants.MATCH_ENGINE_ORDER_TRADE_APPLIED_QUEUE;
import static com.eap.common.constants.RabbitMQConstants.MATCH_ENGINE_WALLET_TRADE_SETTLED_QUEUE;

@Component
@RequiredArgsConstructor
@Slf4j
public class TradeCompletionListener {

    private final TradeCompletionService tradeCompletionService;

    @RabbitListener(
            queues = MATCH_ENGINE_ORDER_TRADE_APPLIED_QUEUE,
            concurrency = "${eap.match-engine.listeners.order-trade-applied.concurrency:2}")
    public void handleOrderTradeApplied(OrderTradeAppliedEvent event) {
        tradeCompletionService.markOrderApplied(event);
        log.debug("Order trade applied marker consumed: tradeId={}, buyerOrderId={}, sellerOrderId={}",
                event.getTradeId(), event.getBuyerOrderId(), event.getSellerOrderId());
    }

    @RabbitListener(
            queues = MATCH_ENGINE_WALLET_TRADE_SETTLED_QUEUE,
            concurrency = "${eap.match-engine.listeners.wallet-trade-settled.concurrency:2}")
    public void handleWalletTradeSettled(WalletTradeSettledEvent event) {
        tradeCompletionService.markWalletSettled(event);
        log.debug("Wallet trade settled marker consumed: tradeId={}", event.getTradeId());
    }
}
