package com.eap.eap_matchengine.configuration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class MatchEngineSchedulerConfig {

    public static final String TRADE_OUTBOX_SCHEDULER = "tradeOutboxTaskScheduler";
    public static final String RESERVATION_MAINTENANCE_SCHEDULER = "reservationMaintenanceTaskScheduler";
    public static final String DEFAULT_SCHEDULER = "taskScheduler";

    @Bean(name = DEFAULT_SCHEDULER)
    ThreadPoolTaskScheduler taskScheduler() {
        return singleThreadScheduler("match-scheduler-");
    }

    @Bean(name = TRADE_OUTBOX_SCHEDULER)
    ThreadPoolTaskScheduler tradeOutboxTaskScheduler() {
        return singleThreadScheduler("match-trade-outbox-");
    }

    @Bean(name = RESERVATION_MAINTENANCE_SCHEDULER)
    ThreadPoolTaskScheduler reservationMaintenanceTaskScheduler() {
        return singleThreadScheduler("match-reservation-maintenance-");
    }

    private ThreadPoolTaskScheduler singleThreadScheduler(String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
