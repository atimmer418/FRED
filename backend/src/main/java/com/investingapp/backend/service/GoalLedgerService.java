package com.investingapp.backend.service;

import com.investingapp.backend.dto.UnitTally;
import com.investingapp.backend.model.Goal;
import com.investingapp.backend.model.GoalLedgerEntry;
import com.investingapp.backend.model.GoalLedgerEntry.LedgerKind;
import com.investingapp.backend.model.GoalType;
import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.goals.AccountState;
import com.investingapp.backend.model.goals.GoalConfigCodec;
import com.investingapp.backend.model.goals.KidsConfig;
import com.investingapp.backend.model.goals.VacationConfig;
import com.investingapp.backend.repository.GoalLedgerEntryRepository;
import com.investingapp.backend.repository.GoalRepository;
import com.investingapp.backend.service.goals.PaydayCalendar;
import com.investingapp.backend.service.goals.PaydayCalendar.PayRule;
import com.investingapp.backend.util.EasternTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps each goal's "Saved so far" tally. On every read the goal is rolled forward: each payday since
 * the unit's {@code creditedThrough} credits one paycheck's share of the anchored monthly. The ledger
 * is the history; the stored figure (the goal balance, the vacation's savedThisYear or a kid account's
 * balance) is the running total, and both change in the same transaction.
 *
 * <p>Pure with respect to the clock: callers pass Eastern {@code today}.
 */
@Service
@Transactional
public class GoalLedgerService {

    /** The unit key of a simple or vacation goal. */
    public static final String GOAL_UNIT = "goal";
    private static final String BROKERAGE = "brokerage";
    private static final String PLAN_529 = "plan529";
    private static final String FIRST_YEAR = "firstYear";
    /** How far ahead to look for the next payday; covers a monthly cadence plus holiday pushes. */
    private static final int LOOK_AHEAD_DAYS = 45;
    /** The largest Missed one or Doubled up amount a client may send, the same cap as a set. */
    private static final BigDecimal MAX_CORRECTION = new BigDecimal("100000000");

    private final GoalLedgerEntryRepository entries;
    private final GoalRepository goals;
    private final GoalConfigCodec codec;

    public GoalLedgerService(GoalLedgerEntryRepository entries, GoalRepository goals, GoalConfigCodec codec) {
        this.entries = entries;
        this.goals = goals;
        this.codec = codec;
    }

    /** The unit key of one kid account: {@code kid:<index>:<plan529|brokerage|firstYear>}. */
    public static String kidUnit(int kidIndex, String account) {
        return "kid:" + kidIndex + ":" + account;
    }

    /**
     * Credits every payday after each unit's {@code creditedThrough} up to and including today, then
     * advances it to today. A unit never rolled before is only stamped with today. Returns whether
     * anything changed; when it did, the entries and the goal are saved.
     */
    public boolean rollForward(Goal goal, Optional<InvestmentSchedule> schedule, LocalDate today) {
        Units units = Units.of(goal, codec);
        PayRule rule = ruleFor(goal, schedule);
        LocalDateTime now = EasternTime.now();
        boolean changed = false;
        for (Unit unit : units.all) {
            changed |= roll(goal, unit, rule, today, now);
        }
        if (changed) {
            units.writeTo(goal, codec);
            goals.save(goal);
        }
        return changed;
    }

    /**
     * The read path's roll-forward: reloads the goal and rolls it in a transaction of its own, so a
     * credit another read already wrote for the same day (the unique credit key) fails only this
     * transaction. The caller catches DataIntegrityViolationException and re-reads the goal. Must be
     * called through the Spring proxy. Returns false when the goal no longer exists.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean rollForwardAlone(Long goalId, Optional<InvestmentSchedule> schedule, LocalDate today) {
        return goals.findById(goalId).map(goal -> rollForward(goal, schedule, today)).orElse(false);
    }

    /**
     * Starts a new goal's ledger: one SET entry per unit dated today with the starting tally, and
     * {@code creditedThrough} = today. The goal must already be saved.
     */
    public void seed(Goal goal, LocalDate today) {
        Units units = Units.of(goal, codec);
        for (Unit unit : units.all) {
            write(goal, unit.key, today, LedgerKind.SET, unit.tally);
            unit.creditedThrough = today;
        }
        units.writeTo(goal, codec);
        goals.save(goal);
    }

