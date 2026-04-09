package com.eap.eap_matchengine.application;

import com.eap.common.event.AuctionBidSubmittedEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Core clearing algorithm for Timed Double Auction using Uniform Price mechanism.
 *
 * Algorithm:
 * 1. Expand stepwise bids into flat (price, qty, userId, side) entries
 * 2. Build aggregate demand curve (buy: price DESC, cumulative qty)
 * 3. Build aggregate supply curve (sell: price ASC, cumulative qty)
 * 4. Find MCP (Market Clearing Price) where min(demand, supply) is maximized
 * 5. Allocate to individual bids with pro-rata for marginal orders
 * 6. Build per-user results with settlement amounts
 *
 * This service is pure business logic with no external dependencies (Redis, RabbitMQ),
 * making it easy to unit test.
 */
@Service
@Slf4j
public class AuctionClearingService {

    /**
     * Execute the clearing algorithm on collected bids.
     *
     * @param buyBids  list of buy-side bid events
     * @param sellBids list of sell-side bid events
     * @return ClearingResult with MCP, MCV, status, and per-user allocation results
     */
    public ClearingResult clear(List<AuctionBidSubmittedEvent> buyBids,
                                List<AuctionBidSubmittedEvent> sellBids) {
        if (buyBids == null || buyBids.isEmpty() || sellBids == null || sellBids.isEmpty()) {
            log.info("Clearing failed: no bids on one or both sides (buy={}, sell={})",
                    buyBids == null ? 0 : buyBids.size(),
                    sellBids == null ? 0 : sellBids.size());
            return ClearingResult.builder()
                    .clearingPrice(0)
                    .clearingVolume(0)
                    .status("FAILED")
                    .results(List.of())
                    .build();
        }

        // Step 1: Expand stepwise bids to flat entries
        List<FlatBid> allBuys = expandBids(buyBids, "BUY");
        List<FlatBid> allSells = expandBids(sellBids, "SELL");

        // Step 2: Build aggregate demand curve (buy: sort price DESC, cumulate qty)
        allBuys.sort(Comparator.comparingInt(FlatBid::getPrice).reversed()
                .thenComparing(fb -> fb.insertionOrder));
        TreeMap<Integer, Integer> demandCurve = buildCumulativeCurve(allBuys, true);

        // Step 3: Build aggregate supply curve (sell: sort price ASC, cumulate qty)
        allSells.sort(Comparator.comparingInt(FlatBid::getPrice)
                .thenComparing(fb -> fb.insertionOrder));
        TreeMap<Integer, Integer> supplyCurve = buildCumulativeCurve(allSells, false);

        // Step 4: Find MCP & MCV
        // Collect all unique price levels
        TreeMap<Integer, int[]> priceVolumes = new TreeMap<>(); // price -> [demand, supply, tradeable]
        for (Integer price : demandCurve.keySet()) {
            priceVolumes.putIfAbsent(price, new int[3]);
        }
        for (Integer price : supplyCurve.keySet()) {
            priceVolumes.putIfAbsent(price, new int[3]);
        }

        // For each price level, compute demand(p) and supply(p)
        for (Map.Entry<Integer, int[]> entry : priceVolumes.entrySet()) {
            int p = entry.getKey();
            // demand(p) = cumulative buy qty at price >= p
            // In demandCurve (keyed by price DESC logic), we need ceilingEntry(p)
            Map.Entry<Integer, Integer> demandEntry = demandCurve.ceilingEntry(p);
            int demand = demandEntry != null ? demandEntry.getValue() : 0;

            // Actually, demandCurve stores cumulative from highest price down.
            // For demand at price p: total qty of buys with price >= p
            // Since demandCurve is built with descending price order, cumulative at price p
            // = sum of qty for all prices >= p
            // We stored it as: for each price level in descending order, cumQty
            // So we need the entry at price p or the next higher price
            demand = getDemandAtPrice(demandCurve, p);

            // supply(p) = cumulative sell qty at price <= p
            int supply = getSupplyAtPrice(supplyCurve, p);

            int tradeable = Math.min(demand, supply);
            entry.setValue(new int[]{demand, supply, tradeable});
        }

        // Find maximum tradeable volume
        int maxVolume = 0;
        for (int[] vals : priceVolumes.values()) {
            maxVolume = Math.max(maxVolume, vals[2]);
        }

        if (maxVolume == 0) {
            log.info("Clearing failed: no price overlap between buy and sell sides");
            return ClearingResult.builder()
                    .clearingPrice(0)
                    .clearingVolume(0)
                    .status("FAILED")
                    .results(List.of())
                    .build();
        }

        // Find all prices that achieve max volume (for midpoint rule)
        int lowestMcpCandidate = Integer.MAX_VALUE;
        int highestMcpCandidate = Integer.MIN_VALUE;
        for (Map.Entry<Integer, int[]> entry : priceVolumes.entrySet()) {
            if (entry.getValue()[2] == maxVolume) {
                lowestMcpCandidate = Math.min(lowestMcpCandidate, entry.getKey());
                highestMcpCandidate = Math.max(highestMcpCandidate, entry.getKey());
            }
        }

        // Step 5: Determine MCP (midpoint if multiple candidates)
        int mcp = (lowestMcpCandidate + highestMcpCandidate) / 2;
        int mcv = maxVolume;

        log.info("Clearing result: MCP={}, MCV={}, priceRange=[{}, {}]",
                mcp, mcv, lowestMcpCandidate, highestMcpCandidate);

        // Step 6: Allocate to individual bids
        List<BidResult> bidResults = allocateBids(allBuys, allSells, mcp, mcv, buyBids, sellBids);

        return ClearingResult.builder()
                .clearingPrice(mcp)
                .clearingVolume(mcv)
                .status("CLEARED")
                .results(bidResults)
                .build();
    }

