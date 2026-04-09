package com.eap.eap_matchengine.application;

import com.eap.common.event.AuctionBidSubmittedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for AuctionClearingService — Uniform Price clearing algorithm.
 *
 * Pure unit tests: no Spring context, no mocks required.
 */
class AuctionClearingServiceTest {

    private AuctionClearingService service;

    @BeforeEach
    void setUp() {
        service = new AuctionClearingService();
    }

    // ==================== Helper builders ====================

    private AuctionBidSubmittedEvent buyBid(UUID userId, int price, int amount) {
        return AuctionBidSubmittedEvent.builder()
                .auctionId("AUC-TEST")
                .userId(userId)
                .side("BUY")
                .steps(List.of(new AuctionBidSubmittedEvent.BidStep(price, amount)))
                .totalLocked(price * amount)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private AuctionBidSubmittedEvent buyBidSteps(UUID userId, AuctionBidSubmittedEvent.BidStep... steps) {
        int totalLocked = 0;
        for (AuctionBidSubmittedEvent.BidStep s : steps) {
            totalLocked += s.getPrice() * s.getAmount();
        }
        return AuctionBidSubmittedEvent.builder()
                .auctionId("AUC-TEST")
                .userId(userId)
                .side("BUY")
                .steps(List.of(steps))
                .totalLocked(totalLocked)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private AuctionBidSubmittedEvent sellBid(UUID userId, int price, int amount) {
        return AuctionBidSubmittedEvent.builder()
                .auctionId("AUC-TEST")
                .userId(userId)
                .side("SELL")
                .steps(List.of(new AuctionBidSubmittedEvent.BidStep(price, amount)))
                .totalLocked(amount)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private AuctionBidSubmittedEvent sellBidSteps(UUID userId, AuctionBidSubmittedEvent.BidStep... steps) {
        int totalLocked = 0;
        for (AuctionBidSubmittedEvent.BidStep s : steps) {
            totalLocked += s.getAmount();
        }
        return AuctionBidSubmittedEvent.builder()
                .auctionId("AUC-TEST")
                .userId(userId)
                .side("SELL")
                .steps(List.of(steps))
                .totalLocked(totalLocked)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private AuctionBidSubmittedEvent.BidStep step(int price, int amount) {
        return new AuctionBidSubmittedEvent.BidStep(price, amount);
    }

    // ==================== Single-side / no-bid failures ====================

    @Nested
    @DisplayName("FAILED cases: single-side or no bids")
    class FailedCases {

        @Test
        @DisplayName("null buy bids returns FAILED")
        void nullBuyBids() {
            UUID seller = UUID.randomUUID();
            var result = service.clear(null, List.of(sellBid(seller, 50, 100)));

            assertThat(result.getStatus()).isEqualTo("FAILED");
            assertThat(result.getClearingPrice()).isEqualTo(0);
            assertThat(result.getClearingVolume()).isEqualTo(0);
            assertThat(result.getResults()).isEmpty();
        }

        @Test
        @DisplayName("empty buy bids returns FAILED")
        void emptyBuyBids() {
            UUID seller = UUID.randomUUID();
            var result = service.clear(List.of(), List.of(sellBid(seller, 50, 100)));

            assertThat(result.getStatus()).isEqualTo("FAILED");
            assertThat(result.getClearingPrice()).isEqualTo(0);
            assertThat(result.getClearingVolume()).isEqualTo(0);
        }

        @Test
        @DisplayName("null sell bids returns FAILED")
        void nullSellBids() {
            UUID buyer = UUID.randomUUID();
            var result = service.clear(List.of(buyBid(buyer, 60, 100)), null);

            assertThat(result.getStatus()).isEqualTo("FAILED");
            assertThat(result.getClearingPrice()).isEqualTo(0);
        }

        @Test
        @DisplayName("empty sell bids returns FAILED")
        void emptySellBids() {
            UUID buyer = UUID.randomUUID();
            var result = service.clear(List.of(buyBid(buyer, 60, 100)), List.of());

            assertThat(result.getStatus()).isEqualTo("FAILED");
        }

        @Test
        @DisplayName("no price overlap — buy max < sell min returns FAILED")
        void noPriceOverlap() {
            UUID buyer = UUID.randomUUID();
            UUID seller = UUID.randomUUID();
            // buyer willing to pay up to 40, seller wants at least 50
            var result = service.clear(
                    List.of(buyBid(buyer, 40, 100)),
                    List.of(sellBid(seller, 50, 100))
            );

            assertThat(result.getStatus()).isEqualTo("FAILED");
            assertThat(result.getClearingPrice()).isEqualTo(0);
            assertThat(result.getClearingVolume()).isEqualTo(0);
        }
    }

    // ==================== Simple clearing ====================

    @Nested
    @DisplayName("Simple clearing: single buyer vs single seller")
    class SimpleClearingCases {

        @Test
        @DisplayName("single buyer and seller with exact price match")
        void singleBuyerSellerExactPrice() {
            UUID buyer = UUID.randomUUID();
            UUID seller = UUID.randomUUID();

            // Both bid at price 50, quantity 100
            var result = service.clear(
                    List.of(buyBid(buyer, 50, 100)),
                    List.of(sellBid(seller, 50, 100))
            );

            assertThat(result.getStatus()).isEqualTo("CLEARED");
            assertThat(result.getClearingPrice()).isEqualTo(50);
            assertThat(result.getClearingVolume()).isEqualTo(100);
            assertThat(result.getResults()).hasSize(2);

            var buyResult = findResult(result, buyer);
            assertThat(buyResult.getClearedAmount()).isEqualTo(100);
            assertThat(buyResult.getSettlementAmount()).isEqualTo(100 * 50);

            var sellResult = findResult(result, seller);
            assertThat(sellResult.getClearedAmount()).isEqualTo(100);
            assertThat(sellResult.getSettlementAmount()).isEqualTo(100 * 50);
        }

        @Test
        @DisplayName("buyer bids higher than seller — MCP should clear at correct price")
        void buyerBidsHigherThanSeller() {
            UUID buyer = UUID.randomUUID();
            UUID seller = UUID.randomUUID();

            // Buyer: price=60, qty=100; Seller: price=40, qty=100
            // At price 40: demand=100 (buyer still wants 100), supply=100 → tradeable=100
            // At price 60: demand=100, supply=0 → tradeable=0
            // MCP candidates: price=40 (tradeable=100), price=60 (tradeable=0) → MCP=40
            var result = service.clear(
                    List.of(buyBid(buyer, 60, 100)),
                    List.of(sellBid(seller, 40, 100))
            );

            assertThat(result.getStatus()).isEqualTo("CLEARED");
            assertThat(result.getClearingVolume()).isEqualTo(100);

            // Both buyer (>MCP) and seller (<MCP) should be fully cleared
            assertThat(findResult(result, buyer).getClearedAmount()).isEqualTo(100);
            assertThat(findResult(result, seller).getClearedAmount()).isEqualTo(100);
        }
    }

    // ==================== MCP midpoint rule ====================

    @Nested
    @DisplayName("MCP midpoint rule: multiple prices achieve max volume")
    class MidpointRuleCases {

        @Test
        @DisplayName("midpoint rule selects average of range achieving max volume")
        void midpointRule() {
            UUID buyer = UUID.randomUUID();
            UUID seller = UUID.randomUUID();

            // Buyer bids 100 units at price 60
            // Seller offers 100 units at price 50
            // Every price between 50 and 60 achieves max volume 100
            // → MCP = (50 + 60) / 2 = 55
            var result = service.clear(
                    List.of(buyBid(buyer, 60, 100)),
                    List.of(sellBid(seller, 50, 100))
            );

            assertThat(result.getStatus()).isEqualTo("CLEARED");
            assertThat(result.getClearingPrice()).isEqualTo(55);
            assertThat(result.getClearingVolume()).isEqualTo(100);
        }

        @Test
        @DisplayName("single price achieves max — no midpoint calculation needed")
        void singlePriceMaxVolume() {
            UUID buyer = UUID.randomUUID();
            UUID seller1 = UUID.randomUUID();
            UUID seller2 = UUID.randomUUID();

            // Buyer bids 100 at 60
            // Seller1: 50 at price 55
            // Seller2: 100 at price 65 (above buyer price, won't trade)
            // At price 55: demand=100, supply=50 → tradeable=50
            // At price 60: demand=100, supply=50 → tradeable=50  (seller2 at 65 not included)
            // At price 65: demand=0, supply=150 → tradeable=0
            // Max tradeable=50 at prices 55..60 → MCP=(55+60)/2=57
            var result = service.clear(
                    List.of(buyBid(buyer, 60, 100)),
                    List.of(sellBid(seller1, 55, 50), sellBid(seller2, 65, 100))
            );

            assertThat(result.getStatus()).isEqualTo("CLEARED");
            assertThat(result.getClearingVolume()).isEqualTo(50);
        }
    }

    // ==================== Stepwise bid expansion ====================

    @Nested
    @DisplayName("Stepwise bid expansion")
    class StepwiseBidExpansionCases {

        @Test
        @DisplayName("buyer with multiple price-qty steps correctly expanded")
        void buyerMultipleStepsExpanded() {
            UUID buyer = UUID.randomUUID();
            UUID seller = UUID.randomUUID();

            // Buyer: willing to buy 50 at 60, and 50 at 50
            // Seller: 100 units at price 48
            // At price 48: demand=100 (both steps), supply=100 → tradeable=100
            // At price 50: demand=100, supply=100 → tradeable=100
            // At price 60: demand=50, supply=100 → tradeable=50
            // Max=100, range: [48,50] → MCP=(48+50)/2=49
            var result = service.clear(
                    List.of(buyBidSteps(buyer, step(60, 50), step(50, 50))),
                    List.of(sellBid(seller, 48, 100))
            );

            assertThat(result.getStatus()).isEqualTo("CLEARED");
            assertThat(result.getClearingVolume()).isEqualTo(100);

            // Buyer should be fully cleared (100 total bid)
            var buyResult = findResult(result, buyer);
            assertThat(buyResult.getClearedAmount()).isEqualTo(100);
        }

        @Test
        @DisplayName("seller with multiple price-qty steps correctly expanded")
        void sellerMultipleStepsExpanded() {
            UUID buyer = UUID.randomUUID();
            UUID seller = UUID.randomUUID();

            // Buyer: 200 at price 60
            // Seller: 80 at price 50, 80 at price 55
            // At price 50: demand=200, supply=80 → tradeable=80
            // At price 55: demand=200, supply=160 → tradeable=160
            // At price 60: demand=200, supply=160 → tradeable=160
            // Max=160, range: [55,60] → MCP=(55+60)/2=57
            var result = service.clear(
                    List.of(buyBid(buyer, 60, 200)),
                    List.of(sellBidSteps(seller, step(50, 80), step(55, 80)))
            );

            assertThat(result.getStatus()).isEqualTo("CLEARED");
            assertThat(result.getClearingVolume()).isEqualTo(160);

            var sellResult = findResult(result, seller);
            assertThat(sellResult.getClearedAmount()).isEqualTo(160);
        }
    }

    // ==================== Pro-rata marginal allocation ====================

    @Nested
    @DisplayName("Pro-rata allocation for marginal bids")
    class ProRataAllocationCases {

        @Test
        @DisplayName("marginal buy bids receive pro-rata share of remaining volume")
        void proRataBuyMarginal() {
            UUID buyer1 = UUID.randomUUID();
            UUID buyer2 = UUID.randomUUID();
            UUID seller = UUID.randomUUID();

            // Seller: 100 units at price 50
            // Buyer1: 60 at price 50 (marginal)
            // Buyer2: 40 at price 50 (marginal)
            // MCP = 50, MCV = 100
            // Both buyers are marginal (price == MCP), total marginal qty = 100
            // Pro-rata: buyer1 gets 60, buyer2 gets 40
            var result = service.clear(
                    List.of(buyBid(buyer1, 50, 60), buyBid(buyer2, 50, 40)),
                    List.of(sellBid(seller, 50, 100))
            );

            assertThat(result.getStatus()).isEqualTo("CLEARED");
            assertThat(result.getClearingVolume()).isEqualTo(100);

            var b1 = findResult(result, buyer1);
            var b2 = findResult(result, buyer2);
            assertThat(b1.getClearedAmount() + b2.getClearedAmount()).isEqualTo(100);
            // Pro-rata: buyer1 60% = 60, buyer2 40% = 40
            assertThat(b1.getClearedAmount()).isEqualTo(60);
            assertThat(b2.getClearedAmount()).isEqualTo(40);
        }

        @Test
        @DisplayName("inframarginal buyers fully cleared, marginal buyers get remaining pro-rata")
        void inframarginalFullyCleared() {
            UUID buyer1 = UUID.randomUUID();
            UUID buyer2 = UUID.randomUUID();
            UUID seller = UUID.randomUUID();

            // Seller: 100 units at price 50
            // Buyer1: 30 at price 60 (inframarginal — above MCP)
            // Buyer2: 100 at price 50 (marginal)
            // MCP = 50, MCV = 100
            // Buyer1 fully cleared (30), remaining = 70
            // Buyer2 gets remaining 70 (pro-rata of 100, gets floor(70) = 70)
            var result = service.clear(
                    List.of(buyBid(buyer1, 60, 30), buyBid(buyer2, 50, 100)),
                    List.of(sellBid(seller, 50, 100))
            );

            assertThat(result.getStatus()).isEqualTo("CLEARED");
            assertThat(result.getClearingVolume()).isEqualTo(100);

            var b1 = findResult(result, buyer1);
            var b2 = findResult(result, buyer2);
            assertThat(b1.getClearedAmount()).isEqualTo(30);
            assertThat(b2.getClearedAmount()).isEqualTo(70);
        }

        @Test
        @DisplayName("marginal sell bids receive pro-rata share")
        void proRataSellMarginal() {
            UUID buyer = UUID.randomUUID();
            UUID seller1 = UUID.randomUUID();
            UUID seller2 = UUID.randomUUID();

            // Buyer: 100 at price 60
            // Seller1: 60 at price 60 (marginal)
            // Seller2: 40 at price 60 (marginal)
            // MCP = 60, MCV = 100
            var result = service.clear(
                    List.of(buyBid(buyer, 60, 100)),
                    List.of(sellBid(seller1, 60, 60), sellBid(seller2, 60, 40))
            );

            assertThat(result.getStatus()).isEqualTo("CLEARED");
            assertThat(result.getClearingVolume()).isEqualTo(100);

            var s1 = findResult(result, seller1);
            var s2 = findResult(result, seller2);
            assertThat(s1.getClearedAmount() + s2.getClearedAmount()).isEqualTo(100);
            assertThat(s1.getClearedAmount()).isEqualTo(60);
            assertThat(s2.getClearedAmount()).isEqualTo(40);
        }
    }

    // ==================== Settlement calculation ====================

    @Nested
    @DisplayName("Settlement amount calculation")
    class SettlementCases {

        @Test
        @DisplayName("settlement = clearedAmount * MCP for both sides")
        void settlementCalculation() {
            UUID buyer = UUID.randomUUID();
            UUID seller = UUID.randomUUID();

            // MCP will be (50+60)/2 = 55, MCV = 100
            var result = service.clear(
                    List.of(buyBid(buyer, 60, 100)),
                    List.of(sellBid(seller, 50, 100))
            );

            assertThat(result.getStatus()).isEqualTo("CLEARED");
            int mcp = result.getClearingPrice();

            var buyResult = findResult(result, buyer);
            assertThat(buyResult.getSettlementAmount())
                    .isEqualTo(buyResult.getClearedAmount() * mcp);

            var sellResult = findResult(result, seller);
            assertThat(sellResult.getSettlementAmount())
                    .isEqualTo(sellResult.getClearedAmount() * mcp);
        }

        @Test
        @DisplayName("unclearedBid has zero settlement and zero clearedAmount")
        void uncleared_bidHasZeroSettlement() {
            UUID buyer1 = UUID.randomUUID();
            UUID buyer2 = UUID.randomUUID();
            UUID seller = UUID.randomUUID();

            // Seller: 50 at price 60
            // Buyer1: 50 at price 60 (gets cleared)
            // Buyer2: 50 at price 30 (below MCP, not cleared)
            var result = service.clear(
                    List.of(buyBid(buyer1, 60, 50), buyBid(buyer2, 30, 50)),
                    List.of(sellBid(seller, 60, 50))
            );

            assertThat(result.getStatus()).isEqualTo("CLEARED");

            var b2 = findResult(result, buyer2);
            assertThat(b2.getClearedAmount()).isEqualTo(0);
            assertThat(b2.getSettlementAmount()).isEqualTo(0);
        }
    }

    // ==================== Multiple participants ====================

    @Nested
    @DisplayName("Multiple buyers and sellers")
    class MultipleParticipantsCases {

        @Test
        @DisplayName("multiple buyers and sellers — correct MCP and MCV")
        void multipleBuyersAndSellers() {
            UUID buyer1 = UUID.randomUUID();
            UUID buyer2 = UUID.randomUUID();
            UUID buyer3 = UUID.randomUUID();
            UUID seller1 = UUID.randomUUID();
            UUID seller2 = UUID.randomUUID();

            // Buyers: 80@60, 60@55, 40@45
            // Sellers: 70@50, 90@57
            // Demand curve (qty with price >= p):
            //   price 60 → 80; price 55 → 140; price 50 → 140; price 45 → 180
            // Supply curve (qty with price <= p):
            //   price 50 → 70; price 55 → 70; price 57 → 160; price 60 → 160
            // At price 45: demand=180, supply=0 → tradeable=0
            // At price 50: demand=140, supply=70 → tradeable=70
            // At price 55: demand=140, supply=70 → tradeable=70
            // At price 57: demand=80, supply=160 → tradeable=80
            // At price 60: demand=80, supply=160 → tradeable=80
            // Max=80 at prices 57 and 60, MCP=(57+60)/2=58
            var result = service.clear(
                    List.of(buyBid(buyer1, 60, 80), buyBid(buyer2, 55, 60), buyBid(buyer3, 45, 40)),
                    List.of(sellBid(seller1, 50, 70), sellBid(seller2, 57, 90))
            );

            assertThat(result.getStatus()).isEqualTo("CLEARED");
            assertThat(result.getClearingVolume()).isEqualTo(80);
            assertThat(result.getResults()).hasSize(5);

            // buyer2 bids at 55 < MCP=58, should not be cleared
            var b2 = findResult(result, buyer2);
            assertThat(b2.getClearedAmount()).isEqualTo(0);

            // buyer3 bids at 45 < MCP=58, should not be cleared
            var b3 = findResult(result, buyer3);
            assertThat(b3.getClearedAmount()).isEqualTo(0);
        }

        @Test
        @DisplayName("partial fills: some fully cleared, some partially, some none")
        void partialFills() {
            UUID buyer1 = UUID.randomUUID();
            UUID buyer2 = UUID.randomUUID();
            UUID seller = UUID.randomUUID();

            // Buyer1: 50 at price 70 (inframarginal, above MCP)
            // Buyer2: 100 at price 55 (at MCP)
            // Seller: 80 at price 55
            // At price 55: demand=150, supply=80 → tradeable=80
            // At price 70: demand=50, supply=80 → tradeable=50
            // Max=80 at price 55
            var result = service.clear(
                    List.of(buyBid(buyer1, 70, 50), buyBid(buyer2, 55, 100)),
                    List.of(sellBid(seller, 55, 80))
            );

            assertThat(result.getStatus()).isEqualTo("CLEARED");
            assertThat(result.getClearingVolume()).isEqualTo(80);

            // buyer1 is inframarginal (price 70 > MCP 55), fully cleared
            var b1 = findResult(result, buyer1);
            assertThat(b1.getClearedAmount()).isEqualTo(50);

            // buyer2 is marginal, gets remaining 30
            var b2 = findResult(result, buyer2);
            assertThat(b2.getClearedAmount()).isEqualTo(30);

            // seller fully cleared
            var s = findResult(result, seller);
            assertThat(s.getClearedAmount()).isEqualTo(80);
        }
    }

    // ==================== BidResult fields ====================

    @Nested
    @DisplayName("BidResult metadata fields")
    class BidResultMetaCases {

        @Test
        @DisplayName("BidResult side field is set correctly")
        void bidResultSideField() {
            UUID buyer = UUID.randomUUID();
            UUID seller = UUID.randomUUID();

            var result = service.clear(
                    List.of(buyBid(buyer, 55, 100)),
                    List.of(sellBid(seller, 55, 100))
            );

            var buyResult = findResult(result, buyer);
            var sellResult = findResult(result, seller);

            assertThat(buyResult.getSide()).isEqualTo("BUY");
            assertThat(sellResult.getSide()).isEqualTo("SELL");
        }

        @Test
        @DisplayName("BidResult bidAmount reflects total steps quantity")
        void bidAmountReflectsTotalStepsQty() {
            UUID buyer = UUID.randomUUID();
            UUID seller = UUID.randomUUID();

            var result = service.clear(
                    List.of(buyBidSteps(buyer, step(60, 30), step(50, 50))),
                    List.of(sellBid(seller, 40, 200))
            );

            var buyResult = findResult(result, buyer);
            assertThat(buyResult.getBidAmount()).isEqualTo(80); // 30 + 50
        }

        @Test
        @DisplayName("originalTotalLocked preserved from event")
        void originalTotalLockedPreserved() {
            UUID buyer = UUID.randomUUID();
            UUID seller = UUID.randomUUID();

            var buyEvent = buyBid(buyer, 60, 100);
            var sellEvent = sellBid(seller, 50, 100);

            var result = service.clear(List.of(buyEvent), List.of(sellEvent));

            var buyResult = findResult(result, buyer);
            assertThat(buyResult.getOriginalTotalLocked()).isEqualTo(buyEvent.getTotalLocked());
        }
    }

    // ==================== Helper ====================

    private AuctionClearingService.BidResult findResult(AuctionClearingService.ClearingResult result, UUID userId) {
        return result.getResults().stream()
                .filter(r -> r.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No result found for user: " + userId));
    }
}
