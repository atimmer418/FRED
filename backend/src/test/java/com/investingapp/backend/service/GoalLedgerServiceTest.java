package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.investingapp.backend.dto.UnitTally;
import com.investingapp.backend.model.Goal;
import com.investingapp.backend.model.GoalLedgerEntry;
import com.investingapp.backend.model.GoalLedgerEntry.LedgerKind;
import com.investingapp.backend.model.GoalType;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.goals.AccountState;
import com.investingapp.backend.model.goals.GoalConfigCodec;
import com.investingapp.backend.model.goals.KidsConfig;
import com.investingapp.backend.model.goals.TargetConfig;
import com.investingapp.backend.model.goals.VacationConfig;
import com.investingapp.backend.repository.GoalLedgerEntryRepository;
import com.investingapp.backend.repository.GoalRepository;
import com.investingapp.backend.service.goals.PaydayCalendar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoalLedgerServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    private final GoalConfigCodec codec = new GoalConfigCodec(new ObjectMapper().registerModule(new JavaTimeModule()));
    private final List<GoalLedgerEntry> ledger = new ArrayList<>();
    private GoalLedgerService service;

    @BeforeEach
    void setUp() {
        service = new GoalLedgerService(fakeLedger(ledger), savingGoals(), codec);
    }

    /** An in-memory ledger behind the four repository methods the service reads, plus save. */
    private static GoalLedgerEntryRepository fakeLedger(List<GoalLedgerEntry> rows) {
        GoalLedgerEntryRepository repo = mock(GoalLedgerEntryRepository.class);
        Comparator<GoalLedgerEntry> order = Comparator.comparing(GoalLedgerEntry::getEntryDate)
                .thenComparing(GoalLedgerEntry::getId);
        when(repo.save(any(GoalLedgerEntry.class))).thenAnswer(inv -> {
            GoalLedgerEntry e = inv.getArgument(0);
            e.setId((long) rows.size() + 1);
            rows.add(e);
            return e;
        });
        when(repo.findByGoalAndUnitAndEntryDateGreaterThanEqualOrderByEntryDateAscIdAsc(any(), any(), any()))
                .thenAnswer(inv -> rows.stream()
                        .filter(e -> e.getGoal() == inv.getArgument(0) && e.getUnit().equals(inv.getArgument(1))
                                && !e.getEntryDate().isBefore(inv.getArgument(2)))
                        .sorted(order).toList());
        when(repo.existsByGoalAndUnitAndEntryDateAndKind(any(), any(), any(), any()))
                .thenAnswer(inv -> rows.stream().anyMatch(e -> e.getGoal() == inv.getArgument(0)
                        && e.getUnit().equals(inv.getArgument(1)) && e.getEntryDate().equals(inv.getArgument(2))
                        && e.getKind() == inv.getArgument(3)));
        when(repo.findTopByGoalAndUnitAndKindOrderByEntryDateDescIdDesc(any(), any(), any()))
                .thenAnswer(inv -> rows.stream()
                        .filter(e -> e.getGoal() == inv.getArgument(0) && e.getUnit().equals(inv.getArgument(1))
                                && e.getKind() == inv.getArgument(2))
                        .max(order));
        return repo;
    }

    private static GoalRepository savingGoals() {
        GoalRepository repo = mock(GoalRepository.class);
        when(repo.save(any(Goal.class))).thenAnswer(inv -> inv.getArgument(0));
        return repo;
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static Optional<InvestmentSchedule> schedule(String frequency, LocalDate start) {
        InvestmentSchedule s = new InvestmentSchedule();
        s.setFrequency(frequency);
        s.setStartDate(start);
        return Optional.of(s);
    }

    private static Optional<InvestmentSchedule> semiMonthly() {
        return schedule("SEMI_MONTHLY", LocalDate.of(2026, 1, 1));
    }

    private Goal simpleGoal(String balance, String monthly, LocalDate creditedThrough) {
        Goal g = new Goal();
        g.setId(1L);
        g.setType(GoalType.CUSTOM);
        g.setName("House");
        g.setIcon("flag");
        g.setConfigJson(codec.write(new TargetConfig(bd("100000"), 2030)));
        g.setCurrentBalance(bd(balance));
        g.setAnchorBalance(bd(balance));
        g.setAnchorMonthly(bd(monthly));
        g.setPlanRate(bd("0.0700"));
        g.setPlanRateKind("market");
        g.setPlanAnchoredAt(LocalDateTime.of(2026, 10, 1, 10, 0));
        g.setCreditedThrough(creditedThrough);
        return g;
    }

    private List<GoalLedgerEntry> entries(String unit, LedgerKind kind) {
        return ledger.stream().filter(e -> e.getUnit().equals(unit) && e.getKind() == kind).toList();
    }

    private GoalLedgerEntry entry(Goal goal, String unit, LocalDate date, LedgerKind kind, String amount) {
        GoalLedgerEntry e = new GoalLedgerEntry();
        e.setGoal(goal);
        e.setUnit(unit);
        e.setEntryDate(date);
        e.setKind(kind);
        e.setAmount(bd(amount));
        e.setId((long) ledger.size() + 1);
        ledger.add(e);
        return e;
    }

    @Test
    void firstReadBackfillsCreditedThroughWithoutCredits() {
        Goal goal = simpleGoal("1000", "900", null);

        assertTrue(service.rollForward(goal, semiMonthly(), TODAY));

        assertEquals(TODAY, goal.getCreditedThrough());
        assertTrue(ledger.isEmpty());
        assertMoney("1000", goal.getCurrentBalance());
    }

    @Test
    void creditsOnePaycheckSharePerPaydaySinceCreditedThrough() {
        Goal goal = simpleGoal("1000", "900", LocalDate.of(2026, 10, 31));

        assertTrue(service.rollForward(goal, semiMonthly(), TODAY));

        // Nov 1 and Nov 15 2026 are Sundays, so the paydays are Mon Nov 2 and Mon Nov 16.
        List<GoalLedgerEntry> credits = entries("goal", LedgerKind.CREDIT);
        assertEquals(List.of(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 16)),
                credits.stream().map(GoalLedgerEntry::getEntryDate).toList());
        credits.forEach(c -> assertMoney("450.00", c.getAmount()));
        assertMoney("1900", goal.getCurrentBalance());
        assertEquals(TODAY, goal.getCreditedThrough());
        assertNotNull(goal.getBalanceUpdatedAt());
    }

    @Test
    void creditIsIdempotentPerUnitAndDay() {
        Goal goal = simpleGoal("1000", "900", LocalDate.of(2026, 10, 31));
        service.rollForward(goal, semiMonthly(), TODAY);

        assertFalse(service.rollForward(goal, semiMonthly(), TODAY));
        assertEquals(2, ledger.size());

        // A second device that read the old creditedThrough finds the credits already written.
        goal.setCreditedThrough(LocalDate.of(2026, 10, 31));
        service.rollForward(goal, semiMonthly(), TODAY);
        assertEquals(2, ledger.size());
        assertMoney("1900", goal.getCurrentBalance());
    }

    @Test
    void creditsStopAfterCreditsUntil() {
        Goal goal = simpleGoal("1000", "900", LocalDate.of(2026, 10, 31));
        goal.setCreditsUntil(LocalDate.of(2026, 11, 10));

        service.rollForward(goal, semiMonthly(), TODAY);

        List<GoalLedgerEntry> credits = entries("goal", LedgerKind.CREDIT);
        assertEquals(1, credits.size());
        assertEquals(LocalDate.of(2026, 11, 2), credits.get(0).getEntryDate());
        assertMoney("1450", goal.getCurrentBalance());
        assertEquals(TODAY, goal.getCreditedThrough());
    }

    @Test
    void lastCreditIsCappedAtTheTarget() {
        Goal goal = simpleGoal("99700", "900", LocalDate.of(2026, 10, 31));
        goal.setTarget(bd("100000"));

        service.rollForward(goal, semiMonthly(), TODAY);

        List<GoalLedgerEntry> credits = entries("goal", LedgerKind.CREDIT);
        assertEquals(1, credits.size());
        assertMoney("300", credits.get(0).getAmount());
        assertMoney("100000", goal.getCurrentBalance());
    }

    @Test
    void noScheduleCreditsMonthlyOnTheAnchorDay() {
        Goal goal = simpleGoal("0", "900", LocalDate.of(2026, 9, 30));
        goal.setPlanAnchoredAt(LocalDateTime.of(2026, 9, 10, 8, 0));

        service.rollForward(goal, Optional.empty(), TODAY);

        List<LocalDate> expected = PaydayCalendar.paydays(PaydayCalendar.monthlyOn(LocalDate.of(2026, 9, 10)),
                LocalDate.of(2026, 9, 30), TODAY);
        List<GoalLedgerEntry> credits = entries("goal", LedgerKind.CREDIT);
        assertEquals(2, expected.size());
        assertEquals(expected, credits.stream().map(GoalLedgerEntry::getEntryDate).toList());
        assertEquals(10, credits.get(1).getEntryDate().getDayOfMonth());
        credits.forEach(c -> assertMoney("900.00", c.getAmount()));
        assertMoney("1800", goal.getCurrentBalance());
    }

    @Test
    void frequencyChangeAppliesFromCreditedThroughOnward() {
        Goal goal = simpleGoal("0", "900", LocalDate.of(2026, 10, 31));
        service.rollForward(goal, semiMonthly(), TODAY);

        LocalDate later = LocalDate.of(2026, 12, 4);
        service.rollForward(goal, schedule("WEEKLY", LocalDate.of(2026, 11, 6)), later);

        List<GoalLedgerEntry> credits = entries("goal", LedgerKind.CREDIT);
        // The two semi-monthly credits stay as written; the weekly ones follow Fridays Nov 27 and Dec 4.
        assertEquals(List.of(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 16),
                        LocalDate.of(2026, 11, 27), LocalDate.of(2026, 12, 4)),
                credits.stream().map(GoalLedgerEntry::getEntryDate).toList());
        assertMoney("450.00", credits.get(0).getAmount());
        assertMoney("450.00", credits.get(1).getAmount());
        assertMoney("207.85", credits.get(2).getAmount());
        assertMoney("207.85", credits.get(3).getAmount());
        assertEquals(later, goal.getCreditedThrough());
    }

    @Test
    void rollForwardResetsVacationOnJanuaryFirstBeforeCrediting() {
        Goal goal = simpleGoal("0", "500", LocalDate.of(2026, 12, 20));
        goal.setType(GoalType.VACATION);
        goal.setIcon("flight");
        goal.setConfigJson(codec.write(new VacationConfig(2, bd("3000"), bd("2000"), 2026)));
        goal.setTarget(bd("6000"));
        LocalDate today = LocalDate.of(2027, 1, 5);

        service.rollForward(goal, semiMonthly(), today);

        List<LocalDate> paydays = PaydayCalendar.paydays(PaydayCalendar.fromSchedule(semiMonthly().get()),
                LocalDate.of(2026, 12, 20), today);
        assertEquals(1, paydays.size());
        assertEquals(2027, paydays.get(0).getYear());
        assertEquals(2, ledger.size());
        GoalLedgerEntry reset = ledger.get(0);
        assertEquals(LedgerKind.SET, reset.getKind());
        assertEquals(LocalDate.of(2027, 1, 1), reset.getEntryDate());
        assertMoney("0", reset.getAmount());
        GoalLedgerEntry credit = ledger.get(1);
        assertEquals(LedgerKind.CREDIT, credit.getKind());
        assertEquals(paydays.get(0), credit.getEntryDate());
        assertMoney("250.00", credit.getAmount());

        VacationConfig config = (VacationConfig) codec.parse(GoalType.VACATION, goal.getConfigJson());
        assertMoney("250", config.savedThisYear());
        assertEquals(2027, config.savedYear());
        assertEquals(today, goal.getCreditedThrough());
    }

    @Test
    void kidsRollEachAccountSeparately() {
        LocalDate from = LocalDate.of(2026, 10, 31);
        AccountState brokerage = new AccountState(bd("1000"), null, bd("1000"), bd("100"), from, null, null);
        AccountState plan529 = new AccountState(bd("2000"), null, bd("2000"), bd("200"), from, null, null);
        AccountState firstYear = new AccountState(bd("0"), null, bd("0"), bd("300"), from,
                LocalDate.of(2026, 11, 10), null);
        KidsConfig config = new KidsConfig(bd("50000"), bd("80000"), List.of(new KidsConfig.Kid("Ava", 2027, 3,
                new KidsConfig.Accounts(brokerage, plan529, firstYear), bd("5000"), 2028, 3)), true, false);
        Goal goal = simpleGoal("3000", "600", null);
        goal.setType(GoalType.KIDS);
        goal.setIcon("child_care");
        goal.setConfigJson(codec.write(config));

        service.rollForward(goal, semiMonthly(), TODAY);

        assertEquals(2, entries("kid:0:brokerage", LedgerKind.CREDIT).size());
        entries("kid:0:brokerage", LedgerKind.CREDIT).forEach(c -> assertMoney("50.00", c.getAmount()));
        assertEquals(2, entries("kid:0:plan529", LedgerKind.CREDIT).size());
        entries("kid:0:plan529", LedgerKind.CREDIT).forEach(c -> assertMoney("100.00", c.getAmount()));
        List<GoalLedgerEntry> fund = entries("kid:0:firstYear", LedgerKind.CREDIT);
        assertEquals(1, fund.size());
        assertMoney("150.00", fund.get(0).getAmount());

        KidsConfig.Accounts after = ((KidsConfig) codec.parse(GoalType.KIDS, goal.getConfigJson())).kids().get(0).accounts();
        assertMoney("1100", after.brokerage().balance());
        assertMoney("2200", after.plan529().balance());
        assertMoney("150", after.firstYear().balance());
        assertEquals(TODAY, after.brokerage().creditedThrough());
        assertEquals(TODAY, after.firstYear().creditedThrough());
        assertNotNull(after.plan529().balanceUpdatedAt());
        assertMoney("1000", after.brokerage().anchorBalance());
        // Kids keep their tallies per account; the goal-level fields are not used.
        assertNull(goal.getCreditedThrough());
    }

    @Test
    void netAndNetSinceComeFromAdjustmentsSinceLastRebase() {
        Goal goal = simpleGoal("1000", "900", TODAY);
        goal.setPlanAnchoredAt(LocalDateTime.of(2026, 11, 1, 9, 0));
        LocalDate today = LocalDate.of(2026, 12, 5);
        goal.setCreditedThrough(today);
        entry(goal, "goal", LocalDate.of(2026, 10, 20), LedgerKind.MISSED, "-450");
        entry(goal, "goal", LocalDate.of(2026, 11, 5), LedgerKind.MISSED, "-450");
        entry(goal, "goal", LocalDate.of(2026, 11, 10), LedgerKind.SET, "1000");
        entry(goal, "goal", LocalDate.of(2026, 11, 15), LedgerKind.MISSED, "-450");
        entry(goal, "goal", LocalDate.of(2026, 11, 20), LedgerKind.EXTRA, "450");
        entry(goal, "goal", LocalDate.of(2026, 12, 1), LedgerKind.MISSED, "-450");

        UnitTally tally = service.tallyFor(goal, "goal", semiMonthly(), today);

        assertMoney("-450", tally.net());
        assertEquals(LocalDate.of(2026, 12, 1), tally.netSince());
        assertFalse(tally.startedCrediting());
    }

    @Test
    void netIsPositiveWithNoNetSinceWhenAheadAndZeroAfterASet() {
        Goal goal = simpleGoal("1000", "900", TODAY);
        entry(goal, "goal", LocalDate.of(2026, 11, 2), LedgerKind.EXTRA, "450");
        entry(goal, "goal", LocalDate.of(2026, 11, 3), LedgerKind.MISSED, "-450");
        entry(goal, "goal", LocalDate.of(2026, 11, 4), LedgerKind.EXTRA, "450");

        UnitTally ahead = service.tallyFor(goal, "goal", semiMonthly(), TODAY);
        assertMoney("450", ahead.net());
        assertNull(ahead.netSince());

        entry(goal, "goal", LocalDate.of(2026, 11, 4), LedgerKind.SET, "1500");
        UnitTally rebased = service.tallyFor(goal, "goal", semiMonthly(), TODAY);
        assertMoney("0", rebased.net());
        assertNull(rebased.netSince());
    }

    @Test
    void completedGoalsDoNotCredit() {
        Goal goal = simpleGoal("1000", "900", LocalDate.of(2026, 10, 31));
        goal.setCompleted(true);

        service.rollForward(goal, semiMonthly(), TODAY);

        assertTrue(ledger.isEmpty());
        assertMoney("1000", goal.getCurrentBalance());
        UnitTally tally = service.tallyFor(goal, "goal", semiMonthly(), TODAY);
        assertNull(tally.nextCreditDate());
        assertMoney("0", tally.nextCreditAmount());
    }

    @Test
    void firstCreditIsStrictlyAfterThePlanAnchor() {
        // Anchored on payday Mon Nov 2: that day does not credit, Nov 16 does.
        Goal goal = simpleGoal("0", "900", LocalDate.of(2026, 10, 15));
        goal.setPlanAnchoredAt(LocalDateTime.of(2026, 11, 2, 12, 0));

        service.rollForward(goal, semiMonthly(), TODAY);

        assertEquals(List.of(LocalDate.of(2026, 11, 16)),
                entries("goal", LedgerKind.CREDIT).stream().map(GoalLedgerEntry::getEntryDate).toList());
    }

    @Test
    void tallyForReportsTheNextCreditCappedAtTheTarget() {
        Goal goal = simpleGoal("1000", "900", LocalDate.of(2026, 10, 31));
        goal.setTarget(bd("2000"));
        goal.setCreditsUntil(LocalDate.of(2027, 12, 31));
        service.rollForward(goal, semiMonthly(), TODAY);

        UnitTally tally = service.tallyFor(goal, "goal", semiMonthly(), TODAY);

        assertMoney("1900", tally.tally());
        assertEquals(TODAY, tally.creditedThrough());
        assertEquals(LocalDate.of(2027, 12, 31), tally.creditsUntil());
        // Dec 1 2026 is a Tuesday. Only $100 is left to the target.
        assertEquals(LocalDate.of(2026, 12, 1), tally.nextCreditDate());
        assertMoney("100", tally.nextCreditAmount());
        assertMoney("0", tally.net());
        assertTrue(tally.startedCrediting());
    }

    @Test
    void finishedUnitHasNoNextCredit() {
        Goal atTarget = simpleGoal("2000", "900", TODAY);
        atTarget.setTarget(bd("2000"));
        assertNull(service.tallyFor(atTarget, "goal", semiMonthly(), TODAY).nextCreditDate());

        Goal pastEnd = simpleGoal("100", "900", TODAY);
        pastEnd.setCreditsUntil(LocalDate.of(2026, 11, 30));
        UnitTally ended = service.tallyFor(pastEnd, "goal", semiMonthly(), TODAY);
        assertNull(ended.nextCreditDate());
        assertMoney("0", ended.nextCreditAmount());
    }

    @Test
    void talliesForKidsAreKeyedByUnit() {
        AccountState account = new AccountState(bd("10"), null, bd("10"), bd("100"), TODAY, null, null);
        KidsConfig config = new KidsConfig(bd("50000"), bd("80000"), List.of(new KidsConfig.Kid("Ava", 2020, 3,
                new KidsConfig.Accounts(account, account, null), null, null, null)), false, false);
        Goal goal = simpleGoal("20", "200", null);
        goal.setType(GoalType.KIDS);
        goal.setConfigJson(codec.write(config));

        var tallies = service.talliesFor(goal, semiMonthly(), TODAY);

        assertEquals(List.of("kid:0:brokerage", "kid:0:plan529"), List.copyOf(tallies.keySet()));
        assertMoney("50.00", tallies.get("kid:0:plan529").nextCreditAmount());
    }
}
