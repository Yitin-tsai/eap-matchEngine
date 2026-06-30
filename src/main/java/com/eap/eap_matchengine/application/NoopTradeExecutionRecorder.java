package com.eap.eap_matchengine.application;

import com.eap.common.event.TradeExecutedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(TradeExecutionRecorder.class)
@Slf4j
public class NoopTradeExecutionRecorder implements TradeExecutionRecorder {

    @Override
    public void record(TradeExecutedEvent event) {
        log.debug("TradeExecutionRecorder is disabled; skip persisting tradeId={}", event.getTradeId());
    }
}
