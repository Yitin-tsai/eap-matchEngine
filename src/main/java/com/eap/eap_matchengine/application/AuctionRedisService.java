package com.eap.eap_matchengine.application;

import com.eap.common.dto.AuctionConfigDto;
import com.eap.common.event.AuctionBidSubmittedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import jakarta.annotation.PostConstruct;

/**
 * Service for auction-related Redis operations.
 * Manages auction lifecycle data including bid collection, gate control,
 * and auction configuration in Redis.
 *
 * Follows the same pattern as RedisOrderBookService for Lua script loading
 * and RedisCallback execution.
 */
@Service
@Slf4j
public class AuctionRedisService {

    private static final String AUCTION_PREFIX = "auction:";
    private static final String CURRENT_AUCTION_KEY = "auction:current";
    private static final String GLOBAL_CONFIG_KEY = "auction:config";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    // Lua scripts loaded from classpath
    private String collectBidLuaScript;
    private String getAllBidsLuaScript;

    public AuctionRedisService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Load Lua scripts from classpath during initialization
     */
    @PostConstruct
    public void init() {
        try {
            collectBidLuaScript = loadLuaScript("lua/collect_auction_bid.lua");
            getAllBidsLuaScript = loadLuaScript("lua/get_all_auction_bids.lua");
            log.info("Successfully loaded auction Lua scripts");
        } catch (IOException e) {
            log.error("Failed to load auction Lua scripts", e);
            throw new RuntimeException("Failed to initialize AuctionRedisService", e);
        }
    }

    private String loadLuaScript(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }

    /**
     * Initialize a new auction in Redis.
     * Sets auction config hash with status=OPEN and stores as current auction.
     *
     * @param auctionId  unique auction identifier (e.g., "AUC-2026040910")
     * @param openTime   auction open time
     * @param closeTime  auction close time
     * @param priceFloor minimum allowed price
     * @param priceCeiling maximum allowed price
     */
    public void initAuction(String auctionId, LocalDateTime openTime, LocalDateTime closeTime,
                            Integer priceFloor, Integer priceCeiling) {
        String configKey = AUCTION_PREFIX + auctionId + ":config";

        redisTemplate.opsForHash().put(configKey, "status", "OPEN");
        redisTemplate.opsForHash().put(configKey, "open_time", openTime.toString());
        redisTemplate.opsForHash().put(configKey, "close_time", closeTime.toString());
        redisTemplate.opsForHash().put(configKey, "price_floor", String.valueOf(priceFloor));
        redisTemplate.opsForHash().put(configKey, "price_ceiling", String.valueOf(priceCeiling));

        redisTemplate.opsForValue().set(CURRENT_AUCTION_KEY, auctionId);

        log.info("Initialized auction: auctionId={}, openTime={}, closeTime={}", auctionId, openTime, closeTime);
    }

    /**
     * Atomically collects a bid for an auction using Lua script.
     * The Lua script ensures: gate check + duplicate check + insert are atomic.
     *
     * @param auctionId auction identifier
     * @param side      "buy" or "sell"
     * @param userId    bidder's user ID
     * @param bidJson   serialized bid JSON
     * @return 1=success, -1=gate closed, -2=duplicate bid
     */
    public int collectBid(String auctionId, String side, String userId, String bidJson) {
        String bidsKey = AUCTION_PREFIX + auctionId + ":bids:" + side.toLowerCase();
        String participantsKey = AUCTION_PREFIX + auctionId + ":participants";
        String configKey = AUCTION_PREFIX + auctionId + ":config";

        List<String> keys = List.of(bidsKey, participantsKey, configKey);
        List<String> args = List.of(userId, bidJson);

        Long result = redisTemplate.execute((RedisCallback<Long>) connection -> {
            byte[][] keysBytes = keys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
            byte[][] argsBytes = args.stream().map(a -> a.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);

            byte[][] allParams = new byte[keysBytes.length + argsBytes.length][];
            System.arraycopy(keysBytes, 0, allParams, 0, keysBytes.length);
            System.arraycopy(argsBytes, 0, allParams, keysBytes.length, argsBytes.length);

            Object res = connection.eval(
                    collectBidLuaScript.getBytes(StandardCharsets.UTF_8),
                    ReturnType.INTEGER,
                    keys.size(),
                    allParams
            );
            return res != null ? (Long) res : 0L;
        });

        int code = result != null ? result.intValue() : 0;
        if (code == 1) {
            log.debug("Bid collected: auctionId={}, side={}, userId={}", auctionId, side, userId);
        } else if (code == -1) {
            log.warn("Bid rejected (gate closed): auctionId={}, userId={}", auctionId, userId);
        } else if (code == -2) {
            log.warn("Bid rejected (duplicate): auctionId={}, userId={}", auctionId, userId);
        }
        return code;
    }

    /**
     * Close the auction gate to prevent new bids.
     *
     * @param auctionId auction identifier
     */
    public void closeGate(String auctionId) {
        String configKey = AUCTION_PREFIX + auctionId + ":config";
        redisTemplate.opsForHash().put(configKey, "status", "CLOSED");
        log.info("Auction gate closed: auctionId={}", auctionId);
    }

