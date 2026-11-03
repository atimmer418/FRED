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

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000");
    private static final BigDecimal IMPORT_PLAN_RATE = new BigDecimal("0.1000");
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
        Object config = validate(request);

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
        Object config = validate(request);
        Object stored = codec.parse(goal.getType(), goal.getConfigJson());
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
        List<Goal> saved = new ArrayList<>();
        for (GoalImportRequest.ImportedGoal legacy : request.getGoals()) {
            if (goalRepository.existsByUserAndClientId(user, legacy.getClientId())) {
                continue;
            }
            saved.add(goalRepository.save(buildImportedGoal(user, legacy, nextSort++)));
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
        goal.setPlanRate(request.getPlanRate());
        goal.setPlanRateKind(request.getPlanRateKind());
        goal.setPlanAnchoredAt(now);
        goal.setBalanceUpdatedAt(null);
        Object toStore = config;
        if (config instanceof KidsConfig kids) {
            toStore = mapAccounts(kids, (sent, index, brokerage) -> new AccountState(
                    sent.balance(), null, sent.balance(), orZero(sent.anchorMonthly())));
        }
        goal.setConfigJson(codec.write(toStore));
    }

    /** Balance-only edit: the plan stays exactly as anchored, only balance dates move. */
    private void keepAnchors(Goal goal, GoalRequest request, Object stored, Object config,
                             LocalDateTime now, BigDecimal previousBalance) {
        if (request.getCurrentBalance().compareTo(previousBalance) != 0) {
            goal.setBalanceUpdatedAt(now);
        }
        Object toStore = config;
        if (config instanceof KidsConfig kids && stored instanceof KidsConfig old) {
            toStore = mapAccounts(kids, (sent, index, brokerage) -> {
                AccountState before = accountAt(old, index, brokerage);
                boolean changed = sent.balance().compareTo(before.balance()) != 0;
                return new AccountState(sent.balance(),
                        changed ? now : before.balanceUpdatedAt(),
                        before.anchorBalance(), before.anchorMonthly());
            });
        }
        goal.setConfigJson(codec.write(toStore));
    }

    private boolean planInputsChanged(GoalType type, Object stored, Object incoming) {
        return switch (type) {
            case KIDS -> kidsPlanChanged((KidsConfig) stored, (KidsConfig) incoming);
            case HOUSE, CAR -> {
                HouseCarConfig a = (HouseCarConfig) stored;
                HouseCarConfig b = (HouseCarConfig) incoming;
                yield differs(a.price(), b.price())
                        || !Objects.equals(a.downPaymentPct(), b.downPaymentPct())
                        || !Objects.equals(a.targetYear(), b.targetYear());
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
                || a.kids().size() != b.kids().size()) {
            return true;
        }
        for (int i = 0; i < a.kids().size(); i++) {
            KidsConfig.Kid x = a.kids().get(i);
            KidsConfig.Kid y = b.kids().get(i);
            if (!Objects.equals(x.birthYear(), y.birthYear()) || !Objects.equals(x.birthMonth(), y.birthMonth())) {
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

    @FunctionalInterface
    private interface AccountMapper {
        AccountState map(AccountState sent, int kidIndex, boolean brokerage);
    }

    private static KidsConfig mapAccounts(KidsConfig config, AccountMapper mapper) {
        List<KidsConfig.Kid> kids = new ArrayList<>();
        for (int i = 0; i < config.kids().size(); i++) {
            KidsConfig.Kid kid = config.kids().get(i);
            kids.add(new KidsConfig.Kid(kid.label(), kid.birthYear(), kid.birthMonth(),
                    new KidsConfig.Accounts(
                            mapper.map(kid.accounts().brokerage(), i, true),
                            mapper.map(kid.accounts().plan529(), i, false))));
        }
        return new KidsConfig(config.brokerageTarget(), config.plan529Target(), kids);
    }

    private static AccountState accountAt(KidsConfig config, int index, boolean brokerage) {
        KidsConfig.Accounts accounts = config.kids().get(index).accounts();
        return brokerage ? accounts.brokerage() : accounts.plan529();
    }

    // ---- validation ----

    /** Parses and validates the request, returning the normalized typed config record. */
    private Object validate(GoalRequest request) {
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
        Object config = codec.parse(request.getType(), request.getConfig());
        return switch (request.getType()) {
            case KIDS -> validateKids((KidsConfig) config, year);
            case HOUSE, CAR -> validateHouseCar((HouseCarConfig) config, year);
            case VACATION -> validateVacation((VacationConfig) config, year);
            case EMERGENCY_FUND -> validateEmergencyFund((EmergencyFundConfig) config, year);
            case WEDDING, CUSTOM -> validateTarget((TargetConfig) config, year);
        };
    }

    private void validateIcon(GoalType type, String icon) {
        if (icon == null || !ICON_ALLOWLIST.get(type).contains(icon)) {
            throw new GoalValidationException("Pick one of the icons offered for this kind of goal.");
        }
    }

    private KidsConfig validateKids(KidsConfig config, int year) {
        requireTarget(config.brokerageTarget());
        requireTarget(config.plan529Target());
        if (config.brokerageTarget().signum() == 0 && config.plan529Target().signum() == 0) {
            throw new GoalValidationException("Set a target for at least one account.");
        }
        if (config.kids() == null || config.kids().isEmpty() || config.kids().size() > 10) {
            throw new GoalValidationException("Add between 1 and 10 kids.");
        }
        List<KidsConfig.Kid> kids = new ArrayList<>();
        for (KidsConfig.Kid kid : config.kids()) {
            String label = kid.label() == null ? null : kid.label().trim();
            if (label != null && label.length() > 30) {
                throw new GoalValidationException("Keep each kid's name to 30 characters or fewer.");
            }
            Integer birthYear = kid.birthYear();
            if (birthYear != null) {
                if (birthYear < year - 17) {
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
            kids.add(new KidsConfig.Kid(label, birthYear, kid.birthMonth(), kid.accounts()));
        }
        return new KidsConfig(config.brokerageTarget(), config.plan529Target(), kids);
    }

    private HouseCarConfig validateHouseCar(HouseCarConfig config, int year) {
        requireAmount(config.price(), "price");
        requireTargetYear(config.targetYear(), year);
        Integer pct = config.downPaymentPct();
        if (pct != null && (pct < 1 || pct > 100)) {
            throw new GoalValidationException("Enter a down payment between 1% and 100%.");
        }
        return config;
    }

    private VacationConfig validateVacation(VacationConfig config, int year) {
        if (config.tripsPerYear() == null || config.tripsPerYear() < 1 || config.tripsPerYear() > 4) {
            throw new GoalValidationException("Choose between 1 and 4 trips per year.");
        }
        requireAmount(config.budgetPerTrip(), "trip budget");
        requireTarget(config.savedThisYear());
        if (config.savedYear() == null || config.savedYear() < year - 1 || config.savedYear() > year) {
            throw new GoalValidationException("The saved-for year must be this year or last year.");
        }
        return config;
    }

    private EmergencyFundConfig validateEmergencyFund(EmergencyFundConfig config, int year) {
        requireAmount(config.monthlyExpenses(), "monthly expenses");
        if (config.months() == null || (config.months() != 3 && config.months() != 6)) {
            throw new GoalValidationException("Choose 3 or 6 months of expenses.");
        }
        requireTargetYear(config.targetYear(), year);
        return config;
    }

    private TargetConfig validateTarget(TargetConfig config, int year) {
        requireAmount(config.targetAmount(), "target amount");
        requireTargetYear(config.targetYear(), year);
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

    private void requireAccountBalance(AccountState state) {
        BigDecimal balance = state.balance();
        if (balance == null || balance.signum() < 0 || balance.compareTo(MAX_AMOUNT) > 0) {
            throw new GoalValidationException("Enter an account balance between $0 and $100,000,000.");
        }
    }

    private void requireTargetYear(Integer targetYear, int year) {
        if (targetYear == null || targetYear < year || targetYear > year + 50) {
            throw new GoalValidationException("Pick a target year between " + year + " and " + (year + 50) + ".");
        }
    }

    // ---- legacy import ----

    /** Normalizes the legacy values into the valid range first, then builds a CUSTOM goal from them. */
    private Goal buildImportedGoal(User user, GoalImportRequest.ImportedGoal legacy, int sortOrder) {
        int year = EasternTime.today().getYear();
        int targetYear = Math.max(legacy.getTargetYear(), year);
        BigDecimal target = legacy.getTargetAmount().max(BigDecimal.ONE).min(MAX_AMOUNT)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal starting = legacy.getStartingValue().max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        String icon = CUSTOM_ICONS.contains(legacy.getIcon()) ? legacy.getIcon() : "flag";
        String name = legacy.getName().trim();
        if (name.length() > 50) {
            name = name.substring(0, 50);
        }

        validateIcon(GoalType.CUSTOM, icon);
        TargetConfig config = validateTarget(new TargetConfig(target, targetYear), year);

        LocalDateTime now = EasternTime.now();
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
        goal.setPlanRate(IMPORT_PLAN_RATE);
        goal.setPlanRateKind("market");
        goal.setPlanAnchoredAt(now);
        goal.setCompleted(legacy.isCompleted());
        goal.setSortOrder(sortOrder);
        return goal;
    }
}
