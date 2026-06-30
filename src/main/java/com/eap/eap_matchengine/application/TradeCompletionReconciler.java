package com.eap.eap_matchengine.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
@Slf4j
@ConditionalOnProperty(
        name = "eap.match-engine.trade-completion-reconciler.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class TradeCompletionReconciler {

    private final TradeCompletionService tradeCompletionService;
    private final TradeCompletionMetrics metrics;
    private final Duration delayedThreshold;
    private final int batchSize;

    public TradeCompletionReconciler(
            TradeCompletionService tradeCompletionService,
            TradeCompletionMetrics metrics,
            @Value("${eap.match-engine.trade-completion-reconciler.delayed-threshold-seconds:30}") long delayedThresholdSeconds,
            @Value("${eap.match-engine.trade-completion-reconciler.batch-size:100}") int batchSize) {
        this.tradeCompletionService = tradeCompletionService;
        this.metrics = metrics;
        this.delayedThreshold = Duration.ofSeconds(delayedThresholdSeconds);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${eap.match-engine.trade-completion-reconciler.poll-interval-ms:5000}")
    public void reconcile() {
        int backfilled = tradeCompletionService.backfillMissingCompletionRows();
        if (backfilled > 0) {
            log.warn("Backfilled missing trade completion rows: count={}", backfilled);
        }

        int completed = tradeCompletionService.completeReadyRows();
        if (completed > 0) {
            log.info("Completed ready trade completion rows during reconciliation: count={}", completed);
        }

        List<TradeCompletionService.DelayedTradeCompletion> delayed =
                tradeCompletionService.findDelayedCompletions(delayedThreshold, batchSize);

        for (TradeCompletionService.DelayedTradeCompletion row : delayed) {
            metrics.delayedDetected();
            boolean requeued = tradeCompletionService.markDelayedAndRequeueTradeExecuted(row);
            if (requeued) {
                metrics.repairRequeued();
                log.warn("Delayed trade detected; TradeExecuted republish scheduled: tradeId={}, missing={}, executedAt={}, previousRepairAttempts={}",
                        row.tradeId(), row.missingMarkers(), row.tradeExecutedAt(), row.repairAttemptCount());
            } else {
                log.warn("Delayed trade detected; TradeExecuted already pending or outbox missing: tradeId={}, missing={}, executedAt={}, previousRepairAttempts={}",
                        row.tradeId(), row.missingMarkers(), row.tradeExecutedAt(), row.repairAttemptCount());
            }
        }
    }
}
