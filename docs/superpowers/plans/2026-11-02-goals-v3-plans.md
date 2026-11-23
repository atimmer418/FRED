# Goals v3: Plans FRED Proposes, Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace projection-only goals with plans FRED proposes (monthly, per paycheck, kid rituals, milestones), stored on the backend, shown on a pushed goal page, with the old localStorage goals imported.

**Architecture:** All plan math is pure TypeScript in `utils/goal-plan.utils.ts`; the backend stores inputs plus a small plan anchor and never computes a plan. A rewritten `GoalsService` is an HTTP store exposing a signal. One standalone `GoalPage` serves setup (two steps) and edit. My-profile keeps a summary card that reads the signal.

**Tech Stack:** Spring Boot 3 (JPA, Flyway on MySQL, Jackson, JUnit 5 + Mockito, Gradle), Angular 19 standalone + Ionic (signals, HttpClient, Karma/Jasmine).

**Spec:** `docs/superpowers/specs/2026-11-02-goals-v3-plans-design.md`. Read it first; every number, label and rule below comes from it. Three calls this plan makes that the spec left open are marked **(plan call)**: a numeric `plan_rate` is stored next to `plan_rate_kind`; the server decides re-anchoring by comparing plan inputs (no client flag); imported goals carry `anchor_monthly = 0` until first saved on the goal page.

## Global Constraints

