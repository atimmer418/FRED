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
        // Round-trip through text like a real HTTP body; valueToTree would turn 400000 into 4E+5.
        try {
            r.setConfig(mapper.readTree(mapper.writeValueAsString(config)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
        return new KidsConfig.Kid(label, birthYear, 6, new KidsConfig.Accounts(brokerage, plan529));
    }

    private KidsConfig kids(String brokerageTarget, String plan529Target, KidsConfig.Kid... kids) {
        return new KidsConfig(bd(brokerageTarget), bd(plan529Target), List.of(kids));
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
    void createStoresReserializedConfigNotTheRequestText() {
        stubSave();
        HouseCarConfig config = new HouseCarConfig(bd("400000"), 20, year + 5);
        Goal goal = service.create(user, houseRequest("400000", "0", "0"));

        assertEquals(codec.write(config), goal.getConfigJson());
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
    void kidsRejectEleventhKid() {
        List<KidsConfig.Kid> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(kid("Kid" + i, year - 2, acct("0", "0"), acct("0", "0")));
        }
        KidsConfig config = new KidsConfig(bd("1000"), bd("1000"), eleven);

        assertThrows(GoalValidationException.class, () -> service.create(user, kidsRequest(config, "0", "0")));
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
}
