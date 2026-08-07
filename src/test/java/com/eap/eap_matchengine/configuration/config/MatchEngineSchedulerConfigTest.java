package com.eap.eap_matchengine.configuration.config;

import com.eap.eap_matchengine.application.ReservationCleanupWorker;
import com.eap.eap_matchengine.application.ReservationReconciler;
import com.eap.eap_matchengine.application.TradeExecutionCheckpointRelay;
import com.eap.eap_matchengine.application.TradeOutboxRelay;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MatchEngineSchedulerConfigTest {

    private final MatchEngineSchedulerConfig config = new MatchEngineSchedulerConfig();

    @Test
    void createsIndependentSingleThreadSchedulers() throws Exception {
        ThreadPoolTaskScheduler outboxScheduler = config.tradeOutboxTaskScheduler();
        ThreadPoolTaskScheduler reservationScheduler = config.reservationMaintenanceTaskScheduler();
        ThreadPoolTaskScheduler defaultScheduler = config.taskScheduler();
        outboxScheduler.initialize();
        reservationScheduler.initialize();
        defaultScheduler.initialize();

        try {
            String outboxThread = outboxScheduler.submit(() -> Thread.currentThread().getName())
                    .get(5, TimeUnit.SECONDS);
            String reservationThread = reservationScheduler.submit(() -> Thread.currentThread().getName())
                    .get(5, TimeUnit.SECONDS);
            String defaultThread = defaultScheduler.submit(() -> Thread.currentThread().getName())
                    .get(5, TimeUnit.SECONDS);

            assertThat(outboxThread).startsWith("match-trade-outbox-");
            assertThat(reservationThread).startsWith("match-reservation-maintenance-");
            assertThat(defaultThread).startsWith("match-scheduler-");
            assertThat(outboxThread).isNotEqualTo(reservationThread);
            assertThat(defaultThread).isNotIn(outboxThread, reservationThread);
        } finally {
            outboxScheduler.shutdown();
            reservationScheduler.shutdown();
            defaultScheduler.shutdown();
        }
    }

    @Test
    void assignsScheduledTasksToTheirOwnedDomains() throws Exception {
        assertScheduler(TradeOutboxRelay.class, "pollAndPublish",
                MatchEngineSchedulerConfig.TRADE_OUTBOX_SCHEDULER);
        assertScheduler(TradeExecutionCheckpointRelay.class, "pollAndPublish",
                MatchEngineSchedulerConfig.TRADE_OUTBOX_SCHEDULER);
        assertScheduler(ReservationCleanupWorker.class, "cleanup",
                MatchEngineSchedulerConfig.RESERVATION_MAINTENANCE_SCHEDULER);
        assertScheduler(ReservationReconciler.class, "reconcile",
                MatchEngineSchedulerConfig.RESERVATION_MAINTENANCE_SCHEDULER);
    }

    private void assertScheduler(Class<?> taskType, String methodName, String expectedScheduler)
            throws NoSuchMethodException {
        Method method = taskType.getMethod(methodName);
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.scheduler()).isEqualTo(expectedScheduler);
    }
}
