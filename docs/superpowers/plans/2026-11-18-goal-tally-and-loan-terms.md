# Goal Tally and Loan Terms Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FRED credits one paycheck's share to every goal unit on each payday, the user corrects it with Missed one / Doubled up taps, status reads off those taps; the house quiz offers 5/10/20% down with a mortgage line at a user-set rate and the car quiz a dollar down payment with a loan line.

**Architecture:** The backend owns the tally: a `goal_ledger_entries` table, a pure `PaydayCalendar` built from the investment schedule's rule fields, and a `GoalLedgerService` that rolls every goal forward on each read and applies adjustments through `POST /api/goals/{id}/ledger`. The frontend stops grading typed balances: `tallyStatus` reads the server's per-unit `net`, the hero re-solves from the plan's value, and two pills under Saved so far write adjustments. The quiz gains sourced loan-rate constants, a `loanPayment` helper and new `HouseCarConfig` fields.

**Tech Stack:** Spring Boot 3 (JPA, Flyway, JUnit 5 + Mockito), Angular 19 standalone + signals, Ionic 8, Karma/Jasmine.

**Spec:** `docs/superpowers/specs/2026-11-18-goal-tally-and-loan-terms-design.md` (read it first; where this plan and the spec disagree, the spec wins).

## Global Constraints

- Plain language, no em dashes anywhere (code comments and copy included), "~$1,540" never "about", titles on one line.
- No AI-written numbers: every on-screen figure is arithmetic over stored inputs and sourced constants. Published constants are quoted exactly with source, series and as-of label in the JSDoc (`frontend/src/app/constants/kid-costs.ts` pattern); never recalled from memory.
- No PII and no raw amounts to PostHog; new event properties declared in both taxonomies and in `FREDdocs/analytics-events.md` (`scripts/docs/assert-events-documented.mjs` fails the build otherwise). Never run `flyway repair` on dev.
- Backend dates are Eastern (`EasternTime.today()` / `now()`). The frontend never computes a payday; it renders server strings. Never pass `YYYY-MM-DD` to `new Date(str)`; use `utils/local-date.ts` `toLocalDate`.
- `InvestmentSchedule.calculateNextInvestmentDate` is not modified (it moves real money).
- The return assumption stays set only on the Freedom Plan page. A loan rate is a different number and lives in the goal's config.
- Goal money lives in accounts the user opens outside FRED; copy never promises FRED will hold or move it.
- Migrations: never edit a run migration; information_schema guards (V6/V7 style); next version is **V10**, and `FREDdocs/handoff-paycheck-autopilot.md` is updated to say V11 in the same commit.
- Karma `--include` type-checks the whole app; 13 FRED-226 failures are known and pre-existing. Gates per task: the task's specs green, `npm run lint` clean, `npx ng build --configuration dev` 0 errors (frontend) or `./gradlew test` green (backend).
- Never `git checkout`, `restore`, `stash` or `reset --hard`; stage only the files you changed; never push.

## Review Focus

1. A vacation goal read on January 2 after a year of credits: the roll-forward must write the Jan 1 `set 0` before any January credits, so the new year starts from $0, not from last year's total. (Task 3 test `rollForwardResetsVacationOnJanuaryFirstBeforeCrediting`.)
2. Missed one when the tally is $200 and the share is $450: the entry is `−200`, the tally is $0, the response says it was capped, and a second Missed one writes nothing. (Task 4 test `missedNeverTakesTallyBelowZero`.)
3. A BIWEEKLY payday that falls on a holiday Monday at a month boundary: the credit is dated the pushed day, parity with the scheduler holds, and the next payday still keeps its two-week rhythm from the nominal date, not the pushed one. (Task 2 test `biweeklyKeepsParityAcrossAHolidayPush`.)
4. The user changes pay frequency on the Recurring investments page between two reads: credits already written stay, and the walk from `credited_through` uses the new cadence. (Task 3 test `frequencyChangeAppliesFromCreditedThroughOnward`.)
5. The car price slider is dragged below the current down payment: the down payment clamps to the price, Continue stays enabled, and the lines recompute; at $0 down Continue disables with the hint. (Task 9 test `car down payment clamps to the price and $0 blocks Continue`.)

---

## File map

Backend (`backend/src/main/java/com/investingapp/backend/`):
- Modify `model/Goal.java` (+`creditedThrough`, `creditsUntil` LocalDate), `model/goals/AccountState.java` (+`creditedThrough`, `creditsUntil`), `model/goals/HouseCarConfig.java` (+`loanRate`, `downPaymentAmount`), `dto/GoalRequest.java` (+`creditsUntil`), `dto/GoalResponse.java` (+tally fields), `service/GoalService.java` (ignore client balances, seed set entry, wire roll-forward, validation), `service/UserDeletionService.java`, `controller/GoalController.java` (+ledger endpoint).
- Create `model/GoalLedgerEntry.java`, `repository/GoalLedgerEntryRepository.java`, `service/goals/PaydayCalendar.java`, `service/GoalLedgerService.java`, `dto/GoalLedgerRequest.java`, `dto/UnitTally.java`, `resources/db/migration/V10__goal_ledger.sql`.
- Tests under `backend/src/test/java/com/investingapp/backend/`: `service/goals/PaydayCalendarTest.java`, `service/GoalLedgerServiceTest.java`, additions to `service/GoalServiceTest.java`, `controller/GoalControllerTest.java`, `model/goals/GoalConfigCodecTest.java`.

