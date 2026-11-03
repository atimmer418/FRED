# Goals v3: Plans FRED Proposes, Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace projection-only goals with plans FRED proposes (monthly, per paycheck, kid rituals, milestones), stored on the backend, shown on a pushed goal page, with the old localStorage goals imported.

**Architecture:** All plan math is pure TypeScript in `utils/goal-plan.utils.ts`; the backend stores inputs plus a small plan anchor and never computes a plan. A rewritten `GoalsService` is an HTTP store exposing a signal. One standalone `GoalPage` serves setup (two steps) and edit. My-profile keeps a summary card that reads the signal.

**Tech Stack:** Spring Boot 3 (JPA, Flyway on MySQL, Jackson, JUnit 5 + Mockito, Gradle), Angular 19 standalone + Ionic (signals, HttpClient, Karma/Jasmine).

**Spec:** `docs/superpowers/specs/2026-11-02-goals-v3-plans-design.md`. Read it first; every number, label and rule below comes from it.

## Global Constraints

- No em dashes in any user-facing string (HTML, TS copy, toasts, error messages).
- Manrope, `material-symbols-outlined` icons, light-only, section-card patterns per `FREDdocs/FRED_UI_STYLE_GUIDE.md`.
- Never raw dollar amounts, free text or ids in analytics; new properties go in `DECLARED_PROPERTY_KEYS` and `FREDdocs/analytics-events.md` in the same commit (`FREDdocs/analytics-roadmap.md` rule 3).
- Backend money is `BigDecimal` scale 2; frontend money math rounds UP to whole dollars for proposed amounts.
- Ordinary annuity everywhere: grow, then post, end of month; `r = annualRate / 12`.
- Migration: `V9__goals.sql`, idempotent with `information_schema` guards; `ddl-auto=validate` must pass; never edit a run migration.
- `git add` named files only. `FREDdocs/backlog.md` is dirty with someone else's work in progress and must never be staged. Never `git checkout`, `restore` or `stash`. Never push.
- Valid AOT gate is `npx ng build --configuration dev` (not "development").
- Karma: `cd frontend && npx ng test --watch=false --include='<spec path>'` (type-checks the whole app, so unrelated compile errors surface here).
- Backend tests: `cd backend && ./gradlew test --tests '<FQCN>'`.
- Every commit message ends with:
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_016NWfpfB1UseViYRVCZvNQU
  ```

## Review Focus

1. A kid born in December of this year, viewed in January: n must be exactly 216 and the first birthday lump must fall in December of next year, not this month. Test in Task 7.
2. A user types a target of $999,999,999 or a year of 9999 on the goal page: the backend rejects with 400 and the page shows the inline message, never a NaN hero. Tests in Task 3 (bounds) and Task 12 (client clamp).
3. The user has no investment schedule (`currentFrequency === ''`): no per-paycheck line anywhere, and no "about $X a paycheck" equal to the monthly figure. Tests in Task 6 and Task 13.
4. The JWT expires between opening the goal page and tapping Start: the 401 path must keep the form state and surface the existing re-auth flow, not a silent nothing. Test in Task 13.
5. Two devices: device A creates a goal, device B still shows zero goals until it returns to my-profile. `ionViewWillEnter` refresh must bypass the TTL cache. Test in Task 14.

---

## Task 1: Backend typed goal configs and the JSON codec

**Files:**
- Create: `backend/src/main/java/com/investingapp/backend/model/GoalType.java`
- Create: `backend/src/main/java/com/investingapp/backend/model/goals/AccountState.java`
- Create: `backend/src/main/java/com/investingapp/backend/model/goals/KidsConfig.java`
- Create: `backend/src/main/java/com/investingapp/backend/model/goals/HouseCarConfig.java`
- Create: `backend/src/main/java/com/investingapp/backend/model/goals/VacationConfig.java`
- Create: `backend/src/main/java/com/investingapp/backend/model/goals/EmergencyFundConfig.java`
- Create: `backend/src/main/java/com/investingapp/backend/model/goals/TargetConfig.java`
- Create: `backend/src/main/java/com/investingapp/backend/model/goals/GoalConfigCodec.java`
- Create: `backend/src/main/java/com/investingapp/backend/service/GoalValidationException.java`
- Test: `backend/src/test/java/com/investingapp/backend/model/goals/GoalConfigCodecTest.java`

**Interfaces:**
- Produces: `enum GoalType { KIDS, HOUSE, VACATION, CAR, EMERGENCY_FUND, WEDDING, CUSTOM }`.
- Produces records (all fields `BigDecimal` for money, `Integer` for years/months/counts, `LocalDateTime` for timestamps, serialized ISO via jsr310):
  - `AccountState(BigDecimal balance, LocalDateTime balanceUpdatedAt, BigDecimal anchorBalance, BigDecimal anchorMonthly)`
  - `KidsConfig(BigDecimal brokerageTarget, BigDecimal plan529Target, List<Kid> kids)` with nested `record Kid(String label, Integer birthYear, Integer birthMonth, Accounts accounts)` and `record Accounts(AccountState brokerage, AccountState plan529)`
  - `HouseCarConfig(BigDecimal price, Integer downPaymentPct, Integer targetYear)` (`downPaymentPct` null = full price)
  - `VacationConfig(Integer tripsPerYear, BigDecimal budgetPerTrip, BigDecimal savedThisYear, Integer savedYear)`
  - `EmergencyFundConfig(BigDecimal monthlyExpenses, Integer months, Integer targetYear)`
  - `TargetConfig(BigDecimal targetAmount, Integer targetYear)`
- Produces `GoalConfigCodec` (`@Component`, constructor takes `ObjectMapper`): `Object parse(GoalType type, String json)` returns the record for the type or throws `GoalValidationException`; `String write(Object config)`. The mapper copy used inside enables `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES`.
- Produces `class GoalValidationException extends RuntimeException` with `(String message)`.

- [ ] **Step 1: Write the failing codec test**

```java
@Test void parsesKidsConfigRoundTrip() // write(parse(KIDS, json)) re-serializes to equal fields
@Test void rejectsUnknownProperty()    // {"targetAmount":1,"targetYear":2030,"extra":1} with CUSTOM -> GoalValidationException
@Test void rejectsWrongShapeForType()  // a KidsConfig json parsed as HOUSE -> GoalValidationException
@Test void houseAndCarShareOneRecord() // parse(HOUSE, json) and parse(CAR, json) both return HouseCarConfig
@Test void timestampsRoundTripAsIso()  // AccountState.balanceUpdatedAt "2026-11-02T10:15:30" survives write(parse())
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.model.goals.GoalConfigCodecTest'`
Expected: compilation failure (classes missing).

- [ ] **Step 3: Implement the enum, the records, the exception and the codec**

`parse` maps `HOUSE` and `CAR` to `HouseCarConfig`, `WEDDING` and `CUSTOM` to `TargetConfig`, each other type to its own record; wraps every `JsonProcessingException` in `GoalValidationException("Goal details are not in the expected shape.")`.

- [ ] **Step 4: Run the test to verify it passes**

Run: same command. Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/investingapp/backend/model/GoalType.java backend/src/main/java/com/investingapp/backend/model/goals backend/src/main/java/com/investingapp/backend/service/GoalValidationException.java backend/src/test/java/com/investingapp/backend/model/goals
git commit -m "feat(goals): typed goal configs and the JSON codec"
```

---

## Task 2: Migration V9, Goal entity, repository, and user deletion

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__goals.sql`
- Create: `backend/src/main/java/com/investingapp/backend/model/Goal.java`
- Create: `backend/src/main/java/com/investingapp/backend/repository/GoalRepository.java`
- Modify: `backend/src/main/java/com/investingapp/backend/service/UserDeletionService.java` (constructor + the delete block at lines ~137-159)
- Modify: `backend/src/test/java/com/investingapp/backend/service/UserDeletionServiceTest.java`
- Modify: `FREDdocs/handoff-paycheck-autopilot.md` section 5 (lines ~176-184)

