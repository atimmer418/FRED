package com.investingapp.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerGateTest {

    @Test
    void isEnabledReflectsTheFlag() {
        assertTrue(new SchedulerGate(true).isEnabled());
        assertFalse(new SchedulerGate(false).isEnabled());
    }

    @Test
    void springFillsTheFlagFromInvestmentSchedulerEnabledDefaultingToTrue() throws Exception {
        Value value = SchedulerGate.class.getDeclaredField("enabled").getAnnotation(Value.class);
        assertNotNull(value, "the enabled field must carry @Value");
        assertEquals("${investment.scheduler.enabled:true}", value.value());
        assertTrue(new SchedulerGate().isEnabled(), "a bare instance (no Spring) defaults to open");
    }
}
