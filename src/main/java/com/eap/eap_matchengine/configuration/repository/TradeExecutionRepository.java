package com.eap.eap_matchengine.configuration.repository;

import com.eap.eap_matchengine.domain.entity.TradeExecutionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
