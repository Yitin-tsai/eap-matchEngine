package com.eap.eap_matchengine.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongUnaryOperator;

@Component
@Slf4j
@ConditionalOnProperty(
        name = "eap.match-engine.order-admission-inbox.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class MatchOrderAdmissionReconciler {

    private final MatchOrderAdmissionInbox inbox;
    private final MatchOrderAdmissionProcessor processor;
    private final MatchOrderAdmissionErrorClassifier classifier;
    private final MatchOrderAdmissionInboxMetrics metrics;
    private final String owner;
    private final int batchSize;
    private final long leaseMs;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final LongUnaryOperator jitter;
    private final Executor workerExecutor;
    private final ExecutorService ownedExecutor;
    private final Semaphore workerCapacity;

    @Autowired
    public MatchOrderAdmissionReconciler(
            MatchOrderAdmissionInbox inbox,
            MatchOrderAdmissionProcessor processor,
            MatchOrderAdmissionErrorClassifier classifier,
            MatchOrderAdmissionInboxMetrics metrics,
            @Value("${eap.match-engine.order-admission-inbox.batch-size:100}") int batchSize,
            @Value("${eap.match-engine.order-admission-inbox.lease-ms:30000}") long leaseMs,
            @Value("${eap.match-engine.order-admission-inbox.max-attempts:20}") int maxAttempts,
            @Value("${eap.match-engine.order-admission-inbox.initial-backoff-ms:250}") long initialBackoffMs,
            @Value("${eap.match-engine.order-admission-inbox.max-backoff-ms:30000}") long maxBackoffMs,
            @Value("${eap.match-engine.order-admission-inbox.worker-concurrency:16}") int workerConcurrency) {
        this(inbox, processor, classifier, metrics,
                batchSize, leaseMs, maxAttempts, initialBackoffMs, maxBackoffMs,
                base -> ThreadLocalRandom.current().nextLong(
                        Math.max(1, base / 2), Math.max(2, base + 1)),
                createWorkerPool(workerConcurrency));
    }

    MatchOrderAdmissionReconciler(
            MatchOrderAdmissionInbox inbox,
            MatchOrderAdmissionProcessor processor,
            MatchOrderAdmissionErrorClassifier classifier,
            MatchOrderAdmissionInboxMetrics metrics,
            int batchSize,
            long leaseMs,
            int maxAttempts,
            long initialBackoffMs,
            long maxBackoffMs,
            LongUnaryOperator jitter) {
        this(inbox, processor, classifier, metrics,
                batchSize, leaseMs, maxAttempts, initialBackoffMs, maxBackoffMs,
                jitter, Runnable::run, null, 1);
    }

    private MatchOrderAdmissionReconciler(
            MatchOrderAdmissionInbox inbox,
            MatchOrderAdmissionProcessor processor,
            MatchOrderAdmissionErrorClassifier classifier,
            MatchOrderAdmissionInboxMetrics metrics,
            int batchSize,
            long leaseMs,
            int maxAttempts,
            long initialBackoffMs,
            long maxBackoffMs,
            LongUnaryOperator jitter,
            WorkerPool workerPool) {
        this(inbox, processor, classifier, metrics,
                batchSize, leaseMs, maxAttempts, initialBackoffMs, maxBackoffMs,
                jitter, workerPool.executor(), workerPool.executor(), workerPool.concurrency());
    }

    private MatchOrderAdmissionReconciler(
            MatchOrderAdmissionInbox inbox,
            MatchOrderAdmissionProcessor processor,
            MatchOrderAdmissionErrorClassifier classifier,
            MatchOrderAdmissionInboxMetrics metrics,
            int batchSize,
            long leaseMs,
            int maxAttempts,
            long initialBackoffMs,
            long maxBackoffMs,
            LongUnaryOperator jitter,
            Executor workerExecutor,
            ExecutorService ownedExecutor,
            int workerConcurrency) {
        this.inbox = inbox;
        this.processor = processor;
        this.classifier = classifier;
        this.metrics = metrics;
        this.owner = UUID.randomUUID().toString();
        this.batchSize = Math.max(1, batchSize);
        this.leaseMs = Math.max(1, leaseMs);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoffMs = Math.max(1, initialBackoffMs);
        this.maxBackoffMs = Math.max(this.initialBackoffMs, maxBackoffMs);
        this.jitter = jitter;
        this.workerExecutor = workerExecutor;
        this.ownedExecutor = ownedExecutor;
        this.workerCapacity = new Semaphore(Math.max(1, workerConcurrency));
    }

    @Scheduled(
            fixedDelayString = "${eap.match-engine.order-admission-inbox.poll-interval-ms:100}",
            initialDelayString = "${eap.match-engine.order-admission-inbox.initial-delay-ms:500}")
    public void reconcile() {
        int availableWorkers = workerCapacity.availablePermits();
        if (availableWorkers == 0) {
            return;
        }
        List<MatchOrderAdmissionInbox.InboxEntry> entries =
                inbox.claimRetryable(Math.min(batchSize, availableWorkers), owner, leaseMs);
        for (MatchOrderAdmissionInbox.InboxEntry entry : entries) {
            workerCapacity.acquireUninterruptibly();
            try {
                workerExecutor.execute(() -> {
                    try {
                        process(entry);
                    } finally {
                        workerCapacity.release();
                    }
                });
            } catch (RuntimeException submissionFailure) {
                workerCapacity.release();
                log.warn("Could not submit Match admission work; lease expiry will retry: orderId={}",
                        entry.orderId(), submissionFailure);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }

    private void process(MatchOrderAdmissionInbox.InboxEntry entry) {
        try {
            processor.process(entry.event());
            if (!inbox.markApplied(entry, owner)) {
                throw new IllegalStateException(
                        "Lost Match admission inbox lease before APPLIED: orderId=" + entry.orderId());
            }
            metrics.applied();
        } catch (Exception failure) {
            try {
                handleFailure(entry, failure);
            } catch (Exception recordFailure) {
                log.warn("Could not record Match admission failure; lease expiry will retry: orderId={}",
                        entry.orderId(), recordFailure);
            }
        }
    }

    private void handleFailure(MatchOrderAdmissionInbox.InboxEntry entry, Exception failure) {
        MatchOrderAdmissionErrorClassifier.Classification classification = classifier.classify(failure);
        if (classification.retryable() && entry.attemptCount() < maxAttempts) {
            long delayMs = retryDelayMs(entry.attemptCount());
            String status = classification.category()
                    == MatchOrderAdmissionErrorClassifier.Category.PREREQUISITE
                    ? "PENDING_PREREQUISITE"
                    : "FAILED_RETRYABLE";
            if (!inbox.reschedule(
                    entry, owner, status, classification.errorType(), failure, delayMs)) {
                log.warn("Lost Match admission lease while rescheduling: orderId={}", entry.orderId());
                return;
            }
            if (classification.category() == MatchOrderAdmissionErrorClassifier.Category.PREREQUISITE) {
                metrics.prerequisiteScheduled();
            } else {
                metrics.retryScheduled();
            }
            return;
        }

        String terminalType = classification.retryable()
                ? "RETRY_EXHAUSTED_" + classification.errorType()
                : classification.errorType();
        if (!inbox.markPermanent(entry, owner, terminalType, failure)) {
            log.warn("Lost Match admission lease while marking permanent: orderId={}", entry.orderId());
            return;
        }
        metrics.permanentFailure();
        log.error("Match admission requires intervention: orderId={}, type={}, attempts={}",
                entry.orderId(), terminalType, entry.attemptCount(), failure);
    }

    private long retryDelayMs(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 30);
        long multiplier = 1L << exponent;
        long base = initialBackoffMs > maxBackoffMs / multiplier
                ? maxBackoffMs
                : Math.min(initialBackoffMs * multiplier, maxBackoffMs);
        return Math.max(1, Math.min(maxBackoffMs, jitter.applyAsLong(base)));
    }

    private static WorkerPool createWorkerPool(int requestedConcurrency) {
        int concurrency = Math.max(1, requestedConcurrency);
        return new WorkerPool(
                Executors.newFixedThreadPool(concurrency, new AdmissionWorkerThreadFactory()),
                concurrency);
    }

    private record WorkerPool(ExecutorService executor, int concurrency) {
    }

    private static final class AdmissionWorkerThreadFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            return new Thread(task, "match-order-admission-" + sequence.incrementAndGet());
        }
    }
}