Frontend (`frontend/src/app/`):
- Modify `models/goal.model.ts`, `utils/goal-plan.utils.ts`, `pages/goal/goal-plan.view-model.ts`, `pages/goal/goal.page.ts|html|scss|spec.ts`, `pages/goal/goal-form.model.ts`, `components/goal-unit-detail-sheet/*`, `pages/goal-quiz/goal-quiz.page.ts|html|scss|spec.ts`, `pages/goal-quiz/goal-quiz.steps.ts|spec.ts`, `services/goals.service.ts`, `services/analytics/analytics-events.ts`, `pages/my-profile/my-profile.page.ts`.
- Create `utils/loan.utils.ts` (+spec), `constants/loan-rates.ts`, `components/tally-pills/tally-pills.component.ts|html|scss|spec.ts`.
- Docs: `FREDdocs/loan-rate-figures.md`, `FREDdocs/analytics-events.md`, `FREDdocs/handoff-paycheck-autopilot.md`, `FREDdocs/backlog.md` (new story via the backlog-add skill), the spec's dated notes if a ruling changes.

---

### Task 1: Schema and model for the ledger, credits window, and loan terms

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__goal_ledger.sql`, `model/GoalLedgerEntry.java`, `repository/GoalLedgerEntryRepository.java`
- Modify: `model/Goal.java`, `model/goals/AccountState.java`, `model/goals/HouseCarConfig.java`, `dto/GoalRequest.java`, `service/GoalService.java` (validation + `planInputsChanged` + `keepAnchors`/`reanchor` carry the new AccountState fields), `service/UserDeletionService.java`, `FREDdocs/handoff-paycheck-autopilot.md`
- Test: `model/goals/GoalConfigCodecTest.java`, `service/GoalServiceTest.java`, `service/UserDeletionServiceTest.java`

**Interfaces:**
- Produces: `GoalLedgerEntry { Long id; Goal goal; String unit; LocalDate entryDate; LedgerKind kind; BigDecimal amount; LocalDateTime createdAt }` with `enum LedgerKind { CREDIT, MISSED, EXTRA, SET }` (`@Enumerated(STRING)`, column `kind VARCHAR(8)`); `GoalLedgerEntryRepository extends JpaRepository<GoalLedgerEntry, Long>` with `List<GoalLedgerEntry> findByGoalAndUnitAndEntryDateGreaterThanEqualOrderByEntryDateAscIdAsc(Goal, String, LocalDate)`, `boolean existsByGoalAndUnitAndEntryDateAndKind(Goal, String, LocalDate, LedgerKind)`, `Optional<GoalLedgerEntry> findTopByGoalAndUnitAndKindOrderByEntryDateDescIdDesc(Goal, String, LedgerKind)`, `void deleteAllByGoalIn(Collection<Goal>)`.
- `Goal` gains `LocalDate creditedThrough` (column `credited_through DATE NULL`), `LocalDate creditsUntil` (`credits_until DATE NULL`) and `BigDecimal target` (`target DECIMAL(12,2) NULL`: the unit's savings target as the client computed it at anchor time; the server has no plan math and needs it to cap the last credit and to read Done).
- `AccountState(BigDecimal balance, LocalDateTime balanceUpdatedAt, BigDecimal anchorBalance, BigDecimal anchorMonthly, LocalDate creditedThrough, LocalDate creditsUntil, BigDecimal target)` with `@JsonFormat(shape = STRING, pattern = "yyyy-MM-dd")` on both dates; old JSON without them parses (nulls).
- `HouseCarConfig(BigDecimal price, Integer downPaymentPct, Integer targetYear, BigDecimal loanRate, BigDecimal downPaymentAmount)`.
- `GoalRequest` gains `LocalDate creditsUntil` (nullable, `@JsonFormat pattern yyyy-MM-dd`) and `BigDecimal target` (nullable, 0..100,000,000, 2 decimals). Both are plan figures the client writes beside `anchorMonthly`; `reanchor` stores them, `keepAnchors` keeps the stored ones.
- Unit key convention (string): `"goal"` for simple and vacation goals; `"kid:" + index + ":" + account` with account in `plan529 | brokerage | firstYear`.

- [ ] **Step 1: Write the failing codec tests**

In `GoalConfigCodecTest`:
```java
@Test void accountStateWithoutCreditFieldsStillParses() // JSON lacking creditedThrough/creditsUntil -> nulls
@Test void accountStateRoundTripsCreditDates()          // "2026-12-15" in, same string out
@Test void houseCarConfigRoundTripsLoanRateAndDownPaymentAmount() // loanRate 6.5, downPaymentAmount 5000
@Test void houseCarConfigWithoutLoanFieldsStillParses()
```

- [ ] **Step 2: Run them, expect compile failures on the missing record components**

Run: `cd backend && ./gradlew test --tests '*GoalConfigCodecTest*'`
Expected: FAIL (compilation).

- [ ] **Step 3: Write the migration**

`V10__goal_ledger.sql`, each statement guarded with the `information_schema` + `PREPARE` pattern from `V6__paycheck_autopilot.sql` (~213-220): `ALTER TABLE goals ADD COLUMN credited_through DATE NULL`, `ADD COLUMN credits_until DATE NULL`; `CREATE TABLE IF NOT EXISTS goal_ledger_entries (id BIGINT AUTO_INCREMENT PRIMARY KEY, goal_id BIGINT NOT NULL, unit VARCHAR(40) NOT NULL, entry_date DATE NOT NULL, kind VARCHAR(8) NOT NULL, amount DECIMAL(12,2) NOT NULL, created_at DATETIME(6) NOT NULL, credit_key VARCHAR(80) GENERATED ALWAYS AS (IF(kind = 'CREDIT', CONCAT(unit, ':', entry_date), NULL)) STORED, UNIQUE KEY uq_goal_ledger_credit (goal_id, credit_key), KEY ix_goal_ledger_goal_unit_date (goal_id, unit, entry_date), CONSTRAINT fk_goal_ledger_goal FOREIGN KEY (goal_id) REFERENCES goals(id))`. The generated column makes a credit unique per unit and day while adjustments may repeat. Entity maps `credit_key` as `@Column(insertable = false, updatable = false)`.

- [ ] **Step 4: Add the entity, repository, record fields, request field, validation**

Validation in `GoalService.validateHouseCar`: `loanRate` null or `0 <= loanRate <= 30` with at most 3 decimals, message `"Enter a loan rate from 0% to 30%."`; `downPaymentAmount` null or `0 <= downPaymentAmount <= price`, message `"Enter a down payment from $0 up to the price."`; a CAR with `downPaymentAmount` of 0 is rejected with `"With $0 down there is nothing to save."` (the quiz blocks it first). `planInputsChanged` HOUSE/CAR adds `downPaymentAmount`, not `loanRate`. `reanchor` writes `creditsUntil` from the request (goal level) and from each sent `AccountState` (kids); `keepAnchors` keeps the stored `creditedThrough`/`creditsUntil` and copies a newly sent `creditsUntil` only when non-null. `UserDeletionService` deletes `goalLedgerEntryRepository.deleteAllByGoalIn(goals)` before `goalRepository.deleteAllByUser(user)`.

- [ ] **Step 5: Service tests**

In `GoalServiceTest`: `loanRateAbove30IsRejected`, `downPaymentAmountAbovePriceIsRejected`, `carWithZeroDownPaymentIsRejected`, `changingLoanRateDoesNotReanchor`, `changingDownPaymentAmountReanchors`, `reanchorStoresCreditsUntilPerAccount`. In `UserDeletionServiceTest`: `deletesLedgerEntriesBeforeGoals` (verify order with `InOrder`).

- [ ] **Step 6: Run, expect green; boot check**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL. Then `./gradlew bootRun` against the local DB long enough to see `Migrating schema ... to version "10 - goal ledger"` and `ddl-auto=validate` pass (stop it after).

- [ ] **Step 7: Update the Autopilot handoff and commit**

Edit `FREDdocs/handoff-paycheck-autopilot.md` where it names its migration version: V10 becomes V11, with a one-line note dated 2026-11-18 that goals took V10.
```bash
git add backend/src/main/resources/db/migration/V10__goal_ledger.sql backend/src/main/java/com/investingapp/backend/model/GoalLedgerEntry.java backend/src/main/java/com/investingapp/backend/repository/GoalLedgerEntryRepository.java backend/src/main/java/com/investingapp/backend/model/Goal.java backend/src/main/java/com/investingapp/backend/model/goals/AccountState.java backend/src/main/java/com/investingapp/backend/model/goals/HouseCarConfig.java backend/src/main/java/com/investingapp/backend/dto/GoalRequest.java backend/src/main/java/com/investingapp/backend/service/GoalService.java backend/src/main/java/com/investingapp/backend/service/UserDeletionService.java backend/src/test/java/com/investingapp/backend/model/goals/GoalConfigCodecTest.java backend/src/test/java/com/investingapp/backend/service/GoalServiceTest.java backend/src/test/java/com/investingapp/backend/service/UserDeletionServiceTest.java FREDdocs/handoff-paycheck-autopilot.md
git commit -m "feat(goals): ledger table, credits window and loan-term fields (V10)"
```

---

### Task 2: PaydayCalendar

**Files:**
- Create: `service/goals/PaydayCalendar.java`
- Modify: `model/InvestmentSchedule.java` only if `adjustForBusinessDay` is not public (make it public; no logic change)
- Test: `service/goals/PaydayCalendarTest.java`

**Interfaces:**
- Produces: `public final class PaydayCalendar` with `public static List<LocalDate> paydays(PayRule rule, LocalDate afterExclusive, LocalDate throughInclusive)` and `public record PayRule(InvestmentFrequency frequency, LocalDate anchor, DayOfWeek dayOfWeek, Integer dayOfMonth)` plus `public static PayRule fromSchedule(InvestmentSchedule s)` (anchor = `chosenDate ?? startDate`, dayOfWeek/dayOfMonth from the entity's fields, falling back to the anchor's) and `public static PayRule monthlyOn(LocalDate anchorDate)` (MONTHLY on `anchorDate.getDayOfMonth()`, used when the user has no schedule).
- Rules: generate nominal dates per frequency inside `[afterExclusive − 7 days, throughInclusive + 7 days]`, push each with `InvestmentSchedule.adjustForBusinessDay` (static or via a throwaway entity if it is an instance method; the implementer picks and documents), keep those strictly after `afterExclusive` and not after `throughInclusive`, sorted, de-duplicated. WEEKLY: every `dayOfWeek`. BIWEEKLY: every `dayOfWeek` whose `ChronoUnit.WEEKS.between(anchor, nominal) % 2 == 0`. SEMI_MONTHLY: the 1st and 15th. MONTHLY: `min(dayOfMonth, lengthOfMonth)` each month.

- [ ] **Step 1: Failing tests**

```java
@Test void semiMonthlyIsFirstAndFifteenthPushedOffWeekends()   // Nov 1 2026 is a Sunday -> Nov 2
@Test void weeklyListsEveryPreferredWeekdayInWindow()
@Test void biweeklyKeepsParityAcrossAHolidayPush()              // nominal Mon Jan 18 2027 (MLK) -> Jan 19; next is Feb 1, not Feb 2
@Test void monthlyClampsToShortMonths()                         // dayOfMonth 31 -> Feb 26 2027 (28th is Sunday)
@Test void windowIsExclusiveStartInclusiveEnd()
@Test void parityWithSchedulerFromARealPayday()                 // for each frequency: paydays(rule, p, p+60d).get(0) == schedule.calculateNextInvestmentDate(p) where p is a real adjusted payday
@Test void monthlyOnAnchorDayWhenNoSchedule()                   // anchor Nov 18 -> Dec 18, Jan 19 2027 (18th is Monday holiday? verify with the entity's list; assert via adjustForBusinessDay)
```
Dates must be checked against `InvestmentSchedule.isUSHoliday` / `adjustForBusinessDay` in the test rather than hand-asserted where a holiday is involved.

- [ ] **Step 2: Run, expect failure**

Run: `cd backend && ./gradlew test --tests '*PaydayCalendarTest*'`

- [ ] **Step 3: Implement**

- [ ] **Step 4: Run, expect green; commit**

```bash
git add backend/src/main/java/com/investingapp/backend/service/goals/PaydayCalendar.java backend/src/test/java/com/investingapp/backend/service/goals/PaydayCalendarTest.java backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java
git commit -m "feat(goals): payday calendar from the schedule's rule fields, parity-tested with the scheduler"
```

---

### Task 3: Roll-forward on read

**Files:**
- Create: `service/GoalLedgerService.java`, `dto/UnitTally.java`
- Modify: `service/GoalService.java` (`listForUser` / any read path and `create`), `dto/GoalResponse.java`, `model/goals/KidsConfig.java` only if a helper for walking accounts is needed
- Test: `service/GoalLedgerServiceTest.java`, `service/GoalServiceTest.java`

**Interfaces:**
- Consumes: Task 1 entities, Task 2 calendar, `InvestmentFrequency.fromMonthly(BigDecimal monthly, InvestmentFrequency f)` (existing, cents HALF_UP), `InvestmentScheduleRepository` (the user's schedule, may be absent), `EasternTime.today()`.
- Produces: `public class GoalLedgerService` with `public boolean rollForward(Goal goal, Optional<InvestmentSchedule> schedule, LocalDate today)` (returns whether anything changed; persists entries and the goal) and `public UnitTally tallyFor(Goal goal, String unit, Optional<InvestmentSchedule> schedule, LocalDate today)`; `public record UnitTally(BigDecimal tally, LocalDate creditedThrough, LocalDate creditsUntil, LocalDate nextCreditDate, BigDecimal nextCreditAmount, BigDecimal net, LocalDate netSince, boolean startedCrediting)`.
- `GoalResponse` gains the `UnitTally` fields at top level (simple/vacation) and, for KIDS, `Map<String, UnitTally> unitTallies` keyed by unit string (the frontend reads `unitTallies['kid:0:plan529']`).
- Share per payday: `fromMonthly(anchorMonthly, frequency)`; with no schedule, the MONTHLY share (the full monthly).
- Tally fields per unit: simple goal → `goal.currentBalance`, `goal.creditedThrough`, `goal.creditsUntil`, `goal.anchorMonthly`; vacation → `config.savedThisYear` (monthly = anchorMonthly, creditsUntil null); kid account → the `AccountState`.

- [ ] **Step 1: Failing tests (`GoalLedgerServiceTest`, fixed `today`, in-memory fakes or Mockito for the repositories)**

```java
@Test void firstReadBackfillsCreditedThroughWithoutCredits()        // creditedThrough null -> today, no entries, tally unchanged
@Test void creditsOnePaycheckSharePerPaydaySinceCreditedThrough()  // SEMI_MONTHLY, $900/mo, 2 paydays -> two 450.00 CREDIT entries, tally +900
@Test void creditIsIdempotentPerUnitAndDay()                        // second rollForward same day writes nothing
@Test void creditsStopAfterCreditsUntil()
@Test void lastCreditIsCappedAtTheTarget()                          // tally 99,700, target 100,000, share 450 -> credit 300
@Test void noScheduleCreditsMonthlyOnTheAnchorDay()
@Test void frequencyChangeAppliesFromCreditedThroughOnward()
@Test void rollForwardResetsVacationOnJanuaryFirstBeforeCrediting()// Dec 20 -> Jan 2: SET 0 dated Jan 1, then Jan 1 credit (pushed to Jan 4 if weekend... assert via calendar)
@Test void kidsRollEachAccountSeparately()                          // 529 and brokerage shares differ; firstYear stops at its creditsUntil
@Test void netAndNetSinceComeFromAdjustmentsSinceLastRebase()       // MISSED 450 (Nov 15), EXTRA 450 (Nov 20), MISSED 450 (Dec 1) -> net -450, netSince Dec 1
@Test void completedGoalsDoNotCredit()
```
The target per unit is the stored `target` from Task 1 (`goal.target`, `AccountState.target`); a null target (a row from before this change) means no cap and no Done-at-target until the next save writes one.

- [ ] **Step 2: Run, expect failure**

- [ ] **Step 3: Implement `GoalLedgerService` and wire it**

`GoalService.listForUser(user)` (and `findForUser(id)` if present): load the schedule once, `rollForward` each goal, then map to responses with `tallyFor` per unit. `create`: tally = request balance, write a `SET` entry dated today, `creditedThrough = today`. `update` for an existing goal: ignore `request.currentBalance` and sent account balances (keep stored); on re-anchor keep `creditedThrough`. All inside the existing `@Transactional`.

- [ ] **Step 4: Run all backend tests, expect green; commit**

```bash
git add backend/src/main/java/com/investingapp/backend/service/GoalLedgerService.java backend/src/main/java/com/investingapp/backend/dto/UnitTally.java backend/src/main/java/com/investingapp/backend/dto/GoalResponse.java backend/src/main/java/com/investingapp/backend/service/GoalService.java backend/src/test/java/com/investingapp/backend/service/GoalLedgerServiceTest.java backend/src/test/java/com/investingapp/backend/service/GoalServiceTest.java
git commit -m "feat(goals): roll every goal forward on read, one paycheck share per payday"
```

---

### Task 4: Adjustments endpoint

**Files:**
- Create: `dto/GoalLedgerRequest.java`
- Modify: `service/GoalLedgerService.java`, `controller/GoalController.java`
- Test: `service/GoalLedgerServiceTest.java`, `controller/GoalControllerTest.java`

**Interfaces:**
- Produces: `POST /api/goals/{id}/ledger` body `GoalLedgerRequest { @NotBlank String unit; @NotBlank @Pattern("missed|extra|set") String kind (lower case on the wire, mapped to LedgerKind; "credit" rejected 400); BigDecimal amount (required for set, 0..100,000,000, 2 decimals) }` → `200 GoalResponse` (after a roll-forward, so the response is current) with header `X-Fred-Capped: true` when a MISSED was capped at zero; `404` when the goal is not the principal's; `400 ErrorCodeResponse { code: "INVALID_UNIT" }` for an unknown unit.
- `GoalLedgerService.adjust(Goal goal, String unit, LedgerKind kind, BigDecimal amount, Optional<InvestmentSchedule> schedule, LocalDate today)` returns `AdjustResult(Goal goal, boolean capped)`.
- A `SET` stamps `balanceUpdatedAt = now` as today's balance write does; MISSED/EXTRA stamp it too.

- [ ] **Step 1: Failing tests**

```java
// service
@Test void missedWritesMinusOneShareAndLowersTally()
@Test void extraWritesPlusOneShareAndRaisesTally()
@Test void missedNeverTakesTallyBelowZero()        // tally 200, share 450 -> entry 200, tally 0, capped true; again -> no entry
@Test void setRebasesTallyAndResetsNet()
@Test void adjustOnFinishedUnitIsRejected()        // tally >= target or creditsUntil passed -> IllegalStateException mapped to 409
// controller
@Test void ledgerPostReturnsUpdatedGoal()
@Test void ledgerPostUnknownUnitIs400()
@Test void ledgerPostOtherUsersGoalIs404()
@Test void ledgerPostCreditKindIs400()
```

- [ ] **Step 2: Run, expect failure** · **Step 3: Implement** · **Step 4: Run `./gradlew test`, expect green**

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/investingapp/backend/dto/GoalLedgerRequest.java backend/src/main/java/com/investingapp/backend/service/GoalLedgerService.java backend/src/main/java/com/investingapp/backend/controller/GoalController.java backend/src/test/java/com/investingapp/backend/service/GoalLedgerServiceTest.java backend/src/test/java/com/investingapp/backend/controller/GoalControllerTest.java
git commit -m "feat(goals): Missed one / Doubled up / set entries through POST /api/goals/{id}/ledger"
```