    /**
     * Expand stepwise bids into flat (price, qty) entries.
     * Each step in a bid becomes a separate FlatBid entry.
     */
    private List<FlatBid> expandBids(List<AuctionBidSubmittedEvent> bids, String side) {
        List<FlatBid> flat = new ArrayList<>();
        int insertionOrder = 0;
        for (AuctionBidSubmittedEvent bid : bids) {
            if (bid.getSteps() == null) continue;
            for (AuctionBidSubmittedEvent.BidStep step : bid.getSteps()) {
                flat.add(FlatBid.builder()
                        .userId(bid.getUserId())
                        .side(side)
                        .price(step.getPrice())
                        .quantity(step.getAmount())
                        .insertionOrder(insertionOrder++)
                        .totalLocked(bid.getTotalLocked())
                        .build());
            }
        }
        return flat;
    }

    /**
     * Build a cumulative curve from sorted flat bids.
     * For demand (buy): sorted by price DESC, cumulative qty increases as price decreases.
     * For supply (sell): sorted by price ASC, cumulative qty increases as price increases.
     *
     * Returns a TreeMap where key=price, value=cumulative qty at that price level.
     */
    private TreeMap<Integer, Integer> buildCumulativeCurve(List<FlatBid> sortedBids, boolean isDemand) {
        TreeMap<Integer, Integer> curve = new TreeMap<>();
        int cumQty = 0;
        for (FlatBid bid : sortedBids) {
            cumQty += bid.quantity;
            curve.put(bid.price, cumQty);
        }
        return curve;
    }

    /**
     * Get demand at price p: total qty of all buy bids with price >= p.
     * demandCurve is built from price DESC order, so cumQty at price p
     * represents total qty from highest price down to p.
     */
    private int getDemandAtPrice(TreeMap<Integer, Integer> demandCurve, int price) {
        // demandCurve is built from price DESC order with cumulative qty:
        //   Price 60: cumQty = 50 (only bids at 60)
        //   Price 55: cumQty = 80 (bids at 60 + 55)
        //   Price 50: cumQty = 100 (bids at 60 + 55 + 50)
        //
        // demand(p) = total buy qty at price >= p
        // We need ceilingEntry(p): the smallest key >= p, whose cumQty represents
        // total qty from the highest price down to that key (which includes all prices >= key >= p).
        //
        // Example: demand(57) -> ceilingEntry(57) = (60, 50) -> 50 units at >= 57 ✓
        //          demand(55) -> ceilingEntry(55) = (55, 80) -> 80 units at >= 55 ✓
        //          demand(50) -> ceilingEntry(50) = (50, 100) -> 100 units at >= 50 ✓
        //          demand(70) -> ceilingEntry(70) = null -> 0 ✓
        Map.Entry<Integer, Integer> entry = demandCurve.ceilingEntry(price);
        return entry != null ? entry.getValue() : 0;
    }

    /**
     * Get supply at price p: total qty of all sell bids with price <= p.
     * supplyCurve is built from price ASC order.
     */
    private int getSupplyAtPrice(TreeMap<Integer, Integer> supplyCurve, int price) {
        // supplyCurve: built in ascending price order.
        // Price 45: cumQty = 30 (bids at 45)
        // Price 50: cumQty = 70 (bids at 45 + 50)
        // Price 55: cumQty = 90 (bids at 45 + 50 + 55)
        //
        // supply(p) = total qty of sells with price <= p = floorEntry(p).value
        Map.Entry<Integer, Integer> entry = supplyCurve.floorEntry(price);
        return entry != null ? entry.getValue() : 0;
    }

