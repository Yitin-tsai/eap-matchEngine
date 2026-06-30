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
}