---

### Task 5: Frontend model and status from the tally

**Files:**
- Modify: `models/goal.model.ts`, `utils/goal-plan.utils.ts`, `pages/goal/goal-plan.view-model.ts`, `pages/goal/goal-form.model.ts` (`toLocalIso` neighbours; add `creditsUntilFor`), `pages/my-profile/my-profile.page.ts` (chip map unchanged, uses the new status), `services/goals.service.ts` (+`adjust`)
- Test: `utils/goal-plan.utils.spec.ts`, `pages/goal/goal-plan.view-model.spec.ts`, `services/goals.service.spec.ts`

**Interfaces:**
- Consumes: Task 3/4 response shape.
- Produces in `goal.model.ts`: `interface UnitTally { tally: number; creditedThrough: string | null; creditsUntil: string | null; nextCreditDate: string | null; nextCreditAmount: number; net: number; netSince: string | null; startedCrediting: boolean }`; `Goal` gains `tally: UnitTally` (simple/vacation), `unitTallies: Record<string, UnitTally>` (kids), `target: number | null`; `AccountState` gains `creditedThrough: string | null; creditsUntil: string | null; target: number | null`; `GoalWrite` gains `creditsUntil: string | null; target: number | null`; `HouseCarConfig` gains `loanRate: number | null; downPaymentAmount: number | null`; `type LedgerKind = 'missed' | 'extra' | 'set'`; `export function unitKey(kid: number, account: 'plan529' | 'brokerage' | 'firstYear'): string` → `kid:${kid}:${account}`.
- `goal-plan.utils.ts`: `tallyStatus(input: { completed: boolean; imported: boolean; tally: UnitTally | null; target: number; today: Date }): PlanStatus` where `PlanStatus` becomes `{ kind: PlanStatusKind; net: number; netSince: string | null }` (`stale` and `delta` removed; `asOf` removed); `planValue(anchorBalance, anchorMonthly, monthsSinceAnchor, annualRate, net): number` = `futureValue(anchorBalance, anchorMonthly, months, rate, []) + net`; `yourPart(anchorBalance, anchorMonthly, planMonths): number`; `worstOf(statuses: PlanStatus[]): PlanStatus` (behind > ahead > on_plan > started > done, imported passes through when all are imported). `STALE_BALANCE_DAYS` deleted.
- `goals.service.ts`: `adjust(id: number, unit: string, kind: LedgerKind, amount?: number): Observable<{ goal: Goal; capped: boolean }>` posting to `${apiUrl}/${id}/ledger` and patching the `goals` signal; `GoalWrite` bodies carry `creditsUntil`.
- View model: `buildGoalPlanView` solves simple plans with `balance: planValue(...)` (anchor fields from `GoalPlanInput`, `net` from `goal.tally`), kids accounts likewise per `AccountState`; preview modes (no anchor) keep the draft balance; `progressPct = clamp(tally / yourPart)`; `GoalPlanView` gains `tallyLine: { saved: string; part: string; next: string | null; net: string | null }` built from the tally (`Saved $900`, `3% of your $30K`, `Next $450 Jan 1` via `fmtMonthDay`, `Behind by $450 since Nov 15` / `Ahead by $450`) and `status` from `tallyStatus`; kids status = `worstOf` the saving units'. `creditsUntilFor(type, config, kidIndex?, account?)` returns the `yyyy-MM-dd` the spec names, used by Task 6 when writing.

