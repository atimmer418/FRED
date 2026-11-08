package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.investingapp.backend.dto.GoalImportRequest;
import com.investingapp.backend.dto.GoalRequest;
import com.investingapp.backend.model.Goal;
import com.investingapp.backend.model.GoalType;
import com.investingapp.backend.model.User;
import com.investingapp.backend.model.goals.AccountState;
import com.investingapp.backend.model.goals.GoalConfigCodec;
import com.investingapp.backend.model.goals.HouseCarConfig;
import com.investingapp.backend.model.goals.KidsConfig;
import com.investingapp.backend.model.goals.TargetConfig;
import com.investingapp.backend.repository.GoalRepository;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoalServiceTest {

    @Mock
    private GoalRepository goalRepository;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final GoalConfigCodec codec = new GoalConfigCodec(mapper);
    private final int year = EasternTime.today().getYear();

    private GoalService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new GoalService(goalRepository, codec);
        user = new User();
        user.setSelectedTier("pro");
    }

    private void stubSave() {
        when(goalRepository.save(any(Goal.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private GoalRequest request(GoalType type, String icon, Object config, String balance, String monthly) {
        GoalRequest r = new GoalRequest();
        r.setType(type);
        r.setName("  My goal ");
        r.setIcon(icon);
        r.setConfig(mapper.valueToTree(config));
        r.setCurrentBalance(bd(balance));
        r.setAnchorMonthly(bd(monthly));
        r.setPlanRate(bd("0.0700"));
        r.setPlanRateKind("market");
        return r;
    }

    private GoalRequest houseRequest(String price, String balance, String monthly) {
        return request(GoalType.HOUSE, "home", new HouseCarConfig(bd(price), 20, year + 5), balance, monthly);
    }

    private static AccountState acct(String balance, String monthly) {
        return new AccountState(bd(balance), null, null, monthly == null ? null : bd(monthly));
    }

    private static KidsConfig.Kid kid(String label, Integer birthYear, AccountState brokerage, AccountState plan529) {
        return new KidsConfig.Kid(label, birthYear, 6, new KidsConfig.Accounts(brokerage, plan529, null),
                null, null, null);
    }

    private KidsConfig kids(String brokerageTarget, String plan529Target, KidsConfig.Kid... kids) {
        return new KidsConfig(bd(brokerageTarget), bd(plan529Target), List.of(kids), null, null);
    }

    private GoalRequest kidsRequest(KidsConfig config, String balance, String monthly) {
        return request(GoalType.KIDS, "child_care", config, balance, monthly);
    }

    private KidsConfig oneKid(String brokerageBalance, String plan529Balance) {
        return kids("50000", "30000",
                kid("Sam", year - 3, acct(brokerageBalance, "100"), acct(plan529Balance, "200")));
    }

    private Goal storedGoal(Goal created, Long id) {
        created.setId(id);
        when(goalRepository.findByIdAndUser(id, user)).thenReturn(Optional.of(created));
        return created;
    }

    // ---- create ----

    @Test
    void createAnchorsToCurrentBalanceAndNow() {
        stubSave();
        Goal goal = service.create(user, houseRequest("400000", "1500.50", "417"));

        assertEquals(bd("1500.50"), goal.getAnchorBalance());
        assertEquals(bd("417"), goal.getAnchorMonthly());
        assertEquals(bd("0.0700"), goal.getPlanRate());
        assertEquals("market", goal.getPlanRateKind());
        assertNotNull(goal.getPlanAnchoredAt());
        assertNull(goal.getBalanceUpdatedAt());
        assertEquals("My goal", goal.getName());
    }

    @Test
    void createStoresReserializedConfigNotTheRequestText() throws Exception {
        stubSave();
        String requestText = "{ \"targetYear\" : " + (year + 5) + " ,  \"downPaymentPct\":20, \"price\": 400000.00 }";
        GoalRequest r = houseRequest("400000", "0", "0");
        r.setConfig(mapper.readTree(requestText));

        Goal goal = service.create(user, r);

        String expected = codec.write(codec.parse(GoalType.HOUSE, r.getConfig()));
        assertEquals(expected, goal.getConfigJson());
        assertNotEquals(requestText, goal.getConfigJson());
        assertNotEquals(r.getConfig().toString(), goal.getConfigJson());
    }

    @Test
    void createRejectsSecondActiveGoalForCoreUser() {
        user.setSelectedTier("core");
        when(goalRepository.countByUserAndCompletedFalse(user)).thenReturn(1L);

        assertThrows(TierLimitException.class, () -> service.create(user, houseRequest("400000", "0", "0")));
        verify(goalRepository, never()).save(any());
    }

    @Test
    void createAllowsSecondGoalWhenFirstIsCompleted() {
        stubSave();
        user.setSelectedTier("core");
        when(goalRepository.countByUserAndCompletedFalse(user)).thenReturn(0L);

        assertNotNull(service.create(user, houseRequest("400000", "0", "0")));
    }

    @Test
    void createAllowsSecondGoalForPlus() {
        stubSave();
        user.setSelectedTier("plus");

        assertNotNull(service.create(user, houseRequest("400000", "0", "0")));
    }

    @Test
    void createFailsClosedOnNullTier() {
        user.setSelectedTier(null);
        when(goalRepository.countByUserAndCompletedFalse(user)).thenReturn(1L);

        assertThrows(TierLimitException.class, () -> service.create(user, houseRequest("400000", "0", "0")));
    }

    // ---- validation ----

    @Test
    void kidsRequireAtLeastOneTarget() {
        KidsConfig config = kids("0", "0", kid("Sam", year - 3, acct("0", "0"), acct("0", "0")));

        assertThrows(GoalValidationException.class, () -> service.create(user, kidsRequest(config, "0", "0")));
    }

    @Test
    void kidsAllow529OnlyFamily() {
        stubSave();
        KidsConfig config = kids("0", "30000", kid("Sam", year - 3, acct("0", "0"), acct("100", "50")));

        assertNotNull(service.create(user, kidsRequest(config, "100", "50")));
    }

    @Test
    void kidsRejectBirthYearOfAnAdult() {
        KidsConfig config = kids("50000", "0", kid("Sam", year - 18, acct("0", "0"), acct("0", "0")));

        GoalValidationException e = assertThrows(GoalValidationException.class,
                () -> service.create(user, kidsRequest(config, "0", "0")));
        assertTrue(e.getMessage().startsWith("Already 18 or older"));
    }

    @Test
    void sevenKidsAreRejected() {
        KidsConfig config = new KidsConfig(bd("1000"), bd("1000"), kidList(7), null, null);

        GoalValidationException e = assertThrows(GoalValidationException.class,
                () -> service.create(user, kidsRequest(config, "0", "0")));
        assertEquals("Add between 1 and 6 kids.", e.getMessage());
    }

    @Test
    void sixKidsAreAccepted() {
        stubSave();
        KidsConfig config = new KidsConfig(bd("1000"), bd("1000"), kidList(6), null, null);

        assertNotNull(service.create(user, kidsRequest(config, "0", "0")));
    }

    private List<KidsConfig.Kid> kidList(int count) {
        List<KidsConfig.Kid> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(kid("Kid" + i, year - 2, acct("0", "0"), acct("0", "0")));
        }
        return list;
    }

    @Test
    void elevenTripsAreRejectedAndTenAccepted() {
        stubSave();
        var ten = new com.investingapp.backend.model.goals.VacationConfig(10, bd("3000"), bd("500"), year);
        var eleven = new com.investingapp.backend.model.goals.VacationConfig(11, bd("3000"), bd("500"), year);

        assertNotNull(service.create(user, requestWith(GoalType.VACATION, "flight", ten, "0", false)));
        GoalValidationException e = assertThrows(GoalValidationException.class,
                () -> service.create(user, requestWith(GoalType.VACATION, "flight", eleven, "0", false)));
        assertEquals("Pick 1 to 10 trips a year.", e.getMessage());
    }

    @Test
    void flippingGiftDaysIsAPlanInputChangeAndReanchors() {
        stubSave();
        KidsConfig off = kids("1000", "1000", kid("Sam", year - 2, acct("0", "0"), acct("0", "0")));
        Goal stored = storedGoal(service.create(user, kidsRequest(off, "100", "50")), 21L);
        var oldAnchoredAt = stored.getPlanAnchoredAt().minusDays(30);
        stored.setPlanAnchoredAt(oldAnchoredAt);

        KidsConfig on = new KidsConfig(off.brokerageTarget(), off.plan529Target(), off.kids(), null, true);
        Goal updated = service.update(user, 21L, kidsRequest(on, "150", "50"));

        assertTrue(updated.getPlanAnchoredAt().isAfter(oldAnchoredAt));
        assertEquals(bd("150"), updated.getAnchorBalance());
        assertEquals(Boolean.TRUE, storedConfigOf(updated).giftDays());
    }

    @Test
    void nullAndFalseGiftDaysAreTheSameForReanchoring() {
        stubSave();
        KidsConfig stale = kids("1000", "1000", kid("Sam", year - 2, acct("0", "0"), acct("0", "0")));
        Goal stored = storedGoal(service.create(user, kidsRequest(stale, "100", "50")), 22L);
        var anchoredAt = stored.getPlanAnchoredAt().minusDays(30);
        stored.setPlanAnchoredAt(anchoredAt);

        KidsConfig explicitFalse = new KidsConfig(stale.brokerageTarget(), stale.plan529Target(), stale.kids(),
                null, false);
        Goal updated = service.update(user, 22L, kidsRequest(explicitFalse, "150", "50"));

        assertEquals(anchoredAt, updated.getPlanAnchoredAt());
        assertEquals(bd("100"), updated.getAnchorBalance());
        assertEquals(Boolean.FALSE, storedConfigOf(stored).giftDays());
    }

    @Test
    void kidsRejectNegativeAccountBalance() {
        KidsConfig config = kids("1000", "1000", kid("Sam", year - 2, acct("-1", "0"), acct("0", "0")));

        assertThrows(GoalValidationException.class, () -> service.create(user, kidsRequest(config, "0", "0")));
    }

    @Test
    void kidsCreateCopiesAnchorMonthlyFromConfig() {
        stubSave();
        Goal goal = service.create(user, kidsRequest(oneKid("1000", "2000"), "3000", "300"));

        KidsConfig stored = (KidsConfig) codec.parse(GoalType.KIDS, goal.getConfigJson());
        KidsConfig.Accounts accounts = stored.kids().get(0).accounts();
        assertEquals(0, bd("100").compareTo(accounts.brokerage().anchorMonthly()));
        assertEquals(0, bd("1000").compareTo(accounts.brokerage().anchorBalance()));
        assertEquals(0, bd("200").compareTo(accounts.plan529().anchorMonthly()));
        assertEquals(0, bd("2000").compareTo(accounts.plan529().anchorBalance()));
        assertNull(accounts.brokerage().balanceUpdatedAt());
    }

    @Test
    void rejectsAmountAboveOneHundredMillion() {
        assertThrows(GoalValidationException.class,
                () -> service.create(user, houseRequest("100000000.01", "0", "0")));
    }

    @Test
    void rejectsTargetYearBeyondFiftyYears() {
        GoalRequest r = request(GoalType.CUSTOM, "flag", new TargetConfig(bd("5000"), year + 51), "0", "0");

        assertThrows(GoalValidationException.class, () -> service.create(user, r));
    }

    @Test
    void rejectsIconNotInAllowlistForType() {
        GoalRequest r = request(GoalType.HOUSE, "flight", new HouseCarConfig(bd("400000"), 20, year + 5), "0", "0");

        assertThrows(GoalValidationException.class, () -> service.create(user, r));
    }

    // ---- update ----

    @Test
    void updateReanchorsWhenAPlanInputChanges() {
        stubSave();
        Goal stored = storedGoal(service.create(user, houseRequest("400000", "1000", "417")), 7L);
        var oldAnchoredAt = stored.getPlanAnchoredAt().minusDays(30);
        stored.setPlanAnchoredAt(oldAnchoredAt);

        Goal updated = service.update(user, 7L, houseRequest("450000", "2500", "500"));

        assertEquals(bd("2500"), updated.getAnchorBalance());
        assertEquals(bd("500"), updated.getAnchorMonthly());
        assertTrue(updated.getPlanAnchoredAt().isAfter(oldAnchoredAt));
        assertNull(updated.getBalanceUpdatedAt());
    }

    @Test
    void updateKeepsAnchorWhenOnlyBalancesChange() {
        stubSave();
        Goal stored = storedGoal(service.create(user, houseRequest("400000", "1000", "417")), 7L);
        var anchoredAt = stored.getPlanAnchoredAt().minusDays(30);
        stored.setPlanAnchoredAt(anchoredAt);

        GoalRequest edit = houseRequest("400000", "1800", "999");
        edit.setPlanRate(bd("0.0900"));
        Goal updated = service.update(user, 7L, edit);

        assertEquals(bd("1000"), updated.getAnchorBalance());
        assertEquals(bd("417"), updated.getAnchorMonthly());
        assertEquals(bd("0.0700"), updated.getPlanRate());
        assertEquals(anchoredAt, updated.getPlanAnchoredAt());
        assertEquals(bd("1800"), updated.getCurrentBalance());
        assertNotNull(updated.getBalanceUpdatedAt());
    }

    @Test
    void updateWithSameBalanceLeavesBalanceDateAlone() {
        stubSave();
        Goal stored = storedGoal(service.create(user, houseRequest("400000", "1000", "417")), 7L);
        var stamp = EasternTime.now().minusDays(5);
        stored.setBalanceUpdatedAt(stamp);

        GoalRequest edit = houseRequest("400000", "1000", "417");
        edit.setName("Renamed");
        Goal updated = service.update(user, 7L, edit);

        assertEquals(stamp, updated.getBalanceUpdatedAt());
        assertEquals("Renamed", updated.getName());
    }

    @Test
    void updateReanchorsAnImportedGoalOnFirstPlan() {
        stubSave();
        Goal imported = new Goal();
        imported.setId(9L);
        imported.setType(GoalType.HOUSE);
        imported.setConfigJson(codec.write(new HouseCarConfig(bd("400000"), 20, year + 5)));
        imported.setCurrentBalance(bd("800"));
        imported.setAnchorBalance(bd("800"));
        imported.setAnchorMonthly(BigDecimal.ZERO);
        imported.setPlanRate(bd("0.1000"));
        imported.setPlanRateKind("market");
        imported.setPlanAnchoredAt(EasternTime.now().minusDays(60));
        when(goalRepository.findByIdAndUser(9L, user)).thenReturn(Optional.of(imported));

        Goal updated = service.update(user, 9L, houseRequest("400000", "950", "417"));

        assertEquals(bd("950"), updated.getAnchorBalance());
        assertEquals(bd("417"), updated.getAnchorMonthly());
        assertEquals(bd("0.0700"), updated.getPlanRate());
        assertTrue(updated.getPlanAnchoredAt().isAfter(EasternTime.now().minusMinutes(1)));
    }

    @Test
    void kidsUpdateStampsOnlyTheChangedAccount() {
        stubSave();
        Goal stored = storedGoal(service.create(user, kidsRequest(oneKid("1000", "2000"), "3000", "300")), 5L);
        var anchoredAt = stored.getPlanAnchoredAt();

        Goal updated = service.update(user, 5L, kidsRequest(oneKid("1000", "2500"), "3500", "300"));

        KidsConfig result = (KidsConfig) codec.parse(GoalType.KIDS, updated.getConfigJson());
        KidsConfig.Accounts accounts = result.kids().get(0).accounts();
        assertNull(accounts.brokerage().balanceUpdatedAt());
        assertNotNull(accounts.plan529().balanceUpdatedAt());
        assertEquals(0, bd("2500").compareTo(accounts.plan529().balance()));
        assertEquals(0, bd("2000").compareTo(accounts.plan529().anchorBalance()));
        assertEquals(0, bd("200").compareTo(accounts.plan529().anchorMonthly()));
        assertEquals(anchoredAt, updated.getPlanAnchoredAt());
    }

    @Test
    void renamingAKidKeepsTheAnchor() {
        stubSave();
        Goal stored = storedGoal(service.create(user, kidsRequest(oneKid("1000", "2000"), "3000", "300")), 5L);
        var anchoredAt = stored.getPlanAnchoredAt();

        KidsConfig renamed = kids("50000", "30000",
                kid("Samantha", year - 3, acct("1000", "100"), acct("2000", "200")));
        Goal updated = service.update(user, 5L, kidsRequest(renamed, "3000", "300"));

        assertEquals(anchoredAt, updated.getPlanAnchoredAt());
        assertEquals(bd("3000"), updated.getAnchorBalance());
        KidsConfig result = (KidsConfig) codec.parse(GoalType.KIDS, updated.getConfigJson());
        assertEquals("Samantha", result.kids().get(0).label());
    }

    @Test
    void updateOfAnotherUsersGoalThrowsNoSuchElement() {
        when(goalRepository.findByIdAndUser(42L, user)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.update(user, 42L, houseRequest("400000", "0", "0")));
    }

    // ---- import ----

    private GoalImportRequest.ImportedGoal legacy(String clientId, String icon, String amount, int targetYear, String start) {
        GoalImportRequest.ImportedGoal g = new GoalImportRequest.ImportedGoal();
        g.setClientId(clientId);
        g.setName("  Legacy " + clientId + " ");
        g.setIcon(icon);
        g.setTargetAmount(bd(amount));
        g.setTargetYear(targetYear);
        g.setStartingValue(bd(start));
        return g;
    }

    private GoalImportRequest importOf(GoalImportRequest.ImportedGoal... goals) {
        GoalImportRequest r = new GoalImportRequest();
        r.setGoals(List.of(goals));
        return r;
    }

    @Test
    void importSkipsExistingClientIdsAndBypassesTier() {
        stubSave();
        user.setSelectedTier("core");
        when(goalRepository.existsByUserAndClientId(user, "a")).thenReturn(false);
        when(goalRepository.existsByUserAndClientId(user, "b")).thenReturn(true);
        when(goalRepository.existsByUserAndClientId(user, "c")).thenReturn(false);

        List<Goal> saved = service.importLegacy(user, importOf(
                legacy("a", "flag", "1000", year + 2, "0"),
                legacy("b", "flag", "1000", year + 2, "0"),
                legacy("c", "flag", "1000", year + 2, "0")));

        assertEquals(2, saved.size());
        verify(goalRepository, never()).countByUserAndCompletedFalse(any());
    }

    @Test
    void importMapsLegacyFields() {
        stubSave();
        List<Goal> saved = service.importLegacy(user, importOf(legacy("a", "savings", "25000", year + 3, "1200")));

        Goal goal = saved.get(0);
        assertEquals(GoalType.CUSTOM, goal.getType());
        assertEquals("a", goal.getClientId());
        assertEquals("Legacy a", goal.getName());
        assertEquals(codec.write(new TargetConfig(bd("25000.00"), year + 3)), goal.getConfigJson());
        assertEquals(bd("1200.00"), goal.getCurrentBalance());
        assertEquals(bd("1200.00"), goal.getAnchorBalance());
        assertEquals(0, goal.getAnchorMonthly().signum());
        assertEquals(bd("0.1000"), goal.getPlanRate());
        assertEquals("market", goal.getPlanRateKind());
        assertNotNull(goal.getBalanceUpdatedAt());
    }

    @Test
    void importClampsPastTargetYearAndUnknownIcon() {
        stubSave();
        List<Goal> saved = service.importLegacy(user, importOf(legacy("a", "sparkle", "1000", 2019, "-50")));

        Goal goal = saved.get(0);
        TargetConfig config = (TargetConfig) codec.parse(GoalType.CUSTOM, goal.getConfigJson());
        assertEquals(year, config.targetYear());
        assertEquals("flag", goal.getIcon());
        assertEquals(0, goal.getCurrentBalance().signum());
    }

    @Test
    void importClampsFarFutureTargetYear() {
        stubSave();
        List<Goal> saved = service.importLegacy(user, importOf(legacy("a", "flag", "1000", 2099, "0")));

        TargetConfig config = (TargetConfig) codec.parse(GoalType.CUSTOM, saved.get(0).getConfigJson());
        assertEquals(year + 50, config.targetYear());
    }

    @Test
    void kidsRejectNegativeAccountAnchorMonthly() {
        KidsConfig config = kids("1000", "1000", kid("Sam", year - 2, acct("0", "-5"), acct("0", "0")));

        assertThrows(GoalValidationException.class, () -> service.create(user, kidsRequest(config, "0", "0")));
    }

    @Test
    void rejectsOutOfRangeTopLevelAnchorMonthlyAndBalanceScale() {
        assertThrows(GoalValidationException.class,
                () -> service.create(user, houseRequest("400000", "0", "100000001")));
        assertThrows(GoalValidationException.class,
                () -> service.create(user, houseRequest("400000", "0", "10.005")));
        assertThrows(GoalValidationException.class,
                () -> service.create(user, houseRequest("400000", "10.005", "0")));
    }

    @Test
    void updateRejectsChangingTheGoalType() {
        Goal custom = new Goal();
        custom.setId(3L);
        custom.setType(GoalType.CUSTOM);
        when(goalRepository.findByIdAndUser(3L, user)).thenReturn(Optional.of(custom));

        assertThrows(GoalValidationException.class,
                () -> service.update(user, 3L, houseRequest("400000", "0", "0")));
    }

    // ---- first-year fund ----

    private KidsConfig.Kid fundKid(String label, Integer birthYear, AccountState firstYear, String target,
                                   Integer dueYear, Integer dueMonth) {
        return new KidsConfig.Kid(label, birthYear, 6,
                new KidsConfig.Accounts(acct("0", "0"), acct("0", "0"), firstYear),
                target == null ? null : bd(target), dueYear, dueMonth);
    }

    private KidsConfig fundGoal(String brokerageTarget, String plan529Target, Boolean fund, KidsConfig.Kid... kids) {
        return new KidsConfig(bd(brokerageTarget), bd(plan529Target), List.of(kids), fund, null);
    }

    private KidsConfig oneFundKid(String balance, String target, int dueYear) {
        return fundGoal("0", "30000", true,
                fundKid("Baby", year + 1, acct(balance, "100"), target, dueYear, 6));
    }

    private KidsConfig storedConfigOf(Goal goal) {
        return (KidsConfig) codec.parse(GoalType.KIDS, goal.getConfigJson());
    }

    private Goal rawStoredKidsGoal(Long id, String configJson) {
        Goal g = new Goal();
        g.setId(id);
        g.setUser(user);
        g.setType(GoalType.KIDS);
        g.setName("Kids");
        g.setIcon("child_care");
        g.setConfigJson(configJson);
        g.setCurrentBalance(bd("3000"));
        g.setAnchorBalance(bd("3000"));
        g.setAnchorMonthly(bd("300"));
        g.setPlanRate(bd("0.0700"));
        g.setPlanRateKind("market");
        g.setPlanAnchoredAt(EasternTime.now().minusDays(30));
        when(goalRepository.findByIdAndUser(id, user)).thenReturn(Optional.of(g));
        return g;
    }

    @Test
    void kidsFundOnRequiresAKidWithAFund() {
        KidsConfig config = fundGoal("0", "30000", true, fundKid("Sam", year - 3, null, null, null, null));

        GoalValidationException e = assertThrows(GoalValidationException.class,
                () -> service.create(user, kidsRequest(config, "0", "0")));
        assertEquals("Turn off the first-year fund or add a kid who is not here yet.", e.getMessage());
    }

    @Test
    void kidsFundAccountRequiresATargetAndADueDate() {
        List<KidsConfig> badTargets = List.of(
                fundGoal("0", "30000", true, fundKid("A", year + 1, acct("0", "0"), null, year + 1, 6)),
                fundGoal("0", "30000", true, fundKid("A", year + 1, acct("0", "0"), "0", year + 1, 6)),
                fundGoal("0", "30000", true, fundKid("A", year + 1, acct("0", "0"), "100000001", year + 1, 6)),
                fundGoal("0", "30000", true, fundKid("A", year + 1, acct("0", "0"), "1000.123", year + 1, 6)));
        for (KidsConfig config : badTargets) {
            GoalValidationException e = assertThrows(GoalValidationException.class,
                    () -> service.create(user, kidsRequest(config, "0", "0")));
            assertEquals("Enter a first-year amount greater than $0.", e.getMessage());
        }
        List<KidsConfig> badDates = List.of(
                fundGoal("0", "30000", true, fundKid("A", year + 1, acct("0", "0"), "5000", year + 1, 13)),
                fundGoal("0", "30000", true, fundKid("A", year + 1, acct("0", "0"), "5000", year + 1, 0)),
                fundGoal("0", "30000", true, fundKid("A", year + 1, acct("0", "0"), "5000", year + 1, null)),
                fundGoal("0", "30000", true, fundKid("A", year + 1, acct("0", "0"), "5000", null, 6)));
        for (KidsConfig config : badDates) {
            assertThrows(GoalValidationException.class,
                    () -> service.create(user, kidsRequest(config, "0", "0")));
        }
    }

    @Test
    void kidsRejectANewFirstYearAccountOnAKidBornLastYear() {
        KidsConfig config = fundGoal("0", "30000", true,
                fundKid("Sam", year - 1, acct("0", "0"), "5000", year, 6));

        GoalValidationException e = assertThrows(GoalValidationException.class,
                () -> service.create(user, kidsRequest(config, "0", "0")));
        assertEquals("The first-year fund is for kids who are not here yet.", e.getMessage());
    }

    @Test
    void kidsRejectANewFirstYearDueYearOutsideTheWindow() {
        for (int due : new int[] {year - 1, year + 12}) {
            KidsConfig config = fundGoal("0", "30000", true,
                    fundKid("Baby", year + 1, acct("0", "0"), "5000", due, 6));
            assertThrows(GoalValidationException.class,
                    () -> service.create(user, kidsRequest(config, "0", "0")));
        }
    }

    @Test
    void storedFirstYearAccountSurvivesTheNewYear() {
        stubSave();
        AccountState storedFund = new AccountState(bd("1000"), null, bd("1000"), bd("100"));
        KidsConfig storedConfig = fundGoal("0", "30000", true,
                new KidsConfig.Kid("Sam", year - 1, 6,
                        new KidsConfig.Accounts(acct("0", "0"), acct("0", "0"), storedFund),
                        bd("5000"), year - 1, 12));
        Goal stored = rawStoredKidsGoal(9L, codec.write(storedConfig));

        KidsConfig sent = fundGoal("0", "30000", true,
                new KidsConfig.Kid("Sam", year - 1, 6,
                        new KidsConfig.Accounts(acct("0", "0"), acct("0", "0"), acct("1200", "100")),
                        bd("5000"), year - 1, 12));
        var anchoredAt = stored.getPlanAnchoredAt();
        Goal updated = service.update(user, 9L, kidsRequest(sent, "3200", "300"));

        KidsConfig.Accounts accounts = storedConfigOf(updated).kids().get(0).accounts();
        assertEquals(anchoredAt, updated.getPlanAnchoredAt());
        assertEquals(0, bd("1200").compareTo(accounts.firstYear().balance()));
        assertEquals(0, bd("1000").compareTo(accounts.firstYear().anchorBalance()));
        assertEquals(0, bd("100").compareTo(accounts.firstYear().anchorMonthly()));
        assertNotNull(accounts.firstYear().balanceUpdatedAt());
        assertEquals(year - 1, storedConfigOf(updated).kids().get(0).firstYearDueYear());
    }

    @Test
    void kidsAllowFirstYearOnlyGoal() {
        stubSave();
        KidsConfig config = fundGoal("0", "0", true,
                fundKid("Someday", null, acct("0", "50"), "5000", null, null));

        Goal goal = service.create(user, kidsRequest(config, "0", "50"));

        KidsConfig result = storedConfigOf(goal);
        assertEquals(Boolean.TRUE, result.firstYearFund());
        assertNotNull(result.kids().get(0).accounts().firstYear());
    }

    @Test
    void newUndecidedFundGetsAServerDueDateThirtySixMonthsOut() {
        stubSave();
        var before = EasternTime.now().plusMonths(36);
        KidsConfig config = fundGoal("0", "0", true,
                fundKid("Someday", null, acct("0", "50"), "5000", 1999, 1));

        Goal goal = service.create(user, kidsRequest(config, "0", "50"));

        var after = EasternTime.now().plusMonths(36);
        KidsConfig.Kid result = storedConfigOf(goal).kids().get(0);
        boolean matchesBefore = result.firstYearDueYear() == before.getYear()
                && result.firstYearDueMonth() == before.getMonthValue();
        boolean matchesAfter = result.firstYearDueYear() == after.getYear()
                && result.firstYearDueMonth() == after.getMonthValue();
        assertTrue(matchesBefore || matchesAfter,
                "due " + result.firstYearDueYear() + "-" + result.firstYearDueMonth()
                        + " expected " + before.getYear() + "-" + before.getMonthValue());
    }

    @Test
    void kidsFundOffStripsEveryFirstYearField() {
        stubSave();
        for (Boolean off : new Boolean[] {false, null}) {
            KidsConfig config = fundGoal("5000", "30000", off,
                    fundKid("Baby", year + 1, acct("10", "5"), "5000", year + 1, 6));

            Goal goal = service.create(user, kidsRequest(config, "0", "0"));

            KidsConfig result = storedConfigOf(goal);
            KidsConfig.Kid kid = result.kids().get(0);
            assertEquals(Boolean.FALSE, result.firstYearFund());
            assertNull(kid.accounts().firstYear());
            assertNull(kid.firstYearTarget());
            assertNull(kid.firstYearDueYear());
            assertNull(kid.firstYearDueMonth());
        }
    }

    @Test
    void kidsFundOnNullsFundFieldsOnAKidWithoutAFund() {
        stubSave();
        KidsConfig config = fundGoal("0", "30000", true,
                fundKid("Baby", year + 1, acct("0", "0"), "5000", year + 1, 6),
                fundKid("Sam", year - 3, null, "7000", year + 2, 3));

        KidsConfig result = storedConfigOf(service.create(user, kidsRequest(config, "0", "0")));

        KidsConfig.Kid sam = result.kids().get(1);
        assertNull(sam.accounts().firstYear());
        assertNull(sam.firstYearTarget());
        assertNull(sam.firstYearDueYear());
        assertNull(sam.firstYearDueMonth());
        assertEquals(0, bd("5000").compareTo(result.kids().get(0).firstYearTarget()));
    }

    @Test
    void kidsRejectAllZeroTargetsWhenTheFundIsOff() {
        KidsConfig off = fundGoal("0", "0", false, fundKid("Baby", year + 1, acct("0", "0"), "5000", year + 1, 6));

        GoalValidationException e = assertThrows(GoalValidationException.class,
                () -> service.create(user, kidsRequest(off, "0", "0")));
        assertEquals("Set a target for at least one account.", e.getMessage());
    }

    @Test
    void kidsCreateAnchorsTheFirstYearAccount() {
        stubSave();

        Goal goal = service.create(user, kidsRequest(oneFundKid("400", "5000", year + 1), "400", "100"));

        KidsConfig.Kid kid = storedConfigOf(goal).kids().get(0);
        AccountState fund = kid.accounts().firstYear();
        assertEquals(0, bd("400").compareTo(fund.balance()));
        assertEquals(0, bd("400").compareTo(fund.anchorBalance()));
        assertEquals(0, bd("100").compareTo(fund.anchorMonthly()));
        assertNull(fund.balanceUpdatedAt());
        assertEquals(0, bd("5000").compareTo(kid.firstYearTarget()));
        assertEquals(year + 1, kid.firstYearDueYear());
        assertEquals(6, kid.firstYearDueMonth());
    }

    @Test
    void updateReanchorsWhenTheFundIsSwitchedOff() {
        stubSave();
        storedGoal(service.create(user, kidsRequest(oneFundKid("400", "5000", year + 1), "400", "100")), 6L);

        KidsConfig off = fundGoal("0", "30000", false,
                fundKid("Baby", year + 1, acct("400", "100"), "5000", year + 1, 6));
        Goal updated = service.update(user, 6L, kidsRequest(off, "450", "100"));

        assertEquals(bd("450"), updated.getAnchorBalance());
        KidsConfig result = storedConfigOf(updated);
        assertNull(result.kids().get(0).accounts().firstYear());
        assertEquals(Boolean.FALSE, result.firstYearFund());
    }

    @Test
    void updateReanchorsWhenAFirstYearTargetOrDueDateChanges() {
        stubSave();
        storedGoal(service.create(user, kidsRequest(oneFundKid("400", "5000", year + 1), "400", "100")), 6L);

        Goal targetChanged = service.update(user, 6L, kidsRequest(oneFundKid("400", "6000", year + 1), "450", "100"));
        assertEquals(bd("450"), targetChanged.getAnchorBalance());

        Goal dueChanged = service.update(user, 6L, kidsRequest(oneFundKid("400", "6000", year + 2), "470", "100"));
        assertEquals(bd("470"), dueChanged.getAnchorBalance());
        assertEquals(year + 2, storedConfigOf(dueChanged).kids().get(0).firstYearDueYear());
    }

    @Test
    void kidsUpdateStampsOnlyTheChangedFirstYearBalance() {
        stubSave();
        Goal stored = storedGoal(
                service.create(user, kidsRequest(oneFundKid("400", "5000", year + 1), "400", "100")), 6L);
        var anchoredAt = stored.getPlanAnchoredAt();

        Goal same = service.update(user, 6L, kidsRequest(oneFundKid("400", "5000", year + 1), "400", "100"));
        assertNull(storedConfigOf(same).kids().get(0).accounts().firstYear().balanceUpdatedAt());

        Goal updated = service.update(user, 6L, kidsRequest(oneFundKid("650", "5000", year + 1), "650", "100"));

        KidsConfig.Accounts accounts = storedConfigOf(updated).kids().get(0).accounts();
        assertEquals(anchoredAt, updated.getPlanAnchoredAt());
        assertNull(accounts.plan529().balanceUpdatedAt());
        assertNotNull(accounts.firstYear().balanceUpdatedAt());
        assertEquals(0, bd("650").compareTo(accounts.firstYear().balance()));
        assertEquals(0, bd("400").compareTo(accounts.firstYear().anchorBalance()));
    }

    @Test
    void legacyStoredKidsGoalStillUpdates() {
        stubSave();
        String legacy = "{\"brokerageTarget\":50000,\"plan529Target\":80000,\"kids\":[{\"label\":\"Ava\","
                + "\"birthYear\":" + (year - 3) + ",\"birthMonth\":4,\"accounts\":{"
                + "\"brokerage\":{\"balance\":1000,\"balanceUpdatedAt\":null,\"anchorBalance\":900,\"anchorMonthly\":100},"
                + "\"plan529\":{\"balance\":2000,\"balanceUpdatedAt\":null,\"anchorBalance\":1800,\"anchorMonthly\":200}}}]}";
        Goal stored = rawStoredKidsGoal(11L, legacy);
        var anchoredAt = stored.getPlanAnchoredAt();
        KidsConfig sent = new KidsConfig(bd("50000"), bd("80000"), List.of(new KidsConfig.Kid("Ava", year - 3, 4,
                new KidsConfig.Accounts(acct("1000", "100"), acct("2500", "200"), null), null, null, null)), null, null);

        Goal updated = service.update(user, 11L, kidsRequest(sent, "3500", "300"));

        KidsConfig.Accounts accounts = storedConfigOf(updated).kids().get(0).accounts();
        assertEquals(anchoredAt, updated.getPlanAnchoredAt());
        assertEquals(0, bd("1800").compareTo(accounts.plan529().anchorBalance()));
        assertEquals(0, bd("2500").compareTo(accounts.plan529().balance()));
        assertNull(accounts.firstYear());
    }

    // ---- final review fixes ----

    private Goal rawStoredGoal(Long id, GoalType type, String icon, Object config, String balance) {
        Goal g = new Goal();
        g.setId(id);
        g.setUser(user);
        g.setType(type);
        g.setName("Stored");
        g.setIcon(icon);
        g.setConfigJson(codec.write(config));
        g.setCurrentBalance(bd(balance));
        g.setAnchorBalance(bd(balance));
        g.setAnchorMonthly(bd("300"));
        g.setPlanRate(bd("0.0400"));
        g.setPlanRateKind("cash");
        g.setPlanAnchoredAt(EasternTime.now().minusDays(400));
        when(goalRepository.findByIdAndUser(id, user)).thenReturn(Optional.of(g));
        return g;
    }

    private GoalRequest requestWith(GoalType type, String icon, Object config, String balance, boolean completed) {
        GoalRequest r = request(type, icon, config, balance, "300");
        r.setCompleted(completed);
        return r;
    }

    @Test
    void reopeningACompletedGoalIsRejectedForCoreUserWithAnotherActiveGoal() {
        Goal stored = rawStoredGoal(4L, GoalType.CUSTOM, "flag", new TargetConfig(bd("5000"), year + 2), "100");
        stored.setCompleted(true);
        user.setSelectedTier("core");
        when(goalRepository.countByUserAndCompletedFalse(user)).thenReturn(1L);

        assertThrows(TierLimitException.class, () -> service.update(user, 4L,
                requestWith(GoalType.CUSTOM, "flag", new TargetConfig(bd("5000"), year + 2), "100", false)));
        verify(goalRepository, never()).save(any());
    }

    @Test
    void reopeningACompletedGoalSucceedsWithNoOtherActiveGoal() {
        stubSave();
        Goal stored = rawStoredGoal(4L, GoalType.CUSTOM, "flag", new TargetConfig(bd("5000"), year + 2), "100");
        stored.setCompleted(true);
        user.setSelectedTier("core");
        when(goalRepository.countByUserAndCompletedFalse(user)).thenReturn(0L);

        Goal updated = service.update(user, 4L,
                requestWith(GoalType.CUSTOM, "flag", new TargetConfig(bd("5000"), year + 2), "100", false));
        assertEquals(false, updated.isCompleted());
    }

    @Test
    void reopeningACompletedGoalSucceedsForPlus() {
        stubSave();
        Goal stored = rawStoredGoal(4L, GoalType.CUSTOM, "flag", new TargetConfig(bd("5000"), year + 2), "100");
        stored.setCompleted(true);
        user.setSelectedTier("plus");

        Goal updated = service.update(user, 4L,
                requestWith(GoalType.CUSTOM, "flag", new TargetConfig(bd("5000"), year + 2), "100", false));
        assertEquals(false, updated.isCompleted());
    }

    @Test
    void storedGoalWithPastTargetYearStillUpdates() {
        stubSave();
        TargetConfig past = new TargetConfig(bd("5000"), year - 1);
        rawStoredGoal(12L, GoalType.CUSTOM, "flag", past, "1000");

        Goal balance = service.update(user, 12L, requestWith(GoalType.CUSTOM, "flag", past, "1500", false));
        assertEquals(bd("1500"), balance.getCurrentBalance());
        Goal done = service.update(user, 12L, requestWith(GoalType.CUSTOM, "flag", past, "1500", true));
        assertTrue(done.isCompleted());

        HouseCarConfig house = new HouseCarConfig(bd("30000"), 20, year - 1);
        rawStoredGoal(13L, GoalType.HOUSE, "home", house, "1000");
        assertNotNull(service.update(user, 13L, requestWith(GoalType.HOUSE, "home", house, "2000", false)));
    }

    @Test
    void changingATargetYearToAPastValueIsStillRejected() {
        rawStoredGoal(12L, GoalType.CUSTOM, "flag", new TargetConfig(bd("5000"), year - 1), "1000");

        assertThrows(GoalValidationException.class, () -> service.update(user, 12L,
                requestWith(GoalType.CUSTOM, "flag", new TargetConfig(bd("5000"), year - 2), "1000", false)));
    }

    @Test
    void storedKidTurning18ThisYearStillUpdates() {
        stubSave();
        KidsConfig stored = kids("50000", "30000",
                kid("Ava", year - 18, acct("1000", "100"), acct("2000", "200")));
        rawStoredKidsGoal(14L, codec.write(stored));

        KidsConfig sent = kids("50000", "30000",
                kid("Ava", year - 18, acct("1500", "100"), acct("2000", "200")));
        Goal updated = service.update(user, 14L, kidsRequest(sent, "3500", "300"));
        assertEquals(0, bd("1500").compareTo(storedConfigOf(updated).kids().get(0).accounts().brokerage().balance()));

        KidsConfig older = kids("50000", "30000",
                kid("Ava", year - 19, acct("1500", "100"), acct("2000", "200")));
        assertThrows(GoalValidationException.class, () -> service.update(user, 14L, kidsRequest(older, "3500", "300")));
    }

    @Test
    void storedVacationSavedYearIsAcceptedUnchanged() {
        stubSave();
        com.investingapp.backend.model.goals.VacationConfig stale =
                new com.investingapp.backend.model.goals.VacationConfig(2, bd("3000"), bd("500"), year - 3);
        rawStoredGoal(15L, GoalType.VACATION, "flight", stale, "0");

        assertNotNull(service.update(user, 15L, requestWith(GoalType.VACATION, "flight", stale, "0", false)));

        com.investingapp.backend.model.goals.VacationConfig changed =
                new com.investingapp.backend.model.goals.VacationConfig(2, bd("3000"), bd("500"), year - 4);
        assertThrows(GoalValidationException.class, () -> service.update(user, 15L,
                requestWith(GoalType.VACATION, "flight", changed, "0", false)));
    }

    @Test
    void importPicksCashForAShortHorizonAndMarketForALongOne() {
        stubSave();
        List<Goal> saved = service.importLegacy(user, importOf(
                legacy("a", "flag", "20000", year + 1, "2000"),
                legacy("b", "flag", "20000", year + 3, "2000")));

        assertEquals("cash", saved.get(0).getPlanRateKind());
        assertEquals(bd("0.0400"), saved.get(0).getPlanRate());
        assertEquals("market", saved.get(1).getPlanRateKind());
        assertEquals(bd("0.1000"), saved.get(1).getPlanRate());
    }

    @Test
    void importRoundsLegacyCentsToWholeDollarsAndCapsTheStart() {
        stubSave();
        List<Goal> saved = service.importLegacy(user, importOf(
                legacy("a", "flag", "25000.49", year + 3, "1200.50"),
                legacy("b", "flag", "25000", year + 3, "1000000000000")));

        TargetConfig config = (TargetConfig) codec.parse(GoalType.CUSTOM, saved.get(0).getConfigJson());
        assertEquals(0, bd("25000").compareTo(config.targetAmount()));
        assertEquals(0, bd("1201").compareTo(saved.get(0).getCurrentBalance()));
        assertEquals(0, bd("1201").compareTo(saved.get(0).getAnchorBalance()));
        assertEquals(0, bd("100000000").compareTo(saved.get(1).getCurrentBalance()));
    }

    @Test
    void importStopsAtFiftyImportedRows() {
        stubSave();
        when(goalRepository.countByUserAndClientIdIsNotNull(user)).thenReturn(49L);

        List<Goal> saved = service.importLegacy(user, importOf(
                legacy("a", "flag", "1000", year + 2, "0"),
                legacy("b", "flag", "1000", year + 2, "0"),
                legacy("c", "flag", "1000", year + 2, "0")));

        assertEquals(1, saved.size());
        assertEquals("a", saved.get(0).getClientId());
    }

    @Test
    void codecRejectsANullConfig() {
        assertThrows(GoalValidationException.class, () -> codec.parse(GoalType.CUSTOM, "null"));
        assertThrows(GoalValidationException.class,
                () -> codec.parse(GoalType.CUSTOM, com.fasterxml.jackson.databind.node.NullNode.getInstance()));
        assertThrows(GoalValidationException.class,
                () -> codec.parse(GoalType.CUSTOM, (com.fasterxml.jackson.databind.JsonNode) null));

        GoalRequest r = request(GoalType.CUSTOM, "flag", new TargetConfig(bd("5000"), year + 2), "0", "0");
        r.setConfig(null);
        assertThrows(GoalValidationException.class, () -> service.create(user, r));
    }

    @Test
    void kidsRejectANullKidElement() {
        KidsConfig config = new KidsConfig(bd("50000"), bd("30000"),
                java.util.Arrays.asList(kid("Sam", year - 3, acct("0", "0"), acct("0", "0")), null), null, null);

        GoalValidationException e = assertThrows(GoalValidationException.class,
                () -> service.create(user, kidsRequest(config, "0", "0")));
        assertEquals("Goal details are not in the expected shape.", e.getMessage());
    }

    @Test
    void monthlyAmountMustFitTheColumn() {
        stubSave();
        assertNotNull(service.create(user, houseRequest("400000", "0", "99999999.99")));
        assertThrows(GoalValidationException.class,
                () -> service.create(user, houseRequest("400000", "0", "100000000")));

        KidsConfig config = kids("50000", "30000",
                kid("Sam", year - 3, acct("0", "100000000"), acct("0", "0")));
        assertThrows(GoalValidationException.class, () -> service.create(user, kidsRequest(config, "0", "0")));
    }
}
