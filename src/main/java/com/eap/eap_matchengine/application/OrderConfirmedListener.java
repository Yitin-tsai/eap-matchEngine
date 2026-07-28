package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderConfirmedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

import static com.eap.common.constants.RabbitMQConstants.MATCH_ENGINE_ORDER_CONFIRMED_QUEUE;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderConfirmedListener {

    private final MatchingEngineService matchingEngineService;
    private final MatchingEngineMetrics metrics;

    /**
     * CDA mode only - handles continuous double auction order matching.
     * Auction bids flow through AuctionBidConfirmedListener instead.
     */
    @RabbitListener(
            queues = MATCH_ENGINE_ORDER_CONFIRMED_QUEUE,
            concurrency = "${eap.match-engine.listeners.order-confirmed.concurrency:8}")
    public void handleConfirmedOrder(OrderConfirmedEvent event) throws JsonProcessingException {
        Instant startedAt = Instant.now();
        try {
            log.info("Confirmed order received: orderId={}, userId={}, type={}, price={}, amount={}",
                    event.getOrderId(), event.getUserId(), event.getOrderType(),
                    event.getPrice(), event.getAmount());

            matchingEngineService.tryMatch(event);
        } finally {
            metrics.recordOrderConfirmedListener(Duration.between(startedAt, Instant.now()));
        }
    }
}
