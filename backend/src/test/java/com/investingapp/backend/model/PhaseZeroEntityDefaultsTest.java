package com.investingapp.backend.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The V6 defaults (plaid_item_status 'OK', mode 'MANUAL', attempts 0) must be mirrored by field
 * initialisers, because User(String) and the InvestmentExecution constructors never set them and
 * Hibernate validate checks column types only, never defaults.
 */
class PhaseZeroEntityDefaultsTest {

    @Test
    void userStartsLinkedOkWithUnknownConsent() {
        User user = new User("phase0@fredvested.com");
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.OK);
        assertThat(user.getPlaidTransactionsConsented()).isNull();
        assertThat(user.getPlaidAccountMask()).isNull();
        assertThat(user.getPlaidItemErrorCode()).isNull();
        assertThat(user.getPlaidItemLinkedAt()).isNull();
        assertThat(user.getPlaidWebhookAckedAt()).isNull();
        assertThat(user.getPreviousAchRelationshipId()).isNull();
    }

    @Test
    void scheduleIsManualWithNoHold() {
        InvestmentSchedule schedule = new InvestmentSchedule();
        assertThat(schedule.getMode()).isEqualTo(ScheduleMode.MANUAL);
        assertThat(schedule.getHoldReason()).isNull();
    }

    @Test
    void executionIdempotencyFieldsStartEmpty() {
        InvestmentExecution execution = new InvestmentExecution();
        assertThat(execution.getSchedule()).isNull();
        assertThat(execution.getSourceKey()).isNull();
        assertThat(execution.getNightKey()).isNull();
        assertThat(execution.getBatchNight()).isNull();
        assertThat(execution.getTransferAttemptedAt()).isNull();
    }

    @Test
    void countersStartAtZero() {
        assertThat(new PlaidWebhookEvent().getAttempts()).isEqualTo((short) 0);
        assertThat(new PlaidItemRemoval().getAttempts()).isZero();
    }

    @Test
    void enumNamesFitTheirColumns() {
        assertThat(longest(PlaidItemStatus.values())).isLessThanOrEqualTo(24);
        assertThat(longest(ScheduleMode.values())).isLessThanOrEqualTo(10);
        assertThat(longest(HoldReason.values())).isLessThanOrEqualTo(32);
        assertThat(longest(PlaidWebhookEventStatus.values())).isLessThanOrEqualTo(12);
        assertThat(HoldReason.values()).hasSize(10);
        assertThat(PlaidItemStatus.values()).hasSize(7);
    }

    private static int longest(Enum<?>[] values) {
        return Arrays.stream(values).mapToInt(v -> v.name().length()).max().orElse(0);
    }
}
