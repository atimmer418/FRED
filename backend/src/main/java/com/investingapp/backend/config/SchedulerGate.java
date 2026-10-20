package com.investingapp.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads investment.scheduler.enabled (false under the ci profile). The investment, ACH and
 * Plaid lifecycle @Scheduled jobs check it as their first statement and return when false.
 * Spring uses the no-arg constructor and injects the field; tests use the boolean constructor.
 */
@Component
public class SchedulerGate {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerGate.class);

    @Value("${investment.scheduler.enabled:true}")
    private boolean enabled = true;

    public SchedulerGate() {
    }

    public SchedulerGate(boolean enabled) {
        this.enabled = enabled;
    }

    @PostConstruct
    void logState() {
        if (!enabled) {
            logger.info("investment.scheduler.enabled=false: the investment, ACH and Plaid lifecycle jobs return immediately");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
