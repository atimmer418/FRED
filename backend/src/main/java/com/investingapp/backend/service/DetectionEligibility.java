package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;

import java.util.Objects;
import java.util.Optional;

/**
 * Ruling R8: who detection never runs for. Every entry point (evaluate, reconcile, sync pipeline,
 * INIT enqueue, RECONNECT) asks this one pure method, so the rule cannot drift. First match wins,
 * in enum order. PENDING_DISCONNECT stays eligible: the Item works until its disconnect time.
 */
public final class DetectionEligibility {

    /** Why a user is excluded from detection. DISABLED means paycheck.autopilot.enabled=false. */
    public enum ExclusionReason {
        DISABLED, ACCOUNT_REJECTED, OPTED_OUT, NO_TOKEN, ITEM_STATUS
    }

    static final String ACCOUNT_STATUS_REJECTED = "REJECTED";

    private DetectionEligibility() {
    }

    public static Optional<ExclusionReason> exclusion(User user, PaycheckAutopilotProperties props) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(props, "props");
        if (!props.isEnabled()) {
            return Optional.of(ExclusionReason.DISABLED);
        }
        // A closed account waiting to be deleted is out for the same reason a rejected one is.
        if (ACCOUNT_STATUS_REJECTED.equals(user.getAccountStatus()) || user.isClosed()) {
            return Optional.of(ExclusionReason.ACCOUNT_REJECTED);
        }
        if (Boolean.TRUE.equals(user.getPaycheckAutopilotOptOut())) {
            return Optional.of(ExclusionReason.OPTED_OUT);
        }
        String token = user.getPlaidAccessToken();
        if (token == null || token.isBlank()) {
            return Optional.of(ExclusionReason.NO_TOKEN);
        }
        PlaidItemStatus status = user.getPlaidItemStatus();
        // The column is NOT NULL DEFAULT 'OK'; a null can only be an unsaved entity, read as OK.
        if (status != null && status != PlaidItemStatus.OK && status != PlaidItemStatus.PENDING_DISCONNECT) {
            return Optional.of(ExclusionReason.ITEM_STATUS);
        }
        return Optional.empty();
    }
}