**Interfaces:**
- Produces entity `Goal` (`@Entity @Table(name="goals")`, Lombok `@Data @NoArgsConstructor`): `Long id`; `@ManyToOne(fetch=LAZY) @JoinColumn(name="user_id", nullable=false) @JsonIgnore User user`; `String clientId` (`client_id`, length 36); `@Enumerated(STRING) GoalType type` (length 20); `String name` (50); `String icon` (30); `@Column(name="config", columnDefinition="TEXT", nullable=false) String configJson`; `BigDecimal currentBalance` (12,2); `LocalDateTime balanceUpdatedAt`; `BigDecimal anchorBalance` (12,2); `BigDecimal anchorMonthly` (10,2); `String planRateKind` (10); `LocalDateTime planAnchoredAt`; `boolean completed`; `int sortOrder`; `LocalDateTime createdAt, updatedAt` set in `@PrePersist`/`@PreUpdate` via `EasternTime.now()`.
- Produces `GoalRepository extends JpaRepository<Goal, Long>`: `List<Goal> findByUserOrderBySortOrderAscCreatedAtAsc(User)`, `Optional<Goal> findByIdAndUser(Long, User)`, `long countByUserAndCompletedFalse(User)`, `boolean existsByUserAndClientId(User, String)`, `void deleteAllByUser(User)`.

- [ ] **Step 1: Write the failing deletion test**

In `UserDeletionServiceTest`, add a `GoalRepository goalRepository = mock(...)` field, pass it to the constructor, and add:

```java
@Test @DisplayName("goals are deleted before the user row")
void deletesGoalsBeforeUser() {
    service.delete(user, DeletionReason.ACCOUNT_CLOSED);
    InOrder order = inOrder(goalRepository, userRepository);
    order.verify(goalRepository).deleteAllByUser(user);
    order.verify(userRepository).delete(user);
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.UserDeletionServiceTest'`
Expected: compilation failure (no `GoalRepository`, constructor arity).

- [ ] **Step 3: Write `V9__goals.sql`**

Copy the guard shape of `V8__analytics_person_deletions.sql` (`CREATE TABLE IF NOT EXISTS`, utf8mb4). Columns exactly as the spec's data model table: `id bigint AUTO_INCREMENT`, `user_id bigint NOT NULL`, `client_id varchar(36) NULL`, `type varchar(20) NOT NULL`, `name varchar(50) NOT NULL`, `icon varchar(30) NOT NULL`, `config text NOT NULL`, `current_balance decimal(12,2) NOT NULL DEFAULT 0`, `balance_updated_at datetime(6) NULL`, `anchor_balance decimal(12,2) NOT NULL DEFAULT 0`, `anchor_monthly decimal(10,2) NOT NULL DEFAULT 0`, `plan_rate_kind varchar(10) NOT NULL`, `plan_anchored_at datetime(6) NOT NULL`, `completed tinyint(1) NOT NULL DEFAULT 0`, `sort_order int NOT NULL DEFAULT 0`, `created_at datetime(6) NOT NULL`, `updated_at datetime(6) NOT NULL`; `PRIMARY KEY (id)`, `UNIQUE KEY uq_goals_user_client (user_id, client_id)`, `KEY ix_goals_user (user_id)`, `CONSTRAINT fk_goals_user FOREIGN KEY (user_id) REFERENCES users(id)`. Header comment: what the table is, why config is TEXT (house pattern, `ddl-auto=validate`), why no cascade (UserDeletionService deletes children explicitly).

- [ ] **Step 4: Implement `Goal`, `GoalRepository`, and the deletion change**

In `UserDeletionService.delete`, add `goalRepository.deleteAllByUser(user);` immediately before `userRepository.delete(user);` and inject `GoalRepository` through the constructor. Update the class javadoc list of purged tables.

- [ ] **Step 5: Run the deletion test and the whole backend suite**

Run: `cd backend && ./gradlew test`
Expected: all green, including the new test and `FrequencyFactorParityTest`.

- [ ] **Step 6: Boot the backend locally and confirm Flyway + validate**

Run: `cd backend && ./gradlew bootRun` (local profile), then in MySQL: `SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;`
Expected: version 9, success 1, and no `SchemaManagementException` in the boot log. Stop the server.

- [ ] **Step 7: Update the Autopilot handoff**

In `FREDdocs/handoff-paycheck-autopilot.md` section 5: add row `| V9__goals.sql | goals v3 (2026-11-02) |`, change the heading and the bold line to **V10**, keep the "check the directory first" warning.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V9__goals.sql backend/src/main/java/com/investingapp/backend/model/Goal.java backend/src/main/java/com/investingapp/backend/repository/GoalRepository.java backend/src/main/java/com/investingapp/backend/service/UserDeletionService.java backend/src/test/java/com/investingapp/backend/service/UserDeletionServiceTest.java FREDdocs/handoff-paycheck-autopilot.md
git commit -m "feat(goals): V9 goals table, entity, repository; deletion purges goals"
```

---

## Task 3: GoalService (validation, anchor rules, tier rule, import)

**Files:**
- Create: `backend/src/main/java/com/investingapp/backend/service/GoalService.java`
- Create: `backend/src/main/java/com/investingapp/backend/service/TierLimitException.java`
- Create: `backend/src/main/java/com/investingapp/backend/dto/GoalRequest.java`
- Create: `backend/src/main/java/com/investingapp/backend/dto/GoalImportRequest.java`
- Test: `backend/src/test/java/com/investingapp/backend/service/GoalServiceTest.java`

**Interfaces:**
- Consumes: Task 1 records and codec; Task 2 entity and repository.
- Produces `GoalRequest` (Lombok `@Data`, jakarta validation): `@NotNull GoalType type`; `@NotBlank @Size(max=50) String name`; `@NotBlank @Size(max=30) String icon`; `@NotNull JsonNode config`; `@NotNull @DecimalMin("0") BigDecimal currentBalance`; `boolean completed`; `@NotNull @DecimalMin("0") BigDecimal anchorMonthly`; `@NotNull @Pattern(regexp="market|cash") String planRateKind`; `boolean replan` (PUT only; ignored on POST).
- Produces `GoalImportRequest`: `@NotNull @Size(max=50) List<@Valid ImportedGoal> goals` with nested `@Data static class ImportedGoal { @NotBlank @Size(max=36) String clientId; @NotBlank @Size(max=50) String name; @NotBlank @Size(max=30) String icon; @NotNull BigDecimal targetAmount; @NotNull Integer targetYear; @NotNull BigDecimal startingValue; boolean completed; }`.
- Produces `TierLimitException extends RuntimeException`.
- Produces `GoalService` (`@Service @Transactional`): `List<Goal> list(User)`; `Goal create(User, GoalRequest)`; `Goal update(User, Long id, GoalRequest)`; `void delete(User, Long id)`; `List<Goal> importLegacy(User, GoalImportRequest)`. Not-found and ownership failures throw `java.util.NoSuchElementException`.

Validation rules (one private method per type, all throwing `GoalValidationException` with the spec's messages): amounts `0 < x <= 100_000_000`, scale <= 2; `targetYear` in `[currentYear, currentYear + 50]`; KIDS: 1..10 kids, label trimmed <= 30, `birthYear` null or in `[currentYear - 17, currentYear + 10]` (message "Already 18 or older. Use Something else with a target year instead." when below), `birthMonth` null or 1..12, at least one of the two targets > 0, each target >= 0; `downPaymentPct` null or 1..100; `tripsPerYear` 1..4; `months` 3 or 6; `savedYear` in `[currentYear - 1, currentYear]`; icon in `ICON_ALLOWLIST` map per type (KIDS: child_care, school, savings; HOUSE: home; CAR: directions_car; VACATION: flight; EMERGENCY_FUND: health_and_safety; WEDDING: favorite; CUSTOM: all of flag, balance, savings, home, school, directions_car, health_and_safety).

Anchor rules: on `create`, `anchorBalance = currentBalance`, `planAnchoredAt = EasternTime.now()`, `anchorMonthly` and `planRateKind` from the request, `balanceUpdatedAt = null`; for KIDS every `AccountState.anchorBalance = balance`, `balanceUpdatedAt = null`, `anchorMonthly` from the request config. On `update` with `replan = true`: same re-anchoring on the new values. On `update` with `replan = false`: keep stored `anchorBalance`, `anchorMonthly`, `planRateKind`, `planAnchoredAt`; if `currentBalance` differs from stored, set `balanceUpdatedAt = now`; for KIDS, per account, keep stored anchors and stamp `balanceUpdatedAt` only where that account's balance changed (match kids by index; a kid list whose length changed with `replan = false` is a `GoalValidationException`).

Tier rule in `create` only: `countByUserAndCompletedFalse(user) >= 1` and `!("plus".equals(tier) || "pro".equals(tier))` where `tier = user.getSelectedTier()` → `TierLimitException`. `importLegacy` skips the tier rule, skips entries whose `clientId` already exists for the user, maps each to `CUSTOM` with `TargetConfig`, `currentBalance = startingValue`, `balanceUpdatedAt = now`, `anchorBalance = startingValue`, `anchorMonthly = 0`, `planRateKind = "market"`, and runs inside the class transaction so one invalid entry rolls back all.

- [ ] **Step 1: Write the failing service tests**

`@ExtendWith(MockitoExtension.class)`, `@Mock GoalRepository`, real `GoalConfigCodec(new ObjectMapper().registerModule(new JavaTimeModule()))`, `@InjectMocks`-free manual construction, `save` stubbed with `thenAnswer(inv -> inv.getArgument(0))`.

```java
@Test void createAnchorsToCurrentBalanceAndNow()
@Test void createRejectsSecondActiveGoalForCoreUser()          // selectedTier "core", count 1 -> TierLimitException
@Test void createAllowsSecondGoalWhenFirstIsCompleted()         // countByUserAndCompletedFalse returns 0
@Test void createAllowsSecondGoalForPlus()
@Test void createFailsClosedOnNullTier()                        // selectedTier null, count 1 -> TierLimitException
@Test void kidsRequireAtLeastOneTarget()                        // both 0 -> GoalValidationException
@Test void kidsAllow529OnlyFamily()
@Test void kidsRejectBirthYearOfAnAdult()                       // currentYear - 18 -> message starts "Already 18 or older"
@Test void kidsRejectEleventhKid()
@Test void rejectsAmountAboveOneHundredMillion()
@Test void rejectsTargetYearBeyondFiftyYears()
@Test void rejectsIconNotInAllowlistForType()                   // HOUSE with icon "flight"
@Test void updateWithReplanReanchors()
@Test void updateWithoutReplanKeepsAnchorAndStampsBalanceDate() // balance changed -> balanceUpdatedAt non-null, anchor unchanged
@Test void updateWithoutReplanAndSameBalanceLeavesBalanceDateAlone()
@Test void kidsUpdateWithoutReplanStampsOnlyChangedAccount()
@Test void updateOfAnotherUsersGoalThrowsNoSuchElement()        // findByIdAndUser empty
@Test void importSkipsExistingClientIdsAndBypassesTier()        // core user, 3 goals, one existing -> 2 saved
@Test void importMapsLegacyFields()                             // CUSTOM, TargetConfig, balance = startingValue, balanceUpdatedAt set
```

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.GoalServiceTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement the DTOs, the exception and `GoalService`**

- [ ] **Step 4: Run the tests**

Run: same command. Expected: 19 PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/investingapp/backend/service/GoalService.java backend/src/main/java/com/investingapp/backend/service/TierLimitException.java backend/src/main/java/com/investingapp/backend/dto/GoalRequest.java backend/src/main/java/com/investingapp/backend/dto/GoalImportRequest.java backend/src/test/java/com/investingapp/backend/service/GoalServiceTest.java
git commit -m "feat(goals): GoalService with validation, anchor rules, tier limit and legacy import"
```

