package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderAssetReservationSucceededEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

import static com.eap.common.constants.RabbitMQConstants.MATCH_ENGINE_ORDER_ASSET_RESERVATION_SUCCEEDED_QUEUE;
import static com.eap.eap_matchengine.configuration.reliability.MatchCdaDatabaseOutageCircuitBreaker.RESERVATION_SUCCEEDED_LISTENER_ID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderAssetReservationSucceededListener {

    private final MatchOrderAdmissionInbox inbox;
    private final MatchOrderAdmissionInboxMetrics inboxMetrics;
    private final MatchingEngineMetrics metrics;

    /**
     * CDA mode only - handles continuous double auction order matching.
     * Auction bids flow through AuctionBidConfirmedListener instead.
     */
    @RabbitListener(
            id = RESERVATION_SUCCEEDED_LISTENER_ID,
            queues = MATCH_ENGINE_ORDER_ASSET_RESERVATION_SUCCEEDED_QUEUE,
            concurrency = "${eap.match-engine.listeners.asset-reservation-succeeded.concurrency:8}")
    public void onReservationSucceeded(OrderAssetReservationSucceededEvent event) {
        Instant startedAt = Instant.now();
        try {
            MatchOrderAdmissionInbox.ReceiveOutcome outcome = inbox.receive(event);
            inboxMetrics.received(outcome);
            log.debug("Asset reservation success persisted for Match admission: orderId={}, outcome={}",
                    event.getOrderId(), outcome);
        } finally {
            metrics.recordOrderAssetReservationSucceededListener(Duration.between(startedAt, Instant.now()));
        }
    }
}