    /** One unit's figures for the response. Throws IllegalArgumentException for a unit the goal does not have. */
    public UnitTally tallyFor(Goal goal, String unit, Optional<InvestmentSchedule> schedule, LocalDate today) {
        Unit found = Units.of(goal, codec).all.stream()
                .filter(u -> u.key.equals(unit))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown goal unit: " + unit));
        return tally(goal, found, ruleFor(goal, schedule), today);
    }

    /** Every unit's figures, keyed by unit, in config order. */
    public Map<String, UnitTally> talliesFor(Goal goal, Optional<InvestmentSchedule> schedule, LocalDate today) {
        PayRule rule = ruleFor(goal, schedule);
        Map<String, UnitTally> out = new LinkedHashMap<>();
        for (Unit unit : Units.of(goal, codec).all) {
            out.put(unit.key, tally(goal, unit, rule, today));
        }
        return out;
    }

    /** The goal after an adjustment, and whether a Missed one was cut short at $0 (or not written at all). */
    public record AdjustResult(Goal goal, boolean capped) {
    }

    /**
     * Applies one correction to a unit, dated today: MISSED takes one paycheck share off the tally (never
     * below $0), EXTRA puts one share on, SET makes the tally an exact figure ({@code amount}, required).
     * MISSED and EXTRA move {@code amount} instead of the share when it is sent (an Undo puts back
     * exactly what moved); MISSED is still cut short at $0. The goal should already be rolled forward
     * so the share and the finished check are current. Throws IllegalArgumentException for a unit the
     * goal does not have or a kind other than the three, GoalValidationException for a SET without an
     * amount or a MISSED/EXTRA amount that is not above $0 (or over the cap, or past 2 decimals), and
     * IllegalStateException for a MISSED or EXTRA on a finished unit (target reached, credits window
     * over, or the goal completed). SET stays allowed there, so the tally can always be corrected.
     * Saves the goal.
     */
    public AdjustResult adjust(Goal goal, String unitKey, LedgerKind kind, BigDecimal amount,
                               Optional<InvestmentSchedule> schedule, LocalDate today) {
        if (kind == null || kind == LedgerKind.CREDIT) {
            throw new IllegalArgumentException("Unsupported adjustment: " + kind);
        }
        Units units = Units.of(goal, codec);
        Unit unit = units.all.stream()
                .filter(u -> u.key.equals(unitKey))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown goal unit: " + unitKey));
        if (kind != LedgerKind.SET && isFinished(goal, unit, today)) {
            throw new IllegalStateException("This goal's saving is finished.");
        }
        BigDecimal entryAmount;
        boolean capped = false;
        if (kind == LedgerKind.SET) {
            if (amount == null) {
                throw new GoalValidationException("amount is required to set the tally.");
            }
            entryAmount = amount.setScale(2, RoundingMode.HALF_UP);
        } else {
            BigDecimal share = amount == null ? share(unit, ruleFor(goal, schedule)) : correctionAmount(amount);
            if (kind == LedgerKind.MISSED) {
                BigDecimal taken = share.min(unit.tally);
                capped = taken.compareTo(share) < 0;
                entryAmount = taken.negate();
            } else {
                entryAmount = share;
            }
        }
        if (kind != LedgerKind.SET && entryAmount.signum() == 0) {
            // Nothing to take off, or no plan share to double: no entry and no change.
            return new AdjustResult(goal, capped);
        }
        write(goal, unit.key, today, kind, entryAmount);
        unit.tally = kind == LedgerKind.SET ? entryAmount : unit.tally.add(entryAmount);
        unit.balanceUpdatedAt = EasternTime.now();
        units.writeTo(goal, codec);
        goals.save(goal);
        return new AdjustResult(goal, capped);
    }

