package com.eap.eap_matchengine.application;

import com.eap.common.event.TradeExecutedEvent;

public interface TradeExecutionRecorder {
    void record(TradeExecutedEvent event);
}
