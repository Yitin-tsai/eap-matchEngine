package com.eap.eap_matchengine.application;

import com.eap.common.event.AuctionBidConfirmedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static com.eap.common.constants.RabbitMQConstants.MATCH_ENGINE_AUCTION_BID_CONFIRMED_QUEUE;

/**
 * Listener for wallet-confirmed auction bids.
 *
 * Only bids that have been validated and locked by the wallet module
 * reach this listener, ensuring auction fairness (no unfunded bids).
 *
 * Converts the confirmed event to JSON and collects into Redis via Lua script.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuctionBidConfirmedListener {

    private final AuctionRedisService auctionRedisService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = MATCH_ENGINE_AUCTION_BID_CONFIRMED_QUEUE)
    public void handleConfirmedBid(AuctionBidConfirmedEvent event) {
        log.info("Confirmed auction bid received: auctionId={}, userId={}, side={}, totalLocked={}",
                event.getAuctionId(), event.getUserId(), event.getSide(), event.getTotalLocked());

        try {
            String bidJson = objectMapper.writeValueAsString(event);

            int result = auctionRedisService.collectBid(
                    event.getAuctionId(),
                    event.getSide().toLowerCase(),
                    event.getUserId().toString(),
                    bidJson);

            if (result == 1) {
                log.info("Confirmed bid collected to Redis: auctionId={}, userId={}",
                        event.getAuctionId(), event.getUserId());
            } else if (result == -1) {
                log.warn("Confirmed bid rejected (gate closed): auctionId={}, userId={}",
                        event.getAuctionId(), event.getUserId());
            } else if (result == -2) {
                log.warn("Confirmed bid rejected (duplicate): auctionId={}, userId={}",
                        event.getAuctionId(), event.getUserId());
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize confirmed bid: auctionId={}, userId={}",
                    event.getAuctionId(), event.getUserId(), e);
        }
    }
}
