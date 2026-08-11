package com.eap.eap_matchengine.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Component
public class ReservationCleanupTaskStore {

    private final JdbcTemplate jdbcTemplate;

    public ReservationCleanupTaskStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Set<String> findActiveTradeIds(Collection<String> tradeIds) {
        if (tradeIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(tradeIds.size(), "?"));
        return new HashSet<>(jdbcTemplate.queryForList("""
                SELECT trade_id
                FROM match_engine.reservation_cleanup_tasks
                WHERE status IN ('PENDING', 'PROCESSING')
                  AND trade_id IN (%s)
                """.formatted(placeholders), String.class, tradeIds.toArray()));
    }
}
