package com.investingapp.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class OpsAlertExecutorTest {

    @Test
    void opsAlertExecutorIsOneThreadQueue200DroppingTheOldest() {
        ThreadPoolTaskExecutor executor = new SchedulingConfig().opsAlertExecutor();
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(200);
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.DiscardOldestPolicy.class);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("ops-alert-");
        } finally {
            executor.shutdown();
        }
    }
}
