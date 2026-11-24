package com.investingapp.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The one typed home for paycheck.autopilot.* (spec 12.1). Spring uses the no-arg constructor and
 * fills the fields; tests use the all-args one. Field defaults equal the property defaults, so a
 * bare instance is the safe state: off, shadow, no pulls. Inconsistent tunables stop the boot,
 * because a silently wrong threshold would score every user.
 */
@Component
public class PaycheckAutopilotProperties {

    private static final Logger logger = LoggerFactory.getLogger(PaycheckAutopilotProperties.class);

    /** Plaid accepts days_requested from 1 to 730; it is honoured only on an Item's first sync. */
    static final int DAYS_REQUESTED_MIN = 1;
    static final int DAYS_REQUESTED_MAX = 730;

    @Value("${paycheck.autopilot.enabled:false}")
    private boolean enabled = false;

    @Value("${paycheck.autopilot.shadow:true}")
    private boolean shadow = true;

    @Value("${paycheck.autopilot.pulls.enabled:false}")
    private boolean pullsEnabled = false;

    @Value("${paycheck.autopilot.score.provisional:55}")
    private int scoreProvisional = 55;

    @Value("${paycheck.autopilot.score.confirmed:70}")
    private int scoreConfirmed = 70;

    @Value("${paycheck.autopilot.affordability.share:0.90}")
    private BigDecimal affordabilityShare = new BigDecimal("0.90");

    @Value("${paycheck.autopilot.days-requested:365}")
    private int daysRequested = 365;

    public PaycheckAutopilotProperties() {
    }

    public PaycheckAutopilotProperties(boolean enabled, boolean shadow, boolean pullsEnabled,
            int scoreProvisional, int scoreConfirmed, BigDecimal affordabilityShare, int daysRequested) {
        this.enabled = enabled;
        this.shadow = shadow;
        this.pullsEnabled = pullsEnabled;
        this.scoreProvisional = scoreProvisional;
        this.scoreConfirmed = scoreConfirmed;
        this.affordabilityShare = affordabilityShare;
        this.daysRequested = daysRequested;
        validate();
    }

    @PostConstruct
    void validateAndLog() {
        validate();
        logger.info("paycheck.autopilot.config enabled={} shadow={} pulls_enabled={} score_provisional={}"
                        + " score_confirmed={} affordability_share={} days_requested={}",
                enabled, shadow, pullsEnabled, scoreProvisional, scoreConfirmed, affordabilityShare, daysRequested);
    }

    void validate() {
        List<String> problems = new ArrayList<>();
        if (scoreProvisional < 0 || scoreConfirmed > 100 || scoreProvisional >= scoreConfirmed) {
            problems.add("paycheck.autopilot.score.provisional (" + scoreProvisional
                    + ") must be below paycheck.autopilot.score.confirmed (" + scoreConfirmed
                    + "), both within 0 to 100");
        }
        if (affordabilityShare == null || affordabilityShare.signum() <= 0
                || affordabilityShare.compareTo(BigDecimal.ONE) > 0) {
            problems.add("paycheck.autopilot.affordability.share (" + affordabilityShare
                    + ") must be above 0 and at most 1");
        }
        if (daysRequested < DAYS_REQUESTED_MIN || daysRequested > DAYS_REQUESTED_MAX) {
            problems.add("paycheck.autopilot.days-requested (" + daysRequested + ") must be from "
                    + DAYS_REQUESTED_MIN + " to " + DAYS_REQUESTED_MAX);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("paycheck.autopilot settings are inconsistent: " + problems);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isShadow() {
        return shadow;
    }

    public boolean isPullsEnabled() {
        return pullsEnabled;
    }

    public int getScoreProvisional() {
        return scoreProvisional;
    }

    public int getScoreConfirmed() {
        return scoreConfirmed;
    }

    public BigDecimal getAffordabilityShare() {
        return affordabilityShare;
    }

    public int getDaysRequested() {
        return daysRequested;
    }
}
