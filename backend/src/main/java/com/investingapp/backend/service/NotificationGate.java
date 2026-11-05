package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Spec 10 and 12.1: every DETECTED-only notice asks this first. Suppressed when disabled, under
 * shadow, or on the KILL_SWITCH hold; logged as paycheck.notice.suppressed with a closed reason.
 * Under Phase 1 (enabled, shadow) all are suppressed: the exit criterion "zero user-facing DETECTED
 * notices". Both-modes notices (RECONNECT) do not come through here.
 */
@Component
public class NotificationGate {

    private static final Logger logger = LoggerFactory.getLogger(NotificationGate.class);

    static final String REASON_DISABLED = "disabled";
    static final String REASON_SHADOW = "shadow";
    static final String REASON_KILL_SWITCH = "kill_switch";

    private final PaycheckAutopilotProperties props;

    public NotificationGate(PaycheckAutopilotProperties props) {
        this.props = props;
    }

    /** True when a DETECTED-only notice for this user must not be sent. Never writes anything. */
    public boolean suppressed(User user, InvestmentSchedule scheduleOrNull) {
        Objects.requireNonNull(user, "user");
        Optional<String> reason = reason(scheduleOrNull);
        reason.ifPresent(r -> {
            logger.info("paycheck.notice.suppressed reason={}", r);
            logger.debug("paycheck.notice.suppressed.detail user={} reason={}", user.getId(), r);
        });
        return reason.isPresent();
    }

    /** The first matching reason: the master switch, then shadow, then the schedule's kill switch. */
    Optional<String> reason(InvestmentSchedule scheduleOrNull) {
        if (!props.isEnabled()) {
            return Optional.of(REASON_DISABLED);
        }
        if (props.isShadow()) {
            return Optional.of(REASON_SHADOW);
        }
        if (scheduleOrNull != null && scheduleOrNull.getHoldReason() == HoldReason.KILL_SWITCH) {
            return Optional.of(REASON_KILL_SWITCH);
        }
        return Optional.empty();
    }
}
