package com.eap.eap_matchengine.application;

import java.util.UUID;

public record ReservationCleanupTask(
        String tradeId,
        UUID orderId,
        UUID userId) {

    public static ReservationCleanupTask completed(String tradeId, UUID orderId, UUID userId) {
        return new ReservationCleanupTask(tradeId, orderId, userId);
    }
}