    /**
     * Allocate cleared volume to individual bids using pro-rata for marginal orders.
     */
    private List<BidResult> allocateBids(List<FlatBid> allBuys, List<FlatBid> allSells,
                                          int mcp, int mcv,
                                          List<AuctionBidSubmittedEvent> originalBuyBids,
                                          List<AuctionBidSubmittedEvent> originalSellBids) {
        // Allocate buy side
        Map<UUID, Integer> buyAllocations = allocateSide(allBuys, mcp, mcv, true);

        // Allocate sell side
        Map<UUID, Integer> sellAllocations = allocateSide(allSells, mcp, mcv, false);

        // Build per-user results
        List<BidResult> results = new ArrayList<>();

        // Process buy bids
        for (AuctionBidSubmittedEvent bid : originalBuyBids) {
            int totalBidAmount = bid.getSteps().stream()
                    .mapToInt(AuctionBidSubmittedEvent.BidStep::getAmount)
                    .sum();
            int clearedAmount = buyAllocations.getOrDefault(bid.getUserId(), 0);
            results.add(BidResult.builder()
                    .userId(bid.getUserId())
                    .side("BUY")
                    .bidAmount(totalBidAmount)
                    .clearedAmount(clearedAmount)
                    .settlementAmount(clearedAmount * mcp)
                    .originalTotalLocked(bid.getTotalLocked())
                    .build());
        }

        // Process sell bids
        for (AuctionBidSubmittedEvent bid : originalSellBids) {
            int totalBidAmount = bid.getSteps().stream()
                    .mapToInt(AuctionBidSubmittedEvent.BidStep::getAmount)
                    .sum();
            int clearedAmount = sellAllocations.getOrDefault(bid.getUserId(), 0);
            results.add(BidResult.builder()
                    .userId(bid.getUserId())
                    .side("SELL")
                    .bidAmount(totalBidAmount)
                    .clearedAmount(clearedAmount)
                    .settlementAmount(clearedAmount * mcp)
                    .originalTotalLocked(bid.getTotalLocked())
                    .build());
        }

        return results;
    }

    /**
     * Allocate volume for one side (buy or sell).
     *
     * For buy side:
     * - Bids with price > MCP: fully filled (inframarginal)
     * - Bids with price == MCP: marginal, pro-rata share of remaining volume
     *
     * For sell side:
     * - Bids with price < MCP: fully filled (inframarginal)
     * - Bids with price == MCP: marginal, pro-rata share of remaining volume
     *
     * @return map of userId -> allocated quantity
     */
    private Map<UUID, Integer> allocateSide(List<FlatBid> flatBids, int mcp, int mcv, boolean isBuy) {
        Map<UUID, Integer> allocations = new HashMap<>();

        // Separate inframarginal and marginal flat bids
        List<FlatBid> inframarginal = new ArrayList<>();
        List<FlatBid> marginal = new ArrayList<>();

        for (FlatBid fb : flatBids) {
            if (isBuy) {
                if (fb.price > mcp) {
                    inframarginal.add(fb);
                } else if (fb.price == mcp) {
                    marginal.add(fb);
                }
                // price < mcp: not cleared
            } else {
                if (fb.price < mcp) {
                    inframarginal.add(fb);
                } else if (fb.price == mcp) {
                    marginal.add(fb);
                }
                // price > mcp: not cleared
            }
        }

        // Fully allocate inframarginal bids
        int inframarginalVolume = 0;
        for (FlatBid fb : inframarginal) {
            allocations.merge(fb.userId, fb.quantity, Integer::sum);
            inframarginalVolume += fb.quantity;
        }

        // Remaining volume for marginal bids
        int remainingVolume = mcv - inframarginalVolume;
        if (remainingVolume > 0 && !marginal.isEmpty()) {
            proRataAllocate(marginal, remainingVolume, allocations);
        }

        return allocations;
    }

    /**
     * Pro-rata allocation for marginal bids.
     * Each marginal bid gets a proportional share of available volume.
     * Remainder (due to integer rounding) is distributed one-by-one by insertion order.
     */
    private void proRataAllocate(List<FlatBid> marginals, int available, Map<UUID, Integer> allocations) {
        int totalMarginalQty = marginals.stream().mapToInt(fb -> fb.quantity).sum();

        if (totalMarginalQty <= available) {
            // All marginal bids can be fully filled
            for (FlatBid fb : marginals) {
                allocations.merge(fb.userId, fb.quantity, Integer::sum);
            }
            return;
        }

        // Pro-rata: each gets floor(qty / totalQty * available)
        int[] allocated = new int[marginals.size()];
        int totalAllocated = 0;
        for (int i = 0; i < marginals.size(); i++) {
            allocated[i] = (int) ((long) marginals.get(i).quantity * available / totalMarginalQty);
            totalAllocated += allocated[i];
        }

        // Distribute remainder one-by-one by insertion order
        int remainder = available - totalAllocated;
        // Sort by insertion order for fair remainder distribution
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < marginals.size(); i++) {
            indices.add(i);
        }
        indices.sort(Comparator.comparingInt(i -> marginals.get(i).insertionOrder));

        for (int idx : indices) {
            if (remainder <= 0) break;
            allocated[idx]++;
            remainder--;
        }

        // Apply allocations
        for (int i = 0; i < marginals.size(); i++) {
            if (allocated[i] > 0) {
                allocations.merge(marginals.get(i).userId, allocated[i], Integer::sum);
            }
        }
    }

    // ==================== Inner Classes ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class FlatBid {
        private UUID userId;
        private String side;
        private int price;
        private int quantity;
        private int insertionOrder;
        private Integer totalLocked;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ClearingResult {
        private Integer clearingPrice;
        private Integer clearingVolume;
        private String status; // "CLEARED" or "FAILED"
        private List<BidResult> results;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BidResult {
        private UUID userId;
        private String side;
        private Integer bidAmount;
        private Integer clearedAmount;
        private Integer settlementAmount;
        private Integer originalTotalLocked;
    }
}
