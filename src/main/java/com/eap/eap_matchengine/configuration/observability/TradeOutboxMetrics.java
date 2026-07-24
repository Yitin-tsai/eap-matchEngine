package com.eap.eap_matchengine.configuration.observability;

import com.eap.eap_matchengine.configuration.repository.TradeOutboxRepository;
import io.micrometer.core.instrument.Counter;
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
    private final Timer confirmDuration;
    private final Timer confirmWallDuration;
    private final Timer postConfirmMarkGapDuration;
    private final Timer markSentDuration;
    private final Timer batchDuration;

    public TradeOutboxMetrics(MeterRegistry registry, TradeOutboxRepository repository) {
        this.publishedTotal = Counter.builder("trade_outbox_published_total")
                .description("Total successfully published TradeExecuted outbox events")
                .register(registry);
        this.publishFailedTotal = Counter.builder("trade_outbox_publish_failed_total")
                .description("Total failed TradeExecuted outbox publish attempts")
                .register(registry);
        this.retryScheduledTotal = Counter.builder("trade_outbox_retry_scheduled_total")
                .description("Total TradeExecuted outbox retries scheduled")
                .register(registry);
        this.publishDuration = Timer.builder("trade_outbox_publish_duration")
                .description("TradeExecuted outbox publish and confirm duration")
                .register(registry);
        this.selectDuration = stageTimer(
                registry,
                "trade_outbox_select_duration",
                "Time spent selecting pending TradeExecuted outbox records");
        this.publishStageDuration = stageTimer(
                registry,
                "trade_outbox_publish_stage_duration",
                "Wall-clock time spent in the TradeExecuted outbox publish stage before mark-SENT");
        this.publishEnqueueDuration = stageTimer(
                registry,
                "trade_outbox_publish_enqueue_duration",
                "Time spent deserializing and enqueueing TradeExecuted outbox records to RabbitMQ");
        this.confirmDuration = stageTimer(
                registry,
                "trade_outbox_confirm_duration",
                "Time spent waiting for RabbitMQ publisher confirms for TradeExecuted outbox records");
        this.confirmWallDuration = stageTimer(
                registry,
                "trade_outbox_confirm_wall_duration",
                "Batch or chunk wall-clock time spent waiting for RabbitMQ publisher confirms for TradeExecuted outbox records");
        this.postConfirmMarkGapDuration = stageTimer(
                registry,
                "trade_outbox_post_confirm_mark_gap_duration",
                "Wall-clock gap between completing TradeExecuted publisher confirms and starting mark-SENT");
        this.markSentDuration = stageTimer(
                registry,
                "trade_outbox_mark_sent_duration",
                "Time spent marking confirmed TradeExecuted outbox records as SENT");
        this.batchDuration = stageTimer(
                registry,
                "trade_outbox_batch_duration",
                "Wall-clock time spent processing one TradeExecuted outbox relay batch");

        Gauge.builder("trade_outbox_pending", repository, repo -> repo.countByStatus("PENDING"))
                .description("Number of pending TradeExecuted outbox events")
                .register(registry);
        Gauge.builder("trade_outbox_failed", repository, repo -> repo.countByStatus("FAILED"))
                .description("Number of permanently failed TradeExecuted outbox events")
                .register(registry);
        Gauge.builder("trade_outbox_oldest_pending_age_seconds", repository, repo -> repo
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

    public void recordConfirm(Duration duration) {
        confirmDuration.record(duration);
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

    private Timer stageTimer(MeterRegistry registry, String name, String description) {
        return Timer.builder(name)
                .description(description)
                .publishPercentileHistogram()
                .register(registry);
    }
}
