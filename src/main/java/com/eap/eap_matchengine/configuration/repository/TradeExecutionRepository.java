package com.eap.eap_matchengine.configuration.repository;

import com.eap.eap_matchengine.domain.entity.TradeExecutionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TradeExecutionRepository extends JpaRepository<TradeExecutionEntity, String> {
    Optional<TradeExecutionEntity> findByTradeId(String tradeId);
}