    /** A MISSED or EXTRA amount the client sent: above $0, at most $100,000,000, at most 2 decimals. */
    private static BigDecimal correctionAmount(BigDecimal amount) {
        if (amount.signum() <= 0 || amount.compareTo(MAX_CORRECTION) > 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new GoalValidationException("Enter an amount above $0 and up to $100,000,000 with at most 2 decimals.");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean isFinished(Goal goal, Unit unit, LocalDate today) {
        return goal.isCompleted()
                || (unit.target != null && unit.tally.compareTo(unit.target) >= 0)
                || (unit.creditsUntil != null && today.isAfter(unit.creditsUntil));
    }

    // ---- the walk ----

    private boolean roll(Goal goal, Unit unit, PayRule rule, LocalDate today, LocalDateTime now) {
        if (unit.creditedThrough == null) {
            // A unit from before the tally existed starts from today, with no back credits.
            unit.creditedThrough = today;
            resetVacationYear(goal, unit, today.getYear(), now);
            return true;
        }
        if (!today.isAfter(unit.creditedThrough)) {
            return false;
        }
        // A plan anchored on a payday does not credit that same day.
        LocalDate after = later(unit.creditedThrough, anchorDate(goal));
        BigDecimal share = share(unit, rule);
        if (!goal.isCompleted()) {
            for (LocalDate payday : PaydayCalendar.paydays(rule, after, today)) {
                resetVacationYear(goal, unit, payday.getYear(), now);
                if (unit.creditsUntil != null && payday.isAfter(unit.creditsUntil)) {
                    break;
                }
                BigDecimal amount = creditAmount(unit, unit.tally, share);
                if (amount.signum() <= 0
                        || entries.existsByGoalAndUnitAndEntryDateAndKind(goal, unit.key, payday, LedgerKind.CREDIT)) {
                    continue;
                }
                write(goal, unit.key, payday, LedgerKind.CREDIT, amount);
                unit.tally = unit.tally.add(amount);
                unit.balanceUpdatedAt = now;
            }
        }
        resetVacationYear(goal, unit, today.getYear(), now);
        unit.creditedThrough = today;
        return true;
    }

    /** Vacation saves per calendar year: entering a new year writes SET 0 dated January 1. */
    private void resetVacationYear(Goal goal, Unit unit, int year, LocalDateTime now) {
        if (unit.savedYear == null || unit.savedYear >= year) {
            return;
        }
        write(goal, unit.key, LocalDate.of(year, 1, 1), LedgerKind.SET, BigDecimal.ZERO);
        unit.tally = BigDecimal.ZERO;
        unit.savedYear = year;
        unit.balanceUpdatedAt = now;
    }

    /** The share, capped so the tally lands on the target exactly; zero or less means nothing to credit. */
    private static BigDecimal creditAmount(Unit unit, BigDecimal tally, BigDecimal share) {
        if (unit.target == null) {
            return share;
        }
        return share.min(unit.target.subtract(tally));
    }

    private void write(Goal goal, String unit, LocalDate date, LedgerKind kind, BigDecimal amount) {
        GoalLedgerEntry entry = new GoalLedgerEntry();
        entry.setGoal(goal);
        entry.setUnit(unit);
        entry.setEntryDate(date);
        entry.setKind(kind);
        entry.setAmount(amount);
        entries.save(entry);
    }

    // ---- the read ----

    private UnitTally tally(Goal goal, Unit unit, PayRule rule, LocalDate today) {
        LocalDate nextDate = null;
        BigDecimal nextAmount = BigDecimal.ZERO;
        BigDecimal share = share(unit, rule);
        if (!goal.isCompleted() && share.signum() > 0) {
            LocalDate from = unit.creditedThrough == null ? today : later(today, unit.creditedThrough);
            List<LocalDate> ahead = PaydayCalendar.paydays(rule, from, from.plusDays(LOOK_AHEAD_DAYS));
            if (!ahead.isEmpty()) {
                LocalDate payday = ahead.get(0);
                boolean newYear = unit.savedYear != null && payday.getYear() > unit.savedYear;
                BigDecimal amount = creditAmount(unit, newYear ? BigDecimal.ZERO : unit.tally, share);
                boolean pastEnd = unit.creditsUntil != null && payday.isAfter(unit.creditsUntil);
                if (!pastEnd && amount.signum() > 0) {
                    nextDate = payday;
                    nextAmount = amount;
                }
            }
        }
        Net net = net(goal, unit);
        boolean started = entries.findTopByGoalAndUnitAndKindOrderByEntryDateDescIdDesc(
                goal, unit.key, LedgerKind.CREDIT).isPresent();
        return new UnitTally(unit.tally, unit.creditedThrough, unit.creditsUntil, nextDate, nextAmount,
                net.amount, net.since, started);
    }

    private record Net(BigDecimal amount, LocalDate since) {
    }

    /** A missed payday not yet fully made up. */
    private static final class Owed {
        final LocalDate date;
        BigDecimal left;

        Owed(LocalDate date, BigDecimal left) {
            this.date = date;
            this.left = left;
        }
    }

    /**
     * Doubled up minus Missed one after the later of the plan anchor and the unit's last SET, in
     * (date, id) order. A missed payday is made up first in, first out by later extras; extras ahead of
     * time cover the next missed ones. {@code since} is the oldest missed payday still open.
     */
    private Net net(Goal goal, Unit unit) {
        LocalDateTime anchoredAt = goal.getPlanAnchoredAt();
        LocalDate anchorDate = anchorDate(goal);
        Optional<GoalLedgerEntry> lastSet = entries.findTopByGoalAndUnitAndKindOrderByEntryDateDescIdDesc(
                goal, unit.key, LedgerKind.SET);
        LocalDate from = lastSet.map(s -> later(s.getEntryDate(), anchorDate)).orElse(anchorDate);
        List<GoalLedgerEntry> rows = entries.findByGoalAndUnitAndEntryDateGreaterThanEqualOrderByEntryDateAscIdAsc(
                goal, unit.key, from);

        boolean pastSet = lastSet.isEmpty() || lastSet.get().getEntryDate().isBefore(from);
        Deque<Owed> open = new ArrayDeque<>();
        BigDecimal surplus = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        for (GoalLedgerEntry row : rows) {
            if (!pastSet) {
                pastSet = row.getId().equals(lastSet.get().getId());
                continue;
            }
            boolean beforeAnchor = row.getEntryDate().equals(anchorDate) && row.getCreatedAt() != null
                    && anchoredAt != null && row.getCreatedAt().isBefore(anchoredAt);
            if (beforeAnchor) {
                continue;
            }
            BigDecimal amount = row.getAmount().abs();
            if (row.getKind() == LedgerKind.MISSED) {
                net = net.subtract(amount);
                BigDecimal covered = surplus.min(amount);
                surplus = surplus.subtract(covered);
                BigDecimal left = amount.subtract(covered);
                if (left.signum() > 0) {
                    open.addLast(new Owed(row.getEntryDate(), left));
                }
            } else if (row.getKind() == LedgerKind.EXTRA) {
                net = net.add(amount);
                BigDecimal left = amount;
                while (left.signum() > 0 && !open.isEmpty()) {
                    Owed oldest = open.peekFirst();
                    BigDecimal used = oldest.left.min(left);
                    left = left.subtract(used);
                    oldest.left = oldest.left.subtract(used);
                    if (oldest.left.signum() == 0) {
                        open.removeFirst();
                    }
                }
                surplus = surplus.add(left);
            }
        }
        LocalDate since = net.signum() < 0 && !open.isEmpty() ? open.peekFirst().date : null;
        return new Net(net, since);
    }

    // ---- rules and shares ----

    /** The user's pay rule, or monthly on the plan's anchor day when there is no usable schedule. */
    private static PayRule ruleFor(Goal goal, Optional<InvestmentSchedule> schedule) {
        if (schedule.isPresent()) {
            InvestmentSchedule s = schedule.get();
            boolean usable = InvestmentFrequency.tryParse(s.getFrequency()).isPresent()
                    && (s.getChosenDate() != null || s.getStartDate() != null);
            if (usable) {
                try {
                    return PaydayCalendar.fromSchedule(s);
                } catch (IllegalArgumentException e) {
                    // A malformed stored weekday: fall through to the no-schedule rule.
                }
            }
        }
        return PaydayCalendar.monthlyOn(anchorDate(goal));
    }

    private static BigDecimal share(Unit unit, PayRule rule) {
        BigDecimal monthly = unit.monthly == null ? BigDecimal.ZERO : unit.monthly;
        return rule.frequency().fromMonthly(monthly);
    }

    /** planAnchoredAt is stored in Eastern time, so its date is the Eastern anchor day. */
    private static LocalDate anchorDate(Goal goal) {
        return goal.getPlanAnchoredAt().toLocalDate();
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    // ---- where each unit's state lives ----

    /** One saving unit's mutable state while it is walked. */
    private static final class Unit {
        final String key;
        final int kidIndex;
        final String account;
        BigDecimal tally;
        LocalDate creditedThrough;
        final LocalDate creditsUntil;
        final BigDecimal monthly;
        final BigDecimal target;
        LocalDateTime balanceUpdatedAt;
        /** Vacation only: the year savedThisYear counts for. Null for every other unit. */
        Integer savedYear;
        final AccountState source;

        Unit(String key, int kidIndex, String account, BigDecimal tally, LocalDate creditedThrough,
             LocalDate creditsUntil, BigDecimal monthly, BigDecimal target, LocalDateTime balanceUpdatedAt,
             Integer savedYear, AccountState source) {
            this.key = key;
            this.kidIndex = kidIndex;
            this.account = account;
            this.tally = tally == null ? BigDecimal.ZERO : tally;
            this.creditedThrough = creditedThrough;
            this.creditsUntil = creditsUntil;
            this.monthly = monthly;
            this.target = target;
            this.balanceUpdatedAt = balanceUpdatedAt;
            this.savedYear = savedYear;
            this.source = source;
        }

        AccountState toAccount() {
            return new AccountState(tally, balanceUpdatedAt, source.anchorBalance(), source.anchorMonthly(),
                    creditedThrough, source.creditsUntil(), source.target());
        }
    }

    /**
     * The units of one goal and where each one's state is stored: a simple goal's columns, a vacation's
     * config (savedThisYear, savedYear) plus the goal's creditedThrough, or a kid account's AccountState.
     */
    private static final class Units {
        final GoalType type;
        final Object config;
        final List<Unit> all;

        private Units(GoalType type, Object config, List<Unit> all) {
            this.type = type;
            this.config = config;
            this.all = all;
        }

        static Units of(Goal goal, GoalConfigCodec codec) {
            GoalType type = goal.getType();
            List<Unit> units = new ArrayList<>();
            if (type == GoalType.KIDS) {
                KidsConfig kids = (KidsConfig) codec.parse(type, goal.getConfigJson());
                for (int i = 0; i < kids.kids().size(); i++) {
                    KidsConfig.Accounts accounts = kids.kids().get(i).accounts();
                    addAccount(units, i, BROKERAGE, accounts.brokerage());
                    addAccount(units, i, PLAN_529, accounts.plan529());
                    addAccount(units, i, FIRST_YEAR, accounts.firstYear());
                }
                return new Units(type, kids, units);
            }
            if (type == GoalType.VACATION) {
                VacationConfig vacation = (VacationConfig) codec.parse(type, goal.getConfigJson());
                units.add(new Unit(GOAL_UNIT, -1, null, vacation.savedThisYear(), goal.getCreditedThrough(),
                        null, goal.getAnchorMonthly(), goal.getTarget(), goal.getBalanceUpdatedAt(),
                        vacation.savedYear(), null));
                return new Units(type, vacation, units);
            }
            units.add(new Unit(GOAL_UNIT, -1, null, goal.getCurrentBalance(), goal.getCreditedThrough(),
                    goal.getCreditsUntil(), goal.getAnchorMonthly(), goal.getTarget(), goal.getBalanceUpdatedAt(),
                    null, null));
            return new Units(type, null, units);
        }

        private static void addAccount(List<Unit> units, int kidIndex, String account, AccountState state) {
            if (state != null) {
                units.add(new Unit(kidUnit(kidIndex, account), kidIndex, account, state.balance(),
                        state.creditedThrough(), state.creditsUntil(), state.anchorMonthly(), state.target(),
                        state.balanceUpdatedAt(), null, state));
            }
        }

        void writeTo(Goal goal, GoalConfigCodec codec) {
            if (type == GoalType.KIDS) {
                goal.setConfigJson(codec.write(rebuildKids((KidsConfig) config)));
                return;
            }
            Unit unit = all.get(0);
            goal.setCreditedThrough(unit.creditedThrough);
            goal.setBalanceUpdatedAt(unit.balanceUpdatedAt);
            if (type == GoalType.VACATION) {
                VacationConfig v = (VacationConfig) config;
                goal.setConfigJson(codec.write(
                        new VacationConfig(v.tripsPerYear(), v.budgetPerTrip(), unit.tally, unit.savedYear)));
            } else {
                goal.setCurrentBalance(unit.tally);
            }
        }

        private KidsConfig rebuildKids(KidsConfig kids) {
            List<KidsConfig.Kid> rebuilt = new ArrayList<>();
            for (int i = 0; i < kids.kids().size(); i++) {
                KidsConfig.Kid kid = kids.kids().get(i);
                KidsConfig.Accounts a = kid.accounts();
                KidsConfig.Accounts accounts = new KidsConfig.Accounts(
                        account(i, BROKERAGE, a.brokerage()),
                        account(i, PLAN_529, a.plan529()),
                        account(i, FIRST_YEAR, a.firstYear()));
                rebuilt.add(new KidsConfig.Kid(kid.label(), kid.birthYear(), kid.birthMonth(), accounts,
                        kid.firstYearTarget(), kid.firstYearDueYear(), kid.firstYearDueMonth()));
            }
            return new KidsConfig(kids.brokerageTarget(), kids.plan529Target(), rebuilt, kids.firstYearFund(),
                    kids.giftDays());
        }

        private AccountState account(int kidIndex, String account, AccountState original) {
            for (Unit unit : all) {
                if (unit.kidIndex == kidIndex && account.equals(unit.account)) {
                    return unit.toAccount();
                }
            }
            return original;
        }
    }
}
