package com.eap.eap_matchengine.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "trade_executions", schema = "match_engine")
@Getter
@NoArgsConstructor
public class TradeExecutionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trade_id", nullable = false, length = 80)
    private String tradeId;

    @Column(name = "sequence", nullable = false)
    private Long sequence;

    @Column(name = "legacy_match_id", nullable = false)
    private Long legacyMatchId;

    @Column(name = "market_id", nullable = false, length = 100)
    private String marketId;

    @Column(name = "buyer_id", nullable = false)
    private UUID buyerId;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(name = "buyer_order_id", nullable = false)
    private UUID buyerOrderId;

    @Column(name = "seller_order_id", nullable = false)
    private UUID sellerOrderId;

    @Column(name = "buyer_market_sequence")
    private Long buyerMarketSequence;

    @Column(name = "seller_market_sequence")
    private Long sellerMarketSequence;

    @Column(name = "origin_buyer_price", nullable = false)
    private Integer originBuyerPrice;

    @Column(name = "origin_seller_price", nullable = false)
    private Integer originSellerPrice;

    @Column(name = "deal_price", nullable = false)
    private Integer dealPrice;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public TradeExecutionEntity(
            String tradeId,
            Long sequence,
            Long legacyMatchId,
            String marketId,
            UUID buyerId,
            UUID sellerId,
            UUID buyerOrderId,
            UUID sellerOrderId,
            Long buyerMarketSequence,
            Long sellerMarketSequence,
            Integer originBuyerPrice,
            Integer originSellerPrice,
            Integer dealPrice,
            Integer quantity,
            LocalDateTime occurredAt) {
        this.tradeId = tradeId;
        this.sequence = sequence;
        this.legacyMatchId = legacyMatchId;
        this.marketId = marketId;
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.buyerOrderId = buyerOrderId;
        this.sellerOrderId = sellerOrderId;
        this.buyerMarketSequence = buyerMarketSequence;
        this.sellerMarketSequence = sellerMarketSequence;
        this.originBuyerPrice = originBuyerPrice;
        this.originSellerPrice = originSellerPrice;
        this.dealPrice = dealPrice;
        this.quantity = quantity;
        this.occurredAt = occurredAt;
    }
}
