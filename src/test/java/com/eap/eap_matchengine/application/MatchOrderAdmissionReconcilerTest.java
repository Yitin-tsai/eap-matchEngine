package com.eap.eap_matchengine.application;

import com.eap.common.event.OrderAssetReservationSucceededEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchOrderAdmissionReconcilerTest {

    @Mock MatchOrderAdmissionInbox inbox;
    @Mock MatchOrderAdmissionProcessor processor;
    @Mock MatchOrderAdmissionErrorClassifier classifier;
    @Mock MatchOrderAdmissionInboxMetrics metrics;

    private MatchOrderAdmissionReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new MatchOrderAdmissionReconciler(
                inbox, processor, classifier, metrics,
                10, 30_000, 5, 250, 30_000, base -> base);
    }

    @Test
    void success_shouldMarkInboxApplied() {
        MatchOrderAdmissionInbox.InboxEntry entry = entry(1);
        when(inbox.claimRetryable(eq(1), anyString(), eq(30_000L)))
                .thenReturn(List.of(entry));
        when(inbox.markApplied(eq(entry), anyString())).thenReturn(true);

        reconciler.reconcile();

        verify(processor).process(entry.event());
        verify(inbox).markApplied(eq(entry), anyString());
        verify(metrics).applied();
    }

    @Test
    void transientFailure_shouldScheduleExponentialRetry() {
        MatchOrderAdmissionInbox.InboxEntry entry = entry(2);
        RuntimeException failure = new RuntimeException("Redis unavailable");
        when(inbox.claimRetryable(eq(1), anyString(), eq(30_000L)))
                .thenReturn(List.of(entry));
        doThrow(failure).when(processor).process(entry.event());
        when(classifier.classify(failure)).thenReturn(new MatchOrderAdmissionErrorClassifier.Classification(
                MatchOrderAdmissionErrorClassifier.Category.TRANSIENT, "TRANSIENT_REDIS"));
        when(inbox.reschedule(
                eq(entry), anyString(), eq("FAILED_RETRYABLE"),
                eq("TRANSIENT_REDIS"), eq(failure), eq(500L)))
                .thenReturn(true);

        reconciler.reconcile();

        verify(inbox).reschedule(
                eq(entry), anyString(), eq("FAILED_RETRYABLE"),
                eq("TRANSIENT_REDIS"), eq(failure), eq(500L));
        verify(metrics).retryScheduled();
    }

    @Test
    void prerequisiteFailure_shouldUseDedicatedNonTerminalState() {
        MatchOrderAdmissionInbox.InboxEntry entry = entry(1);
        MatchOrderAdmissionPrerequisiteNotReadyException failure =
                new MatchOrderAdmissionPrerequisiteNotReadyException("reservation still active");
        when(inbox.claimRetryable(eq(1), anyString(), eq(30_000L)))
                .thenReturn(List.of(entry));
        doThrow(failure).when(processor).process(entry.event());
        when(classifier.classify(failure)).thenReturn(new MatchOrderAdmissionErrorClassifier.Classification(
                MatchOrderAdmissionErrorClassifier.Category.PREREQUISITE,
                "PREREQUISITE_RECOVERY_PENDING"));
        when(inbox.reschedule(
                eq(entry), anyString(), eq("PENDING_PREREQUISITE"),
                eq("PREREQUISITE_RECOVERY_PENDING"), eq(failure), eq(250L)))
                .thenReturn(true);

        reconciler.reconcile();

        verify(inbox).reschedule(
                eq(entry), anyString(), eq("PENDING_PREREQUISITE"),
                eq("PREREQUISITE_RECOVERY_PENDING"), eq(failure), eq(250L));
        verify(metrics).prerequisiteScheduled();
        verify(inbox, never()).markPermanent(eq(entry), anyString(), anyString(), eq(failure));
    }

    @Test
    void retryBudgetExhausted_shouldBecomePermanent() {
        MatchOrderAdmissionInbox.InboxEntry entry = entry(5);
        RuntimeException failure = new RuntimeException("Redis unavailable");
        when(inbox.claimRetryable(eq(1), anyString(), eq(30_000L)))
                .thenReturn(List.of(entry));
        doThrow(failure).when(processor).process(entry.event());
        when(classifier.classify(failure)).thenReturn(new MatchOrderAdmissionErrorClassifier.Classification(
                MatchOrderAdmissionErrorClassifier.Category.TRANSIENT, "TRANSIENT_REDIS"));
        when(inbox.markPermanent(
                eq(entry), anyString(), eq("RETRY_EXHAUSTED_TRANSIENT_REDIS"), eq(failure)))
                .thenReturn(true);

        reconciler.reconcile();

        verify(inbox).markPermanent(
                eq(entry), anyString(), eq("RETRY_EXHAUSTED_TRANSIENT_REDIS"), eq(failure));
        verify(metrics).permanentFailure();
    }

    private MatchOrderAdmissionInbox.InboxEntry entry(int attemptCount) {
        UUID orderId = UUID.randomUUID();
        return new MatchOrderAdmissionInbox.InboxEntry(
                orderId,
                OrderAssetReservationSucceededEvent.builder()
                        .orderId(orderId)
                        .userId(UUID.randomUUID())
                        .marketId("TEST-MARKET")
                        .marketSequence(1L)
                        .price(100)
                        .amount(1)
                        .orderType("BUY")
                        .build(),
                attemptCount);
    }
}
