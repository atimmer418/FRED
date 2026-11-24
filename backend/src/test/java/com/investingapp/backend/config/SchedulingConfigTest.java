package com.investingapp.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest {

    @Test
    void plaidExecutorRunsFourItemsAtOnce() {
        ThreadPoolTaskExecutor executor = new SchedulingConfig().plaidExecutor();
        assertThat(executor.getCorePoolSize()).isEqualTo(4);
        assertThat(executor.getMaxPoolSize()).isEqualTo(4);
    }
}
