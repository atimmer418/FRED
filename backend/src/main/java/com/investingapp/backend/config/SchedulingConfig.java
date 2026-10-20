package com.investingapp.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        // TLH's four jobs sit beside the investment crons; paycheck autopilot adds the webhook
        // inbox, the item removal drain and its reconcile, so the pool is 6 (spec 12.1).
        taskScheduler.setPoolSize(6);
        taskScheduler.setThreadNamePrefix("investment-scheduler-");
        taskScheduler.setWaitForTasksToCompleteOnShutdown(true);
        taskScheduler.setAwaitTerminationSeconds(60);
        return taskScheduler;
    }

    /**
     * Bounded pool for Plaid HTTP calls that run off a request thread (spec 5.6, 12.1). Nothing in
     * the app injects an Executor by type (only TaskScheduler), so a second executor bean is
     * unambiguous; consumers still name it with @Qualifier("plaidExecutor").
     */
    @Bean(name = "plaidExecutor")
    public ThreadPoolTaskExecutor plaidExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("plaid-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
