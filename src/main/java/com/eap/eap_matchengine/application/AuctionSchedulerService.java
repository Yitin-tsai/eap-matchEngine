package com.eap.eap_matchengine.application;

import com.eap.common.dto.AuctionConfigDto;
import com.eap.common.event.AuctionClearedEvent;
import com.eap.common.event.AuctionCreatedEvent;
import com.eap.common.event.AuctionBidSubmittedEvent;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.eap.common.constants.RabbitMQConstants.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Auction lifecycle scheduler.
 * Opens new auctions at :00 every hour and clears them at :10.
 * Uses Redisson distributed lock to prevent duplicate execution in clustered deployments.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionSchedulerService {

    private final AuctionRedisService auctionRedisService;
    private final AuctionClearingService clearingService;
    private final RabbitTemplate rabbitTemplate;
    private final RedissonClient redissonClient;

    private static final DateTimeFormatter AUCTION_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHH");

    @Value("${auction.duration-minutes:10}")
    private int durationMinutes;

    /**
     * Opens a new auction at the top of every hour (:00).
     * Generates auction ID based on delivery hour (current hour + 1).
     * Reads global config for price floor/ceiling.
     * Publishes AuctionCreatedEvent.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void openAuction() {
        if (!auctionRedisService.getGlobalConfig().isAuctionEnabled()) {
            log.debug("Auction scheduling is disabled");
            return;
        }

        RLock lock = redissonClient.getLock("auction:scheduler:open");
        try {
            if (!lock.tryLock(5, 30, TimeUnit.SECONDS)) {
                log.warn("Failed to acquire open auction lock, another instance may be handling it");
                return;
            }

            try {
                LocalDateTime now = LocalDateTime.now();
                LocalDateTime deliveryHour = now.plusHours(1).withMinute(0).withSecond(0).withNano(0);
                String auctionId = "AUC-" + deliveryHour.format(AUCTION_ID_FORMAT);

                AuctionConfigDto config = auctionRedisService.getGlobalConfig();
                LocalDateTime openTime = now;
                LocalDateTime closeTime = now.plusMinutes(durationMinutes);

                auctionRedisService.initAuction(auctionId, openTime, closeTime,
                        config.getPriceFloor(), config.getPriceCeiling());

                AuctionCreatedEvent event = AuctionCreatedEvent.builder()
                        .auctionId(auctionId)
                        .deliveryHour(deliveryHour)
                        .openTime(openTime)
                        .closeTime(closeTime)
                        .priceFloor(config.getPriceFloor())
                        .priceCeiling(config.getPriceCeiling())
                        .createdAt(now)
                        .build();

                rabbitTemplate.convertAndSend(AUCTION_EXCHANGE, AUCTION_CREATED_KEY, event);
                log.info("Auction opened: auctionId={}, deliveryHour={}, closeTime={}",
                        auctionId, deliveryHour, closeTime);
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while acquiring open auction lock", e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Closes the current auction gate and executes clearing at :10 every hour.
     * Reads all bids from Redis, runs the clearing algorithm, and publishes
     * AuctionClearedEvent with results.
     */
    @Scheduled(cron = "0 10 * * * *")
    public void closeAndClear() {
        if (!auctionRedisService.getGlobalConfig().isAuctionEnabled()) {
            log.debug("Auction scheduling is disabled");
            return;
        }

        RLock lock = redissonClient.getLock("auction:scheduler:clear");
        try {
            if (!lock.tryLock(5, 60, TimeUnit.SECONDS)) {
                log.warn("Failed to acquire clearing lock, another instance may be handling it");
                return;
            }

            try {
                String auctionId = auctionRedisService.getCurrentAuctionId();
                if (auctionId == null) {
                    log.warn("No active auction to clear");
                    return;
                }

                log.info("Starting auction clearing: auctionId={}", auctionId);
                boolean clearedEventPublished = false;

                try {
                    // Close gate to prevent new bids
                    auctionRedisService.closeGate(auctionId);

                    // Retrieve all bids
                    AuctionRedisService.AuctionBids bids = auctionRedisService.getAllBids(auctionId);

                    // Execute clearing algorithm
                    AuctionClearingService.ClearingResult result = clearingService.clear(
                            bids.buyBids(), bids.sellBids());

                    // Build and publish clearing event
                    AuctionClearedEvent event = AuctionClearedEvent.builder()
                            .auctionId(auctionId)
                            .clearingPrice(result.getClearingPrice())
                            .clearingVolume(result.getClearingVolume())
                            .status(result.getStatus())
                            .results(result.getResults().stream()
                                    .map(r -> new AuctionClearedEvent.AuctionBidResult(
                                            r.getUserId(),
                                            r.getSide(),
                                            r.getBidAmount(),
                                            r.getClearedAmount(),
                                            r.getSettlementAmount(),
                                            r.getOriginalTotalLocked()))
                                    .collect(Collectors.toList()))
                            .clearedAt(LocalDateTime.now())
                            .build();

                    rabbitTemplate.convertAndSend(AUCTION_EXCHANGE, AUCTION_CLEARED_KEY, event);
                    clearedEventPublished = true;

                    log.info("Auction cleared: auctionId={}, MCP={}, MCV={}, status={}, results={}",
                            auctionId, result.getClearingPrice(), result.getClearingVolume(),
                            result.getStatus(), result.getResults().size());

                    // M-1 fix: cleanup Redis keys after successful clearing
                    auctionRedisService.cleanupAuction(auctionId);

                } catch (Exception e) {
                    log.error("Auction clearing failed: auctionId={}", auctionId, e);
                    // M-2 fix: only publish FAILED event if CLEARED was not already published
                    if (!clearedEventPublished) {
                        try {
                            AuctionClearedEvent failedEvent = AuctionClearedEvent.builder()
                                    .auctionId(auctionId)
                                    .clearingPrice(0)
                                    .clearingVolume(0)
                                    .status("FAILED")
                                    .results(List.of())
                                    .clearedAt(LocalDateTime.now())
                                    .build();
                            rabbitTemplate.convertAndSend(AUCTION_EXCHANGE, AUCTION_CLEARED_KEY, failedEvent);
                        } catch (Exception ex) {
                            log.error("Failed to publish FAILED clearing event: auctionId={}", auctionId, ex);
                        }
                    }
                }
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while acquiring clearing lock", e);
            Thread.currentThread().interrupt();
        }
    }
}