---

## Task 4: GoalController and response DTOs

**Files:**
- Create: `backend/src/main/java/com/investingapp/backend/controller/GoalController.java`
- Create: `backend/src/main/java/com/investingapp/backend/dto/GoalResponse.java`
- Create: `backend/src/main/java/com/investingapp/backend/dto/ErrorCodeResponse.java`
- Test: `backend/src/test/java/com/investingapp/backend/controller/GoalControllerTest.java`

**Interfaces:**
- Consumes: Task 3 service and request DTOs.
- Produces `ErrorCodeResponse` (Lombok `@Data @AllArgsConstructor`): `String code; String message;`.
- Produces `GoalResponse` (Lombok `@Data`, `public GoalResponse(Goal goal, GoalConfigCodec codec)`): `Long id; String clientId; GoalType type; String name; String icon; Object config` (the parsed record, so the client receives JSON, not a string); `BigDecimal currentBalance; LocalDateTime balanceUpdatedAt; BigDecimal anchorBalance; BigDecimal anchorMonthly; String planRateKind; LocalDateTime planAnchoredAt; boolean completed; int sortOrder; LocalDateTime createdAt; LocalDateTime updatedAt`.
- Produces `GoalController` at `@RequestMapping("/api/goals")`, `@CrossOrigin(origins="*", maxAge=3600)`, constructor `(GoalService, GoalConfigCodec, UserRepository)`, private `getCurrentUser()` copied verbatim from `InvestmentScheduleController`. Endpoints: `GET ""` → 200 `List<GoalResponse>`; `POST ""` `@Valid @RequestBody GoalRequest` → 201; `PUT "/{id}"` → 200; `DELETE "/{id}"` → 204; `POST "/import"` → 200 `List<GoalResponse>`. Mapping: null user → 401 `MessageResponse("User not authenticated")`; `GoalValidationException` → 400 `MessageResponse(message)`; `TierLimitException` → 403 `ErrorCodeResponse("TIER_LIMIT", "Piggy Plus unlocks more than one goal.")`; `NoSuchElementException` → 404 `MessageResponse("Goal not found")`; anything else → 500 `MessageResponse("Error saving goal")`, logged.

- [ ] **Step 1: Write the failing controller tests**

Principal set as in `MarketBreakdownControllerTest` (mock `UserDetailsImpl` id 7, `SecurityContextHolder`), service mocked, controller built with `new`.

```java
@Test void listReturns200WithGoals()
@Test void createReturns201()
@Test void createMapsTierLimitTo403WithCode()      // body is ErrorCodeResponse, code "TIER_LIMIT"
@Test void createMapsValidationTo400()
@Test void updateMapsNotFoundTo404()
@Test void deleteReturns204()
@Test void unauthenticatedReturns401()              // SecurityContextHolder.clearContext() first
@Test void importReturns200WithSavedGoals()
```

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.GoalControllerTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement the two DTOs and the controller**

- [ ] **Step 4: Run the controller tests, then the full backend suite**

Run: `cd backend && ./gradlew test`
Expected: all green.

- [ ] **Step 5: Smoke the API against the local backend**

