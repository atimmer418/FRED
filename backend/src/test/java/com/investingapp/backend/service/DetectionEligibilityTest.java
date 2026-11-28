package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class DetectionEligibilityTest {

    private static final PaycheckAutopilotProperties ON = props(true);

    private User user;

    private static PaycheckAutopilotProperties props(boolean enabled) {
        return new PaycheckAutopilotProperties(enabled, true, false, 55, 70, new BigDecimal("0.90"), 365);
    }

    @BeforeEach
    void setUp() {
        user = new User("saver@fredvested.com");
        user.setId(42L);
        user.setAccountStatus("ACTIVE");
        user.setPlaidAccessToken("access-sandbox-token");
        user.setPlaidItemStatus(PlaidItemStatus.OK);
        user.setPaycheckAutopilotOptOut(false);
    }

    @Test
    void eligibleUserHasNoExclusion() {
        assertThat(DetectionEligibility.exclusion(user, ON)).isEmpty();
        for (String status : new String[] {null, "SUBMITTED", "APPROVAL_PENDING", "APPROVED", "ACTION_REQUIRED"}) {
            user.setAccountStatus(status);
            assertThat(DetectionEligibility.exclusion(user, ON)).as("account_status " + status).isEmpty();
        }
    }

    @Test
    void disabledExcludesEveryone() {
        assertThat(DetectionEligibility.exclusion(user, props(false))).contains(ExclusionReason.DISABLED);
    }

    @Test
    void rejectedAccountExcluded() {
        user.setAccountStatus("REJECTED");
        assertThat(DetectionEligibility.exclusion(user, ON)).contains(ExclusionReason.ACCOUNT_REJECTED);
    }

    @Test
    void closedAccountExcluded() {
        user.setAccountStatus("CLOSED");
        assertThat(DetectionEligibility.exclusion(user, ON)).contains(ExclusionReason.ACCOUNT_REJECTED);
    }

    @Test
    void optedOutExcluded() {
        user.setPaycheckAutopilotOptOut(true);
        assertThat(DetectionEligibility.exclusion(user, ON)).contains(ExclusionReason.OPTED_OUT);
        user.setPaycheckAutopilotOptOut(null);
        assertThat(DetectionEligibility.exclusion(user, ON)).as("null is not an opt-out").isEmpty();
    }

    @Test
    void missingOrBlankTokenExcluded() {
        user.setPlaidAccessToken(null);
        assertThat(DetectionEligibility.exclusion(user, ON)).contains(ExclusionReason.NO_TOKEN);
        user.setPlaidAccessToken("   ");
        assertThat(DetectionEligibility.exclusion(user, ON)).contains(ExclusionReason.NO_TOKEN);
    }

    @Test
    void brokenItemStatusExcluded() {
        for (PlaidItemStatus status : EnumSet.complementOf(EnumSet.of(PlaidItemStatus.OK, PlaidItemStatus.PENDING_DISCONNECT))) {
            user.setPlaidItemStatus(status);
            assertThat(DetectionEligibility.exclusion(user, ON)).as(status.name()).contains(ExclusionReason.ITEM_STATUS);
        }
    }

    @Test
    void pendingDisconnectStillEligible() {
        user.setPlaidItemStatus(PlaidItemStatus.PENDING_DISCONNECT);
        assertThat(DetectionEligibility.exclusion(user, ON)).isEmpty();
    }

    @Test
    void firstReasonWinsInEnumOrder() {
        user.setAccountStatus("REJECTED");
        user.setPaycheckAutopilotOptOut(true);
        user.setPlaidAccessToken(null);
        user.setPlaidItemStatus(PlaidItemStatus.REVOKED);
        assertThat(DetectionEligibility.exclusion(user, props(false))).contains(ExclusionReason.DISABLED);
        assertThat(DetectionEligibility.exclusion(user, ON)).contains(ExclusionReason.ACCOUNT_REJECTED);
        user.setAccountStatus("ACTIVE");
        assertThat(DetectionEligibility.exclusion(user, ON)).contains(ExclusionReason.OPTED_OUT);
        user.setPaycheckAutopilotOptOut(false);
        assertThat(DetectionEligibility.exclusion(user, ON)).contains(ExclusionReason.NO_TOKEN);
        user.setPlaidAccessToken("access-sandbox-token");
        assertThat(DetectionEligibility.exclusion(user, ON)).contains(ExclusionReason.ITEM_STATUS);
    }

    @Test
    void reasonsAreExactlyTheFiveOfRuleEight() {
        assertThat(ExclusionReason.values()).containsExactly(ExclusionReason.DISABLED,
                ExclusionReason.ACCOUNT_REJECTED, ExclusionReason.OPTED_OUT, ExclusionReason.NO_TOKEN,
                ExclusionReason.ITEM_STATUS);
    }
}
