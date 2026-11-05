package com.investingapp.backend.model;

import org.hibernate.annotations.DynamicUpdate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ruling R20: the blocking set has one home, InvestmentSchedule.BLOCKING_HOLDS, and callers ask
 * isBlockingHold instead of restating it. Ruling R19: the entity writes only changed columns.
 * No Spring, no database: the real-database proof of R19 is ScheduleStaleWriteTest.
 */
class InvestmentScheduleBlockingHoldTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    /** Spelled out here on purpose: a fourth blocking reason must fail this test and be decided. */
    private static final EnumSet<HoldReason> BLOCKING = EnumSet.of(
            HoldReason.ACH_RETURN_UNAUTHORIZED, HoldReason.ACH_RETURN_ACCOUNT, HoldReason.MANUAL_REVIEW);

    @Test
    void isBlockingHoldIsTrueForExactlyTheThreeBlockingReasons() {
        EnumSet<HoldReason> reported = EnumSet.noneOf(HoldReason.class);
        for (HoldReason hold : HoldReason.values()) {
            if (InvestmentSchedule.isBlockingHold(hold)) {
                reported.add(hold);
            }
        }
        assertThat(reported).isEqualTo(BLOCKING);
        assertThat(EnumSet.complementOf(BLOCKING))
                .as("BANK_CHANGE, the Item-state holds and the DETECTED-mode holds are not blocking")
                .hasSize(7)
                .noneMatch(InvestmentSchedule::isBlockingHold);
    }

    @Test
    void isBlockingHoldIsFalseForNoHold() {
        assertThat(InvestmentSchedule.isBlockingHold(null)).isFalse();
    }

    /** The accessor and the runnable rule read the same set, so they can never disagree. */
    @Test
    void isBlockingHoldAgreesWithTheRunnableRuleForEveryHold() {
        assertThat(runnable().isReadyForInvestment(TODAY)).as("the fixture runs with no hold").isTrue();
        for (HoldReason hold : HoldReason.values()) {
            InvestmentSchedule held = runnable();
            held.setHoldReason(hold);
            assertThat(InvestmentSchedule.isBlockingHold(hold))
                    .as(hold.name())
                    .isEqualTo(!held.isReadyForInvestment(TODAY));
        }
    }

    @Test
    void scheduleUpdatesNameOnlyChangedColumns() {
        assertThat(InvestmentSchedule.class.isAnnotationPresent(DynamicUpdate.class))
                .as("R19: without @DynamicUpdate a stale copy writes every column back")
                .isTrue();
    }

    /** ACTIVE, ACH linked, no hold, next date today, start date two weeks back: due by date only. */
    private static InvestmentSchedule runnable() {
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setFrequency("BIWEEKLY");
        schedule.setInvestmentAmount(new BigDecimal("250.00"));
        schedule.setMonthlyAmount(new BigDecimal("542.50"));
        schedule.setStartDate(TODAY.minusDays(14));
        schedule.setNextInvestmentDate(TODAY);
        schedule.setStatus(InvestmentSchedule.Status.ACTIVE);
        schedule.setAchRequestId("ach-1");
        return schedule;
    }
}
