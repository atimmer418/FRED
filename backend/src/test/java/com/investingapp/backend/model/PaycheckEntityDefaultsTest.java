package com.investingapp.backend.model;

import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 mirrors of the V6 DEFAULTs and enum widths. Hibernate validate checks column types only,
 * never a DEFAULT and never a length, so an initialiser that drifts from V6, or an enum constant
 * longer than its VARCHAR, would only surface as a failed insert at runtime.
 */
class PaycheckEntityDefaultsTest {

    @Test
    void enumNamesFitTheirColumns() {
        assertThat(longest(PaycheckStreamState.values())).isLessThanOrEqualTo(12);
        assertThat(longest(PaycheckStreamRole.values())).isLessThanOrEqualTo(12);
        assertThat(longest(InvestmentFrequency.values())).isLessThanOrEqualTo(12);
        assertThat(longest(PaycheckCadenceSource.values())).isLessThanOrEqualTo(10);
        assertThat(longest(PaycheckEventStatus.values())).isLessThanOrEqualTo(10);
        assertThat(longest(PaycheckCycleState.values())).isLessThanOrEqualTo(10);
        assertThat(longest(PaycheckSkipReason.values())).isLessThanOrEqualTo(32);
        assertThat(longest(PaycheckVoidedReason.values())).isLessThanOrEqualTo(32);
        assertThat(longest(PaycheckRejectReason.values())).isLessThanOrEqualTo(32);
        assertThat(longest(PaycheckRetiredReason.values())).isLessThanOrEqualTo(32);
        assertThat(longest(DetectionState.values())).isLessThanOrEqualTo(16);
    }

    @Test
    void enumValuesMatchTheSpec() {
        assertThat(names(PaycheckStreamState.values()))
                .containsExactly("CANDIDATE", "PROVISIONAL", "CONFIRMED", "DORMANT", "RETIRED", "REJECTED");
        assertThat(names(PaycheckStreamRole.values())).containsExactly("PRIMARY", "SECONDARY", "INTERRUPTION", "NONE");
        assertThat(names(PaycheckCadenceSource.values())).containsExactly("OBSERVED", "PLAID");
        assertThat(names(PaycheckEventStatus.values())).containsExactly("ACTIVE", "REMOVED", "MODIFIED", "SUPERSEDED");
        assertThat(names(PaycheckCycleState.values()))
                .containsExactly("OPEN", "CONFIRMED", "QUEUED", "SKIPPED", "VOIDED", "MISSED");
        assertThat(names(PaycheckSkipReason.values())).containsExactly("SHADOW_CONFIRMED", "AFFORDABILITY",
                "CATCH_UP_CAP", "PAUSED", "HOLD", "NO_AUTHORIZATION", "LATE_SEEN", "ACH_NOT_READY", "KILL_SWITCH",
                "USER_SKIP_THIRD", "STALE");
        assertThat(names(PaycheckVoidedReason.values()))
                .containsExactly("DEPOSIT_REMOVED", "DEPOSIT_PENDING", "AMOUNT_CHANGED", "ADMIN");
        assertThat(names(PaycheckRejectReason.values())).containsExactly("NOT_INFLOW", "NOT_DEPOSITORY",
                "PLAID_TOMBSTONED", "CADENCE_UNSUPPORTED", "PFC_EXCLUDED", "DESCRIPTOR_EXCLUDED", "LOW_MEDIAN", "STALE",
                "BENEFITS_SIGNAL");
        assertThat(names(PaycheckRetiredReason.values())).containsExactly("SUCCEEDED", "DORMANT_120", "ADMIN");
        assertThat(names(DetectionState.values())).containsExactly("SEARCHING", "NEEDS_CONSENT", "UNDETECTABLE",
                "PROVISIONAL", "CONFIRMED", "DORMANT", "ITEM_BROKEN", "REVOKED");
    }

    @Test
    void newCountersDefaultToZero() {
        PaycheckStream stream = new PaycheckStream();
        assertThat(stream.getScore()).isEqualTo((short) 0);
        assertThat(stream.getDepositsFound()).isEqualTo((short) 0);
        assertThat(stream.getMissedCount()).isEqualTo((short) 0);
        assertThat(new InvestmentSchedule().getMissedCount()).isEqualTo((short) 0);
    }

    @Test
    void userAutopilotOptOutDefaultsFalse() {
        for (User user : new User[] {new User("phase1@fredvested.com"), new User()}) {
            assertThat(user.getPaycheckAutopilotOptOut()).isFalse();
            assertThat(user.getPlaidHistoryComplete()).isFalse();
            assertThat(user.getPlaidSyncCursor()).isNull();
            assertThat(user.getPlaidTransactionsUpdateStatus()).isNull();
            assertThat(user.getPlaidDisconnectAt()).isNull();
            assertThat(user.getPlaidLastSyncAt()).isNull();
            assertThat(user.getPlaidStreamsReadAt()).isNull();
        }
    }

    @Test
    void scheduleMirrorStartsEmpty() {
        InvestmentSchedule schedule = new InvestmentSchedule();
        assertThat(schedule.getDetectionState()).isNull();
        assertThat(schedule.getPrimaryStreamId()).isNull();
        assertThat(schedule.getNextExpectedDate()).isNull();
        assertThat(schedule.getDormantSince()).isNull();
        assertThat(schedule.getLastCadenceCheckAt()).isNull();
        assertThat(schedule.getDetectionStartedAt()).isNull();
        assertThat(schedule.getMode()).isEqualTo(ScheduleMode.MANUAL);
        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.PENDING_BANK);
        assertThat(schedule.getHoldReason()).isNull();
    }

    @Test
    void newRowsStartInTheirFirstState() {
        PaycheckStream stream = new PaycheckStream();
        assertThat(stream.getState()).isEqualTo(PaycheckStreamState.CANDIDATE);
        assertThat(stream.getRole()).isEqualTo(PaycheckStreamRole.NONE);
        PaycheckEvent event = new PaycheckEvent();
        assertThat(event.getStatus()).isEqualTo(PaycheckEventStatus.ACTIVE);
        assertThat(event.isPending()).isFalse();
        assertThat(new PaycheckCycle().getState()).isEqualTo(PaycheckCycleState.OPEN);
    }

    @Test
    void timestampsFallBackToEasternNow() {
        LocalDateTime before = EasternTime.now();
        PaycheckStream stream = new PaycheckStream();
        stream.onCreate();
        assertThat(stream.getCreatedAt()).isBetween(before, EasternTime.now());
        assertThat(stream.getUpdatedAt()).isNull();

        LocalDateTime nightly = LocalDateTime.of(2026, 11, 2, 22, 30);
        PaycheckCycle cycle = new PaycheckCycle();
        cycle.setCreatedAt(nightly);
        cycle.onCreate();
        assertThat(cycle.getCreatedAt()).isEqualTo(nightly);
        cycle.onUpdate();
        assertThat(cycle.getUpdatedAt()).isBetween(before, EasternTime.now());
    }

    private static int longest(Enum<?>[] values) {
        return Arrays.stream(values).mapToInt(v -> v.name().length()).max().orElse(0);
    }

    private static String[] names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
}
