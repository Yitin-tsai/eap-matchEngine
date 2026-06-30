package com.eap.eap_matchengine.configuration.repository;

import com.eap.eap_matchengine.domain.entity.TradeOutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeOutboxRepository extends JpaRepository<TradeOutboxEntity, Long> {
}