- [ ] **Step 1: Failing utils specs**

```ts
describe('tallyStatus', () => {
  it('started before the first credit with no taps');
  it('on plan at net 0 once credited');
  it('behind by one share after one missed tap, no tolerance', () => expect(s.kind).toBe('behind'); expect(s.net).toBe(-450); expect(s.netSince).toBe('2026-11-15'));
  it('ahead after a doubled up');
  it('done at the target, and at creditsUntil with net >= 0');
  it('not done at creditsUntil with net < 0');
  it('imported stays imported');
});
describe('planValue', () => { it('equals the anchored plan when net is 0, so the re-solved monthly matches anchorMonthly within $1'); });
describe('worstOf', () => { it('behind beats ahead beats on plan beats started'); });
```
View-model specs: `simple view re-solves from planValue, not the tally` (an invested goal 12 months in, net 0 → `investMonthly` within $1 of `anchorMonthly`); `tallyLine reads Saved $900 · 3% of your $30K · Next $450 Jan 1`; `kids status is the worst unit`; `vacation reads Done when the year's budget is in`.

- [ ] **Step 2: Run, expect failure**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/utils/goal-plan.utils.spec.ts' --include='src/app/pages/goal/goal-plan.view-model.spec.ts'`

- [ ] **Step 3: Implement** (delete `planStatus`'s expected-balance path and `vacationStatus`; callers move to `tallyStatus`)

- [ ] **Step 4: Run the two specs plus `goals.service.spec.ts`, lint, build; commit**

```bash
git add frontend/src/app/models/goal.model.ts frontend/src/app/utils/goal-plan.utils.ts frontend/src/app/utils/goal-plan.utils.spec.ts frontend/src/app/pages/goal/goal-plan.view-model.ts frontend/src/app/pages/goal/goal-plan.view-model.spec.ts frontend/src/app/pages/goal/goal-form.model.ts frontend/src/app/services/goals.service.ts frontend/src/app/services/goals.service.spec.ts frontend/src/app/pages/my-profile/my-profile.page.ts
git commit -m "feat(goals): status and progress read the server tally; hero re-solves from the plan's value"
```

---

### Task 6: Tally pills on the plan page

**Files:**
- Create: `components/tally-pills/tally-pills.component.ts|html|scss|spec.ts`
- Modify: `pages/goal/goal.page.ts|html|scss|spec.ts`
- Test: `components/tally-pills/tally-pills.component.spec.ts`, `pages/goal/goal.page.spec.ts`

**Interfaces:**
- Consumes: Task 5 (`GoalsService.adjust`, `GoalPlanView.tallyLine`, `creditsUntilFor`), the FRED toast service (`services/toast.service.ts`, the `showToast` with an action if it has one; otherwise add an `action?: { label: string; run: () => void }` option there, in this task).
- Produces: `TallyPillsComponent` standalone, selector `app-tally-pills`, inputs `share: number` (required), `disabled = false`; outputs `missed = output<void>()`, `doubled = output<void>()`. Template: two `button.tp-pill` (`remove` icon + `Missed one` + `−$450`, `add` icon + `Doubled up` + `+$450`), 34px tall, full-row pair, Manrope 700, border `1.5px solid #e5e7eb`, radius 999px, `:active` background `#f3f4f6`; hidden entirely (host `display: none`) when `share <= 0`.
- Page: `onTally(path: BalancePath, kind: 'missed' | 'extra')` → `goalsService.adjust(goal.id, unitFor(path), kind)` then `existing.set(goal)` and toast `Saved so far is now ${fmtMoney(tally)}.` with action `Undo` running the opposite kind; when `capped`, toast `Saved so far is at $0; nothing more to take off.` with no Undo. The Saved so far tray's commit posts `adjust(id, unit, 'set', amount)` instead of a PUT for a saved goal (preview modes unchanged). `draftBody` and `withAccountAnchors` write `creditsUntil` from `creditsUntilFor` and `target` (the simple target, or each kid unit's `plan.target` / `milestones.at18`) beside `anchorMonthly`; `GoalWrite` and `AccountState` carry `target` (add to Task 5's model if missed there). `statusChip` drops the `Update balance` branch and `ChipText` loses it; `onChipTap` goes. Band line (non-kids) renders `tallyLine`: `{{ saved }} · {{ part }}` + ` · {{ next }}` when present + ` · {{ net }}` when present. Pills render under the inline Saved so far tile for simple/vacation goals in saved mode only.

- [ ] **Step 1: Failing specs**

Component: `renders the share on both pills`, `emits missed and doubled`, `hides when the share is 0`. Page: `Missed one posts a missed entry for unit goal and shows the toast with Undo`, `Undo after Missed one posts extra`, `a capped miss shows the no-more-to-take-off toast without Undo`, `closing the Saved so far tray posts a set, not a PUT`, `band line shows Saved · part · Next · Behind by`, `no pills in preview modes`, `Update balance chip no longer exists` (replace the existing stale-chip spec), `POST and PUT bodies carry creditsUntil = Dec 31 of the target year for a house`.

- [ ] **Step 2: Run, expect failure** · **Step 3: Implement** · **Step 4: Run `goal*` specs + component spec, lint, build**

Run: `cd frontend && npx ng test --watch=false --browsers=ChromeHeadless --include='src/app/pages/goal/**/*.spec.ts' --include='src/app/components/tally-pills/**/*.spec.ts'`

- [ ] **Step 5: Commit**

```bash
git add frontend/src/app/components/tally-pills frontend/src/app/pages/goal/goal.page.ts frontend/src/app/pages/goal/goal.page.html frontend/src/app/pages/goal/goal.page.scss frontend/src/app/pages/goal/goal.page.spec.ts frontend/src/app/services/toast.service.ts frontend/src/app/services/toast.service.spec.ts
git commit -m "feat(goals): Missed one and Doubled up under Saved so far; the tray sets the tally"
```

---

### Task 7: Tally pills in the kid unit sheet

**Files:**
- Modify: `components/goal-unit-detail-sheet/goal-unit-detail-sheet.component.ts|html|scss|spec.ts`, `pages/goal/goal.page.ts` (`openUnitDetail` props, `unitDetail`)
- Test: the sheet spec, `goal.page.spec.ts`

**Interfaces:**
- Consumes: Task 6 component and `onTally`.
- Produces: `UnitDetail` gains `share: number` and `status.text` may read `Behind by $450` / `Ahead by $450` (one line; the tile's text node, tone unchanged); sheet inputs gain `onTally: ((kind: 'missed' | 'extra') => Promise<void>) | null`. Pills render under the Saved so far + Status tiles when `onTally` is set and `!d.finished`. The sheet's Saved so far tray commit calls `onBalance` as today; the page's `onBalance` for kids posts a `set` for that unit.

- [ ] **Step 1: Failing specs**: `pills show for a saving unit with the unit's share`, `Missed one calls onTally('missed')`, `Status tile reads Behind by $450 after a missed tap`, `everyday has no pills`, `page passes onTally that posts for kid:1:brokerage`.
- [ ] **Step 2: Run, expect failure** · **Step 3: Implement** · **Step 4: Run sheet + goal specs, lint, build**
- [ ] **Step 5: Commit** `feat(goals): per-unit Missed one / Doubled up in the kid sheet`

---

### Task 8: Loan math and sourced rate constants

**Files:**
- Create: `utils/loan.utils.ts`, `utils/loan.utils.spec.ts`, `constants/loan-rates.ts`, `FREDdocs/loan-rate-figures.md`

**Interfaces:**
- Produces: `loanPayment(principal: number, annualRatePct: number, months: number): number` (nearest dollar; 0% divides evenly; `principal <= 0` → 0); `loanLine(kind: 'HOUSE' | 'CAR', price: number, down: number, ratePct: number): string` → `Then the mortgage: ~$3,900 a month at 6.5% over 30 years, before taxes and insurance.` / `Then the loan: ~$520 a month at 7.5% over 60 months.` (rate shown with up to 3 decimals trimmed: `6.5%`, `6.125%`); constants `MORTGAGE_RATE_DEFAULT_PCT`, `CAR_LOAN_RATE_DEFAULT_PCT`, `MORTGAGE_TERM_MONTHS = 360`, `CAR_LOAN_TERM_MONTHS = 60`, `LOAN_RATE_SOURCES: ReadonlyArray<{ name: string; period: string; url: string; usedIn: 'HOUSE' | 'CAR' }>`.
- The two rate defaults are looked up at build time (Freddie Mac PMMS 30-year fixed weekly average; Federal Reserve G.19 60-month new car loan, commercial banks), quoted exactly with the week or quarter in the JSDoc. The implementer fetches the published pages; if a figure cannot be verified from the publisher, stop and report BLOCKED rather than typing one.

- [ ] **Step 1: Failing spec**: `$617,500 at 6.5% over 360 months is $3,903` (pin whatever the formula yields for the implementer's constant: assert against a hand-computed value using the formula in the spec, e.g. `loanPayment(100_000, 6, 360)` → `600`), `0% divides evenly`, `principal 0 is 0`, `loanLine wording`.
- [ ] **Step 2: Run, expect failure** · **Step 3: Implement + docs** · **Step 4: Run spec, lint, build**
- [ ] **Step 5: Commit** `feat(goals): loan payment math and sourced default rates`

---

### Task 9: Quiz house and car terms

**Files:**
- Modify: `pages/goal-quiz/goal-quiz.page.ts|html|scss|spec.ts`, `pages/goal-quiz/goal-quiz.steps.ts|spec.ts`, `pages/goal/goal-form.model.ts`, `components/quiz-controls/quiz-slider.component.ts` only if `tapToType=false` + `format` is not enough for eighths
- Test: `goal-quiz.page.spec.ts`, `goal-quiz.steps.spec.ts`, `goal-form.model.spec.ts` if present

**Interfaces:**
- Consumes: Task 8 helpers and constants; Task 5 `HouseCarConfig` fields.
- Produces: `GoalForm` gains `loanRate: number | null` (percent) and `downPaymentAmount: number | null`; `fullPrice` removed from `GoalForm` (formFromGoal maps a stored `downPaymentPct === null` HOUSE to 20 and a CAR without `downPaymentAmount` to `price × pct / 100` rounded to $500); `defaultForm` HOUSE `downPaymentPct 20`, CAR `downPaymentAmount 5000`; `withQuizDefaults` sets `loanRate` to the type's constant; `formToConfig` writes both; `planInputsChanged` compares `downPaymentAmount` (CAR) and not `loanRate`; `validateForm` CAR: `downPaymentAmount` null or `<= 0` → `errors['downPaymentAmount'] = 'With $0 down there is nothing to save. Set a down payment, or pick Something else.'`, `> price` → clamp (not an error); `stepOwnsError` car_terms owns `downPaymentAmount`.
- Quiz: `downPaymentOptions` = `[5, 10, 20]` (`QuizChipOption<number>`); a `#houseTerms` template (chips, mortgage rate slider, year slider, two lines) and a `#carTerms` template (down payment slider $0..min(25_000, price) step 500 linear format fmtMoney, loan rate slider, year slider, two lines); rate sliders are `app-quiz-slider` with `min 24 max 80` (house) / `min 24 max 120` (car) `step 1`, `[tapToType]="false"`, `format = v => \`${trimPct(v / 8)}%\``, value `Math.round(loanRate × 8)`; `termsLines(): [string, string]` → `[`$32,500 down · ~$X a month to get there`, loanLine(...)]`; step subtitles: house_terms `FRED plans the down payment. The mortgage comes after.`, car_terms `FRED plans the down payment. The loan comes after.`; `ctaDisabled()` true on car_terms when `downPaymentAmount <= 0`.

- [ ] **Step 1: Failing specs** (rewrite the existing house terms spec): `house terms: chips 5/10/20, default 20, no Full price`, `house lines read $130K down · ~$X a month to get there and the mortgage line at the default rate`, `moving the mortgage rate slider to 7.125% updates the line and the form`, `car terms: dollar slider default $5,000, lines`, `car down payment clamps to the price and $0 blocks Continue`, `formFromGoal maps a legacy null pct house to 20%`, `formToConfig writes loanRate and downPaymentAmount`, `changing the loan rate is not a plan input`.
- [ ] **Step 2: Run, expect failure** · **Step 3: Implement** · **Step 4: Run quiz + goal-form specs, lint, build**
- [ ] **Step 5: Commit** `feat(goals): house 5/10/20 down with a mortgage line, car dollar down payment with a loan line`

---

### Task 10: Loan line on the plan page

**Files:**
- Modify: `pages/goal/goal-plan.view-model.ts` (`simpleView` subtitle lead and `coversLine` for HOUSE/CAR; `simpleTarget` CAR uses `downPaymentAmount ?? price × pct / 100`), `utils/goal-plan.utils.ts` (`simpleTarget`), `pages/goal/goal.page.ts` (`infoNotes` sources line for HOUSE/CAR from `LOAN_RATE_SOURCES`)
- Test: `goal-plan.view-model.spec.ts`, `goal.page.spec.ts`

- [ ] **Step 1: Failing specs**: `house subtitle leads with the down payment and the mortgage line`, `car target is the dollar down payment`, `legacy car without downPaymentAmount still targets price × pct`, `info sheet's second note for a house carries the loan line and the Freddie Mac source`.
- [ ] **Step 2: Run, expect failure** · **Step 3: Implement** · **Step 4: Run goal specs, lint, build**
- [ ] **Step 5: Commit** `feat(goals): the plan page says the plan is the down payment and shows the loan that follows`

---

### Task 11: Analytics, backlog, docs

**Files:**
- Modify: `services/analytics/analytics-events.ts` (both taxonomies), `FREDdocs/analytics-events.md`, `pages/goal/goal.page.ts` (emit on adjust), `FREDdocs/backlog.md` (new story via the `backlog-add` skill: "Goal tally: FRED credits paydays, Missed one / Doubled up; house and car loan terms"), the spec (a dated note if any ruling moved during the build)
- Test: `goal.page.spec.ts` (`a missed tap emits goal_tally_adjusted with goal_type and kind only`), `node scripts/docs/assert-events-documented.mjs`

**Interfaces:**
- `goal_tally_adjusted: { goal_type: GoalType; kind: 'missed' | 'extra' | 'set' }`; no amount, no unit.

- [ ] **Step 1: Failing spec** · **Step 2: Run, expect failure** · **Step 3: Implement** · **Step 4: Run the spec, the events script, lint, build**
- [ ] **Step 5: Commit** `feat(goals): goal_tally_adjusted event, backlog story, docs`

---

## Final gates (after Task 11, before hand-back)

- Backend: `cd backend && ./gradlew test` green.
- Frontend: `npx ng test --watch=false --browsers=ChromeHeadless` (whole suite; exactly the 13 known FRED-226 failures, no more), `npm run lint`, `npx ng build --configuration dev`, `node scripts/docs/assert-events-documented.mjs`.
- Grep the new html/ts for em dashes: `grep -rn '—' frontend/src/app/pages/goal frontend/src/app/pages/goal-quiz frontend/src/app/components/tally-pills backend/src/main/java/com/investingapp/backend/service/GoalLedgerService.java` returns nothing.
- Whole-branch review on the most capable model, then hand back to Andy with the device pass list: Missed one / Doubled up / Undo on a simple goal and in a kid sheet; the band's Next date against the Recurring investments page's next date; a house quiz edit moving the rate slider; a car at $0 down; a saved car from before this change opening cleanly.
