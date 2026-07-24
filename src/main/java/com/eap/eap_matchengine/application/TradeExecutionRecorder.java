package com.eap.eap_matchengine.application;

import com.eap.common.event.TradeExecutedEvent;

public interface TradeExecutionRecorder {
    void record(TradeExecutedEvent event);

    default boolean record(TradeExecutedEvent event, ReservationCleanupTask reservationCleanupTask) {
        record(event);
        return false;
    }
}
