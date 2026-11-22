package com.investingapp.backend.service;

import com.investingapp.backend.dto.GoalImportRequest;
import com.investingapp.backend.dto.GoalRequest;
import com.investingapp.backend.model.Goal;
import com.investingapp.backend.model.GoalType;
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.goals.AccountState;
import com.investingapp.backend.model.goals.EmergencyFundConfig;
import com.investingapp.backend.model.goals.GoalConfigCodec;
import com.investingapp.backend.model.goals.HouseCarConfig;
import com.investingapp.backend.model.goals.KidsConfig;
import com.investingapp.backend.model.goals.TargetConfig;
import com.investingapp.backend.model.goals.VacationConfig;
import com.investingapp.backend.repository.GoalRepository;
import com.investingapp.backend.util.EasternTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Goal business rules. The server alone decides when a plan is re-anchored, by comparing the stored
 * plan inputs with the incoming ones. The stored config JSON is always the codec's output.
 */
@Service
@Transactional
public class GoalService {

    private static final int UNDECIDED_RUNWAY_MONTHS = 36;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000");
    /** The anchor_monthly column is DECIMAL(10,2). */
    private static final BigDecimal MAX_MONTHLY = new BigDecimal("99999999.99");
    private static final BigDecimal MAX_LOAN_RATE = new BigDecimal("30");
    private static final int MAX_IMPORTED_GOALS = 50;
    private static final BigDecimal IMPORT_PLAN_RATE = new BigDecimal("0.1000");
    private static final BigDecimal IMPORT_CASH_RATE = new BigDecimal("0.0400");
    private static final Set<String> CUSTOM_ICONS = Set.of(
            "flag", "balance", "savings", "home", "school", "directions_car", "health_and_safety");
    private static final Map<GoalType, Set<String>> ICON_ALLOWLIST = Map.of(
            GoalType.KIDS, Set.of("child_care", "school", "savings"),
            GoalType.HOUSE, Set.of("home"),
            GoalType.CAR, Set.of("directions_car"),
            GoalType.VACATION, Set.of("flight"),
            GoalType.EMERGENCY_FUND, Set.of("health_and_safety"),
            GoalType.WEDDING, Set.of("favorite"),
            GoalType.CUSTOM, CUSTOM_ICONS);

    private final GoalRepository goalRepository;
    private final GoalConfigCodec codec;

    public GoalService(GoalRepository goalRepository, GoalConfigCodec codec) {
        this.goalRepository = goalRepository;
        this.codec = codec;
    }

    @Transactional(readOnly = true)
    public List<Goal> list(User user) {
        return goalRepository.findByUserOrderBySortOrderAscCreatedAtAsc(user);
    }

    public Goal create(User user, GoalRequest request) {
        enforceTierLimit(user);
        Object config = validate(request, null);

        Goal goal = new Goal();
        goal.setUser(user);
        goal.setType(request.getType());
        goal.setSortOrder(goalRepository.findByUserOrderBySortOrderAscCreatedAtAsc(user).size());
        applyEditableFields(goal, request);
        reanchor(goal, request, config, EasternTime.now());
        return goalRepository.save(goal);
    }

    public Goal update(User user, Long id, GoalRequest request) {
        Goal goal = goalRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new NoSuchElementException("Goal not found"));
        if (goal.getType() != request.getType()) {
            throw new GoalValidationException("A goal's type cannot be changed.");
        }
        if (goal.isCompleted() && !request.isCompleted()) {
            // Reopening counts as adding an active goal. The goal is still stored as completed here,
            // so the count covers only the user's other active goals.
            enforceTierLimit(user);
        }
        Object stored = codec.parse(goal.getType(), goal.getConfigJson());
        Object config = validate(request, stored);
        LocalDateTime now = EasternTime.now();