    /**
     * Retrieves all buy and sell bids for an auction using Lua script.
     *
     * @param auctionId auction identifier
     * @return AuctionBids containing buyBids and sellBids lists
     */
    public AuctionBids getAllBids(String auctionId) {
        String buyBidsKey = AUCTION_PREFIX + auctionId + ":bids:buy";
        String sellBidsKey = AUCTION_PREFIX + auctionId + ":bids:sell";

        List<String> keys = List.of(buyBidsKey, sellBidsKey);

        // Execute Lua script that returns [buyBids[], sellBids[]]
        @SuppressWarnings("unchecked")
        List<List<byte[]>> rawResult = redisTemplate.execute((RedisCallback<List<List<byte[]>>>) connection -> {
            byte[][] keysBytes = keys.stream().map(k -> k.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);

            // No ARGV for this script
            Object res = connection.eval(
                    getAllBidsLuaScript.getBytes(StandardCharsets.UTF_8),
                    ReturnType.MULTI,
                    keys.size(),
                    keysBytes
            );
            return (List<List<byte[]>>) res;
        });

        List<AuctionBidSubmittedEvent> buyBids = new ArrayList<>();
        List<AuctionBidSubmittedEvent> sellBids = new ArrayList<>();

        if (rawResult != null && rawResult.size() == 2) {
            buyBids = parseBidList(rawResult.get(0));
            sellBids = parseBidList(rawResult.get(1));
        }

        log.info("Retrieved bids: auctionId={}, buyCount={}, sellCount={}", auctionId, buyBids.size(), sellBids.size());
        return new AuctionBids(buyBids, sellBids);
    }

    @SuppressWarnings("unchecked")
    private List<AuctionBidSubmittedEvent> parseBidList(Object rawList) {
        if (rawList == null) {
            return Collections.emptyList();
        }
        List<AuctionBidSubmittedEvent> bids = new ArrayList<>();
        List<?> items = (List<?>) rawList;
        for (Object item : items) {
            try {
                String json;
                if (item instanceof byte[]) {
                    json = new String((byte[]) item, StandardCharsets.UTF_8);
                } else {
                    json = item.toString();
                }
                AuctionBidSubmittedEvent event = objectMapper.readValue(json, AuctionBidSubmittedEvent.class);
                bids.add(event);
            } catch (Exception e) {
                log.error("Failed to parse bid JSON", e);
            }
        }
        return bids;
    }

    /**
     * Gets the number of unique participants in an auction.
     *
     * @param auctionId auction identifier
     * @return participant count
     */
    public long getParticipantCount(String auctionId) {
        String participantsKey = AUCTION_PREFIX + auctionId + ":participants";
        Long count = redisTemplate.opsForSet().size(participantsKey);
        return count != null ? count : 0L;
    }

    /**
     * Gets the current active auction ID.
     *
     * @return current auction ID or null if none
     */
    public String getCurrentAuctionId() {
        return redisTemplate.opsForValue().get(CURRENT_AUCTION_KEY);
    }

    /**
     * Gets the configuration hash for a specific auction.
     *
     * @param auctionId auction identifier
     * @return config map with keys: status, open_time, close_time, price_floor, price_ceiling
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> getAuctionConfig(String auctionId) {
        String configKey = AUCTION_PREFIX + auctionId + ":config";
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(configKey);
        return (Map<String, String>) (Map<?, ?>) entries;
    }

    /**
     * Saves global auction configuration (used by scheduler to initialize new auctions).
     *
     * @param config auction configuration DTO
     */
    public void saveGlobalConfig(AuctionConfigDto config) {
        redisTemplate.opsForHash().put(GLOBAL_CONFIG_KEY, "price_floor", String.valueOf(config.getPriceFloor()));
        redisTemplate.opsForHash().put(GLOBAL_CONFIG_KEY, "price_ceiling", String.valueOf(config.getPriceCeiling()));
        redisTemplate.opsForHash().put(GLOBAL_CONFIG_KEY, "duration_minutes", String.valueOf(config.getDurationMinutes()));
        redisTemplate.opsForHash().put(GLOBAL_CONFIG_KEY, "auction_enabled", String.valueOf(config.isAuctionEnabled()));
        log.info("Saved global auction config: priceFloor={}, priceCeiling={}, durationMinutes={}",
                config.getPriceFloor(), config.getPriceCeiling(), config.getDurationMinutes());
    }

    /**
     * Gets the global auction configuration.
     *
     * @return AuctionConfigDto or a default config if none exists
     */
    public AuctionConfigDto getGlobalConfig() {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(GLOBAL_CONFIG_KEY);
        if (entries.isEmpty()) {
            // Return defaults
            return AuctionConfigDto.builder()
                    .priceFloor(0)
                    .priceCeiling(9999)
                    .durationMinutes(10)
                    .auctionEnabled(true)
                    .build();
        }
        return AuctionConfigDto.builder()
                .priceFloor(Integer.parseInt((String) entries.getOrDefault("price_floor", "0")))
                .priceCeiling(Integer.parseInt((String) entries.getOrDefault("price_ceiling", "9999")))
                .durationMinutes(Integer.parseInt((String) entries.getOrDefault("duration_minutes", "10")))
                .auctionEnabled(Boolean.parseBoolean((String) entries.getOrDefault("auction_enabled", "true")))
                .build();
    }

    /**
     * Cleans up all Redis keys for a completed auction.
     *
     * @param auctionId auction identifier
     */
    public void cleanupAuction(String auctionId) {
        String prefix = AUCTION_PREFIX + auctionId + ":";
        redisTemplate.delete(List.of(
                prefix + "config",
                prefix + "bids:buy",
                prefix + "bids:sell",
                prefix + "participants"
        ));
        log.info("Cleaned up auction keys: auctionId={}", auctionId);
    }

    /**
     * Container for buy and sell bid lists returned from getAllBids.
     */
    public record AuctionBids(
            List<AuctionBidSubmittedEvent> buyBids,
            List<AuctionBidSubmittedEvent> sellBids
    ) {}
}
