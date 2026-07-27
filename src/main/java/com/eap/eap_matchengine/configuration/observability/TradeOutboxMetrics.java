package com.eap.eap_matchengine.configuration.observability;

import com.eap.eap_matchengine.configuration.repository.TradeOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class TradeOutboxMetrics {

    private final Counter publishedTotal;
    private final Counter publishFailedTotal;
    private final Counter retryScheduledTotal;
    private final Timer publishDuration;
    private final Timer selectDuration;
    private final Timer publishStageDuration;
    private final Timer publishEnqueueDuration;
    private final Timer rowMappingDuration;
    private final Timer payloadRebuildDuration;
    private final Timer messageBuildDuration;
    private final Timer confirmDuration;
    private final Timer firstConfirmDuration;
    private final Timer remainingConfirmDuration;
    private final Timer confirmWallDuration;
    private final Timer postConfirmMarkGapDuration;
    private final Timer markSentDuration;
    private final Timer batchDuration;
    private final DistributionSummary batchSize;
    private final DistributionSummary confirmedBatchSize;

    public TradeOutboxMetrics(MeterRegistry registry, TradeOutboxRepository repository) {
        this.publishedTotal = Counter.builder("match_engine_trade_outbox_published_total")
                .description("Total successfully published TradeExecuted outbox events")
                .register(registry);
        this.publishFailedTotal = Counter.builder("match_engine_trade_outbox_publish_failed_total")
                .description("Total failed TradeExecuted outbox publish attempts")
                .register(registry);
        this.retryScheduledTotal = Counter.builder("match_engine_trade_outbox_retry_scheduled_total")
                .description("Total TradeExecuted outbox retries scheduled")
                .register(registry);
        this.publishDuration = Timer.builder("match_engine_trade_outbox_publish_duration")
                .description("TradeExecuted outbox publish and confirm duration")
                .register(registry);
        this.selectDuration = stageTimer(
                registry,
                "match_engine_trade_outbox_select_duration",
                "Time spent selecting pending TradeExecuted outbox records");
        this.publishStageDuration = stageTimer(
                registry,
                "match_engine_trade_outbox_publish_stage_duration",
                "Wall-clock time spent in the TradeExecuted outbox publish stage before mark-SENT");
        this.publishEnqueueDuration = stageTimer(
                registry,
                "match_engine_trade_outbox_publish_enqueue_duration",
                "Time spent deserializing and enqueueing TradeExecuted outbox records to RabbitMQ");
        this.rowMappingDuration = stageTimer(
                registry,
                "match_engine_trade_outbox_row_mapping_duration",
                "Time spent mapping selected TradeExecuted outbox rows");
        this.payloadRebuildDuration = stageTimer(
                registry,
                "match_engine_trade_outbox_payload_rebuild_duration",
                "Time spent rebuilding TradeExecuted payloads from persisted trade facts");
        this.messageBuildDuration = stageTimer(
                registry,
                "match_engine_trade_outbox_message_build_duration",
                "Time spent building RabbitMQ messages for TradeExecuted outbox records");
        this.confirmDuration = stageTimer(
                registry,
                "match_engine_trade_outbox_confirm_duration",
                "Time spent waiting for RabbitMQ publisher confirms for TradeExecuted outbox records");
        this.firstConfirmDuration = stageTimer(
                registry,
                "match_engine_trade_outbox_first_confirm_duration",
                "Time spent waiting for the first RabbitMQ publisher confirm in a TradeExecuted outbox chunk");
        this.remainingConfirmDuration = stageTimer(
                registry,
                "match_engine_trade_outbox_remaining_confirm_duration",
                "Time spent waiting for RabbitMQ publisher confirms after the first confirmed record in a TradeExecuted outbox chunk");
        this.confirmWallDuration = stageTimer(
                registry,
                "match_engine_trade_outbox_confirm_wall_duration",
                "Batch or chunk wall-clock time spent waiting for RabbitMQ publisher confirms for TradeExecuted outbox records");
        this.postConfirmMarkGapDuration = stageTimer(
                registry,
                "match_engine_trade_outbox_post_confirm_mark_gap_duration",
                "Wall-clock gap between completing TradeExecuted publisher confirms and starting mark-SENT");
        this.markSentDuration = stageTimer(
                registry,
                "match_engine_trade_outbox_mark_sent_duration",
                "Time spent marking confirmed TradeExecuted outbox records as SENT");
        this.batchDuration = stageTimer(
                registry,
                "match_engine_trade_outbox_batch_duration",
                "Wall-clock time spent processing one TradeExecuted outbox relay batch");
        this.batchSize = DistributionSummary.builder("match_engine_trade_outbox_batch_size")
                .description("Number of TradeExecuted outbox records selected per relay batch")
                .register(registry);
        this.confirmedBatchSize = DistributionSummary.builder("match_engine_trade_outbox_confirmed_batch_size")
                .description("Number of TradeExecuted outbox records marked SENT per relay batch")
                .register(registry);

        Gauge.builder("match_engine_trade_outbox_pending", repository, repo -> repo.countByStatus("PENDING"))
                .description("Number of pending TradeExecuted outbox events")
                .register(registry);
        Gauge.builder("match_engine_trade_outbox_failed", repository, repo -> repo.countByStatus("FAILED"))
                .description("Number of permanently failed TradeExecuted outbox events")
                .register(registry);
        Gauge.builder("match_engine_trade_outbox_oldest_pending_age_seconds", repository, repo -> repo
                .findFirstByStatusOrderByCreatedAtAsc("PENDING")
                .map(event -> Duration.between(event.getCreatedAt(), LocalDateTime.now()).toSeconds())
                .orElse(0L))
                .description("Age in seconds of oldest pending TradeExecuted outbox event")
                .register(registry);
    }

    public void published() {
        publishedTotal.increment();
    }

    public void publishFailed() {
        publishFailedTotal.increment();
    }

    public void retryScheduled() {
        retryScheduledTotal.increment();
    }

    public void recordPublish(Duration duration) {
        publishDuration.record(duration);
    }

    public void recordSelect(Duration duration) {
        selectDuration.record(duration);
    }

    public void recordPublishStage(Duration duration) {
        publishStageDuration.record(duration);
    }

    public void recordPublishEnqueue(Duration duration) {
        publishEnqueueDuration.record(duration);
    }

    public void recordRowMapping(Duration duration) {
        rowMappingDuration.record(duration);
    }

    public void recordPayloadRebuild(Duration duration) {
        payloadRebuildDuration.record(duration);
    }

    public void recordMessageBuild(Duration duration) {
        messageBuildDuration.record(duration);
    }

    public void recordConfirm(Duration duration) {
        confirmDuration.record(duration);
    }

    public void recordFirstConfirm(Duration duration) {
        firstConfirmDuration.record(duration);
    }

    public void recordRemainingConfirm(Duration duration) {
        remainingConfirmDuration.record(duration);
    }

    public void recordConfirmWall(Duration duration) {
        confirmWallDuration.record(duration);
    }

    public void recordPostConfirmMarkGap(Duration duration) {
        postConfirmMarkGapDuration.record(duration);
    }

    public void recordMarkSent(Duration duration) {
        markSentDuration.record(duration);
    }

    public void recordBatch(Duration duration) {
        batchDuration.record(duration);
    }

    public void recordBatchSize(int size) {
        batchSize.record(size);
    }

    public void recordConfirmedBatchSize(int size) {
        confirmedBatchSize.record(size);
    }

    private Timer stageTimer(MeterRegistry registry, String name, String description) {
        return Timer.builder(name)
                .description(description)
                .publishPercentileHistogram()
                .register(registry);
    }
}