        boolean firstPlan = goal.getAnchorMonthly().signum() == 0 && request.getAnchorMonthly().signum() > 0;
        boolean reanchor = firstPlan || planInputsChanged(request.getType(), stored, config);
        BigDecimal previousBalance = goal.getCurrentBalance();
        applyEditableFields(goal, request);
        if (reanchor) {
            reanchor(goal, request, config, now);
        } else {
            keepAnchors(goal, request, stored, config, now, previousBalance);
        }
        return goalRepository.save(goal);
    }

    public void delete(User user, Long id) {
        Goal goal = goalRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new NoSuchElementException("Goal not found"));
        goalRepository.delete(goal);
    }

    public List<Goal> importLegacy(User user, GoalImportRequest request) {
        int nextSort = goalRepository.findByUserOrderBySortOrderAscCreatedAtAsc(user).size();
        long imported = goalRepository.countByUserAndClientIdIsNotNull(user);
        List<Goal> saved = new ArrayList<>();
        for (GoalImportRequest.ImportedGoal legacy : request.getGoals()) {
            if (imported >= MAX_IMPORTED_GOALS) {
                break;
            }
            if (goalRepository.existsByUserAndClientId(user, legacy.getClientId())) {
                continue;
            }
            saved.add(goalRepository.save(buildImportedGoal(user, legacy, nextSort++)));
            imported++;
        }
        return saved;
    }

    // ---- tier rule ----

    private void enforceTierLimit(User user) {
        String tier = user.getSelectedTier();
        boolean multiGoal = "plus".equals(tier) || "pro".equals(tier);
        if (!multiGoal && goalRepository.countByUserAndCompletedFalse(user) >= 1) {
            throw new TierLimitException("Your plan includes one active goal. Upgrade to add more.");
        }
    }

    // ---- common field handling ----

    private void applyEditableFields(Goal goal, GoalRequest request) {
        goal.setName(request.getName().trim());
        goal.setIcon(request.getIcon());
        goal.setCurrentBalance(request.getCurrentBalance());
        goal.setCompleted(request.isCompleted());
    }

    // ---- anchor rules ----

    /** Full re-anchor: the plan restarts from the balance and monthly amount sent now. */
    private void reanchor(Goal goal, GoalRequest request, Object config, LocalDateTime now) {
        goal.setAnchorBalance(request.getCurrentBalance());
        goal.setAnchorMonthly(request.getAnchorMonthly());
        goal.setCreditsUntil(request.getCreditsUntil());
        goal.setTarget(request.getTarget());
        goal.setPlanRate(request.getPlanRate());
        goal.setPlanRateKind(request.getPlanRateKind());
        goal.setPlanAnchoredAt(now);
        goal.setBalanceUpdatedAt(null);
        Object toStore = config;
        if (config instanceof KidsConfig kids) {
            toStore = mapAccounts(kids, (sent, index, slot) -> anchored(sent));
        }
        goal.setConfigJson(codec.write(toStore));
    }

    /** Balance-only edit: the plan stays exactly as anchored, only balance dates move. */
    private void keepAnchors(Goal goal, GoalRequest request, Object stored, Object config,
                             LocalDateTime now, BigDecimal previousBalance) {
        if (request.getCurrentBalance().compareTo(previousBalance) != 0) {
            goal.setBalanceUpdatedAt(now);
        }
        if (request.getCreditsUntil() != null) {
            goal.setCreditsUntil(request.getCreditsUntil());
        }
        Object toStore = config;
        if (config instanceof KidsConfig kids && stored instanceof KidsConfig old) {
            toStore = mapAccounts(kids, (sent, index, slot) -> {
                AccountState before = accountAt(old, index, slot);
                if (before == null) {
                    return anchored(sent);
                }
                boolean changed = sent.balance().compareTo(before.balance()) != 0;
                return new AccountState(sent.balance(),
                        changed ? now : before.balanceUpdatedAt(),
                        before.anchorBalance(), before.anchorMonthly(),
                        before.creditedThrough(),
                        sent.creditsUntil() != null ? sent.creditsUntil() : before.creditsUntil(),
                        before.target());
            });
        }
        goal.setConfigJson(codec.write(toStore));
    }

    /** A fresh anchor: the plan starts from this balance and the monthly amount sent. */
    private static AccountState anchored(AccountState sent) {
        return new AccountState(sent.balance(), null, sent.balance(), orZero(sent.anchorMonthly()),
                null, sent.creditsUntil(), sent.target());
    }

    private boolean planInputsChanged(GoalType type, Object stored, Object incoming) {
        return switch (type) {
            case KIDS -> kidsPlanChanged((KidsConfig) stored, (KidsConfig) incoming);
            case HOUSE, CAR -> {
                HouseCarConfig a = (HouseCarConfig) stored;
                HouseCarConfig b = (HouseCarConfig) incoming;
                yield differs(a.price(), b.price())
                        || !Objects.equals(a.downPaymentPct(), b.downPaymentPct())
                        || !Objects.equals(a.targetYear(), b.targetYear())
                        || differs(a.downPaymentAmount(), b.downPaymentAmount());
            }
            case VACATION -> {
                VacationConfig a = (VacationConfig) stored;
                VacationConfig b = (VacationConfig) incoming;
                yield !Objects.equals(a.tripsPerYear(), b.tripsPerYear())
                        || differs(a.budgetPerTrip(), b.budgetPerTrip());
            }
            case EMERGENCY_FUND -> {
                EmergencyFundConfig a = (EmergencyFundConfig) stored;
                EmergencyFundConfig b = (EmergencyFundConfig) incoming;
                yield differs(a.monthlyExpenses(), b.monthlyExpenses())
                        || !Objects.equals(a.months(), b.months())
                        || !Objects.equals(a.targetYear(), b.targetYear());
            }
            case WEDDING, CUSTOM -> {
                TargetConfig a = (TargetConfig) stored;
                TargetConfig b = (TargetConfig) incoming;
                yield differs(a.targetAmount(), b.targetAmount())
                        || !Objects.equals(a.targetYear(), b.targetYear());
            }
        };
    }

    /** A kid's label is display text, not a plan input, so renaming never re-anchors. */
    private boolean kidsPlanChanged(KidsConfig a, KidsConfig b) {
        if (differs(a.brokerageTarget(), b.brokerageTarget())
                || differs(a.plan529Target(), b.plan529Target())
                || Boolean.TRUE.equals(a.firstYearFund()) != Boolean.TRUE.equals(b.firstYearFund())
                || Boolean.TRUE.equals(a.giftDays()) != Boolean.TRUE.equals(b.giftDays())
                || a.kids().size() != b.kids().size()) {
            return true;
        }
        for (int i = 0; i < a.kids().size(); i++) {
            KidsConfig.Kid x = a.kids().get(i);
            KidsConfig.Kid y = b.kids().get(i);
            if (!Objects.equals(x.birthYear(), y.birthYear()) || !Objects.equals(x.birthMonth(), y.birthMonth())
                    || (x.accounts().firstYear() == null) != (y.accounts().firstYear() == null)
                    || differs(x.firstYearTarget(), y.firstYearTarget())
                    || !Objects.equals(x.firstYearDueYear(), y.firstYearDueYear())
                    || !Objects.equals(x.firstYearDueMonth(), y.firstYearDueMonth())) {
                return true;
            }
        }
        return false;
    }

    private static boolean differs(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a != b;
        }
        return a.compareTo(b) != 0;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private enum AccountSlot { BROKERAGE, PLAN_529, FIRST_YEAR }

    @FunctionalInterface
    private interface AccountMapper {
        AccountState map(AccountState sent, int kidIndex, AccountSlot slot);
    }

    private static KidsConfig mapAccounts(KidsConfig config, AccountMapper mapper) {
        List<KidsConfig.Kid> kids = new ArrayList<>();
        for (int i = 0; i < config.kids().size(); i++) {
            KidsConfig.Kid kid = config.kids().get(i);
            AccountState firstYear = kid.accounts().firstYear();
            kids.add(new KidsConfig.Kid(kid.label(), kid.birthYear(), kid.birthMonth(),
                    new KidsConfig.Accounts(
                            mapper.map(kid.accounts().brokerage(), i, AccountSlot.BROKERAGE),
                            mapper.map(kid.accounts().plan529(), i, AccountSlot.PLAN_529),
                            firstYear == null ? null : mapper.map(firstYear, i, AccountSlot.FIRST_YEAR)),
                    kid.firstYearTarget(), kid.firstYearDueYear(), kid.firstYearDueMonth()));
        }
        return new KidsConfig(config.brokerageTarget(), config.plan529Target(), kids, config.firstYearFund(),
                config.giftDays());
    }

    private static AccountState accountAt(KidsConfig config, int index, AccountSlot slot) {
        KidsConfig.Accounts accounts = config.kids().get(index).accounts();
        return switch (slot) {
            case BROKERAGE -> accounts.brokerage();
            case PLAN_529 -> accounts.plan529();
            case FIRST_YEAR -> accounts.firstYear();
        };
    }

    // ---- validation ----

    /** Parses and validates the request, returning the normalized typed config record. */
    private Object validate(GoalRequest request, Object storedOrNull) {
        int year = EasternTime.today().getYear();
        String name = request.getName() == null ? "" : request.getName().trim();
        if (name.isEmpty() || name.length() > 50) {
            throw new GoalValidationException("Give your goal a name of 50 characters or fewer.");
        }
        validateIcon(request.getType(), request.getIcon());
        BigDecimal balance = request.getCurrentBalance();
        if (balance.signum() < 0 || balance.compareTo(MAX_AMOUNT) > 0) {
            throw new GoalValidationException("Enter a balance between $0 and $100,000,000.");
        }
        requireScale(balance, "Enter a balance with at most 2 decimals.");
        requireMonthly(request.getAnchorMonthly(), "Enter a monthly amount from $0 to $99,999,999.99 with at most 2 decimals.");
        BigDecimal target = request.getTarget();
        if (target != null && (target.signum() < 0 || target.compareTo(MAX_AMOUNT) > 0
                || target.stripTrailingZeros().scale() > 2)) {
            throw new GoalValidationException("Enter a target from $0 to $100,000,000 with at most 2 decimals.");
        }
        Object config = codec.parse(request.getType(), request.getConfig());
        // Date lower bounds apply only to values that are new or changed: a stored date that the
        // calendar has since passed is still accepted, so balance logs and completion keep working.
        return switch (request.getType()) {
            case KIDS -> validateKids((KidsConfig) config, (KidsConfig) storedOrNull, year);
            case HOUSE, CAR -> validateHouseCar(request.getType(), (HouseCarConfig) config, year,
                    storedOrNull == null ? null : ((HouseCarConfig) storedOrNull).targetYear());
            case VACATION -> validateVacation((VacationConfig) config, year,
                    storedOrNull == null ? null : ((VacationConfig) storedOrNull).savedYear());
            case EMERGENCY_FUND -> validateEmergencyFund((EmergencyFundConfig) config, year,
                    storedOrNull == null ? null : ((EmergencyFundConfig) storedOrNull).targetYear());
            case WEDDING, CUSTOM -> validateTarget((TargetConfig) config, year,
                    storedOrNull == null ? null : ((TargetConfig) storedOrNull).targetYear());
        };
    }

    private void validateIcon(GoalType type, String icon) {
        if (icon == null || !ICON_ALLOWLIST.get(type).contains(icon)) {
            throw new GoalValidationException("Pick one of the icons offered for this kind of goal.");
        }
    }

    private KidsConfig validateKids(KidsConfig config, KidsConfig storedOrNull, int year) {
        requireTarget(config.brokerageTarget());
        requireTarget(config.plan529Target());
        if (config.kids() == null || config.kids().isEmpty() || config.kids().size() > 6) {
            throw new GoalValidationException("Add between 1 and 6 kids.");
        }
        boolean fundOn = Boolean.TRUE.equals(config.firstYearFund());
        int funded = 0;
        List<KidsConfig.Kid> kids = new ArrayList<>();
        for (int i = 0; i < config.kids().size(); i++) {
            KidsConfig.Kid kid = config.kids().get(i);
            if (kid == null) {
                throw new GoalValidationException("Goal details are not in the expected shape.");
            }
            String label = kid.label() == null ? null : kid.label().trim();
            if (label != null && label.length() > 30) {
                throw new GoalValidationException("Keep each kid's name to 30 characters or fewer.");
            }
            Integer birthYear = kid.birthYear();
            if (birthYear != null) {
                if (birthYear < year - 17 && !hasStoredBirthYear(storedOrNull, i, birthYear)) {
                    throw new GoalValidationException(
                            "Already 18 or older. Use Something else with a target year instead.");
                }
                if (birthYear > year + 10) {
                    throw new GoalValidationException("Enter a birth year no more than 10 years ahead.");
                }
            }
            if (kid.birthMonth() != null && (kid.birthMonth() < 1 || kid.birthMonth() > 12)) {
                throw new GoalValidationException("Enter a birth month from 1 to 12.");
            }
            if (kid.accounts() == null || kid.accounts().brokerage() == null || kid.accounts().plan529() == null) {
                throw new GoalValidationException("Goal details are not in the expected shape.");
            }
            requireAccountBalance(kid.accounts().brokerage());
            requireAccountBalance(kid.accounts().plan529());
            AccountState firstYear = kid.accounts().firstYear();
            KidsConfig.Accounts accounts = new KidsConfig.Accounts(
                    kid.accounts().brokerage(), kid.accounts().plan529(), null);
            if (fundOn && firstYear != null) {
                funded++;
                kids.add(validateFundKid(kid, label, accounts, firstYear, hasStoredFund(storedOrNull, i), year));
            } else {
                kids.add(new KidsConfig.Kid(label, birthYear, kid.birthMonth(), accounts, null, null, null));
            }
        }
        if (fundOn && funded == 0) {
            throw new GoalValidationException("Turn off the first-year fund or add a kid who is not here yet.");
        }
        if (!fundOn && config.brokerageTarget().signum() == 0 && config.plan529Target().signum() == 0) {
            throw new GoalValidationException("Set a target for at least one account.");
        }
        return new KidsConfig(config.brokerageTarget(), config.plan529Target(), kids, fundOn, Boolean.TRUE.equals(config.giftDays()));
    }

    /** Whether the stored kid at this index has the same birth year (a kid who aged past 17 since). */
    private static boolean hasStoredBirthYear(KidsConfig storedOrNull, int index, Integer birthYear) {
        return storedOrNull != null && storedOrNull.kids() != null && index < storedOrNull.kids().size()
                && storedOrNull.kids().get(index) != null
                && Objects.equals(storedOrNull.kids().get(index).birthYear(), birthYear);
    }

    /** Whether the stored goal already holds a first-year account at this kid index. */
    private static boolean hasStoredFund(KidsConfig storedOrNull, int index) {
        return storedOrNull != null && storedOrNull.kids() != null && index < storedOrNull.kids().size()
                && storedOrNull.kids().get(index).accounts() != null
                && storedOrNull.kids().get(index).accounts().firstYear() != null;
    }

    /**
     * Validates one kid's first-year fund. A stored account is never rejected for the kid's age or the
     * end date. A new one needs a kid who is not here yet, and an undecided kid gets a server-set end
     * month 36 months after the anchor month, whatever the client sent.
     */
    private KidsConfig.Kid validateFundKid(KidsConfig.Kid kid, String label, KidsConfig.Accounts accounts,
                                           AccountState firstYear, boolean storedFund, int year) {
        BigDecimal target = kid.firstYearTarget();
        if (target == null || target.signum() <= 0 || target.compareTo(MAX_AMOUNT) > 0
                || target.stripTrailingZeros().scale() > 2) {
            throw new GoalValidationException("Enter a first-year amount greater than $0.");
        }
        Integer dueYear = kid.firstYearDueYear();
        Integer dueMonth = kid.firstYearDueMonth();
        boolean newAccount = !storedFund;
        if (newAccount && kid.birthYear() == null) {
            LocalDateTime end = EasternTime.now().plusMonths(UNDECIDED_RUNWAY_MONTHS);
            dueYear = end.getYear();
            dueMonth = end.getMonthValue();
        }
        if (dueYear == null || dueMonth == null || dueMonth < 1 || dueMonth > 12) {
            throw new GoalValidationException("Pick the month the first year ends.");
        }
        requireAccountBalance(firstYear);
        if (newAccount) {
            if (kid.birthYear() != null && kid.birthYear() < year) {
                throw new GoalValidationException("The first-year fund is for kids who are not here yet.");
            }
            if (dueYear < year || dueYear > year + 11) {
                throw new GoalValidationException("Pick a first-year end date within the next 11 years.");
            }
        }
        return new KidsConfig.Kid(label, kid.birthYear(), kid.birthMonth(),
                new KidsConfig.Accounts(accounts.brokerage(), accounts.plan529(), firstYear),
                target, dueYear, dueMonth);
    }

    private HouseCarConfig validateHouseCar(GoalType type, HouseCarConfig config, int year, Integer storedTargetYear) {
        requireAmount(config.price(), "price");
        requireTargetYear(config.targetYear(), year, storedTargetYear);
        Integer pct = config.downPaymentPct();
        if (pct != null && (pct < 1 || pct > 100)) {
            throw new GoalValidationException("Enter a down payment between 1% and 100%.");
        }
        BigDecimal rate = config.loanRate();
        if (rate != null && (rate.signum() < 0 || rate.compareTo(MAX_LOAN_RATE) > 0
                || rate.stripTrailingZeros().scale() > 3)) {
            throw new GoalValidationException("Enter a loan rate from 0% to 30%.");
        }
        BigDecimal down = config.downPaymentAmount();
        if (down != null) {
            if (down.signum() < 0 || down.compareTo(config.price()) > 0
                    || down.stripTrailingZeros().scale() > 2) {
                throw new GoalValidationException("Enter a down payment from $0 up to the price.");
            }
            if (type == GoalType.CAR && down.signum() == 0) {
                throw new GoalValidationException("With $0 down there is nothing to save.");
            }
        }
        return config;
    }

    private VacationConfig validateVacation(VacationConfig config, int year, Integer storedSavedYear) {
        if (config.tripsPerYear() == null || config.tripsPerYear() < 1 || config.tripsPerYear() > 10) {
            throw new GoalValidationException("Pick 1 to 10 trips a year.");
        }
        requireAmount(config.budgetPerTrip(), "trip budget");
        requireTarget(config.savedThisYear());
        Integer savedYear = config.savedYear();
        boolean tooOld = savedYear != null && savedYear < year - 1 && !savedYear.equals(storedSavedYear);
        if (savedYear == null || tooOld || savedYear > year) {
            throw new GoalValidationException("The saved-for year must be this year or last year.");
        }
        return config;
    }

    private EmergencyFundConfig validateEmergencyFund(EmergencyFundConfig config, int year, Integer storedTargetYear) {
        requireAmount(config.monthlyExpenses(), "monthly expenses");
        if (config.months() == null || (config.months() != 3 && config.months() != 6)) {
            throw new GoalValidationException("Choose 3 or 6 months of expenses.");
        }
        requireTargetYear(config.targetYear(), year, storedTargetYear);
        return config;
    }

    private TargetConfig validateTarget(TargetConfig config, int year, Integer storedTargetYear) {
        requireAmount(config.targetAmount(), "target amount");
        requireTargetYear(config.targetYear(), year, storedTargetYear);
        return config;
    }

    /** Strictly positive, at most 100,000,000, at most 2 decimals. */
    private void requireAmount(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0 || value.compareTo(MAX_AMOUNT) > 0
                || value.stripTrailingZeros().scale() > 2) {
            throw new GoalValidationException(
                    "Enter a " + field + " above $0 and up to $100,000,000 with at most 2 decimals.");
        }
    }

    /** Zero allowed, at most 100,000,000, at most 2 decimals. */
    private void requireTarget(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_AMOUNT) > 0
                || value.stripTrailingZeros().scale() > 2) {
            throw new GoalValidationException("Enter an amount from $0 to $100,000,000 with at most 2 decimals.");
        }
    }

    private void requireScale(BigDecimal value, String message) {
        if (value.stripTrailingZeros().scale() > 2) {
            throw new GoalValidationException(message);
        }
    }

    private void requireMonthly(BigDecimal value, String message) {
        if (value == null || value.signum() < 0 || value.compareTo(MAX_MONTHLY) > 0
                || value.stripTrailingZeros().scale() > 2) {
            throw new GoalValidationException(message);
        }
    }

    private void requireAccountBalance(AccountState state) {
        BigDecimal balance = state.balance();
        if (balance == null || balance.signum() < 0 || balance.compareTo(MAX_AMOUNT) > 0) {
            throw new GoalValidationException("Enter an account balance between $0 and $100,000,000.");
        }
        if (state.anchorMonthly() != null) {
            requireMonthly(state.anchorMonthly(),
                    "Enter a monthly contribution from $0 to $99,999,999.99 with at most 2 decimals.");
        }
    }

    /** A past year is accepted only when it is the stored, unchanged target year. */
    private void requireTargetYear(Integer targetYear, int year, Integer storedTargetYear) {
        boolean pastAndNew = targetYear != null && targetYear < year && !targetYear.equals(storedTargetYear);
        if (targetYear == null || pastAndNew || targetYear > year + 50) {
            throw new GoalValidationException("Pick a target year between " + year + " and " + (year + 50) + ".");
        }
    }

    // ---- legacy import ----

    /** Normalizes the legacy values into the valid range first, then builds a CUSTOM goal from them. */
    private Goal buildImportedGoal(User user, GoalImportRequest.ImportedGoal legacy, int sortOrder) {
        int year = EasternTime.today().getYear();
        int targetYear = Math.min(Math.max(legacy.getTargetYear(), year), year + 50);
        // Whole dollars, because the v3 fields only hold whole dollars.
        BigDecimal target = legacy.getTargetAmount().max(BigDecimal.ONE).min(MAX_AMOUNT)
                .setScale(0, RoundingMode.HALF_UP).setScale(2);
        BigDecimal starting = legacy.getStartingValue().max(BigDecimal.ZERO).min(MAX_AMOUNT)
                .setScale(0, RoundingMode.HALF_UP).setScale(2);
        // Never planned yet, so the stored kind follows the horizon rule: cash under 36 months.
        LocalDateTime nowForKind = EasternTime.now();
        int monthsToDeadline = (targetYear - nowForKind.getYear()) * 12 + (12 - nowForKind.getMonthValue());
        boolean cash = monthsToDeadline < UNDECIDED_RUNWAY_MONTHS;
        String icon = CUSTOM_ICONS.contains(legacy.getIcon()) ? legacy.getIcon() : "flag";
        String name = legacy.getName().trim();
        if (name.length() > 50) {
            name = name.substring(0, 50);
        }

        validateIcon(GoalType.CUSTOM, icon);
        TargetConfig config = validateTarget(new TargetConfig(target, targetYear), year, null);

        LocalDateTime now = nowForKind;
        Goal goal = new Goal();
        goal.setUser(user);
        goal.setClientId(legacy.getClientId());
        goal.setType(GoalType.CUSTOM);
        goal.setName(name);
        goal.setIcon(icon);
        goal.setConfigJson(codec.write(config));
        goal.setCurrentBalance(starting);
        goal.setBalanceUpdatedAt(now);
        goal.setAnchorBalance(starting);
        goal.setAnchorMonthly(BigDecimal.ZERO);
        goal.setPlanRate(cash ? IMPORT_CASH_RATE : IMPORT_PLAN_RATE);
        goal.setPlanRateKind(cash ? "cash" : "market");
        goal.setPlanAnchoredAt(now);
        goal.setCompleted(legacy.isCompleted());
        goal.setSortOrder(sortOrder);
        return goal;
    }
}