Run `./gradlew bootRun`, obtain a JWT for the local test user (same way other curl smoke tests in `FREDdocs/api-endpoints` do), then: GET list (200 `[]`), POST a CUSTOM goal (201), POST a second as a core-tier user (403 with `"code":"TIER_LIMIT"`), PUT with another user's id (404), DELETE (204), POST import twice with the same clientId (second call returns the goals without duplicating; GET count unchanged).
Expected: each status as listed. Stop the server.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/investingapp/backend/controller/GoalController.java backend/src/main/java/com/investingapp/backend/dto/GoalResponse.java backend/src/main/java/com/investingapp/backend/dto/ErrorCodeResponse.java backend/src/test/java/com/investingapp/backend/controller/GoalControllerTest.java
git commit -m "feat(goals): /api/goals controller with typed error codes"
```

---

## Task 5: Per-paycheck conversion and the cash rate constant

**Files:**
- Modify: `frontend/src/app/utils/investment-frequency.utils.ts`
- Modify: `frontend/src/app/utils/investment-frequency.utils.spec.ts`
- Modify: `frontend/src/app/services/rate-assumptions.service.ts`

**Interfaces:**
- Produces `static toPerPaycheckEquivalent(monthly: number, frequency: string | null | undefined): number | null` on `InvestmentFrequencyUtils`: `null` when `parse(frequency)` is null; otherwise `monthly / (factorHundredths / 100)` rounded to cents HALF_UP using integer-cents arithmetic (mirror of backend `InvestmentFrequency.fromMonthly`). `monthly <= 0` → 0 (when frequency known).
- Produces `export const CASH_RATE = 0.04;` in `rate-assumptions.service.ts` with a one-line comment: the rate used for money needed within three years.

- [ ] **Step 1: Write the failing spec cases**

```ts
it('converts monthly to per paycheck with backend parity', () => {
  expect(InvestmentFrequencyUtils.toPerPaycheckEquivalent(433, 'WEEKLY')).toBe(100);
  expect(InvestmentFrequencyUtils.toPerPaycheckEquivalent(217, 'biweekly')).toBe(100);
  expect(InvestmentFrequencyUtils.toPerPaycheckEquivalent(500, 'SEMI_MONTHLY')).toBe(250);
  expect(InvestmentFrequencyUtils.toPerPaycheckEquivalent(500, 'monthly')).toBe(500);
});
it('rounds per paycheck to cents HALF_UP', () => {
  expect(InvestmentFrequencyUtils.toPerPaycheckEquivalent(417, 'BIWEEKLY')).toBe(192.17);
});
it('returns null for an unknown or empty frequency', () => {
  expect(InvestmentFrequencyUtils.toPerPaycheckEquivalent(417, '')).toBeNull();
  expect(InvestmentFrequencyUtils.toPerPaycheckEquivalent(417, null)).toBeNull();
  expect(InvestmentFrequencyUtils.toPerPaycheckEquivalent(417, 'QUARTERLY')).toBeNull();
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npx ng test --watch=false --include='src/app/utils/investment-frequency.utils.spec.ts'`
Expected: FAIL, `toPerPaycheckEquivalent is not a function`.

- [ ] **Step 3: Implement the method and add `CASH_RATE`**

- [ ] **Step 4: Run to verify pass**

Run: same command. Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/utils/investment-frequency.utils.ts frontend/src/app/utils/investment-frequency.utils.spec.ts frontend/src/app/services/rate-assumptions.service.ts
git commit -m "feat(goals): per-paycheck conversion with backend parity, CASH_RATE constant"
```

---

## Task 6: Goal plan math, core functions

**Files:**
- Create: `frontend/src/app/utils/goal-plan.utils.ts`
- Test: `frontend/src/app/utils/goal-plan.utils.spec.ts`

**Interfaces (all exported, pure, no Angular):**
- `interface LumpOccurrence { monthIndex: number; amount: number }` (monthIndex 1..n).
- `horizonMonths(now: Date, deadlineYear: number, deadlineMonth: number): number` — whole months from the first of the month after `now` through `(deadlineYear, deadlineMonth)` inclusive, floored at 0. `deadlineMonth` is 1..12.
- `requiredMonthly(pv: number, fv: number, n: number, annualRate: number): number` — spec formula; `n === 0` → 0; `annualRate === 0` → `(fv - pv) / n`; floor 0; `Math.ceil` to whole dollars.
- `futureValue(pv: number, monthly: number, n: number, annualRate: number, lumps: LumpOccurrence[]): number` — month-by-month, grow then post monthly and any lump at that index.
- `friendlyRound(x: number): number` — nearest $50 under 500, nearest $100 under 2,000, nearest $250 at or above.
- `interface KidLumpSchedule { birthday: number[]; newYear: number[] }`.
- `kidLumpSchedule(n: number, now: Date, birthYear: number | null, birthMonth: number | null): KidLumpSchedule` — birthday indices for every (birthYear + k, month) with k >= 1 that fall in 1..n, excluding index n itself; month = `birthMonth ?? 7`; null birthYear treated as `now.getFullYear()`; newYear = every January index in 1..n.
- `kidDeadline(now: Date, birthYear: number | null, birthMonth: number | null): { year: number; month: number; assumedBirthYear: boolean }` — `(birthYear ?? now.getFullYear()) + 18`, `birthMonth ?? 7`.

- [ ] **Step 1: Write the failing spec**

Use a fixed `NOW = new Date(2026, 10, 15)` (Nov 15 2026) in every case.

```ts
describe('horizonMonths', () => {
  it('counts whole months from next month through the deadline month', () => expect(horizonMonths(NOW, 2044, 11)).toBe(216));
  it('floors at zero when the deadline has passed', () => expect(horizonMonths(NOW, 2026, 10)).toBe(0));
  it('is 1 for next month', () => expect(horizonMonths(NOW, 2026, 12)).toBe(1));
});
describe('requiredMonthly', () => {
  it('pins the flat example: $250K over 216 months at 10% is $417', () => expect(requiredMonthly(0, 250000, 216, 0.10)).toBe(417));
  it('pins 217 months at $413', () => expect(requiredMonthly(0, 250000, 217, 0.10)).toBe(413));
  it('is linear at zero rate', () => expect(requiredMonthly(0, 1200, 12, 0)).toBe(100));
  it('returns 0 when n is 0', () => expect(requiredMonthly(0, 1000, 0, 0.10)).toBe(0));
  it('returns 0 when the balance already covers the target', () => expect(requiredMonthly(5000, 4000, 12, 0.10)).toBe(0));
});
describe('futureValue', () => {
  it('round-trips requiredMonthly to within a dollar before rounding', () => {
    const m = (250000) / (((1 + 0.10/12) ** 216 - 1) / (0.10/12));
    expect(Math.abs(futureValue(0, m, 216, 0.10, []) - 250000)).toBeLessThan(1);
  });
  it('reaches at least the target with the rounded monthly', () => expect(futureValue(0, 417, 216, 0.10, [])).toBeGreaterThanOrEqual(250000));
  it('posts a lump at its index after growth', () => expect(futureValue(0, 0, 2, 0.12, [{ monthIndex: 1, amount: 100 }])).toBeCloseTo(101, 2));
});
describe('friendlyRound', () => {
  it('rounds by band', () => { expect(friendlyRound(999)).toBe(1000); expect(friendlyRound(480)).toBe(500); expect(friendlyRound(2130)).toBe(2250); expect(friendlyRound(20)).toBe(0); });
});
describe('kidLumpSchedule', () => {
  it('starts birthdays the year after birth and never in the deadline month', () => {
    const s = kidLumpSchedule(217, NOW, 2026, 12);   // born Dec 2026, 18 in Dec 2044 = index 217
    expect(s.birthday[0]).toBe(13);                   // Dec 2027
    expect(s.birthday).not.toContain(217);
    expect(s.birthday.length).toBe(17);
  });
  it('places an unknown birth month in July', () => expect(kidLumpSchedule(212, NOW, 2026, null).birthday[0]).toBe(8));
  it('has no birthday lumps before a future birth year', () => expect(kidLumpSchedule(60, NOW, 2030, 5).birthday[0]).toBe(horizonMonths(NOW, 2031, 5)));
  it('lists every January', () => expect(kidLumpSchedule(24, NOW, 2026, 7).newYear).toEqual([2, 14]));
});
describe('kidDeadline', () => {
  it('assumes born this year when undecided', () => expect(kidDeadline(NOW, null, null)).toEqual({ year: 2044, month: 7, assumedBirthYear: true }));
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npx ng test --watch=false --include='src/app/utils/goal-plan.utils.spec.ts'`
Expected: FAIL (module not found).

- [ ] **Step 3: Implement the functions in `goal-plan.utils.ts`**

File header: one paragraph stating the ordinary-annuity convention and that this file is the only place goal math lives.

- [ ] **Step 4: Run to verify pass**

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/utils/goal-plan.utils.ts frontend/src/app/utils/goal-plan.utils.spec.ts
git commit -m "feat(goals): core plan math (PMT, future value, horizons, kid lump schedule)"
```

---

## Task 7: Goal plan math, proposals and status

**Files:**
- Modify: `frontend/src/app/utils/goal-plan.utils.ts`
- Modify: `frontend/src/app/utils/goal-plan.utils.spec.ts`

**Interfaces:**
- `RateKind` is imported from `models/goal.model.ts` (defined there, below); `const CASH_HORIZON_MONTHS = 36`; `const RITUAL_MIN_MONTHS = 24`; `const RITUAL_MIN_ANNUAL = 600`.
- `interface AccountPlan { n: number; rateKind: RateKind; rate: number; monthly: number; birthdayLump: number; newYearLump: number; lumps: LumpOccurrence[]; ritualsReason: 'short_horizon' | 'small_plan' | null; dueNow: number; deadline: { year: number; month: number }; assumedBirthYear: boolean; milestones: { at18: number; at22: number; at30: number } }`.
- `proposeKidAccountPlan(input: { target: number; balance: number; birthYear: number | null; birthMonth: number | null; marketRate: number; now: Date }): AccountPlan` — per spec section 5; `at18 = target`, `at22 = target * (1+r)^48`, `at30 = target * (1+r)^144` with `r = rate/12`.
- `interface SimplePlan { n: number; rateKind: RateKind; rate: number; monthly: number; target: number; dueNow: number; reaches: { year: number; month: number }; fromYou: number; fromGrowth: number }`.
- `proposeSimplePlan(input: { target: number; balance: number; targetYear: number; marketRate: number; forceCash: boolean; now: Date }): SimplePlan` — deadline December of `targetYear`; `rateKind = forceCash || n < 36 ? 'cash' : 'market'`; `fromYou = monthly * n`, `fromGrowth = target - balance - fromYou` floored 0.
- `simpleTarget(type: GoalType, config: GoalConfig): number` — HOUSE/CAR `price * (downPaymentPct ?? 100) / 100`; EMERGENCY `monthlyExpenses * months`; WEDDING/CUSTOM `targetAmount`.
- `interface VacationPlan { annual: number; monthly: number; savedThisYear: number; expectedSoFar: number; resetNeeded: boolean }`.
- `proposeVacationPlan(cfg: VacationConfig, now: Date): VacationPlan` — `annual = tripsPerYear * budgetPerTrip`, `monthly = ceil(annual / 12)`, `resetNeeded = cfg.savedYear !== now.getFullYear()`, `savedThisYear = resetNeeded ? 0 : cfg.savedThisYear`, `expectedSoFar = monthly * now.getMonth()` (months elapsed this year).
- `type PlanStatusKind = 'started' | 'ahead' | 'on_plan' | 'behind' | 'done'`; `interface PlanStatus { kind: PlanStatusKind; delta: number; asOf: string | null }`.
- `monthsBetween(fromIso: string, toIso: string): number` — whole calendar months from the month after `from` through the month of `to`, floored at 0 (same counting as `horizonMonths`).
- `planStatus(args: { completed: boolean; target: number; balance: number; anchorBalance: number; anchorMonthly: number; planAnchoredAt: string; balanceUpdatedAt: string | null; rate: number; lumps: LumpOccurrence[] }): PlanStatus` — `done` when completed or balance >= target; `started` when `balanceUpdatedAt` null; else expected = `futureValue(anchorBalance, anchorMonthly, monthsBetween(planAnchoredAt, balanceUpdatedAt), rate, lumpsWithinThoseMonths)`; `delta = balance - expected`; `ahead` if delta > 1% of target or > $50, `behind` if delta < −1% of target and < −$50, else `on_plan`; `asOf = balanceUpdatedAt`.
- The `GoalType` and config types are imported from `services/goals.service.ts` (Task 8); to avoid a cycle, define the config TYPES in a new `frontend/src/app/models/goal.model.ts` in THIS task and have Task 8 re-export them. Produces `goal.model.ts` with: `GoalType`, `GoalIcon`, `RateKind = 'market' | 'cash'`, `AccountState`, `KidInput`, `KidsConfig`, `HouseCarConfig`, `VacationConfig`, `EmergencyFundConfig`, `TargetConfig`, `GoalConfig`, `Goal`, `GoalWrite` (exact field lists in Task 8's Interfaces).

- [ ] **Step 1: Write the failing spec cases**

```ts
describe('proposeKidAccountPlan', () => {
  const base = { target: 250000, balance: 0, birthYear: 2026, birthMonth: 11, marketRate: 0.10, now: new Date(2026, 10, 15) };
  it('pins the ritual example: $296 a month, $1,000 birthday, $500 New Year', () => {
    const p = proposeKidAccountPlan(base);
    expect(p.n).toBe(216); expect(p.monthly).toBe(296); expect(p.birthdayLump).toBe(1000); expect(p.newYearLump).toBe(500);
    expect(futureValue(0, p.monthly, p.n, p.rate, p.lumps)).toBeGreaterThanOrEqual(250000);
  });
  it('pins milestones from the target', () => {
    const p = proposeKidAccountPlan(base);
    expect(p.milestones.at22).toBeCloseTo(372338.52, 0); expect(p.milestones.at30).toBeCloseTo(825912.24, 0);
  });
  it('keeps lumps at or under 35% of the yearly plan across horizons', () => {
    for (const n of [24, 36, 120, 216]) { /* vary birthYear so horizon ≈ n; assert (B+Y)/(12m+B+Y) <= 0.35 */ }
  });
  it('drops rituals under 24 months with a reason', () => {
    const p = proposeKidAccountPlan({ ...base, birthYear: 2010, birthMonth: 1 });
    expect(p.ritualsReason).toBe('short_horizon'); expect(p.birthdayLump).toBe(0); expect(p.newYearLump).toBe(0);
  });
  it('drops rituals for a small plan', () => expect(proposeKidAccountPlan({ ...base, target: 3000 }).ritualsReason).toBe('small_plan'));
  it('uses the cash rate under 36 months', () => expect(proposeKidAccountPlan({ ...base, birthYear: 2009, birthMonth: 6 }).rateKind).toBe('cash'));
  it('never displays a lump with zero occurrences', () => { /* horizon 30 with birthday after deadline month → birthdayLump 0 */ });
  it('handles n = 0 as due now', () => { const p = proposeKidAccountPlan({ ...base, birthYear: 2008, birthMonth: 10 }); expect(p.n).toBe(0); expect(p.monthly).toBe(0); expect(p.dueNow).toBe(250000); });
  it('flags an undecided birth year', () => expect(proposeKidAccountPlan({ ...base, birthYear: null, birthMonth: null }).assumedBirthYear).toBeTrue());
});
describe('proposeSimplePlan', () => {
  it('uses market at 36 months and cash at 35', () => { /* targetYear chosen so n = 36 vs 35 via now */ });
  it('forces cash for emergency funds', () => expect(proposeSimplePlan({ target: 12000, balance: 0, targetYear: 2032, marketRate: 0.10, forceCash: true, now: NOW }).rateKind).toBe('cash'));
  it('splits from you and from growth', () => { /* fromYou + fromGrowth + balance >= target */ });
});
describe('simpleTarget', () => {
  it('applies the down payment', () => expect(simpleTarget('HOUSE', { price: 1000000, downPaymentPct: 20, targetYear: 2032 })).toBe(200000));
  it('uses the full price when null', () => expect(simpleTarget('CAR', { price: 40000, downPaymentPct: null, targetYear: 2029 })).toBe(40000));
  it('multiplies emergency months', () => expect(simpleTarget('EMERGENCY_FUND', { monthlyExpenses: 4000, months: 6, targetYear: 2028 })).toBe(24000));
});
describe('proposeVacationPlan', () => {
  it('is ceil of trips times budget over 12', () => expect(proposeVacationPlan({ tripsPerYear: 4, budgetPerTrip: 2500, savedThisYear: 0, savedYear: 2026 }, NOW).monthly).toBe(834));
  it('resets saved so far in a new year', () => { const p = proposeVacationPlan({ tripsPerYear: 1, budgetPerTrip: 1200, savedThisYear: 900, savedYear: 2025 }, NOW); expect(p.resetNeeded).toBeTrue(); expect(p.savedThisYear).toBe(0); });
});
describe('planStatus', () => {
  it('is started until a balance is logged', () => expect(planStatus({ ...A, balanceUpdatedAt: null }).kind).toBe('started'));
  it('equals the anchor balance at the anchor date', () => { /* balanceUpdatedAt === planAnchoredAt, balance === anchorBalance → on_plan, delta 0 */ });
  it('is behind when the logged balance trails the plan', () => { /* 12 months later, balance 0, anchorMonthly 417 → behind, delta < 0 */ });
  it('is done at the target', () => expect(planStatus({ ...A, balance: A.target }).kind).toBe('done'));
});
```

- [ ] **Step 2: Run to verify failure**

Expected: FAIL (functions missing).

- [ ] **Step 3: Implement the proposals, status, and `models/goal.model.ts`**

The lump shrink loop in `proposeKidAccountPlan`: when `target - FV_lumps <= 0`, reduce `birthdayLump` by one friendly notch (the next lower value `friendlyRound` can produce), then `newYearLump`, alternating, at most 6 steps; if still non-positive, zero both and set `ritualsReason = 'small_plan'`.

- [ ] **Step 4: Run to verify pass**

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/utils/goal-plan.utils.ts frontend/src/app/utils/goal-plan.utils.spec.ts frontend/src/app/models/goal.model.ts
git commit -m "feat(goals): plan proposals, vacation plan and anchored status"
```

---

## Task 8: GoalsService rewrite as the backend-backed store

**Files:**
- Rewrite: `frontend/src/app/services/goals.service.ts`
- Create: `frontend/src/app/services/goals.service.spec.ts`

**Interfaces:**
- Re-exports everything from `models/goal.model.ts`, plus keeps `RebalanceSchedule`, `ProfilePrefs`, `getPrefs(userId)`, `savePrefs(userId, prefs)` unchanged (localStorage, `fred.profilePrefs.v2.<userId>`).
- `goal.model.ts` types (defined in Task 7):
  - `type GoalType = 'KIDS' | 'HOUSE' | 'VACATION' | 'CAR' | 'EMERGENCY_FUND' | 'WEDDING' | 'CUSTOM'`
  - `type GoalIcon = 'home' | 'health_and_safety' | 'flag' | 'balance' | 'savings' | 'school' | 'directions_car' | 'child_care' | 'flight' | 'favorite'`
  - `interface AccountState { balance: number; balanceUpdatedAt: string | null; anchorBalance: number; anchorMonthly: number }`
  - `interface KidInput { label: string; birthYear: number | null; birthMonth: number | null; accounts: { brokerage: AccountState; plan529: AccountState } }`
  - `interface KidsConfig { brokerageTarget: number; plan529Target: number; kids: KidInput[] }`
  - `interface HouseCarConfig { price: number; downPaymentPct: number | null; targetYear: number }`
  - `interface VacationConfig { tripsPerYear: 1 | 2 | 3 | 4; budgetPerTrip: number; savedThisYear: number; savedYear: number }`
  - `interface EmergencyFundConfig { monthlyExpenses: number; months: 3 | 6; targetYear: number }`
  - `interface TargetConfig { targetAmount: number; targetYear: number }`
  - `type GoalConfig = KidsConfig | HouseCarConfig | VacationConfig | EmergencyFundConfig | TargetConfig`
  - `interface Goal { id: number; clientId: string | null; type: GoalType; name: string; icon: GoalIcon; config: GoalConfig; currentBalance: number; balanceUpdatedAt: string | null; anchorBalance: number; anchorMonthly: number; planRateKind: RateKind; planAnchoredAt: string; completed: boolean; sortOrder: number; createdAt: string; updatedAt: string }`
  - `interface GoalWrite { type: GoalType; name: string; icon: GoalIcon; config: GoalConfig; currentBalance: number; completed: boolean; anchorMonthly: number; planRateKind: RateKind; replan?: boolean }`
  - `interface LegacyGoalV2 { id: string; name: string; icon: string; targetAmount: number; targetYear: number; startingValue: number; perPaycheckOutsideFRED: number; completed: boolean }`
- `GoalsService` (`providedIn: 'root'`, constructor `(HttpClient, DeviceIdService, AuthService)`):
  - `readonly goals = signal<Goal[]>([])`, `readonly loaded = signal(false)`
  - `refresh(): Observable<Goal[]>` — GET, bypasses TTL, single-flight, sets signals; no-op `of([])` when `localStorage.userId` is empty.
  - `load(): Observable<Goal[]>` — like refresh but honors a 60 s TTL per user.
  - `create(body: GoalWrite): Observable<Goal>`, `update(id: number, body: GoalWrite): Observable<Goal>`, `remove(id: number): Observable<void>`, `importLegacy(goals: LegacyGoalV2[]): Observable<Goal[]>` — each clears the cache and patches the signal on success.
  - `readLegacyV2(userId: string): LegacyGoalV2[]`, `clearLegacyV2(userId: string): void` — the old `fred.goals.v2.<userId>` reader, same tolerance as today.
  - `clearMemory(): void` — called on `authService.isLoggedIn$` false.
  - private `getAuthHeaders()` copied from `beneficiary.service.ts`; base url `${environment.backendApiUrl}/goals`.

- [ ] **Step 1: Write the failing spec**

`HttpClientTestingModule` + `HttpTestingController`; `AuthService` stub `{ isLoggedIn$: new BehaviorSubject(true) }`; `DeviceIdService` stub.

```ts
it('refresh GETs /goals with a bearer header and sets the signal')
it('load reuses the cache inside the TTL and refresh ignores it')
it('refresh is a no-op without a userId')
it('create POSTs and appends to the signal')
it('update PUTs and replaces the goal in the signal')
it('remove DELETEs and drops the goal')
it('importLegacy POSTs /goals/import')
it('readLegacyV2 tolerates corrupt storage')  // bad JSON → []
it('clears memory on logout')
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npx ng test --watch=false --include='src/app/services/goals.service.spec.ts'`
Expected: FAIL. Note: my-profile and the old sheet still import the old shape, so the whole-app type-check fails too; that is expected until Task 11 and is why Tasks 8 through 11 are committed together only when the app compiles again. Keep going.

- [ ] **Step 3: Rewrite the service**

Keep the file's prefs section byte-identical except for the moved type exports.

- [ ] **Step 4: Do NOT run the full karma yet; commit only when Task 11 compiles**

Proceed to Task 9.

---

## Task 9: Goal type picker sheet

**Files:**
- Create: `frontend/src/app/components/goal-type-picker-sheet/goal-type-picker-sheet.component.ts|html|scss|spec.ts`

**Interfaces:**
- Standalone component, `host: { class: 'ion-content-scroll-host' }`, `hostDirectives: [SheetDragDismissDirective]`, opened with `BOTTOM_SHEET_MODAL_OPTS`.
- Produces `export const GOAL_TYPE_TILES: ReadonlyArray<{ type: GoalType; icon: GoalIcon; title: string; line: string }>` in this order and with this copy:
  - KIDS, `child_care`, "Kids", "A 529 and a brokerage for each kid, with birthday and New Year rituals."
  - HOUSE, `home`, "House", "The down payment, planned to the month."
  - EMERGENCY_FUND, `health_and_safety`, "Emergency fund", "Three or six months of expenses, kept in cash."
  - CAR, `directions_car`, "Car", "Down payment or the full price."
  - VACATION, `flight`, "Vacation", "Trips a year and a budget each. We set the monthly aside."
  - WEDDING, `favorite`, "Wedding", "One date, one number, one plan."
  - CUSTOM, `flag`, "Something else", "Any amount by any year."
- Tapping a tile dismisses with role `'picked'` and data `GoalType`. Cancel dismisses with no role.
- Markup: `.gs-handle`, `h3` "What are you saving for?", a 2-column grid of tiles (`.gtp-tile` with icon tile, title 13.5px/800, line 11px/600 gray), `.gs-cancel`. Reuse the goal-add-sheet SCSS tokens (copy the handful needed; that file is deleted in Task 11).

- [ ] **Step 1: Write the failing spec**

```ts
it('renders seven tiles in the documented order')
it('dismisses with role picked and the type on tap')
it('has no em dash in any tile copy')   // regex /—/ over GOAL_TYPE_TILES
```

- [ ] **Step 2: Implement the component**

- [ ] **Step 3: Run its spec (expect compile errors from other files until Task 11; read the goal-type-picker results specifically)**

---

## Task 10: Goal page, setup inputs (step 1)

**Files:**
- Create: `frontend/src/app/pages/goal/goal.page.ts|html|scss|spec.ts`
- Create: `frontend/src/app/pages/goal/goal-form.model.ts`
- Modify: `frontend/src/app/app.routes.ts` (add two routes before `my-profile`)

**Interfaces:**
- Routes: `{ path: 'goals/new/:type', loadComponent: () => import('./pages/goal/goal.page').then(m => m.GoalPage) }` and `{ path: 'goals/:id', loadComponent: ... }`. No `data`.
- `goal-form.model.ts` produces `interface GoalForm { type: GoalType; name: string; icon: GoalIcon; kids: { brokerageTarget: number | null; plan529Target: number | null; kids: Array<{ label: string; birthYear: number | null; birthMonth: number | null; undecided: boolean }> }; price: number | null; downPaymentPct: number | null; fullPrice: boolean; targetYear: number | null; tripsPerYear: 1 | 2 | 3 | 4; budgetPerTrip: number | null; monthlyExpenses: number | null; months: 3 | 6; targetAmount: number | null }`, `defaultForm(type: GoalType, now: Date): GoalForm` (name defaults: "Our kids", "First home", "Emergency fund", "Next car", "Trips", "Wedding", ""; `targetYear` default now+5 for HOUSE/WEDDING/CUSTOM, now+3 for CAR, now+2 for EMERGENCY_FUND; kids starts with one kid labeled "Kid 1", undecided), `formToConfig(form: GoalForm, existing: Goal | null): GoalConfig` (carries over `AccountState`s from `existing` by kid index, new kids get zeroed states), `validateForm(form: GoalForm, now: Date): Partial<Record<keyof GoalForm | 'kidsTargets' | `kid${number}`, string>>` with the spec's messages and bounds (amounts 1..100,000,000; years currentYear..currentYear+50; birthYear currentYear−17..currentYear+10 with "Already 18 or older. Use Something else with a target year instead."; at least one kid target).
- `GoalPage` (standalone, `imports: [CommonModule, FormsModule, IonContent, IonHeader, KeyboardAvoidDirective]`): reads `route.snapshot.paramMap` for `type` or `id`; signals `mode: 'setup' | 'edit'`, `step: 1 | 2`, `form`, `errors`; `goBack()` → `navCtrl.navigateBack('/my-profile')`; `showPlan()` validates and sets step 2; kid stepper `addKid()`/`removeKid(i)` bounded 1..10; `toggleUndecided(i)`. Header from `theme/_settings-shell.scss` with title "New goal" / goal name.
- Entering setup with `goalsService.goals().filter(g => !g.completed).length >= 1 && !isPlusOrPro` → open the Plus nudge (same `McInfoSheetComponent` props as my-profile, title "Plan more than one goal with Piggy Plus", body "Kids, a house, a car, trips. Each gets its own plan, side by side.") and do not render the form; on dismiss navigate back. Tier read from `authService.userProgress$` `selectedTier`.
- Numeric inputs: `type="text" inputmode="numeric"`, digits-only sanitize, max 9 digits, `$` prefix span (`.gl-input-wrap`/`.gl-prefix`). Month picker: `<select>` with "Not sure" + 12 months; year: numeric input disabled while `undecided`.

- [ ] **Step 1: Write the failing spec cases**

```ts
describe('goal-form.model', () => {
  it('defaults one undecided kid for KIDS');
  it('rejects an adult birth year with the documented message');
  it('requires at least one kids target');
  it('clamps targetYear to fifty years out');
  it('carries over account states by kid index on edit');
});
describe('GoalPage setup', () => {
  it('reads the type from the route and starts on step 1');
  it('moves to step 2 only when the form validates');
  it('bounds the kid stepper at 1 and 10');
  it('opens the Plus nudge and skips the form for a core user with one active goal');
});
```

- [ ] **Step 2: Implement `goal-form.model.ts`, the page (step 1 markup and logic), and the routes**

Section order and copy per spec "Setup step 1": kids helper line "A 529 is a state college plan. The brokerage is a custodial or regular account. You open both outside FRED."; CTA "Show my plan"; down-payment chips "10%", "20%", "Full price"; emergency chips "3 months", "6 months"; trips chips "1", "2", "3", "4" labeled "trips a year".

- [ ] **Step 3: Continue to Task 11 before running the whole suite**

---

## Task 11: Goal page, plan view (step 2 / edit), save, and my-profile integration

**Files:**
- Modify: `frontend/src/app/pages/goal/goal.page.ts|html|scss|spec.ts`
- Create: `frontend/src/app/pages/goal/goal-plan.view-model.ts`
- Modify: `frontend/src/app/pages/my-profile/my-profile.page.ts|html|scss`
- Rewrite: `frontend/src/app/pages/my-profile/my-profile.page.spec.ts`, `my-profile.goals-render.spec.ts`, `my-profile.analytics.spec.ts` (goal-related parts)
- Delete: `frontend/src/app/components/goal-add-sheet/` (4 files)

**Interfaces:**
- `goal-plan.view-model.ts` produces `buildGoalPlanView(goal: { type; config; currentBalance; completed; anchor fields }, ctx: { marketRate: number; frequency: string; now: Date }): GoalPlanView` where `interface GoalPlanView { monthlyTotal: number; perPaycheck: number | null; rateKind: RateKind; rate: number; headline: string; subtitle: string; status: PlanStatus; progressPct: number; kids?: { account: 'plan529' | 'brokerage'; label: string; totals: { monthly: number; birthday: number; newYear: number }; perKid: Array<{ label: string; plan: AccountPlan; balance: number; relative: boolean }> }[]; simple?: SimplePlan; vacation?: VacationPlan; reasonLine: string | null }`. `subtitle` is the my-profile one-liner: KIDS `"$340/mo · 3 kids · 2044 to 2048"` (relative kids: `"18-year plans"`), VACATION `"$834/mo · 4 trips a year"`, HOUSE/CAR `"$1,310/mo · 20% down · 2032"` or `"· full price ·"`, others `"$X/mo · $T by YYYY"`. `headline` for simple goals: `"Reaches $200K in Dec 2032"`; compact money formatter `fmtCompact(n)` → `$372K`, `$1.18M`. Never emits "—".
- `GoalPage` additions: `selectedRate = signal<number>(rates.annualReturn())` (committed to `RateAssumptionsService.setRates(selectedRate(), rates.withdrawalRate())` only in `save()`); `view = computed(() => buildGoalPlanView(...))`; `save()` builds `GoalWrite` (`anchorMonthly = view().monthlyTotal`, `planRateKind = view().rateKind`, `replan = mode === 'edit' && planInputsChanged()`), POST or PUT, then `router.navigate(['/my-profile'])` with toast "Plan started" / "Plan updated"; on 403 with body `code === 'TIER_LIMIT'` keep state and open the nudge; on 401 keep state (the existing interceptor handles re-auth); on other errors toast "Could not save this goal. Try again." and `analytics.trackErrorShown(...)` as beneficiaries does. `markCompleted()` (PUT, `completed` toggled, `replan: false`), `confirmRemove()` opens an `AlertController` confirm ("Remove this goal?", "Its plan goes with it. This cannot be undone.", buttons Cancel / Remove) then DELETE. Balance tiles (`updateBalance(path, value)`) write a PUT with `replan: false` on blur.
- Plan view markup per spec "Setup step 2 / edit mode": hero `.gl-hero` (monthly large, per-paycheck line hidden when `perPaycheck === null`), chip row labeled "Return assumption, shared with your Freedom Date" reusing `RETURN_SCENARIOS` labels and `.fd-scenario-chips` styling plus "Reset to standard", or the cash label text from the spec; collapsed inputs summary row; kids account cards with "Show each kid"; relative copy and the "Every summer $B (becomes the birthday gift once they arrive)" label when `relative`; reason line "Under two years to go, so this one is a steady monthly amount" / "A small plan, so this one is a steady monthly amount"; simple goal card; vacation card with the reset line; footer `.gl-cta` "Start this plan" / "Save changes", `.gl-complete`, `.gl-remove`.
- my-profile changes: inject `GoalsService` (new shape) and `Router`; `readonly goalSummaries = computed(() => goalsService.goals().map(g => ({ goal: g, view: buildGoalPlanView(g, { marketRate: this.AVG_MARKET_YIELD, frequency: this.frequencySig(), now: new Date() }) })))` where `readonly frequencySig = signal('')` is set wherever `currentFrequency` is assigned (the plain field stays for the existing template); `trackGoal(_i, s) => s.goal.id`; `ionViewWillEnter() { this.goalsService.refresh().subscribe({ error: () => {} }); }`; `openGoal(id)` → `router.navigate(['/goals', id])`; `addGoal()` → tier check (active goals >= 1 && !isPlusOrPro → `openPlusNudge('goals')` with retitled copy) else open the picker sheet, on `'picked'` → `router.navigate(['/goals/new', type])`; remove `openAddGoalSheet`, `deleteGoal`, `openGoalsVeilNudge`, `_projectGoal`, `GoalProjection`, the veil markup, the `lockchip`, and the "—" placeholder in `freedomGoalSub` (use "not set"); add the v2 import in `_loadGoalsAndPrefs` (after `goalsService.load()` succeeds: `readLegacyV2`, if non-empty `importLegacy` then `clearLegacyV2`, toast "Your goals moved to your account. FRED built a plan for each one, tap to review."); disclaimer text per spec; row markup per spec (one-word chip from `status.kind`: Started / On plan / Ahead / Behind / Done, bar `progressPct`).

- [ ] **Step 1: Write the failing specs**

`goal.page.spec.ts` additions:
```ts
it('shows the plan hero after step 1 validates and recomputes on rate chip tap')
it('hides the per-paycheck line when there is no schedule')
it('writes the shared rate only on save')         // RateAssumptionsService.setRates not called on chip tap, called once on save
it('keeps the form and opens the nudge on a 403 TIER_LIMIT')
it('keeps the form on a 401')
it('sends replan true when a plan input changed and false for a balance-only edit')
it('renders relative copy for an undecided kid')  // "At 18", no four-digit year in the kid card
it('confirms before removing')
it('has no em dash in rendered text')
```
`my-profile.goals-render.spec.ts`: keep the same-DOM-node-after-25-CD-cycles assertion, now with `GoalsService` stubbed as `{ goals: signal([GOAL]), load: () => of([GOAL]), refresh: () => of([GOAL]), readLegacyV2: () => [], getPrefs: ... }`. Add `it('refreshes goals on ionViewWillEnter bypassing the cache')` and `it('shows Started, never Behind, for a goal with no logged balance')`.
`my-profile.page.spec.ts`: replace sheet-driven goal tests with `it('navigates to /goals/:id on row tap')`, `it('opens the Plus nudge for a core user with one active goal')`, `it('opens the picker for a plus user and navigates to /goals/new/:type')`, `it('imports v2 goals once and clears the local key')`.
`my-profile.analytics.spec.ts`: goal_changed assertions move to the goal page spec (Task 12) and are removed here.

- [ ] **Step 2: Implement the plan view, save paths, my-profile integration; delete `goal-add-sheet`**

- [ ] **Step 3: Run the four specs and then the whole suite**

Run: `cd frontend && npx ng test --watch=false --include='src/app/pages/goal/**/*.spec.ts' --include='src/app/pages/my-profile/*.spec.ts' --include='src/app/services/goals.service.spec.ts' --include='src/app/components/goal-type-picker-sheet/*.spec.ts' --include='src/app/utils/*.spec.ts'`
Expected: all PASS. Then `npx ng test --watch=false`: only the 14 pre-existing FRED-226 failures listed in `FREDdocs/analytics-roadmap.md` section 3(e), nothing new.

- [ ] **Step 4: AOT and lint gates**

Run: `cd frontend && npx ng build --configuration dev && npm run lint`
Expected: both exit 0.

- [ ] **Step 5: Commit Tasks 8 through 11 together (the app did not compile in between)**

```bash
git add frontend/src/app/models/goal.model.ts frontend/src/app/services/goals.service.ts frontend/src/app/services/goals.service.spec.ts frontend/src/app/components/goal-type-picker-sheet frontend/src/app/pages/goal frontend/src/app/app.routes.ts frontend/src/app/pages/my-profile
git rm -r frontend/src/app/components/goal-add-sheet
git commit -m "feat(goals): backend-backed GoalsService, type picker, goal page, my-profile summary card"
```

---

## Task 12: Analytics for goal changes

**Files:**
- Modify: `frontend/src/app/services/analytics/analytics-events.ts` (`GoalIcon`, `goal_changed`, `DECLARED_PROPERTY_KEYS`)
- Modify: `frontend/src/app/pages/goal/goal.page.ts` (+ spec)
- Modify: `FREDdocs/analytics-events.md` (line ~119 table row and the paragraph under it)

**Interfaces:**
- `goal_changed` props become `{ action: GoalAction; goal_type: GoalType; icon: GoalIcon; plan_rate: '8' | '10' | '12' | 'cash'; target_band: AmountBand; years_to_target: YearsBand; progress: PercentBand | null; goal_count: CountBand }`. Add `goal_type` and `plan_rate` to `DECLARED_PROPERTY_KEYS`. Widen the analytics `GoalIcon` to the ten icons. Import `GoalType` from `models/goal.model.ts`.
- `GoalPage` fires it after each successful create / update / completed / reopened / removed with bands only (`toAmountBand(target)`, `toYearsBand(deadlineYear - currentYear)`, `toPercentBand(progressPct)` or null, `toCountBand(goalsService.goals().length)`); `plan_rate` from `view().rateKind === 'cash' ? 'cash' : String(Math.round(rate * 100))`.

- [ ] **Step 1: Write the failing spec cases**

In `goal.page.spec.ts`: `it('tracks goal_changed with goal_type and plan_rate and no raw amounts')` asserting the exact props object for a created HOUSE goal, and `it('reports cash as the plan rate for an emergency fund')`.

- [ ] **Step 2: Implement; update the docs table and prose** (name the new properties, the ten icons, and that goals now live on the server so the save point is the goal page)

- [ ] **Step 3: Verify**

Run: `cd frontend && npm run test:events-documented && npx ng test --watch=false --include='src/app/pages/goal/*.spec.ts' --include='src/app/services/analytics/*.spec.ts'`
Expected: events-documented exits 0; specs PASS (including `analytics-forbidden-payload.spec.ts`).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/services/analytics/analytics-events.ts frontend/src/app/pages/goal FREDdocs/analytics-events.md
git commit -m "feat(goals): goal_changed carries goal_type and plan_rate"
```

---

## Task 13: Dev mock, backlog entry, and device verification

**Files:**
- Create: `frontend/src/app/services/dev/goals-mock.ts` (+ `goals-mock.spec.ts`)
- Modify: `frontend/src/app/services/goals.service.ts` (read the mock only when `environment.local`)
- Modify: `FREDdocs/backlog.md` via the `backlog-add` skill (new story; FRED-216 goals criteria superseded). Re-scan the max story number right before appending; this file is dirty with foreign WIP, so stage ONLY the hunks you added (`git add -p`) and verify with `git diff --cached`.

**Interfaces:**
- `goals-mock.ts`: `const MOCK_CHOICES: Array<'none' | 'one_of_each' | 'kids_only'>` (first uncommented wins, default `'none'` commented pattern as `tier-mock.ts`), `export const ACTIVE_GOALS_MOCK`, forced null under Karma, `export function mockGoals(now: Date): Goal[]` producing one sample per type with realistic anchors. `GoalsService.load()` returns the mock instead of GET when `environment.local && ACTIVE_GOALS_MOCK`.

- [ ] **Step 1: Spec**: `it('is null under karma')`, `it('produces one goal per type for one_of_each')`.
- [ ] **Step 2: Implement.**
- [ ] **Step 3: Device/browser pass** on `local.fredvested.com` (servlocal), tier mock `plus` then `core`, following the spec's Verification item 5 line by line. Record each result in the final report; anything failing is fixed before the commit.
- [ ] **Step 4: Backlog entry** via `backlog-add`.
- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/services/dev/goals-mock.ts frontend/src/app/services/dev/goals-mock.spec.ts frontend/src/app/services/goals.service.ts
git add -p FREDdocs/backlog.md   # only the new story hunks
git commit -m "chore(goals): local goals mock and backlog story"
```

---

## Final gate (before handing back)

- `cd backend && ./gradlew test` green.
- `cd frontend && npx ng build --configuration dev && npm run lint && npm run test:events-documented` all exit 0.
- `cd frontend && npx ng test --watch=false`: only the 14 known FRED-226 failures.
- `grep -rn $'—' frontend/src/app/pages/goal frontend/src/app/components/goal-type-picker-sheet frontend/src/app/pages/my-profile/*.html frontend/src/app/pages/my-profile/*.ts` returns nothing.
- `git status` shows `FREDdocs/backlog.md` still dirty with ONLY the foreign WIP (your story hunks committed), nothing else unstaged.
- Not pushed. Tell Andy: V9 taken by goals, Autopilot handoff updated to V10; `goal_changed` gained two properties (roadmap rule 8 asks that he be told).
