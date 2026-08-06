package com.eap.eap_matchengine.configuration.repository;

import com.eap.eap_matchengine.domain.entity.TradeExecutionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface TradeExecutionRepository extends JpaRepository<TradeExecutionEntity, String> {
    Optional<TradeExecutionEntity> findByTradeId(String tradeId);

    Optional<TradeExecutionEntity>
            findFirstByCreatedAtGreaterThanEqualAndBuyerOrderIdOrCreatedAtGreaterThanEqualAndSellerOrderIdOrderByCreatedAtDesc(
                    LocalDateTime buyerReservedAt,
                    UUID buyerOrderId,
                    LocalDateTime sellerReservedAt,
                    UUID sellerOrderId);

    @Query("select coalesce(sum(t.quantity), 0) from TradeExecutionEntity t where t.buyerOrderId = :orderId")
    long sumQuantityByBuyerOrderId(@Param("orderId") UUID orderId);

    @Query("select coalesce(sum(t.quantity), 0) from TradeExecutionEntity t where t.sellerOrderId = :orderId")
    long sumQuantityBySellerOrderId(@Param("orderId") UUID orderId);
}
