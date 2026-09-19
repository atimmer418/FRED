package com.investingapp.backend.service;

import com.investingapp.backend.model.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plain JUnit 5 (no Spring context) — unified freedom-target resolution and
 * freedom-year math.
 *
 * PARITY: the parityFixtureFreedomYear values are mirrored in
 * frontend/src/app/services/freedom-stats.service.spec.ts ("cross-stack parity
 * fixture"). If you change one, change both.
 */
class MonthlyFreedomUpdateServiceTest {

    private final MonthlyFreedomUpdateService service = new MonthlyFreedomUpdateService();

    @Test
    void targetIsLiveIncomeBasedAndIgnoresStoredTargetPortfolio() {
        User user = new User();
        user.setRetirementIncome(60000.0);
        user.setTargetPortfolio(150000.0); // stale signup snapshot — must be ignored
        assertEquals(1_500_000.0, service.resolveTargetPortfolio(user), 0.001);
    }

    @Test
    void targetDefaultsWhenIncomeAbsent() {
        User user = new User();
        user.setTargetPortfolio(150000.0);
        assertEquals(1_500_000.0, service.resolveTargetPortfolio(user), 0.001);
    }

    @Test
    void targetDefaultsWhenIncomeZero() {
        User user = new User();
        user.setRetirementIncome(0.0);
        assertEquals(1_500_000.0, service.resolveTargetPortfolio(user), 0.001);
    }

    @Test
    void parityFixtureFreedomYear() {
        // equity $10,000 · $500/month · target $1,500,000
        // → months-to-target ≈ 374.02 → ceil(374.02 / 12) = 32 years
        int year = service.calculateFreedomYear(
                new BigDecimal("10000"), new BigDecimal("500"), new BigDecimal("1500000"));
        assertEquals(LocalDate.now().getYear() + 32, year);
    }

    @Test
    void targetDefaultsWhenIncomeNegative() {
        User user = new User();
        user.setRetirementIncome(-5000.0);
        assertEquals(1_500_000.0, service.resolveTargetPortfolio(user), 0.001);
    }

    @Test
    void contributionPrefersSavedGoalOverSchedule() {
        // Flipped 2026-08-01 (was schedule-else-survey): the saved goal drives
        // the freedom date so my-profile saves always propagate.
        // PARITY: mirrored in freedom-stats.service.spec.ts "prefers the saved
        // Investing goal over the actual schedule". Change one → change both.
        User user = new User();
        user.setMonthlyInvestment(999.0); // saved goal wins
        BigDecimal result = service.resolveMonthlyContribution(new BigDecimal("433.00"), user);
        assertEquals(0, new BigDecimal("999.0").compareTo(result));
    }

    @Test
    void contributionUsesGoalWhenNoSchedule() {
        User user = new User();
        user.setMonthlyInvestment(500.0);
        BigDecimal result = service.resolveMonthlyContribution(BigDecimal.ZERO, user);
        assertEquals(0, new BigDecimal("500").compareTo(result));
    }

    @Test
    void contributionFallsBackToScheduleWhenNoGoal() {
        User user = new User();
        user.setMonthlyInvestment(null);
        BigDecimal result = service.resolveMonthlyContribution(new BigDecimal("433.00"), user);
        assertEquals(0, new BigDecimal("433.00").compareTo(result));
    }

    @Test
    void contributionZeroWhenNoScheduleAndNoSurvey() {
        User user = new User();
        assertEquals(0, BigDecimal.ZERO.compareTo(service.resolveMonthlyContribution(BigDecimal.ZERO, user)));
        User userNull = new User();
        userNull.setMonthlyInvestment(null);
        assertEquals(0, BigDecimal.ZERO.compareTo(service.resolveMonthlyContribution(null, userNull)));
    }

    // --- Dynamic "invest more" boost (percentage of the member's own recurring amount) -------------
    // Cycle 0 (mfuCount % 3 == 0) suggests +20%, cycle 1 suggests +10%, both rounded to the nearest
    // $10 and floored at the old flat amount ($50 cycle 0, $25 cycle 1) so small/zero contributors
    // still get a meaningful nudge.

    @Test
    void boostIsTwentyPercentOnCycleZero() {
        assertEquals(0, new BigDecimal("100").compareTo(service.calculateBoostAmount(new BigDecimal("500"), 0)));
    }

    @Test
    void boostIsTenPercentOnCycleOne() {
        // 10% of $500 = $50 (>= the $25 cycle-1 floor)
        assertEquals(0, new BigDecimal("50").compareTo(service.calculateBoostAmount(new BigDecimal("500"), 1)));
    }

    @Test
    void boostRoundsToNearestTenDollars() {
        // 20% of $317 = $63.40 → nearest $10 = $60
        assertEquals(0, new BigDecimal("60").compareTo(service.calculateBoostAmount(new BigDecimal("317"), 0)));
        // 20% of $325 = $65.00 → HALF_UP to nearest $10 = $70
        assertEquals(0, new BigDecimal("70").compareTo(service.calculateBoostAmount(new BigDecimal("325"), 0)));
    }

    @Test
    void boostFloorsAtFlatAmountForSmallContributors() {
        // 20% of $60 = $12 → rounds to $10 → floored up to the $50 cycle-0 minimum
        assertEquals(0, new BigDecimal("50").compareTo(service.calculateBoostAmount(new BigDecimal("60"), 0)));
        // 10% of $60 = $6 → rounds to $10 → floored up to the $25 cycle-1 minimum
        assertEquals(0, new BigDecimal("25").compareTo(service.calculateBoostAmount(new BigDecimal("60"), 1)));
    }

    @Test
    void boostFloorsAtFlatAmountForZeroOrNullContribution() {
        assertEquals(0, new BigDecimal("50").compareTo(service.calculateBoostAmount(BigDecimal.ZERO, 0)));
        assertEquals(0, new BigDecimal("25").compareTo(service.calculateBoostAmount(BigDecimal.ZERO, 1)));
        assertEquals(0, new BigDecimal("50").compareTo(service.calculateBoostAmount(null, 3))); // mfuCount 3 → cycle 0
    }

    @Test
    void boostCycleDerivesFromMfuCountModuloThree() {
        // mfuCount 3 → cycle 0 (+20%), mfuCount 4 → cycle 1 (+10%)
        assertEquals(0, new BigDecimal("100").compareTo(service.calculateBoostAmount(new BigDecimal("500"), 3)));
        assertEquals(0, new BigDecimal("50").compareTo(service.calculateBoostAmount(new BigDecimal("500"), 4)));
    }

    @Test
    void monthlyEquivalentFactorsMatchFrontendUtil() {
        // PARITY: mirrored in frontend/src/app/utils/investment-frequency.utils.spec.ts.
        // If you change one, change both. (WEEKLY x4.33, BIWEEKLY x2.17, SEMI_MONTHLY x2)
        assertEquals(new BigDecimal("433.00"), service.calculateMonthlyEquivalent(new BigDecimal("100"), "WEEKLY"));
        assertEquals(new BigDecimal("217.00"), service.calculateMonthlyEquivalent(new BigDecimal("100"), "BIWEEKLY"));
        assertEquals(new BigDecimal("499.99"), service.calculateMonthlyEquivalent(new BigDecimal("115.47"), "WEEKLY"));
        assertEquals(new BigDecimal("500.00"), service.calculateMonthlyEquivalent(new BigDecimal("250"), "SEMI_MONTHLY"));
        assertEquals(new BigDecimal("500.00"), service.calculateMonthlyEquivalent(new BigDecimal("250"), "SEMIMONTHLY"));
        assertEquals(0, new BigDecimal("500").compareTo(service.calculateMonthlyEquivalent(new BigDecimal("500"), "MONTHLY")));
    }
}
