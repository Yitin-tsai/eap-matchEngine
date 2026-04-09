package com.eap.eap_matchengine.controller;

import com.eap.common.dto.AuctionBidRequest;
import com.eap.common.dto.AuctionBidResponse;
import com.eap.common.dto.AuctionConfigDto;
import com.eap.common.dto.AuctionStatusDto;
import com.eap.common.event.AuctionBidSubmittedEvent;
import com.eap.eap_matchengine.application.AuctionRedisService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for auction operations.
 * Provides endpoints for bid submission, auction status, and configuration management.
 *
 * Follows the same pattern as OrderController: @Autowired field injection, ResponseEntity wrapping.
 */
@RestController
@RequestMapping("v1/auction")
public class AuctionController {

    @Autowired
    AuctionRedisService auctionRedisService;

    @Autowired
    ObjectMapper objectMapper;

    /**
     * Submit a bid to the current auction.
     * Validates the request, converts to event JSON, and collects via Redis Lua script.
     */
    @PostMapping("bid")
    public ResponseEntity<AuctionBidResponse> submitBid(@RequestBody AuctionBidRequest request) {
        // Validate request
        if (!request.isValid()) {
            return ResponseEntity.badRequest().body(
                    AuctionBidResponse.failure(request.getValidationError()));
        }

        // Resolve auction ID
        String auctionId = request.getAuctionId();
        if (auctionId == null || auctionId.trim().isEmpty()) {
            auctionId = auctionRedisService.getCurrentAuctionId();
            if (auctionId == null) {
                return ResponseEntity.badRequest().body(
                        AuctionBidResponse.failure("No active auction"));
            }
        }

        // Build event JSON for Redis storage
        try {
            int totalLocked;
            if (request.isBuy()) {
                totalLocked = request.getSteps().stream()
                        .mapToInt(s -> s.getPrice() * s.getAmount())
                        .sum();
            } else {
                totalLocked = request.getSteps().stream()
                        .mapToInt(AuctionBidRequest.BidStep::getAmount)
                        .sum();
            }

            AuctionBidSubmittedEvent event = AuctionBidSubmittedEvent.builder()
                    .auctionId(auctionId)
                    .userId(UUID.fromString(request.getUserId()))
                    .side(request.getSide().toUpperCase())
                    .steps(request.getSteps().stream()
                            .map(s -> new AuctionBidSubmittedEvent.BidStep(s.getPrice(), s.getAmount()))
                            .collect(Collectors.toList()))
                    .totalLocked(totalLocked)
                    .createdAt(LocalDateTime.now())
                    .build();

            String bidJson = objectMapper.writeValueAsString(event);

            // Collect bid atomically in Redis via Lua script
            int result = auctionRedisService.collectBid(
                    auctionId, request.getSide().toLowerCase(),
                    request.getUserId(), bidJson);

            if (result == 1) {
                return ResponseEntity.ok(AuctionBidResponse.success(
                        auctionId, request.getUserId(), request.getSide(), totalLocked));
            } else if (result == -1) {
                return ResponseEntity.badRequest().body(
                        AuctionBidResponse.failure("Auction gate is closed"));
            } else if (result == -2) {
                return ResponseEntity.badRequest().body(
                        AuctionBidResponse.failure("Duplicate bid - you have already submitted a bid"));
            } else {
                return ResponseEntity.badRequest().body(
                        AuctionBidResponse.failure("Failed to submit bid"));
            }
        } catch (JsonProcessingException e) {
            return ResponseEntity.internalServerError().body(
                    AuctionBidResponse.failure("Internal error serializing bid"));
        }
    }

    /**
     * Get current auction status including participant count and config.
     */
    @GetMapping("status")
    public ResponseEntity<AuctionStatusDto> getStatus() {
        String auctionId = auctionRedisService.getCurrentAuctionId();
        if (auctionId == null) {
            return ResponseEntity.ok(AuctionStatusDto.builder()
                    .status("NO_ACTIVE_AUCTION")
                    .build());
        }

        Map<String, String> config = auctionRedisService.getAuctionConfig(auctionId);
        long participantCount = auctionRedisService.getParticipantCount(auctionId);

        AuctionStatusDto status = AuctionStatusDto.builder()
                .auctionId(auctionId)
                .status(config.getOrDefault("status", "UNKNOWN"))
                .openTime(parseDateTime(config.get("open_time")))
                .closeTime(parseDateTime(config.get("close_time")))
                .participantCount((int) participantCount)
                .priceFloor(parseInteger(config.get("price_floor")))
                .priceCeiling(parseInteger(config.get("price_ceiling")))
                .build();

        return ResponseEntity.ok(status);
    }

    /**
     * Get global auction configuration.
     */
    @GetMapping("config")
    public ResponseEntity<AuctionConfigDto> getConfig() {
        AuctionConfigDto config = auctionRedisService.getGlobalConfig();
        return ResponseEntity.ok(config);
    }

    /**
     * Update global auction configuration.
     */
    @PutMapping("config")
    public ResponseEntity<AuctionConfigDto> updateConfig(@RequestBody AuctionConfigDto config) {
        if (config.getPriceFloor() == null || config.getPriceFloor() < 0) {
            return ResponseEntity.badRequest().build();
        }
        if (config.getPriceCeiling() == null || config.getPriceCeiling() <= config.getPriceFloor()) {
            return ResponseEntity.badRequest().build();
        }

        auctionRedisService.saveGlobalConfig(config);
        return ResponseEntity.ok(config);
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