- No em dashes in any user-facing string (HTML, TS copy, toasts, error messages).
- Manrope, `material-symbols-outlined` icons, light-only, section-card patterns per `FREDdocs/FRED_UI_STYLE_GUIDE.md`.
- Never raw dollar amounts, free text or ids in analytics; new properties go in `DECLARED_PROPERTY_KEYS` and `FREDdocs/analytics-events.md` in the same commit (`FREDdocs/analytics-roadmap.md` rule 3).
- Backend money is `BigDecimal` scale 2; frontend money math rounds UP to whole dollars for proposed amounts.
- Ordinary annuity everywhere: grow, then post, end of month; `r = annualRate / 12`.
- Horizon counting: index 1 is the month AFTER `now`; `n` runs through the deadline month inclusive.
- Every `LocalDateTime` that reaches JSON is an ISO string `yyyy-MM-dd'T'HH:mm:ss` (the app's `@Primary` ObjectMapper in `config/JacksonConfig.java` does not disable `WRITE_DATES_AS_TIMESTAMPS`, so annotate fields and configure the codec mapper).
- Migration: `V9__goals.sql`, idempotent with `CREATE TABLE IF NOT EXISTS`; booleans are `bit(1)` as in `V1__baseline.sql`; `ddl-auto=validate` must pass; never edit a run migration.
- `git add` named files only. `FREDdocs/backlog.md` is dirty with someone else's work in progress and must never be staged wholesale. Never `git checkout`, `restore` or `stash`. Never push.
- Valid AOT gate is `npx ng build --configuration dev` (not "development").
- Karma: `cd frontend && npx ng test --watch=false --include='<spec path>'` (type-checks the whole app, so unrelated compile errors surface here). Tasks 8, 9 and 10 are written without running karma or the build; the first green run is Task 11 Step 3.
- Backend tests: `cd backend && ./gradlew test --tests '<FQCN>'`.
- Every commit message ends with:
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_016NWfpfB1UseViYRVCZvNQU
  ```

## Review Focus

1. Born Dec 2026. Viewed Nov 15 2026: n = 217, first birthday index 13. Viewed Jan 15 2027: n = 215, first birthday index 11 (Dec 2027), and index 1 (Feb 2027) is never a birthday. Tests in Task 6.
2. A user types $999,999,999 or year 9999 on the goal page: client clamps to 9 digits and validation rejects with the documented message; the backend independently rejects with 400. Tests in Task 3 and Task 10.
3. The user has no investment schedule (`currentFrequency === ''`): no per-paycheck line anywhere, never a per-paycheck figure equal to the monthly figure. Tests in Task 5 and Task 11.
4. The JWT expires between opening the goal page and tapping Start: on 401 the form state is kept, a toast says the session ended, and `AuthService.logout()` runs (the house path to sign-in). Test in Task 11.
5. Device A creates a goal, device B returns to my-profile: `ionViewWillEnter` refresh bypasses the TTL cache and the new goal appears. Test in Task 11.

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
- Produces records (money `BigDecimal`, years/months/counts `Integer`, timestamps `LocalDateTime` annotated `@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")`, precedent `Beneficiary.java:125`):
  - `AccountState(BigDecimal balance, LocalDateTime balanceUpdatedAt, BigDecimal anchorBalance, BigDecimal anchorMonthly)`
  - `KidsConfig(BigDecimal brokerageTarget, BigDecimal plan529Target, List<Kid> kids)` with nested `record Kid(String label, Integer birthYear, Integer birthMonth, Accounts accounts)` and `record Accounts(AccountState brokerage, AccountState plan529)`
  - `HouseCarConfig(BigDecimal price, Integer downPaymentPct, Integer targetYear)` (`downPaymentPct` null = full price)
  - `VacationConfig(Integer tripsPerYear, BigDecimal budgetPerTrip, BigDecimal savedThisYear, Integer savedYear)`
  - `EmergencyFundConfig(BigDecimal monthlyExpenses, Integer months, Integer targetYear)`
  - `TargetConfig(BigDecimal targetAmount, Integer targetYear)`
- Produces `GoalConfigCodec` (`@Component`, constructor `(ObjectMapper appMapper)`; builds `mapper = appMapper.copy().registerModule(new JavaTimeModule()).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)`): `Object parse(GoalType type, String json)`; `Object parse(GoalType type, JsonNode node)` (via `treeToValue`); `String write(Object config)`. HOUSE and CAR map to `HouseCarConfig`; WEDDING and CUSTOM map to `TargetConfig`. Every `JsonProcessingException` becomes `GoalValidationException("Goal details are not in the expected shape.")`.
- Produces `class GoalValidationException extends RuntimeException` with `(String message)`.

- [ ] **Step 1: Write the failing codec test**

```java
@Test void parsesKidsConfigRoundTrip()        // write(parse(KIDS, json)) has equal fields
@Test void parsesFromJsonNodeToo()            // parse(CUSTOM, mapper.readTree(json)) equals parse(CUSTOM, json)
@Test void rejectsUnknownProperty()           // {"targetAmount":1,"targetYear":2030,"extra":1} with CUSTOM -> GoalValidationException
@Test void rejectsWrongShapeForType()         // KidsConfig json parsed as HOUSE -> GoalValidationException
@Test void houseAndCarShareOneRecord()        // both return HouseCarConfig
@Test void timestampsWriteAsIsoStrings()      // written JSON contains the literal "2026-11-02T10:15:30", never an array
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.model.goals.GoalConfigCodecTest'`
Expected: compilation failure (classes missing).

- [ ] **Step 3: Implement the enum, the records, the exception and the codec**

- [ ] **Step 4: Run the test to verify it passes**

Run: same command. Expected: 6 tests PASS.

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
- Modify: `backend/src/main/java/com/investingapp/backend/service/UserDeletionService.java` (constructor; the delete block ~lines 137-159; class javadoc list of purged tables)
- Modify: `backend/src/test/java/com/investingapp/backend/service/UserDeletionServiceTest.java`
- Modify: `FREDdocs/handoff-paycheck-autopilot.md` section 5 (~lines 176-184)

**Interfaces:**
- Produces entity `Goal` (`@Entity @Table(name="goals")`, Lombok `@Data @NoArgsConstructor`): `Long id` (IDENTITY); `@ManyToOne(fetch=LAZY) @JoinColumn(name="user_id", nullable=false) @JsonIgnore User user`; `@Column(name="client_id", length=36) String clientId`; `@Enumerated(STRING) @Column(length=20, nullable=false) GoalType type`; `String name` (50); `String icon` (30); `@Column(name="config", columnDefinition="TEXT", nullable=false) String configJson`; `BigDecimal currentBalance` (precision 12, scale 2); `LocalDateTime balanceUpdatedAt`; `BigDecimal anchorBalance` (12,2); `BigDecimal anchorMonthly` (10,2); `BigDecimal planRate` (precision 5, scale 4); `String planRateKind` (10); `LocalDateTime planAnchoredAt`; `boolean completed`; `int sortOrder`; `LocalDateTime createdAt, updatedAt` set in `@PrePersist`/`@PreUpdate` via `EasternTime.now()`.
- Produces `GoalRepository extends JpaRepository<Goal, Long>`: `List<Goal> findByUserOrderBySortOrderAscCreatedAtAsc(User)`, `Optional<Goal> findByIdAndUser(Long, User)`, `long countByUserAndCompletedFalse(User)`, `boolean existsByUserAndClientId(User, String)`, `void deleteAllByUser(User)`.

- [ ] **Step 1: Write the failing deletion test**

Add a `GoalRepository goalRepository = mock(GoalRepository.class)` field, pass it to the constructor, and add (the two-arg `delete(User, DeletionReason)` overload exists at `UserDeletionService.java:112`):

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
Expected: compilation failure.

- [ ] **Step 3: Write `V9__goals.sql`**

Copy the shape of `V8__analytics_person_deletions.sql`. Columns: `id bigint NOT NULL AUTO_INCREMENT`, `user_id bigint NOT NULL`, `client_id varchar(36) DEFAULT NULL`, `type varchar(20) NOT NULL`, `name varchar(50) NOT NULL`, `icon varchar(30) NOT NULL`, `config text NOT NULL`, `current_balance decimal(12,2) NOT NULL DEFAULT '0.00'`, `balance_updated_at datetime(6) DEFAULT NULL`, `anchor_balance decimal(12,2) NOT NULL DEFAULT '0.00'`, `anchor_monthly decimal(10,2) NOT NULL DEFAULT '0.00'`, `plan_rate decimal(5,4) NOT NULL`, `plan_rate_kind varchar(10) NOT NULL`, `plan_anchored_at datetime(6) NOT NULL`, `completed bit(1) NOT NULL DEFAULT b'0'`, `sort_order int NOT NULL DEFAULT '0'`, `created_at datetime(6) NOT NULL`, `updated_at datetime(6) NOT NULL`; `PRIMARY KEY (id)`, `UNIQUE KEY uq_goals_user_client (user_id, client_id)`, `KEY ix_goals_user (user_id)`, `CONSTRAINT fk_goals_user FOREIGN KEY (user_id) REFERENCES users (id)`. Header comment: what the table is, why `config` is TEXT (house pattern, `ddl-auto=validate`), why there is no cascade (UserDeletionService deletes children explicitly), and that `plan_rate` is the numeric rate the anchor was computed at.

- [ ] **Step 4: Implement `Goal`, `GoalRepository`, and the deletion change**

Add `goalRepository.deleteAllByUser(user);` immediately before `userRepository.delete(user);`; inject through the constructor.

- [ ] **Step 5: Run the deletion test and the whole backend suite**

Run: `cd backend && ./gradlew test`
Expected: all green.

- [ ] **Step 6: Boot the backend locally and confirm Flyway + validate**

Run the backend the way the local dev loop does (`cd backend && ./gradlew bootRun`; the default profile is local). In MySQL: `SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;`
Expected: version 9, success 1, no `SchemaManagementException` in the log. If validate complains about `completed`, the column type and the entity disagree; fix the SQL or the field, never `ddl-auto`. Stop the server.

- [ ] **Step 7: Update the Autopilot handoff**

Section 5: add the row `| V9__goals.sql | goals v3 (2026-11-02) |`, change the heading and the bold line to **V10**, keep the "check the directory first" warning.

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
- Consumes: Task 1 records and codec (JsonNode overload for requests, String overload for stored rows); Task 2 entity and repository.
- Produces `GoalRequest` (Lombok `@Data`, jakarta validation): `@NotNull GoalType type`; `@NotBlank @Size(max=50) String name`; `@NotBlank @Size(max=30) String icon`; `@NotNull JsonNode config`; `@NotNull @DecimalMin("0") BigDecimal currentBalance`; `boolean completed`; `@NotNull @DecimalMin("0") BigDecimal anchorMonthly`; `@NotNull @DecimalMin("0.0001") @DecimalMax("0.5") BigDecimal planRate`; `@NotNull @Pattern(regexp="market|cash") String planRateKind`.
- Produces `GoalImportRequest`: `@NotNull @Size(max=50) List<@Valid ImportedGoal> goals`; nested `@Data static class ImportedGoal { @NotBlank @Size(max=36) String clientId; @NotBlank @Size(max=50) String name; @NotBlank @Size(max=30) String icon; @NotNull BigDecimal targetAmount; @NotNull Integer targetYear; @NotNull BigDecimal startingValue; boolean completed; }`.
- Produces `TierLimitException extends RuntimeException`.
- Produces `GoalService` (`@Service @Transactional`): `List<Goal> list(User)`; `Goal create(User, GoalRequest)`; `Goal update(User, Long id, GoalRequest)`; `void delete(User, Long id)`; `List<Goal> importLegacy(User, GoalImportRequest)`. Not-found and ownership failures throw `java.util.NoSuchElementException`. `configJson` is ALWAYS `codec.write(parsedRecord)` after anchor mutation; the request node is never stored.

Validation (one private method per type, all `GoalValidationException` with the spec's messages): amounts `0 < x <= 100_000_000`, scale <= 2; `targetYear` in `[currentYear, currentYear + 50]`; KIDS: 1..10 kids, label trimmed <= 30, `birthYear` null or in `[currentYear - 17, currentYear + 10]` (message "Already 18 or older. Use Something else with a target year instead." when below), `birthMonth` null or 1..12, each target >= 0 and at least one > 0, each `AccountState.balance` in `[0, 100_000_000]`; `downPaymentPct` null or 1..100; `tripsPerYear` 1..4; `months` 3 or 6; `savedYear` in `[currentYear - 1, currentYear]`; `icon` in `ICON_ALLOWLIST` per type (KIDS: child_care, school, savings; HOUSE: home; CAR: directions_car; VACATION: flight; EMERGENCY_FUND: health_and_safety; WEDDING: favorite; CUSTOM: flag, balance, savings, home, school, directions_car, health_and_safety).

Anchor rules **(plan call: the server decides, there is no client flag)**:
- `create`: `anchorBalance = currentBalance`, `planAnchoredAt = EasternTime.now()`, `anchorMonthly`, `planRate`, `planRateKind` from the request, `balanceUpdatedAt = null`; KIDS: every `AccountState` becomes `(balance, null, balance, anchorMonthly as sent)`.
- `update`: load stored config; `planInputsChanged = ` per type: KIDS targets, kids count, any kid's label/birthYear/birthMonth; HOUSE/CAR price, downPaymentPct, targetYear; VACATION tripsPerYear, budgetPerTrip; EMERGENCY_FUND monthlyExpenses, months, targetYear; WEDDING/CUSTOM targetAmount, targetYear. `firstPlan = stored.anchorMonthly == 0 && request.anchorMonthly > 0`. If `planInputsChanged || firstPlan`: re-anchor exactly as `create` on the new values (KIDS per account: `anchorBalance = balance`, `anchorMonthly` as sent, `balanceUpdatedAt = null`). Else balance-only: keep stored `anchorBalance`, `anchorMonthly`, `planRate`, `planRateKind`, `planAnchoredAt`; if `currentBalance` differs set `balanceUpdatedAt = now`; KIDS per account (matched by index; a different kids count without `planInputsChanged` is impossible by definition): keep stored anchors, stamp `balanceUpdatedAt = now` only where that account's balance changed, keep the stored timestamp otherwise. `name`, `icon`, `completed` always update.
- Tier rule in `create` only: `countByUserAndCompletedFalse(user) >= 1 && !("plus".equals(tier) || "pro".equals(tier))` with `tier = user.getSelectedTier()` → `TierLimitException`.
- `importLegacy`: no tier rule; skip entries where `existsByUserAndClientId`; normalize BEFORE validation: `targetYear = max(targetYear, currentYear)`, `targetAmount` clamped to `[1, 100_000_000]`, `startingValue = max(0, startingValue)`, icon not in the CUSTOM allowlist → `flag`, name trimmed and truncated to 50; then build a CUSTOM goal with `TargetConfig`, `currentBalance = startingValue`, `balanceUpdatedAt = now`, `anchorBalance = startingValue`, `anchorMonthly = 0`, `planRate = 0.1000`, `planRateKind = "market"`, `clientId` set. All in the class transaction.

- [ ] **Step 1: Write the failing service tests**

`@ExtendWith(MockitoExtension.class)`, `@Mock GoalRepository`, real `GoalConfigCodec(new ObjectMapper().registerModule(new JavaTimeModule()))`, service built by hand, `save` stubbed `thenAnswer(inv -> inv.getArgument(0))`.

```java
@Test void createAnchorsToCurrentBalanceAndNow()
@Test void createStoresReserializedConfigNotTheRequestText()        // stored json equals codec.write(record)
@Test void createRejectsSecondActiveGoalForCoreUser()
@Test void createAllowsSecondGoalWhenFirstIsCompleted()
@Test void createAllowsSecondGoalForPlus()
@Test void createFailsClosedOnNullTier()
@Test void kidsRequireAtLeastOneTarget()
@Test void kidsAllow529OnlyFamily()
@Test void kidsRejectBirthYearOfAnAdult()                            // message starts "Already 18 or older"
@Test void kidsRejectEleventhKid()
@Test void kidsRejectNegativeAccountBalance()
@Test void kidsCreateCopiesAnchorMonthlyFromConfig()                 // each account's anchorMonthly == sent, anchorBalance == balance
@Test void rejectsAmountAboveOneHundredMillion()
@Test void rejectsTargetYearBeyondFiftyYears()
@Test void rejectsIconNotInAllowlistForType()                        // HOUSE with "flight"
@Test void updateReanchorsWhenAPlanInputChanges()                    // price changed -> anchorBalance = new currentBalance, planAnchoredAt moved
@Test void updateKeepsAnchorWhenOnlyBalancesChange()                 // balance changed -> balanceUpdatedAt set, anchor unchanged
@Test void updateWithSameBalanceLeavesBalanceDateAlone()
@Test void updateReanchorsAnImportedGoalOnFirstPlan()                // stored anchorMonthly 0, request 417 -> re-anchored
@Test void kidsUpdateStampsOnlyTheChangedAccount()
@Test void updateOfAnotherUsersGoalThrowsNoSuchElement()
@Test void importSkipsExistingClientIdsAndBypassesTier()             // core user, 3 goals, one existing -> 2 saved
@Test void importMapsLegacyFields()                                  // CUSTOM, TargetConfig, balance = startingValue, anchorMonthly 0, planRate 0.1000
@Test void importClampsPastTargetYearAndUnknownIcon()                // 2019 -> currentYear, "sparkle" -> "flag"
```

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.GoalServiceTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement the DTOs, the exception and `GoalService`**

- [ ] **Step 4: Run the tests**

Run: same command. Expected: 24 PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/investingapp/backend/service/GoalService.java backend/src/main/java/com/investingapp/backend/service/TierLimitException.java backend/src/main/java/com/investingapp/backend/dto/GoalRequest.java backend/src/main/java/com/investingapp/backend/dto/GoalImportRequest.java backend/src/test/java/com/investingapp/backend/service/GoalServiceTest.java
git commit -m "feat(goals): GoalService with validation, server-side anchor rules, tier limit and legacy import"
```

---

## Task 4: GoalController and response DTOs

**Files:**
- Create: `backend/src/main/java/com/investingapp/backend/controller/GoalController.java`
- Create: `backend/src/main/java/com/investingapp/backend/dto/GoalResponse.java`
- Create: `backend/src/main/java/com/investingapp/backend/dto/ErrorCodeResponse.java`
- Test: `backend/src/test/java/com/investingapp/backend/controller/GoalControllerTest.java`

**Interfaces:**
- Produces `ErrorCodeResponse` (Lombok `@Data @AllArgsConstructor`): `String code; String message;`.
- Produces `GoalResponse` (Lombok `@Data`, `public GoalResponse(Goal goal, GoalConfigCodec codec)`): `Long id; String clientId; GoalType type; String name; String icon; Object config` (the parsed record, so the wire form is JSON); `BigDecimal currentBalance; LocalDateTime balanceUpdatedAt; BigDecimal anchorBalance; BigDecimal anchorMonthly; BigDecimal planRate; String planRateKind; LocalDateTime planAnchoredAt; boolean completed; int sortOrder; LocalDateTime createdAt; LocalDateTime updatedAt`. Every `LocalDateTime` field carries `@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")`.
- Produces `GoalController` at `@RequestMapping("/api/goals")`, `@CrossOrigin(origins="*", maxAge=3600)`, constructor `(GoalService, GoalConfigCodec, UserRepository)`. Private `getCurrentUser()` has the same shape as `InvestmentScheduleController.getCurrentUser()` but guards the null context: `if (authentication == null || !(authentication.getPrincipal() instanceof UserDetailsImpl)) return null;`. Endpoints: `GET ""` → 200 `List<GoalResponse>`; `POST ""` `@Valid @RequestBody GoalRequest` → 201; `PUT "/{id}"` → 200; `DELETE "/{id}"` → 204; `POST "/import"` `@Valid @RequestBody GoalImportRequest` → 200 `List<GoalResponse>`. Mapping: null user → 401 `MessageResponse("User not authenticated")`; `GoalValidationException` → 400 `MessageResponse(message)`; `TierLimitException` → 403 `ErrorCodeResponse("TIER_LIMIT", "Piggy Plus unlocks more than one goal.")`; `NoSuchElementException` → 404 `MessageResponse("Goal not found")`; anything else → 500 `MessageResponse("Error saving goal")`, logged.

- [ ] **Step 1: Write the failing controller tests**

Principal as in `MarketBreakdownControllerTest` (mock `UserDetailsImpl` id 7); service mocked; controller built with `new`; one MockMvc standalone case for the JSON shape.

```java
@Test void listReturns200WithGoals()
@Test void createReturns201()
@Test void createMapsTierLimitTo403WithCode()        // body ErrorCodeResponse, code "TIER_LIMIT"
@Test void createMapsValidationTo400()
@Test void updateMapsNotFoundTo404()
@Test void deleteReturns204()
@Test void unauthenticatedReturns401()                // SecurityContextHolder.clearContext() first; must not be 500
@Test void importReturns200WithSavedGoals()
@Test void timestampsSerializeAsIsoStrings()          // MockMvc GET: jsonPath("$[0].planAnchoredAt").isString() and matches ^\d{4}-\d{2}-\d{2}T
```

- [ ] **Step 2: Run to verify failure**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.GoalControllerTest'`
Expected: compilation failure.

- [ ] **Step 3: Implement the two DTOs and the controller**

- [ ] **Step 4: Run the controller tests, then the full backend suite**

Run: `cd backend && ./gradlew test`
Expected: all green.

- [ ] **Step 5: Smoke the API against the local backend**

Start the backend (`cd backend && ./gradlew bootRun`) and the frontend (`servlocal`, the alias for `ionic serve --configuration=local`), sign in on `local.fredvested.com`, copy `localStorage.jwtToken` from devtools into `JWT`, then with `-H "Authorization: Bearer $JWT" -H "Content-Type: application/json"` against `https://local.fredvested.com/api/goals`:
1. GET → 200 `[]`.
2. POST a CUSTOM goal → 201; body shows `planAnchoredAt` as a quoted ISO string, not an array.
3. With the tier mock on `core`, POST a second → 403 `{"code":"TIER_LIMIT",...}`.
4. PUT the first with `completed: true`, then POST a second → 201 (completed frees the slot).
5. PUT `/goals/999999` → 404.
6. POST `/goals/import` with 3 entries as core → 200 with 3 goals; repeat → 200 and GET count unchanged.
7. DELETE one → 204.
8. Run the admin user-delete path on a throwaway local user that owns a goal (DevUserController) → no FK error.
Stop the servers.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/investingapp/backend/controller/GoalController.java backend/src/main/java/com/investingapp/backend/dto/GoalResponse.java backend/src/main/java/com/investingapp/backend/dto/ErrorCodeResponse.java backend/src/test/java/com/investingapp/backend/controller/GoalControllerTest.java
git commit -m "feat(goals): /api/goals controller with typed error codes"
```

---

## Task 5: Per-paycheck conversion, cash rate, exported return scenarios

**Files:**
- Modify: `frontend/src/app/utils/investment-frequency.utils.ts` (+ `.spec.ts`)
- Modify: `frontend/src/app/services/rate-assumptions.service.ts`

**Interfaces:**
- Produces `static toPerPaycheckEquivalent(monthly: number, frequency: string | null | undefined): number | null` on `InvestmentFrequencyUtils`: `null` when `parse(frequency)` is null; otherwise `monthly ÷ (factorHundredths / 100)` rounded to cents HALF_UP with integer-cents arithmetic (mirror of backend `InvestmentFrequency.fromMonthly`); `monthly <= 0` with a known frequency → 0. The per-paycheck shown in the UI is of the ROUNDED monthly ($417 → $192.17 biweekly); the spec's earlier $191.83 was the unrounded figure and is not what the UI shows.
- Produces in `rate-assumptions.service.ts`: `export const CASH_RATE = 0.04;` and `export const RETURN_SCENARIOS: ReadonlyArray<{ label: string; rate: number; preset: ReturnPreset }>` with the three entries copied from `freedom-detail-sheet.component.ts:199-203` (`'Conservative (8%)'`, `'Moderate (10%)'`, `'Optimistic (12%)'`). The freedom sheet keeps its own field untouched.

- [ ] **Step 1: Write the failing spec cases**

```ts
it('converts monthly to per paycheck with backend parity', () => {
  expect(InvestmentFrequencyUtils.toPerPaycheckEquivalent(433, 'WEEKLY')).toBe(100);
  expect(InvestmentFrequencyUtils.toPerPaycheckEquivalent(217, 'biweekly')).toBe(100);
  expect(InvestmentFrequencyUtils.toPerPaycheckEquivalent(500, 'SEMI_MONTHLY')).toBe(250);
  expect(InvestmentFrequencyUtils.toPerPaycheckEquivalent(500, 'monthly')).toBe(500);
});
it('rounds per paycheck to cents HALF_UP', () => expect(InvestmentFrequencyUtils.toPerPaycheckEquivalent(417, 'BIWEEKLY')).toBe(192.17));
it('returns null for an unknown or empty frequency', () => {
  expect(InvestmentFrequencyUtils.toPerPaycheckEquivalent(417, '')).toBeNull();
  expect(InvestmentFrequencyUtils.toPerPaycheckEquivalent(417, null)).toBeNull();
  expect(InvestmentFrequencyUtils.toPerPaycheckEquivalent(417, 'QUARTERLY')).toBeNull();
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npx ng test --watch=false --include='src/app/utils/investment-frequency.utils.spec.ts'`
Expected: FAIL, not a function.

- [ ] **Step 3: Implement the method, `CASH_RATE` and `RETURN_SCENARIOS`**

- [ ] **Step 4: Run to verify pass**

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/utils/investment-frequency.utils.ts frontend/src/app/utils/investment-frequency.utils.spec.ts frontend/src/app/services/rate-assumptions.service.ts
git commit -m "feat(goals): per-paycheck conversion with backend parity, CASH_RATE and RETURN_SCENARIOS"
```

---

## Task 6: Goal plan math, core functions

**Files:**
- Create: `frontend/src/app/models/goal.model.ts`
- Create: `frontend/src/app/utils/goal-plan.utils.ts`
- Test: `frontend/src/app/utils/goal-plan.utils.spec.ts`

**Interfaces:**
- `goal.model.ts` produces the shared types (no Angular imports):
  - `type GoalType = 'KIDS' | 'HOUSE' | 'VACATION' | 'CAR' | 'EMERGENCY_FUND' | 'WEDDING' | 'CUSTOM'`
  - `type GoalIcon = 'home' | 'health_and_safety' | 'flag' | 'balance' | 'savings' | 'school' | 'directions_car' | 'child_care' | 'flight' | 'favorite'`
  - `type RateKind = 'market' | 'cash'`
  - `interface AccountState { balance: number; balanceUpdatedAt: string | null; anchorBalance: number; anchorMonthly: number }`
  - `interface KidInput { label: string; birthYear: number | null; birthMonth: number | null; accounts: { brokerage: AccountState; plan529: AccountState } }`
  - `interface KidsConfig { brokerageTarget: number; plan529Target: number; kids: KidInput[] }`
  - `interface HouseCarConfig { price: number; downPaymentPct: number | null; targetYear: number }`
  - `interface VacationConfig { tripsPerYear: 1 | 2 | 3 | 4; budgetPerTrip: number; savedThisYear: number; savedYear: number }`
  - `interface EmergencyFundConfig { monthlyExpenses: number; months: 3 | 6; targetYear: number }`
  - `interface TargetConfig { targetAmount: number; targetYear: number }`
  - `type GoalConfig = KidsConfig | HouseCarConfig | VacationConfig | EmergencyFundConfig | TargetConfig`
  - `interface Goal { id: number; clientId: string | null; type: GoalType; name: string; icon: GoalIcon; config: GoalConfig; currentBalance: number; balanceUpdatedAt: string | null; anchorBalance: number; anchorMonthly: number; planRate: number; planRateKind: RateKind; planAnchoredAt: string; completed: boolean; sortOrder: number; createdAt: string; updatedAt: string }`
  - `interface GoalWrite { type: GoalType; name: string; icon: GoalIcon; config: GoalConfig; currentBalance: number; completed: boolean; anchorMonthly: number; planRate: number; planRateKind: RateKind }`
  - `interface LegacyGoalV2 { id: string; name: string; icon: string; targetAmount: number; targetYear: number; startingValue: number; perPaycheckOutsideFRED: number; completed: boolean }`
- `goal-plan.utils.ts` produces (pure; header paragraph states the ordinary-annuity convention and that this file is the only home of goal math):
  - `interface LumpOccurrence { monthIndex: number; amount: number }` (1..n)
  - `horizonMonths(now: Date, deadlineYear: number, deadlineMonth: number): number` (month 1..12; whole months from the month after `now` through the deadline month inclusive; floor 0)
  - `monthsBetween(fromIso: string, toIso: string): number` (same counting between two ISO timestamps, floor 0)
  - `requiredMonthly(pv: number, fv: number, n: number, annualRate: number): number` (n = 0 → 0; rate 0 → linear; floor 0; `Math.ceil`)
  - `futureValue(pv: number, monthly: number, n: number, annualRate: number, lumps: LumpOccurrence[]): number`
  - `friendlyRound(x: number): number` (nearest 50 under 500, nearest 100 under 2,000, nearest 250 above)
  - `interface KidLumpSchedule { birthday: number[]; newYear: number[] }`
  - `kidLumpSchedule(n: number, now: Date, birthYear: number | null, birthMonth: number | null): KidLumpSchedule` (birthdays from `birthYear + 1` on, in `birthMonth ?? 7`, indices in 1..n−1 only; `birthYear ?? now.getFullYear()`; newYear = every January index in 1..n)
  - `kidDeadline(now: Date, birthYear: number | null, birthMonth: number | null): { year: number; month: number; assumedBirthYear: boolean }`

- [ ] **Step 1: Write the failing spec**

`const NOW = new Date(2026, 10, 15); const JAN = new Date(2027, 0, 15);`

```ts
describe('horizonMonths', () => {
  it('counts whole months from next month through the deadline month', () => expect(horizonMonths(NOW, 2044, 11)).toBe(216));
  it('is 217 to December 2044 and 215 when viewed in January 2027', () => { expect(horizonMonths(NOW, 2044, 12)).toBe(217); expect(horizonMonths(JAN, 2044, 12)).toBe(215); });
  it('floors at zero when the deadline has passed', () => expect(horizonMonths(NOW, 2026, 10)).toBe(0));
  it('is 1 for next month', () => expect(horizonMonths(NOW, 2026, 12)).toBe(1));
});
describe('monthsBetween', () => {
  it('matches horizonMonths counting', () => expect(monthsBetween('2026-11-15T10:00:00', '2027-11-01T00:00:00')).toBe(12));
  it('floors at zero', () => expect(monthsBetween('2027-01-01T00:00:00', '2026-06-01T00:00:00')).toBe(0));
});
describe('requiredMonthly', () => {
  it('pins $250K over 216 months at 10% as $417', () => expect(requiredMonthly(0, 250000, 216, 0.10)).toBe(417));
  it('pins 217 months as $413', () => expect(requiredMonthly(0, 250000, 217, 0.10)).toBe(413));
  it('is linear at zero rate', () => expect(requiredMonthly(0, 1200, 12, 0)).toBe(100));
  it('returns 0 when n is 0', () => expect(requiredMonthly(0, 1000, 0, 0.10)).toBe(0));
  it('returns 0 when the balance already covers the target', () => expect(requiredMonthly(5000, 4000, 12, 0.10)).toBe(0));
});
describe('futureValue', () => {
  it('round-trips the unrounded payment to within a dollar', () => {
    const m = 250000 / (((1 + 0.10 / 12) ** 216 - 1) / (0.10 / 12));
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
    expect(s.birthday[0]).toBe(13);                  // Dec 2027
    expect(s.birthday).not.toContain(217);
    expect(s.birthday.length).toBe(17);
  });
  it('shifts correctly when viewed in January', () => { const s = kidLumpSchedule(215, JAN, 2026, 12); expect(s.birthday[0]).toBe(11); expect(s.birthday).not.toContain(1); });
  it('places an unknown birth month in July', () => expect(kidLumpSchedule(212, NOW, 2026, null).birthday[0]).toBe(8));
  it('has no birthday lumps before a future birth year', () => expect(kidLumpSchedule(60, NOW, 2030, 5).birthday[0]).toBe(horizonMonths(NOW, 2031, 5)));
  it('is empty when the only birthday would be the deadline month', () => expect(kidLumpSchedule(13, NOW, 2026, 12).birthday).toEqual([]));
  it('lists every January', () => expect(kidLumpSchedule(24, NOW, 2026, 7).newYear).toEqual([2, 14]));
});
describe('kidDeadline', () => {
  it('assumes born this year when undecided', () => expect(kidDeadline(NOW, null, null)).toEqual({ year: 2044, month: 7, assumedBirthYear: true }));
});
```

- [ ] **Step 2: Run to verify failure**

Run: `cd frontend && npx ng test --watch=false --include='src/app/utils/goal-plan.utils.spec.ts'`
Expected: FAIL (module not found).

- [ ] **Step 3: Implement `goal.model.ts` and the core functions**

- [ ] **Step 4: Run to verify pass**

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/models/goal.model.ts frontend/src/app/utils/goal-plan.utils.ts frontend/src/app/utils/goal-plan.utils.spec.ts
git commit -m "feat(goals): goal model types and core plan math"
```

---

## Task 7: Goal plan math, proposals and status

**Files:**
- Modify: `frontend/src/app/utils/goal-plan.utils.ts` (+ `.spec.ts`)

**Interfaces:**
- Constants: `CASH_HORIZON_MONTHS = 36`, `RITUAL_MIN_MONTHS = 24`, `RITUAL_MIN_ANNUAL = 600`, `STALE_BALANCE_DAYS = 60`. `CASH_RATE` imported from `rate-assumptions.service.ts`.
- `resolveRate(args: { n: number; forceCash: boolean; marketRate: number; rateKindOverride: RateKind | null }): { rateKind: RateKind; rate: number }` — `forceCash` → cash; else `rateKindOverride` when non-null; else `n < 36 ? cash : market`; `rate = cash ? CASH_RATE : marketRate`.
- `interface AccountPlan { n: number; rateKind: RateKind; rate: number; monthly: number; birthdayLump: number; newYearLump: number; lumps: LumpOccurrence[]; ritualsReason: 'short_horizon' | 'small_plan' | null; dueNow: number; deadline: { year: number; month: number }; assumedBirthYear: boolean; milestones: { at18: number; at22: number; at30: number } }`
- `proposeKidAccountPlan(input: { target: number; balance: number; birthYear: number | null; birthMonth: number | null; marketRate: number; rateKindOverride: RateKind | null; now: Date }): AccountPlan` per spec section 5. Lump shrink: when `target − FV_lumps <= 0`, lower `birthdayLump` then `newYearLump` by one friendly notch alternately, max 6 steps, else zero both with `ritualsReason = 'small_plan'`. A lump with zero occurrences is 0. `at18 = target`, `at22 = target × (1+r)^48`, `at30 = target × (1+r)^144`.
- `interface SimplePlan { n: number; rateKind: RateKind; rate: number; monthly: number; target: number; dueNow: number; reaches: { year: number; month: number }; fromYou: number; fromGrowth: number }`
- `proposeSimplePlan(input: { target: number; balance: number; targetYear: number; marketRate: number; forceCash: boolean; rateKindOverride: RateKind | null; now: Date }): SimplePlan` — deadline December of `targetYear`; `fromYou = monthly × n`; `fromGrowth = max(0, target − balance − fromYou)`.
- `simpleTarget(type: GoalType, config: GoalConfig): number` — HOUSE/CAR `price × (downPaymentPct ?? 100) / 100`; EMERGENCY_FUND `monthlyExpenses × months`; WEDDING/CUSTOM `targetAmount`; others 0.
- `interface VacationPlan { annual: number; monthly: number; savedThisYear: number; expectedSoFar: number; resetNeeded: boolean }`
- `proposeVacationPlan(cfg: VacationConfig, now: Date): VacationPlan` — `annual = tripsPerYear × budgetPerTrip`; `monthly = ceil(annual / 12)`; `resetNeeded = savedYear !== now.getFullYear()`; `savedThisYear = resetNeeded ? 0 : cfg.savedThisYear`; `expectedSoFar = monthly × now.getMonth()`.
- `type PlanStatusKind = 'started' | 'imported' | 'ahead' | 'on_plan' | 'behind' | 'done'`; `interface PlanStatus { kind: PlanStatusKind; delta: number; asOf: string | null; stale: boolean }`
- `planStatus(args: { completed: boolean; imported: boolean; target: number; balance: number; anchorBalance: number; anchorMonthly: number; planAnchoredAt: string; balanceUpdatedAt: string | null; rate: number; lumps: LumpOccurrence[]; now: Date }): PlanStatus` — `done` when completed or `balance >= target`; else `imported` when `args.imported` (caller passes `goal.clientId !== null && goal.anchorMonthly === 0`); else `started` when `balanceUpdatedAt` null; else `expected = futureValue(anchorBalance, anchorMonthly, monthsBetween(planAnchoredAt, balanceUpdatedAt), rate, lumps filtered to that range)`, `delta = balance − expected`, `tolerance = max(50, 0.01 × target)`, `ahead` if `delta > tolerance`, `behind` if `delta < −tolerance`, else `on_plan`; `asOf = balanceUpdatedAt`; `stale = balanceUpdatedAt !== null && now − balanceUpdatedAt > 60 days`.
- `vacationStatus(plan: VacationPlan, now: Date): PlanStatus` — never `done`; `started` when `savedThisYear === 0 && expectedSoFar === 0`; else same tolerance rule on `savedThisYear − expectedSoFar` with `target = annual`; `asOf = null`; `stale = false`.

- [ ] **Step 1: Write the failing spec cases**

```ts
describe('proposeKidAccountPlan', () => {
  const base = { target: 250000, balance: 0, birthYear: 2026, birthMonth: 11, marketRate: 0.10, rateKindOverride: null, now: NOW };
  it('pins the ritual example: $296 a month, $1,000 birthday, $500 New Year, FV $250,526', () => {
    const p = proposeKidAccountPlan(base);
    expect(p.n).toBe(216); expect(p.monthly).toBe(296); expect(p.birthdayLump).toBe(1000); expect(p.newYearLump).toBe(500);
    expect(Math.round(futureValue(0, p.monthly, p.n, p.rate, p.lumps))).toBe(250526);
  });
  it('pins milestones from the target', () => { const p = proposeKidAccountPlan(base); expect(p.milestones.at22).toBeCloseTo(372338.52, 0); expect(p.milestones.at30).toBeCloseTo(825912.24, 0); });
  it('keeps lumps at or under 35% of the yearly plan across horizons', () => { /* birthYear 2008..2026 with month 11 giving n in {24, 36, 120, 216}: (B+Y)/(12m+B+Y) <= 0.35 */ });
  it('drops rituals under 24 months with a reason', () => { const p = proposeKidAccountPlan({ ...base, birthYear: 2010, birthMonth: 1 }); expect(p.ritualsReason).toBe('short_horizon'); expect(p.birthdayLump).toBe(0); expect(p.newYearLump).toBe(0); });
  it('drops rituals for a small plan', () => expect(proposeKidAccountPlan({ ...base, target: 3000 }).ritualsReason).toBe('small_plan'));
  it('uses the cash rate under 36 months', () => expect(proposeKidAccountPlan({ ...base, birthYear: 2009, birthMonth: 6 }).rateKind).toBe('cash'));
  it('keeps a market plan at market when overridden', () => expect(proposeKidAccountPlan({ ...base, birthYear: 2009, birthMonth: 6, rateKindOverride: 'market' }).rateKind).toBe('market'));
  it('handles n = 0 as due now', () => { const p = proposeKidAccountPlan({ ...base, birthYear: 2008, birthMonth: 10 }); expect(p.n).toBe(0); expect(p.monthly).toBe(0); expect(p.dueNow).toBe(250000); });
  it('flags an undecided birth year', () => expect(proposeKidAccountPlan({ ...base, birthYear: null, birthMonth: null }).assumedBirthYear).toBeTrue());
});
describe('proposeSimplePlan', () => {
  const s = (targetYear: number, now: Date, extra = {}) => proposeSimplePlan({ target: 30000, balance: 0, targetYear, marketRate: 0.10, forceCash: false, rateKindOverride: null, now, ...extra });
  it('uses market at 36 months and cash at 35', () => { expect(s(2029, new Date(2026, 11, 15)).n).toBe(36); expect(s(2029, new Date(2026, 11, 15)).rateKind).toBe('market'); expect(s(2029, new Date(2027, 0, 15)).rateKind).toBe('cash'); });
  it('keeps the anchored kind until a re-plan', () => expect(s(2029, new Date(2027, 0, 15), { rateKindOverride: 'market' }).rateKind).toBe('market'));
  it('forces cash for emergency funds even when overridden to market', () => expect(s(2032, NOW, { forceCash: true, rateKindOverride: 'market' }).rateKind).toBe('cash'));
  it('splits from you and from growth', () => { const p = s(2036, NOW); expect(p.fromYou + p.fromGrowth).toBeGreaterThanOrEqual(30000); });
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
  const A = { completed: false, imported: false, target: 250000, balance: 0, anchorBalance: 0, anchorMonthly: 417, planAnchoredAt: '2026-11-15T10:00:00', balanceUpdatedAt: null as string | null, rate: 0.10, lumps: [], now: new Date(2027, 10, 20) };
  it('is started until a balance is logged', () => expect(planStatus(A).kind).toBe('started'));
  it('is imported for a never-planned import', () => expect(planStatus({ ...A, imported: true }).kind).toBe('imported'));
  it('is on plan at the anchor date with the anchor balance', () => expect(planStatus({ ...A, balanceUpdatedAt: A.planAnchoredAt }).kind).toBe('on_plan'));
  it('is behind when the logged balance trails the plan', () => { const r = planStatus({ ...A, balanceUpdatedAt: '2027-11-15T10:00:00' }); expect(r.kind).toBe('behind'); expect(r.delta).toBeLessThan(-5000); });
  it('uses one symmetric tolerance', () => { /* delta = +tolerance -> on_plan, +tolerance+1 -> ahead, -tolerance-1 -> behind */ });
  it('is done at the target', () => expect(planStatus({ ...A, balance: 250000 }).kind).toBe('done'));
  it('flags a stale balance after 60 days', () => expect(planStatus({ ...A, balanceUpdatedAt: '2027-08-01T10:00:00' }).stale).toBeTrue());
});
describe('vacationStatus', () => {
  it('never reads done', () => expect(vacationStatus({ annual: 10000, monthly: 834, savedThisYear: 10000, expectedSoFar: 8340, resetNeeded: false }, NOW).kind).toBe('ahead'));
  it('is started at the top of the year', () => expect(vacationStatus({ annual: 10000, monthly: 834, savedThisYear: 0, expectedSoFar: 0, resetNeeded: false }, new Date(2027, 0, 5)).kind).toBe('started'));
});
```

- [ ] **Step 2: Run to verify failure**

- [ ] **Step 3: Implement the proposals, `resolveRate`, `planStatus`, `vacationStatus`**

- [ ] **Step 4: Run to verify pass**

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/utils/goal-plan.utils.ts frontend/src/app/utils/goal-plan.utils.spec.ts
git commit -m "feat(goals): plan proposals, vacation plan and anchored status"
```

---

## Task 8: GoalsService rewrite as the backend-backed store

**Files:**
- Rewrite: `frontend/src/app/services/goals.service.ts`
- Create: `frontend/src/app/services/goals.service.spec.ts`

**Interfaces:**
- Re-exports every type from `models/goal.model.ts`; keeps `RebalanceSchedule`, `ProfilePrefs`, `getPrefs(userId)`, `savePrefs(userId, prefs)` byte-identical.
- `GoalsService` (`providedIn: 'root'`, constructor `(HttpClient, DeviceIdService, AuthService)`), base url `${environment.backendApiUrl}/goals`, private `getAuthHeaders()` copied from `beneficiary.service.ts`:
  - `readonly goals = signal<Goal[]>([])`, `readonly loaded = signal(false)`
  - `load(): Observable<Goal[]>` — GET with a 60 s per-user TTL and single flight (shape of `AuthService.getCurrentInvestmentSchedule`); `of([])` when `localStorage.userId` is empty.
  - `refresh(): Observable<Goal[]>` — same, ignoring the TTL.
  - `create(body: GoalWrite): Observable<Goal>`, `update(id: number, body: GoalWrite): Observable<Goal>`, `remove(id: number): Observable<void>`, `importLegacy(goals: LegacyGoalV2[]): Observable<Goal[]>` (POST `/import` with `{ goals: [{ clientId: g.id, name, icon, targetAmount, targetYear, startingValue, completed }] }`) — each clears the cache and patches the signal on success.
  - `readLegacyV2(userId: string): LegacyGoalV2[]`, `clearLegacyV2(userId: string): void` — key `fred.goals.v2.<userId>`, same tolerance as today's `getGoals`.
  - `clearMemory(): void`, wired to `authService.isLoggedIn$` false (as `mfu-store.service.ts:50-55`).
  - When `environment.local && ACTIVE_GOALS_MOCK` (Task 13) `load()` returns the mock instead of GET.

- [ ] **Step 1: Write the failing spec** (`HttpClientTestingModule`, `AuthService` stub `{ isLoggedIn$: new BehaviorSubject(true) }`)

```ts
it('load GETs /goals with a bearer header and sets the signal')
it('load reuses the cache inside the TTL and refresh ignores it')
it('load is a no-op without a userId')
it('create POSTs and appends to the signal')
it('update PUTs and replaces the goal in the signal')
it('remove DELETEs and drops the goal')
it('importLegacy POSTs /goals/import with clientId from the v2 id')
it('readLegacyV2 tolerates corrupt storage')
it('clears memory on logout')
```

- [ ] **Step 2: Rewrite the service.** Do not run karma (my-profile still imports the old shape). Continue to Task 9.

---

## Task 9: Goal type picker sheet and remove-confirm sheet

**Files:**
- Create: `frontend/src/app/components/goal-type-picker-sheet/goal-type-picker-sheet.component.ts|html|scss|spec.ts`
- Create: `frontend/src/app/components/goal-remove-confirm-sheet/goal-remove-confirm-sheet.component.ts|html|scss|spec.ts`

**Interfaces:**
- Both standalone, `host: { class: 'ion-content-scroll-host' }`, `hostDirectives: [SheetDragDismissDirective]`, opened with `BOTTOM_SHEET_MODAL_OPTS`, chrome copied from today's `goal-add-sheet.component.scss` (`.gs-handle`, `.gs-cta`, `.gs-cancel`, `.gs-remove` tokens; that component is deleted in Task 11).
- Picker produces `export const GOAL_TYPE_TILES: ReadonlyArray<{ type: GoalType; icon: GoalIcon; title: string; line: string }>` in this order with this copy:
  - KIDS, `child_care`, "Kids", "A 529 and a brokerage for each kid, with birthday and New Year rituals."
  - HOUSE, `home`, "House", "The down payment, planned to the month."
  - EMERGENCY_FUND, `health_and_safety`, "Emergency fund", "Three or six months of expenses, kept in cash."
  - CAR, `directions_car`, "Car", "Down payment or the full price."
  - VACATION, `flight`, "Vacation", "Trips a year and a budget each. We set the monthly aside."
  - WEDDING, `favorite`, "Wedding", "One date, one number, one plan."
  - CUSTOM, `flag`, "Something else", "Any amount by any year."
  Heading "What are you saving for?"; 2-column tile grid; tap → dismiss role `'picked'` with the `GoalType`; Cancel → no role.
- Remove-confirm: `@Input() goalName: string`; heading "Remove this goal?", body "Its plan goes with it. This cannot be undone.", buttons "Remove goal" (role `'remove'`) and "Keep it" (no role).

- [ ] **Step 1: Write the failing specs**

```ts
// picker
it('renders seven tiles in the documented order')
it('dismisses with role picked and the type on tap')
it('has no em dash in any tile copy')
// remove confirm
it('dismisses with role remove on the destructive button and no role on keep')
```

- [ ] **Step 2: Implement both components.** No karma run yet.

---

## Task 10: Goal page, setup inputs (step 1) and routing

**Files:**
- Create: `frontend/src/app/pages/goal/goal.page.ts|html|scss|spec.ts`
- Create: `frontend/src/app/pages/goal/goal-form.model.ts` (+ `.spec.ts`)
- Modify: `frontend/src/app/app.routes.ts` (two routes before `my-profile`)

**Interfaces:**
- Routes: `{ path: 'goals/new/:type', loadComponent: () => import('./pages/goal/goal.page').then(m => m.GoalPage) }` and `{ path: 'goals/:id', loadComponent: ... }`. No `data`. (Angular matches the literal `new` segment before `:id`, so order between the two is irrelevant.)
- `goal-form.model.ts` produces `interface GoalForm { type: GoalType; name: string; icon: GoalIcon; kids: { brokerageTarget: number | null; plan529Target: number | null; kids: Array<{ label: string; birthYear: number | null; birthMonth: number | null; undecided: boolean }> }; price: number | null; downPaymentPct: number | null; fullPrice: boolean; targetYear: number | null; tripsPerYear: 1 | 2 | 3 | 4; budgetPerTrip: number | null; monthlyExpenses: number | null; months: 3 | 6; targetAmount: number | null }`; `defaultForm(type: GoalType, now: Date): GoalForm` (names "Our kids", "First home", "Emergency fund", "Next car", "Trips", "Wedding", "My goal"; default icon = the type's single allowlisted icon, `flag` for CUSTOM; `targetYear` now+5 for HOUSE/WEDDING/CUSTOM, now+3 CAR, now+2 EMERGENCY_FUND; `downPaymentPct` 20; `tripsPerYear` 2; `months` 6; kids = one kid "Kid 1", undecided); `formFromGoal(goal: Goal): GoalForm`; `formToConfig(form: GoalForm, existing: Goal | null): GoalConfig` (KIDS carries `AccountState`s over by index from `existing`, new kids get `{ balance: 0, balanceUpdatedAt: null, anchorBalance: 0, anchorMonthly: 0 }`); `planInputsChanged(form: GoalForm, existing: Goal): boolean` (same field list as the server rule in Task 3); `validateForm(form: GoalForm, now: Date): Record<string, string>` keyed by field (`name`, `kidsTargets`, `kid.<i>.birthYear`, `price`, `targetYear`, `budgetPerTrip`, `monthlyExpenses`, `targetAmount`) with messages: "Give this goal a name." / "Enter at least one target for your kids." / "Already 18 or older. Use Something else with a target year instead." / "Enter an amount greater than $0." / `Enter a year from ${currentYear} to ${currentYear + 50}.` and amount cap 100,000,000 ("That is more than we can plan for. Try a smaller amount.").
- `GoalPage` (standalone; `imports: [CommonModule, FormsModule, IonContent, IonHeader, KeyboardAvoidDirective]`): on init read `paramMap`; `type` must be in the `GoalType` union else `navCtrl.navigateBack('/my-profile')`; `id` must pass `Number.isInteger(+id) && +id > 0` else back; if the goal is not in `goalsService.goals()`, `await firstValueFrom(goalsService.load())` and go back if still absent. Signals: `mode: 'setup' | 'edit'`, `step: 1 | 2`, `form`, `errors`. `goBack()` → `navCtrl.navigateBack('/my-profile')`. `showPlan()` validates then `step.set(2)`. Kid stepper `addKid()`/`removeKid(i)` bounded 1..10; `toggleUndecided(i)`. Setup-mode tier gate: active goals >= 1 and not Plus/Pro (tier from `authService.userProgress$` `selectedTier`) → open `McInfoSheetComponent` nudge (title "Plan more than one goal with Piggy Plus", body "Kids, a house, a car, trips. Each gets its own plan, side by side.", CTA "Upgrade to Piggy Plus", `targetTier: 'plus'`), render no form, navigate back on dismiss.
- Step 1 markup (one card): "Goal name" text input (max 50) for every type; icon row (the seven CUSTOM icons) for CUSTOM only; then per type: KIDS family targets "529 target per kid" and "Brokerage target per kid" (either may be empty) + helper "A 529 is a state college plan. The brokerage is a custodial or regular account. You open both outside FRED." + kid list (label, month `<select>` with "Not sure" + 12 months, year numeric input disabled while "Undecided" is checked, +/− stepper); HOUSE/CAR price + chips "10%", "20%", "Full price" + target year; VACATION chips "1", "2", "3", "4" under "Trips a year" + "Budget per trip"; EMERGENCY_FUND "Monthly expenses" + chips "3 months", "6 months" + target year; WEDDING/CUSTOM amount + year. CTA "Show my plan". Numeric inputs `type="text" inputmode="numeric"`, digits only, max 9 digits, `$` prefix span. Header from `theme/_settings-shell.scss` (title "New goal" or the goal name).

- [ ] **Step 1: Write the failing specs**

```ts
// goal-form.model.spec.ts
it('defaults one undecided kid for KIDS and a name for every type')
it('rejects an empty name')
it('rejects an adult birth year with the documented message')
it('requires at least one kids target')
it('rejects a year beyond fifty years out')
it('carries over account states by kid index on edit')
it('detects a plan-input change and ignores a name-only change')
// goal.page.spec.ts (setup)
it('reads the type from the route and starts on step 1')
it('navigates back for an unknown type or a non-numeric id')
it('loads goals before rendering a deep-linked goal')
it('moves to step 2 only when the form validates')
it('bounds the kid stepper at 1 and 10')
it('lets Something else pick an icon and hides the icon row for other types')
it('opens the Plus nudge and skips the form for a core user with one active goal')
```

- [ ] **Step 2: Implement `goal-form.model.ts`, the page (step 1), and the routes.** No karma run yet.

---

## Task 11: Goal page plan view, save paths, and my-profile integration

**Files:**
- Modify: `frontend/src/app/pages/goal/goal.page.ts|html|scss|spec.ts`
- Create: `frontend/src/app/pages/goal/goal-plan.view-model.ts` (+ `.spec.ts`)
- Modify: `frontend/src/app/pages/my-profile/my-profile.page.ts|html|scss`
- Rewrite goal parts of: `my-profile.page.spec.ts`, `my-profile.goals-render.spec.ts`, `my-profile.analytics.spec.ts`
- Modify: `frontend/src/app/services/analytics/analytics-events.ts` (ONLY widen `GoalIcon` to the ten icons; event changes are Task 12)
- Delete: `frontend/src/app/components/goal-add-sheet/` (4 files)

**Interfaces:**
- `goal-plan.view-model.ts` produces `buildGoalPlanView(goal: Pick<Goal, 'type' | 'config' | 'currentBalance' | 'completed' | 'clientId' | 'anchorBalance' | 'anchorMonthly' | 'planRate' | 'planRateKind' | 'planAnchoredAt' | 'balanceUpdatedAt'>, ctx: { marketRate: number; frequency: string; now: Date; rateKindOverride: RateKind | null }): GoalPlanView` with
  `interface GoalPlanView { monthlyTotal: number; perPaycheck: number | null; rateKind: RateKind; rate: number; headline: string; subtitle: string; status: PlanStatus; progressPct: number; relative: boolean; reasonLine: string | null; kids?: Array<{ account: 'plan529' | 'brokerage'; label: '529 plans' | 'Brokerage'; totals: { monthly: number; birthday: number; newYear: number }; perKid: Array<{ label: string; plan: AccountPlan; balance: number; relative: boolean }> }>; simple?: SimplePlan; vacation?: VacationPlan }`.
  Rules: `rateKindOverride` is passed straight into the proposals. `relative` per kid = `plan.assumedBirthYear || birthYear > now.getFullYear()`. KIDS: status via `planStatus` over sums (balance = Σ account balances; anchorBalance = Σ anchorBalances; anchorMonthly = Σ anchorMonthlys; target = kids.length × (plan529Target + brokerageTarget); lumps = concatenation of every account's schedule; `balanceUpdatedAt` = latest non-null account timestamp, or null); `progressPct` = balance ÷ target clamped 0..100. VACATION: `vacationStatus`, `progressPct = savedThisYear ÷ annual`. Others: `planStatus` with `currentBalance`, `progressPct = currentBalance ÷ target`. **Rate passed to status for every type = `goal.planRateKind === 'cash' ? CASH_RATE : goal.planRate`** (the stored numeric rate, never the live chip), so chip taps cannot move the status. `imported` = `goal.clientId !== null && goal.anchorMonthly === 0`. `perPaycheck = toPerPaycheckEquivalent(monthlyTotal, frequency)`.
  Copy: `subtitle` KIDS `"$340/mo · 3 kids · 2044 to 2048"` (all relative → `"$340/mo · 3 kids · 18-year plans"`); VACATION `"$834/mo · 4 trips a year"`; HOUSE/CAR `"$1,310/mo · 20% down · 2032"` or `"· full price ·"`; others `"$X/mo · $T by YYYY"`; imported → `"Plan updated, take a look"`. `headline` simple goals `"Reaches $200K in Dec 2032"`; `fmtCompact(n)` → `$372K`, `$1.18M`. `reasonLine`: `'short_horizon'` → "Under two years to go, so this one is a steady monthly amount"; `'small_plan'` → "A small plan, so this one is a steady monthly amount". Never emits "—".
- `GoalPage` additions: `selectedRate = signal(rates.annualReturn())` committed via `rates.setRates(selectedRate(), rates.withdrawalRate())` ONLY inside `save()`; `rateKindOverride = computed(() => mode() === 'edit' && !planInputsChanged(form(), goal) ? goal.planRateKind : null)`; `view = computed(() => buildGoalPlanView(draftGoal(), { marketRate: selectedRate(), frequency, now, rateKindOverride }))`. `save()`: build `GoalWrite` with `anchorMonthly = view().monthlyTotal`, `planRate = view().rate`, `planRateKind = view().rateKind`; for KIDS rebuild `config.kids[i].accounts.{plan529,brokerage}` so each `anchorMonthly = that account's AccountPlan.monthly` (balances and timestamps unchanged) and send `currentBalance: 0`; POST in setup, PUT in edit; then `router.navigate(['/my-profile'])` with toast "Plan started" / "Plan updated". Error paths: 403 with `code === 'TIER_LIMIT'` → keep state, open the nudge, no toast; 401 → keep state, toast "Your session ended. Sign in again to save this plan.", then `authService.logout()` (`auth.service.ts:197`); other → toast "Could not save this goal. Try again." and `analytics.trackErrorShown('save_failed', 'goals', 'toast', httpStatusOf(err), true)`. `markCompleted()` → PUT with `completed` toggled (hidden for VACATION). `confirmRemove()` → `GoalRemoveConfirmSheetComponent`; on role `'remove'` → DELETE, toast "Goal removed", navigate back. `updateBalance(path, value)` on blur → PUT; VACATION writes `config.savedThisYear = value` and `config.savedYear = now.getFullYear()`. For an `imported` goal the CTA reads "Start this plan" (its PUT carries the first real `anchorMonthly`, which the server treats as the first plan).
- Plan view markup: hero `.gl-hero` (monthly large; per-paycheck line `"about $X a paycheck"` hidden when null); chip row label "Return assumption, shared with your Freedom Date" with `RETURN_SCENARIOS` chips (`.fd-scenario-chips` styling copied into `goal.page.scss`) and "Reset to standard", or the cash label "Planned as cash at about 4%, like a high-yield savings account. Money you need within 3 years should stay out of the market."; collapsed inputs row `"3 kids · $100K 529 · $250K brokerage · Edit"`; KIDS two account cards ("529 plans", "Brokerage") each `"$X/mo, $B each birthday, $Y each New Year, all kids"` + "Show each kid" → per-kid card with rituals ("Every month $m", "Every birthday $B" or, when relative, "Every summer $B (becomes the birthday gift once they arrive)", "Every New Year $Y"), milestones 3-up ("At 18 / At 22 / At 30" when relative, else with years) in `fmtCompact`, and that account's balance tile; relative note "Undecided plans as if born this year, so you save a little more than you may need"; `reasonLine`; simple card (headline, "from you $S, from growth $G", balance tile, status "as of Mon YYYY", catch-up sentence "$X a month gets you back on plan" when behind); vacation card (reset line "New year, new trips. Saved so far resets, tap to update." when `resetNeeded`); "Update your balance" soft line when `status.stale`; footer `.gl-cta`, `.gl-complete`, `.gl-remove`.
- my-profile: `readonly frequencySig = signal('')` set wherever `currentFrequency` is assigned; `readonly goalSummaries = computed(() => this.goalsService.goals().map(g => ({ goal: g, view: buildGoalPlanView(g, { marketRate: this.AVG_MARKET_YIELD, frequency: this.frequencySig(), now: new Date(), rateKindOverride: g.planRateKind }) })))`; `trackGoal(_i, s) => s.goal.id`; `ionViewWillEnter() { this.goalsService.refresh().subscribe({ error: () => {} }); }`; `openGoal(id)` → `router.navigate(['/goals', id])`; `addGoal()` → active-goal tier check → `openPlusNudge('goals')` (title "Plan more than one goal with Piggy Plus", body as Task 10) else picker sheet → on `'picked'` `router.navigate(['/goals/new', type])`. Remove `openAddGoalSheet`, `deleteGoal`, `openGoalsVeilNudge`, `_projectGoal`, `GoalProjection`, the veil markup, `lockchip`, and the "—" in `freedomGoalSub` (use "not set"). Import in `_loadGoalsAndPrefs`: after `load()` succeeds, `readLegacyV2`; if non-empty and not already attempted this session (`private importAttempted = new Set<string>()` keyed by userId) → `importLegacy`; on 2xx `clearLegacyV2` and toast "Your goals moved to your account. FRED built a plan for each one, tap to review."; on error `trackErrorShown('load_failed', 'goals', 'silent', httpStatusOf(err), false)` and do not retry this session. Row: icon, name, `view.subtitle`, one-word chip (`started` → "Started", `imported` → "Plan updated", `on_plan` → "On plan", `ahead` → "Ahead", `behind` → "Behind", `done` → "Done"), bar `progressPct`. Disclaimer: "FRED invests only toward your Freedom Date. Goal plans are projections at the rate you choose. FRED never holds or moves goal money."

- [ ] **Step 1: Write the failing specs**

`goal-plan.view-model.spec.ts`:
```ts
it('aggregates kids accounts into one status and progress')
it('grades status with the stored plan rate, not the live chip')     // same view twice with marketRate 0.08 vs 0.12 → identical status
it('reads imported for a never-planned import')
it('builds each documented subtitle shape and never emits an em dash')
it('hides per paycheck without a frequency')
```
`goal.page.spec.ts` additions:
```ts
it('shows the plan hero after step 1 and recomputes on a rate chip tap')
it('writes the shared rate only on save')
it('writes each kid account anchorMonthly from the proposed plan on save')
it('keeps the form and opens the nudge on a 403 TIER_LIMIT')
it('on 401 keeps the form, toasts, and calls logout')
it('renders relative copy for an undecided kid')                      // "At 18", no four-digit year in the kid card
it('hides Mark completed for a vacation goal and writes savedYear on a balance edit')
it('prompts to update a balance logged more than 60 days ago')
it('confirms before removing')
it('has no em dash in rendered text')
```
`my-profile.goals-render.spec.ts`: stub `GoalsService` as `{ goals: signal([GOAL]), load: () => of([GOAL]), refresh: jasmine.createSpy().and.returnValue(of([GOAL])), readLegacyV2: () => [], getPrefs: ... }`; keep the same-DOM-node-after-25-CD-cycles assertion; add `it('refreshes goals on ionViewWillEnter')`, `it('shows Started, never Behind, for a goal with no logged balance')`, `it('shows Plan updated for an imported goal that has not been planned')`.
`my-profile.page.spec.ts`: `it('navigates to /goals/:id on row tap')`, `it('opens the Plus nudge for a core user with one active goal')`, `it('opens the picker for a plus user and navigates to /goals/new/:type')`, `it('imports v2 goals once, clears the key, and does not retry after a failure')`.
`my-profile.analytics.spec.ts`: remove the goal_changed cases (they move to Task 12).

- [ ] **Step 2: Implement the view model, plan view, save paths, my-profile integration; widen the analytics `GoalIcon`; delete `goal-add-sheet`**

- [ ] **Step 3: Run the new and touched specs, then the whole suite**

Run: `cd frontend && npx ng test --watch=false --include='src/app/pages/goal/*.spec.ts' --include='src/app/pages/my-profile/*.spec.ts' --include='src/app/services/goals.service.spec.ts' --include='src/app/components/goal-type-picker-sheet/*.spec.ts' --include='src/app/components/goal-remove-confirm-sheet/*.spec.ts' --include='src/app/utils/*.spec.ts'`
Expected: all PASS. Then `npx ng test --watch=false`: only the 14 pre-existing FRED-226 failures listed in `FREDdocs/analytics-roadmap.md` section 3(e), nothing new.

- [ ] **Step 4: AOT and lint gates**

Run: `cd frontend && npx ng build --configuration dev && npm run lint`
Expected: both exit 0.

- [ ] **Step 5: Commit Tasks 8 through 11 together (the app did not compile in between)**

```bash
git add frontend/src/app/services/goals.service.ts frontend/src/app/services/goals.service.spec.ts frontend/src/app/components/goal-type-picker-sheet frontend/src/app/components/goal-remove-confirm-sheet frontend/src/app/pages/goal frontend/src/app/app.routes.ts frontend/src/app/pages/my-profile frontend/src/app/services/analytics/analytics-events.ts
git rm -r frontend/src/app/components/goal-add-sheet
git commit -m "feat(goals): backend-backed GoalsService, type picker, goal page, my-profile summary card"
```

---

## Task 12: Analytics for goal changes

**Files:**
- Modify: `frontend/src/app/services/analytics/analytics-events.ts` (`goal_changed`, `DECLARED_PROPERTY_KEYS`)
- Modify: `frontend/src/app/pages/goal/goal.page.ts` (+ spec)
- Modify: `FREDdocs/analytics-events.md` (row ~119 and the paragraph under it)

**Interfaces:**
- `goal_changed` props: `{ action: GoalAction; goal_type: GoalType; icon: GoalIcon; plan_rate: '8' | '10' | '12' | 'cash'; target_band: AmountBand; years_to_target: YearsBand; progress: PercentBand | null; goal_count: CountBand }`. Add `goal_type` and `plan_rate` to `DECLARED_PROPERTY_KEYS`.
- `GoalPage` fires it after each successful create / update / completed / reopened / removed with bands only: `toAmountBand(target)`, `toYearsBand(deadlineYear − currentYear)`, `toPercentBand(progressPct)` or null, `toCountBand(goalsService.goals().length)`; `plan_rate = view().rateKind === 'cash' ? 'cash' : String(Math.round(view().rate * 100))`.

- [ ] **Step 1: Failing spec cases** in `goal.page.spec.ts`: `it('tracks goal_changed with goal_type and plan_rate and no raw amounts')` (exact props object for a created HOUSE goal) and `it('reports cash as the plan rate for an emergency fund')`.
- [ ] **Step 2: Implement; update the docs row and prose** (new properties, the ten icons, goals now live on the server so the save point is the goal page).
- [ ] **Step 3: Verify**: `cd frontend && npm run test:events-documented && npx ng test --watch=false --include='src/app/pages/goal/*.spec.ts' --include='src/app/services/analytics/*.spec.ts'` → exit 0, all PASS including `analytics-forbidden-payload.spec.ts`.
- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/services/analytics/analytics-events.ts frontend/src/app/pages/goal FREDdocs/analytics-events.md
git commit -m "feat(goals): goal_changed carries goal_type and plan_rate"
```

---

## Task 13: Dev mock, backlog entry, and device verification

**Files:**
- Create: `frontend/src/app/services/dev/goals-mock.ts` (+ `.spec.ts`)
- Modify: `frontend/src/app/services/goals.service.ts` (read the mock only when `environment.local`)
- Modify: `FREDdocs/backlog.md` via the `backlog-add` skill (new story; FRED-216 goals criteria superseded). Re-scan the max story number right before appending; stage ONLY your hunks with `git add -p` and check `git diff --cached`.

**Interfaces:**
- `goals-mock.ts`: `const MOCK_CHOICES: Array<'one_of_each' | 'kids_only'>` (all commented = off, first uncommented wins, as `tier-mock.ts`), `export const ACTIVE_GOALS_MOCK`, forced null under Karma, `export function mockGoals(now: Date): Goal[]` with one sample per type and realistic anchors.

- [ ] **Step 1: Spec**: `it('is null under karma')`, `it('produces one goal per type for one_of_each')`.
- [ ] **Step 2: Implement.**
- [ ] **Step 3: Device/browser pass** on `local.fredvested.com` (`servlocal`), tier mock `plus` then `core`, spec Verification item 5 line by line, plus a `prefers-reduced-motion` pass and a hardware-keyboard pass on the goal page. Record each result; fix before committing.
- [ ] **Step 4: Backlog entry** via `backlog-add`.
- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/services/dev/goals-mock.ts frontend/src/app/services/dev/goals-mock.spec.ts frontend/src/app/services/goals.service.ts
git add -p FREDdocs/backlog.md
git commit -m "chore(goals): local goals mock and backlog story"
```

---

## Final gate (before handing back)

- `cd backend && ./gradlew test` green.
- `cd frontend && npx ng build --configuration dev && npm run lint && npm run test:events-documented` all exit 0.
- `cd frontend && npx ng test --watch=false`: only the 14 known FRED-226 failures.
- `grep -rn $'—' frontend/src/app/pages/goal frontend/src/app/components/goal-type-picker-sheet frontend/src/app/components/goal-remove-confirm-sheet frontend/src/app/pages/my-profile/*.html frontend/src/app/pages/my-profile/*.ts` returns nothing.
- `git status` shows `FREDdocs/backlog.md` dirty with ONLY the foreign WIP, nothing else unstaged.
- Not pushed. Tell Andy: V9 taken by goals and the Autopilot handoff now says V10; `goal_changed` gained `goal_type` and `plan_rate` (roadmap rule 8); the three plan calls in the header.
