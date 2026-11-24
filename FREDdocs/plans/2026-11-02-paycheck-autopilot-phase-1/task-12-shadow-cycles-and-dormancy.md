### Task 12: shadow-cycles-and-dormancy

Pay-period cycles for the primary stream (spec 4.6), spec 4.7 rules 1 to 7 as a pure matcher, the `CycleGateChain` seam with `ShadowGate` and `KillSwitchGate` (R7), the would-have-pulled amount, and the reconcile's miss, stale and 120-day rules as stream and cycle state only (R6, spec 6.4). Wires `PaycheckCycleService` into T11's `PaycheckDetectionService` (Decision 43): `evaluate` opens the cycle at first qualification, `evaluateAndApply` applies new deposits under the same user row lock. Nothing here moves money, writes `status`, `mode` or `hold_reason`, changes `isReadyForInvestment`, sends a notice, emits analytics or calls Plaid or Alpaca.

Facts this task depends on:

- `status` (PENDING_BANK, ACTIVE, PAUSED_BY_USER) is the user and bank lifecycle, `hold_reason` the system's orthogonal stop; only `isReadyForInvestment(todayET)` decides whether money moves. T03's `PhaseOneMoneyInvarianceTest` fails any `paycheck/**` or `service/Paycheck*` source that calls the status or mode setters.
- R1: outcomes mirror onto the MANUAL row when one exists; `mode` stays MANUAL. R2, R6: no detection hold under shadow; dormancy lives on the stream, cycle and mirror only. R7: rule 8 writes SKIPPED `SHADOW_CONFIRMED` with `deposit_amount`, `event_id`, `confirmed_at` and `amount_to_pull` (the MANUAL row's `investmentAmount` as is, else the capped spec 4.9 prefill from `User.monthlyInvestment`, 500 fallback). Steps 9 to 11 are Phase 2; Phase 1 has no CONFIRMED writer (Decision 9) and no cycle analytics event (Decision 2).
- Decision 44: writes run in a per-user transaction already holding `userRepository.findByIdForUpdate(userId)` (T11's `evaluate`, `evaluateAndApply`, or one T15 template per user), so writers are `MANDATORY`; the three finders are read-only and run before those templates. INFO logs carry codes and counts only, ids go to DEBUG.

Conventions: `.../` = `backend/src/main/java/com/investingapp/backend/`, tests mirror it under `backend/src/test/java/...`. In import lists `M` = `com.investingapp.backend.model`, `C` = `...paycheck.cycle`, `D` = `...paycheck.detect`, `R` = `...repository` (all under `com.investingapp.backend`). Each block starts at the type declaration; the line above it gives package and exact imports. Blocks use two-space indentation to fit the size limit; re-indent to four when pasting. Count command (from `backend/`): `grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-<class FQN>.xml`.

**Files:**
- Create: `.../paycheck/cycle/MatchDecision.java`, `CycleMatcher.java`, `CycleGate.java`, `CycleContext.java`, `CycleGateChain.java`, `ShadowGate.java`, `KillSwitchGate.java`, `WouldHavePulled.java`, `MissDetector.java`; `.../service/PaycheckCycleService.java`.
- Modify: `.../service/PaycheckDetectionService.java` (T11; absent at HEAD 137998c, so the anchor is the signature T11's contract fixes, quoted in Step 9): a field and constructor parameter, the wrapper `evaluate`, `evaluateAndApply`, T11's body renamed `evaluateWithoutCycles`. `backend/src/test/.../service/PaycheckDetectionServiceTest.java` (T11): each `new PaycheckDetectionService(` gains a last argument.
- Test: `paycheck/cycle/CycleMatcherTest`, `MissDetectorTest`, `CycleGateChainTest`, `WouldHavePulledTest`; `service/PaycheckCycleServiceTest`, `service/PaycheckDetectionCycleWiringTest`.
- Properties: none added, so the gitignored `application-local.properties` needs no hand edit.

**Interfaces:**
- Consumes. T02: entities `PaycheckStream`, `PaycheckEvent`, `PaycheckCycle` (Lombok accessors, bare `Long` ids, `short` counters, `Short anchorDow`, `isPending()`, timestamps set by the caller) and the `Paycheck*` and `DetectionState` enums; `InvestmentSchedule` mirror fields `detectionState`, `primaryStreamId`, `nextExpectedDate`, `short missedCount`, `dormantSince`; `PaycheckCycleRepository.findByUserIdAndState(Long, PaycheckCycleState)`, `findByStreamIdAndPeriodKey(Long, String): Optional`, `findByStateAndWindowEndBefore(PaycheckCycleState, LocalDate)`, `findByStateAndConfirmedAtBefore(PaycheckCycleState, LocalDateTime)`; `PaycheckStreamRepository.findByUserIdOrderByIdAsc(Long)`, `findByUserIdAndState(Long, PaycheckStreamState)`; `PaycheckEventRepository.findByStreamIdAndStatusOrderByPostedDateDesc(Long, PaycheckEventStatus)`; `JpaRepository.findById`, `findAllById`, `save`. T03: `config/PaycheckAutopilotProperties.isShadow()`, `isPullsEnabled()`, `BigDecimal getAffordabilityShare()`. T09 (`D`): `record CadenceResult(InvestmentFrequency cadence, PaycheckCadenceSource source, Integer anchorDow, LocalDate anchorParityDate, String anchorDay1, String anchorDay2, int intervals, boolean allInBand, int outsideBand)`; `PayCalendar.predictedNext(CadenceResult, LocalDate)`, `periodDays(InvestmentFrequency)`, `periodKey(CadenceResult, LocalDate): String`, `window(InvestmentFrequency, LocalDate)` returning `CycleWindow(start, end)`; `BusinessDays.plus(LocalDate, int)`; `PayerTokenizer.token(String originalDescription, String name)`; `AmountPrefill.compute(Double, InvestmentFrequency, BigDecimal median, BigDecimal share)` with `prefillAmount()`, `goalSource()` (`CycleWindow` and `Prefill` read through `var`). T11: `@Transactional EvaluationOutcome evaluate(Long userId, EvaluationTrigger trigger, LocalDate todayET)`; `paycheck.EvaluationTrigger`; `paycheck.EvaluationOutcome(DetectionState state, Long primaryStreamId, boolean primaryChanged, boolean switched, Optional<ExclusionReason> excluded)`. HEAD: `UserRepository.findById`; `InvestmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(User)` (`InvestmentScheduleRepository.java:19`); `EasternTime.now()` (`EasternTime.java:28`).
- Produces: the recap at the end.

- [ ] **Step 1: Failing tests for the matcher and the miss rules**

`CycleMatcherTest` (package `C`; imports `M.*`, `org.junit.jupiter.api.*`, `java.math.BigDecimal`, `java.time.*`, `java.util.Optional`, static `C.MatchDecision.*`, static `org.assertj.core.api.Assertions.assertThat`):

```java
class CycleMatcherTest {

  private static final LocalDate EXPECTED = LocalDate.of(2026, 9, 25);
  private PaycheckStream stream;
  private PaycheckCycle open;

  @BeforeEach
  void setUp() {
    stream = new PaycheckStream();
    stream.setState(PaycheckStreamState.PROVISIONAL);
    stream.setCadence(InvestmentFrequency.BIWEEKLY);
    stream.setTrailingMedian(new BigDecimal("2000.00"));
    stream.setTrailingCv(new BigDecimal("0.0500"));
    stream.setDepositsFound((short) 3);
    open = new PaycheckCycle();
    open.setState(PaycheckCycleState.OPEN);
    open.setExpectedDate(EXPECTED);
    open.setWindowStart(LocalDate.of(2026, 9, 18));
    open.setWindowEnd(LocalDate.of(2026, 10, 6));
  }

  private static PaycheckEvent deposit(LocalDate posted, String amount) {
    PaycheckEvent e = new PaycheckEvent();
    e.setPostedDate(posted);
    e.setAmount(new BigDecimal(amount));
    e.setRawAmount(new BigDecimal(amount).negate());
    e.setStatus(PaycheckEventStatus.ACTIVE);
    e.setFirstSeenAt(posted.atTime(9, 0));
    return e;
  }

  private MatchDecision match(LocalDate posted, String amount, Optional<LocalDate> lastConfirmed) {
    PaycheckEvent e = deposit(posted, amount);
    return CycleMatcher.match(e, stream, Optional.of(open), lastConfirmed, e.getFirstSeenAt());
  }

  @Test
  void rule1StreamStateLogsOnly() {
    assertThat(match(EXPECTED, "2000.00", Optional.of(LocalDate.of(2026, 9, 11)))).isEqualTo(CONFIRMABLE);
    stream.setState(PaycheckStreamState.CANDIDATE);
    assertThat(match(EXPECTED, "2000.00", Optional.empty())).isEqualTo(LOGGED_STREAM_STATE);
    stream.setState(PaycheckStreamState.RETIRED);
    assertThat(match(EXPECTED, "2000.00", Optional.empty())).isEqualTo(LOGGED_STREAM_STATE);
    stream.setState(PaycheckStreamState.DORMANT); // DORMANT resumes on a qualifying deposit
    assertThat(match(EXPECTED, "2000.00", Optional.empty())).isEqualTo(CONFIRMABLE);
  }

  @Test
  void rule2LateSeenOverSevenEtDays() {
    PaycheckEvent e = deposit(EXPECTED, "2000.00");
    assertThat(CycleMatcher.match(e, stream, Optional.of(open), Optional.empty(), LocalDateTime.of(2026, 10, 3, 8, 0)))
        .isEqualTo(LATE_SEEN);
    assertThat(CycleMatcher.match(e, stream, Optional.of(open), Optional.empty(), LocalDateTime.of(2026, 10, 2, 23, 59)))
        .isEqualTo(CONFIRMABLE);
  }

  @Test
  void rule3AmountFloorIsAsymmetric() {
    assertThat(match(EXPECTED, "1199.99", Optional.empty())).isEqualTo(AMOUNT_MISMATCH);
    assertThat(match(EXPECTED, "1200.00", Optional.empty())).isEqualTo(CONFIRMABLE);
    assertThat(match(EXPECTED, "9000.00", Optional.empty())).isEqualTo(CONFIRMABLE);
    stream.setTrailingCv(new BigDecimal("0.4000"));
    assertThat(match(EXPECTED, "900.00", Optional.empty())).isEqualTo(CONFIRMABLE);
    assertThat(match(EXPECTED, "899.99", Optional.empty())).isEqualTo(AMOUNT_MISMATCH);
  }

  @Test
  void rule4GapTooShort() {
    Optional<LocalDate> last = Optional.of(LocalDate.of(2026, 9, 14));
    assertThat(match(LocalDate.of(2026, 9, 22), "2000.00", last)).isEqualTo(GAP_TOO_SHORT); // 8 < ceil(8.4)
    assertThat(match(LocalDate.of(2026, 9, 23), "2000.00", last)).isEqualTo(CONFIRMABLE);
  }

  @Test
  void rule5OutsideWindowIsSecondaryUnlessGapHolds() {
    assertThat(match(LocalDate.of(2026, 10, 8), "2000.00", Optional.empty())).isEqualTo(OUTSIDE_WINDOW_SECONDARY);
    assertThat(match(LocalDate.of(2026, 10, 8), "2000.00", Optional.of(LocalDate.of(2026, 9, 14)))).isEqualTo(CONFIRMABLE);
    assertThat(match(LocalDate.of(2026, 9, 16), "2000.00", Optional.of(LocalDate.of(2026, 9, 11))))
        .isEqualTo(OUTSIDE_WINDOW_SECONDARY);
  }

  @Test
  void rule6PeriodTakenWithoutOpenCycle() {
    PaycheckEvent e = deposit(EXPECTED, "2000.00");
    assertThat(CycleMatcher.match(e, stream, Optional.empty(), Optional.empty(), e.getFirstSeenAt())).isEqualTo(PERIOD_TAKEN);
    open.setState(PaycheckCycleState.SKIPPED);
    assertThat(match(EXPECTED, "2000.00", Optional.empty())).isEqualTo(PERIOD_TAKEN);
  }

  @Test
  void rule7StreamTrustNeedsTwoDepositsCountingThisOne() {
    stream.setDepositsFound((short) 1);
    assertThat(match(EXPECTED, "2000.00", Optional.empty())).isEqualTo(STREAM_UNTRUSTED);
    stream.setDepositsFound((short) 2);
    assertThat(match(EXPECTED, "2000.00", Optional.empty())).isEqualTo(CONFIRMABLE);
  }
}
```

`MissDetectorTest` (package `C`; imports `M.*`, `org.junit.jupiter.api.Test`, `java.time.LocalDate`, `java.util.List`, static `assertThat`):

```java
class MissDetectorTest {

  private static PaycheckCycle cycle(Long streamId, PaycheckCycleState state, LocalDate expected, LocalDate windowEnd) {
    PaycheckCycle c = new PaycheckCycle();
    c.setStreamId(streamId);
    c.setState(state);
    c.setExpectedDate(expected);
    c.setWindowEnd(windowEnd);
    return c;
  }

  @Test
  void windowPastSevenDaysIsMissed() {
    PaycheckCycle open = cycle(7L, PaycheckCycleState.OPEN, LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 6));
    assertThat(MissDetector.missed(open, LocalDate.of(2026, 10, 13))).isFalse();
    assertThat(MissDetector.missed(open, LocalDate.of(2026, 10, 14))).isTrue();
    open.setState(PaycheckCycleState.SKIPPED);
    assertThat(MissDetector.missed(open, LocalDate.of(2026, 12, 31))).isFalse();
  }

  @Test
  void retireDueAt120DaysFromTheFirstMissOfTheSpell() {
    PaycheckStream stream = new PaycheckStream();
    stream.setId(7L);
    stream.setLastDate(LocalDate.of(2026, 5, 1));
    List<PaycheckCycle> missed = List.of(
        cycle(7L, PaycheckCycleState.MISSED, LocalDate.of(2026, 4, 17), LocalDate.of(2026, 4, 28)), // before last deposit
        cycle(7L, PaycheckCycleState.MISSED, LocalDate.of(2026, 5, 29), LocalDate.of(2026, 6, 9)),
        cycle(7L, PaycheckCycleState.MISSED, LocalDate.of(2026, 5, 15), LocalDate.of(2026, 5, 26)),
        cycle(8L, PaycheckCycleState.MISSED, LocalDate.of(2026, 5, 8), LocalDate.of(2026, 5, 19)));
    assertThat(MissDetector.dormantSince(stream, missed)).contains(LocalDate.of(2026, 5, 26));
    assertThat(MissDetector.retireDue(LocalDate.of(2026, 5, 26), LocalDate.of(2026, 9, 22))).isFalse();
    assertThat(MissDetector.retireDue(LocalDate.of(2026, 5, 26), LocalDate.of(2026, 9, 23))).isTrue();
  }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.cycle.CycleMatcherTest' --console=plain`. Expected: `compileTestJava` fails with `error: cannot find symbol` naming `class MatchDecision`, `class CycleMatcher` and `class MissDetector`.

- [ ] **Step 2: MatchDecision, CycleMatcher, MissDetector**

`MatchDecision` (package `C`, no imports):

```java
public enum MatchDecision {
  CONFIRMABLE, LOGGED_STREAM_STATE, LATE_SEEN, AMOUNT_MISMATCH, GAP_TOO_SHORT, OUTSIDE_WINDOW_SECONDARY, PERIOD_TAKEN,
  STREAM_UNTRUSTED
}
```

`CycleMatcher` (package `C`; imports `M.{InvestmentFrequency, PaycheckCycle, PaycheckCycleState, PaycheckEvent, PaycheckStream, PaycheckStreamState}`, `D.{BusinessDays, PayCalendar}`, `java.math.BigDecimal`, `java.time.{LocalDate, LocalDateTime}`, `java.time.temporal.ChronoUnit`, `java.util.{EnumSet, Optional, Set}`):

```java
/** Spec 4.7 rules 1 to 7, pure. The caller has matched the event to the primary. Shadow tuning edits these constants. */
public final class CycleMatcher {

  public static final int LATE_SEEN_DAYS = 7;
  public static final BigDecimal AMOUNT_FLOOR = new BigDecimal("0.60");
  public static final BigDecimal AMOUNT_FLOOR_VOLATILE = new BigDecimal("0.45");
  public static final BigDecimal CV_VOLATILE = new BigDecimal("0.35");
  public static final double GAP_FRACTION = 0.60;
  public static final int GAP_MIN_BUSINESS_DAYS = 5;
  public static final int TRUST_MIN_DEPOSITS = 2;

  private static final Set<PaycheckStreamState> CONFIRMING_STATES =
      EnumSet.of(PaycheckStreamState.PROVISIONAL, PaycheckStreamState.CONFIRMED, PaycheckStreamState.DORMANT);

  private CycleMatcher() {
  }

  /** lastConfirmed: latest posted date of a deposit already on a cycle of this stream (SHADOW_CONFIRMED included). */
  public static MatchDecision match(PaycheckEvent event, PaycheckStream stream, Optional<PaycheckCycle> open,
                                    Optional<LocalDate> lastConfirmed, LocalDateTime firstSeenET) {
    if (!CONFIRMING_STATES.contains(stream.getState())) {
      return MatchDecision.LOGGED_STREAM_STATE;
    }
    if (open.isEmpty() || open.get().getState() != PaycheckCycleState.OPEN) {
      return MatchDecision.PERIOD_TAKEN;
    }
    LocalDate posted = event.getPostedDate();
    if (ChronoUnit.DAYS.between(posted, firstSeenET.toLocalDate()) > LATE_SEEN_DAYS) {
      return MatchDecision.LATE_SEEN;
    }
    if (!amountMatches(event.getAmount(), stream)) {
      return MatchDecision.AMOUNT_MISMATCH;
    }
    boolean inWindow = inWindow(open.get(), posted);
    boolean gapHolds = lastConfirmed.map(last -> gapHolds(last, posted, stream.getCadence())).orElse(true);
    if (!gapHolds) {
      return inWindow ? MatchDecision.GAP_TOO_SHORT : MatchDecision.OUTSIDE_WINDOW_SECONDARY;
    }
    if (!inWindow && lastConfirmed.isEmpty()) { // rule 5 needs a known deposit to measure the gap from
      return MatchDecision.OUTSIDE_WINDOW_SECONDARY;
    }
    if (stream.getDepositsFound() < TRUST_MIN_DEPOSITS) { // T11's evaluate ran first and counted this deposit
      return MatchDecision.STREAM_UNTRUSTED;
    }
    return MatchDecision.CONFIRMABLE;
  }

  public static boolean inWindow(PaycheckCycle cycle, LocalDate date) {
    return !date.isBefore(cycle.getWindowStart()) && !date.isAfter(cycle.getWindowEnd());
  }

  private static boolean amountMatches(BigDecimal amount, PaycheckStream stream) {
    BigDecimal median = stream.getTrailingMedian();
    if (amount == null || median == null) {
      return false;
    }
    BigDecimal cv = stream.getTrailingCv();
    BigDecimal floor = cv != null && cv.compareTo(CV_VOLATILE) > 0 ? AMOUNT_FLOOR_VOLATILE : AMOUNT_FLOOR;
    return amount.compareTo(median.multiply(floor)) >= 0;
  }

  private static boolean gapHolds(LocalDate last, LocalDate posted, InvestmentFrequency cadence) {
    if (cadence == null) {
      return false;
    }
    double periodDays = PayCalendar.periodDays(cadence);
    return ChronoUnit.DAYS.between(last, posted) >= Math.ceil(GAP_FRACTION * periodDays)
        && !posted.isBefore(BusinessDays.plus(last, GAP_MIN_BUSINESS_DAYS));
  }
}
```

`MissDetector` (package `C`; imports `M.{PaycheckCycle, PaycheckCycleState, PaycheckStream}`, `java.time.{LocalDate, LocalDateTime}`, `java.util.{Comparator, List, Optional}`):

```java
public final class MissDetector {

  public static final int MISS_GRACE_DAYS = 7;
  public static final int STALE_CONFIRMED_DAYS = 7;
  public static final int DORMANT_RETIRE_DAYS = 120;

  private MissDetector() {
  }

  public static boolean missed(PaycheckCycle open, LocalDate todayET) {
    return open.getState() == PaycheckCycleState.OPEN && open.getWindowEnd() != null
        && open.getWindowEnd().isBefore(todayET.minusDays(MISS_GRACE_DAYS));
  }

  public static boolean stale(PaycheckCycle cycle, LocalDateTime nowET) {
    return cycle.getState() == PaycheckCycleState.CONFIRMED && cycle.getConfirmedAt() != null
        && cycle.getConfirmedAt().isBefore(nowET.minusDays(STALE_CONFIRMED_DAYS));
  }

  public static Optional<LocalDate> dormantSince(PaycheckStream stream, List<PaycheckCycle> cycles) {
    return cycles.stream()
        .filter(c -> c.getState() == PaycheckCycleState.MISSED && stream.getId().equals(c.getStreamId()))
        .filter(c -> stream.getLastDate() == null || c.getExpectedDate().isAfter(stream.getLastDate()))
        .map(PaycheckCycle::getWindowEnd)
        .min(Comparator.naturalOrder());
  }

  public static boolean retireDue(LocalDate dormantSince, LocalDate todayET) {
    return !todayET.isBefore(dormantSince.plusDays(DORMANT_RETIRE_DAYS));
  }
}
```

Run the Step 1 command, then the same with `MissDetectorTest`. Expected: `BUILD SUCCESSFUL`; counts `tests="7"` and `tests="2"`, each `skipped="0" failures="0" errors="0"`.

- [ ] **Step 3: Failing tests for the gate chain and the would-have-pulled amount**

`CycleGateChainTest` (package `C`; imports `com.investingapp.backend.config.PaycheckAutopilotProperties`, `M.*`, `org.junit.jupiter.api.*`, `org.springframework.core.annotation.Order`, `org.springframework.stereotype.Component`, `java.math.BigDecimal`, `java.util.*`, static `assertThat`, static `org.mockito.Mockito.{mock, when}`):

```java
class CycleGateChainTest {

  private PaycheckAutopilotProperties props;

  @BeforeEach
  void setUp() {
    props = mock(PaycheckAutopilotProperties.class);
  }

  private CycleContext ctx() {
    return new CycleContext(new User("ada@example.com"), Optional.empty(), new PaycheckStream(), new PaycheckEvent(),
        new BigDecimal("150.00"), props);
  }

  @Test
  void shadowGateRunsFirstBeforeAnyNotice() {
    when(props.isShadow()).thenReturn(true);
    CycleGateChain chain = new CycleGateChain(List.of(new KillSwitchGate(), new ShadowGate())); // sorted by @Order
    assertThat(chain.gates()).extracting(g -> g.getClass().getSimpleName()).containsExactly("ShadowGate", "KillSwitchGate");
    assertThat(chain.firstSkip(ctx())).contains(PaycheckSkipReason.SHADOW_CONFIRMED);
    assertThat(ShadowGate.class.getAnnotation(Order.class).value()).isEqualTo(10);
    assertThat(KillSwitchGate.class.getAnnotation(Order.class).value()).isEqualTo(20);
    assertThat(CycleGateChain.class.getAnnotation(Component.class)).isNotNull();
  }

  @Test
  void killSwitchDecidesWhenShadowIsOff() {
    CycleGateChain chain = new CycleGateChain(List.of(new ShadowGate(), new KillSwitchGate()));
    when(props.isShadow()).thenReturn(false);
    assertThat(chain.firstSkip(ctx())).contains(PaycheckSkipReason.KILL_SWITCH);
    when(props.isPullsEnabled()).thenReturn(true);
    assertThat(chain.firstSkip(ctx())).isEmpty();
  }
}
```

`WouldHavePulledTest` (package `C`; imports `M.*`, `D.AmountPrefill`, `org.junit.jupiter.api.*`, `java.math.BigDecimal`, `java.util.{Arrays, Optional}`, static `assertThat`):

```java
class WouldHavePulledTest {

  private static final BigDecimal SHARE = new BigDecimal("0.90");
  private PaycheckStream stream;
  private User user;

  @BeforeEach
  void setUp() {
    stream = new PaycheckStream();
    stream.setCadence(InvestmentFrequency.BIWEEKLY);
    stream.setTrailingMedian(new BigDecimal("2418.00"));
    user = new User("ada@example.com");
  }

  @Test
  void manualRowAmountIsUsedAsIsAndNeverCapped() {
    InvestmentSchedule schedule = new InvestmentSchedule();
    schedule.setInvestmentAmount(new BigDecimal("5000.00"));
    assertThat(WouldHavePulled.amount(Optional.of(schedule), user, stream, SHARE)).isEqualByComparingTo("5000.00");
  }

  @Test
  void prefillFromSurveyGoalWhenNoRowIsCapped() {
    user.setMonthlyInvestment(650.0);
    assertThat(WouldHavePulled.amount(Optional.empty(), user, stream, SHARE)).isEqualByComparingTo("300");
    user.setMonthlyInvestment(6000.0);
    assertThat(WouldHavePulled.amount(Optional.empty(), user, stream, SHARE)).isEqualByComparingTo("2176");
  }

  @Test
  void missingGoalUsesTheDefaultGoalPrefill() {
    var expected = AmountPrefill.compute(null, InvestmentFrequency.BIWEEKLY, new BigDecimal("2418.00"), SHARE);
    assertThat(expected.goalSource()).isEqualTo("default");
    for (Double goal : Arrays.asList(null, 0.0)) {
      user.setMonthlyInvestment(goal);
      assertThat(WouldHavePulled.amount(Optional.empty(), user, stream, SHARE))
          .isEqualByComparingTo(BigDecimal.valueOf(expected.prefillAmount()));
    }
  }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.cycle.CycleGateChainTest' --console=plain`. Expected: `compileTestJava` fails with `error: cannot find symbol` naming `class CycleContext`, `class CycleGateChain`, `class ShadowGate`, `class KillSwitchGate` and `class WouldHavePulled`.

- [ ] **Step 4: The gate seam and WouldHavePulled**

`CycleGate` (package `C`; imports `M.PaycheckSkipReason`, `java.util.Optional`):

```java
public interface CycleGate {
  Optional<PaycheckSkipReason> skip(CycleContext ctx);
}
```

`CycleContext` (package `C`; imports `com.investingapp.backend.config.PaycheckAutopilotProperties`, `M.{InvestmentSchedule, PaycheckEvent, PaycheckStream, User}`, `java.math.BigDecimal`, `java.util.Optional`):

```java
public record CycleContext(User user, Optional<InvestmentSchedule> schedule, PaycheckStream stream, PaycheckEvent event,
                           BigDecimal wouldPull, PaycheckAutopilotProperties props) {
}
```

`ShadowGate` (package `C`; imports `M.PaycheckSkipReason`, `org.springframework.core.annotation.Order`, `org.springframework.stereotype.Component`, `java.util.Optional`):

```java
@Component
@Order(10)
public class ShadowGate implements CycleGate {
  @Override
  public Optional<PaycheckSkipReason> skip(CycleContext ctx) {
    return ctx.props().isShadow() ? Optional.of(PaycheckSkipReason.SHADOW_CONFIRMED) : Optional.empty();
  }
}
```

`KillSwitchGate` (spec 4.7 step 10, Decision 9; package `C`, the same imports as `ShadowGate`):

```java
@Component
@Order(20)
public class KillSwitchGate implements CycleGate {
  @Override
  public Optional<PaycheckSkipReason> skip(CycleContext ctx) {
    return ctx.props().isPullsEnabled() ? Optional.empty() : Optional.of(PaycheckSkipReason.KILL_SWITCH);
  }
}
```

`CycleGateChain` (package `C`; imports `M.PaycheckSkipReason`, `org.springframework.core.annotation.AnnotationAwareOrderComparator`, `org.springframework.stereotype.Component`, `java.util.{ArrayList, List, Optional}`):

```java
@Component
public class CycleGateChain {

  private final List<CycleGate> gates;

  public CycleGateChain(List<CycleGate> gates) {
    List<CycleGate> sorted = new ArrayList<>(gates);
    AnnotationAwareOrderComparator.sort(sorted); // a hand-built list cannot run out of order
    this.gates = List.copyOf(sorted);
  }

  public Optional<PaycheckSkipReason> firstSkip(CycleContext ctx) {
    for (CycleGate gate : gates) {
      Optional<PaycheckSkipReason> reason = gate.skip(ctx);
      if (reason.isPresent()) {
        return reason;
      }
    }
    return Optional.empty();
  }

  public List<CycleGate> gates() {
    return gates;
  }
}
```

`WouldHavePulled` (package `C`; imports `M.{InvestmentSchedule, PaycheckStream, User}`, `D.AmountPrefill`, `java.math.BigDecimal`, `java.util.Optional`):

```java
public final class WouldHavePulled {

  private WouldHavePulled() {
  }

  public static BigDecimal amount(Optional<InvestmentSchedule> schedule, User user, PaycheckStream stream, BigDecimal share) {
    Optional<BigDecimal> manual = schedule.map(InvestmentSchedule::getInvestmentAmount);
    if (manual.isPresent()) {
      return manual.get();
    }
    Double goal = user.getMonthlyInvestment(); // new User(email) starts at 0.0: no goal, as T11's monthlyGoal reads it
    var prefill = AmountPrefill.compute(goal == null || goal <= 0 ? null : goal, stream.getCadence(),
        stream.getTrailingMedian(), share);
    return BigDecimal.valueOf(prefill.prefillAmount());
  }
}
```

Run the Step 3 command, then the same with `WouldHavePulledTest`. Expected: `BUILD SUCCESSFUL`; counts `tests="2"` and `tests="3"`, each `skipped="0" failures="0" errors="0"`.

- [ ] **Step 5: Failing PaycheckCycleService tests (opening and applying deposits)**

The deposit descriptor is the spec 4.3 example, which tokenizes to `ACME CORP`; the schedule's status is never set, only read.

`PaycheckCycleServiceTest` (package `com.investingapp.backend.service`; imports `com.investingapp.backend.config.PaycheckAutopilotProperties`, `M.*`, `C.*`, `D.*`, `R.*`, `org.junit.jupiter.api.*`, `org.mockito.ArgumentCaptor`, `org.springframework.transaction.annotation.*`, `java.io.IOException`, `java.lang.reflect.*`, `java.math.BigDecimal`, `java.nio.file.*`, `java.time.*`, `java.util.*`, `java.util.stream.Stream`, static `M.PaycheckCycleState.{OPEN, SKIPPED, MISSED, CONFIRMED}`, static `org.assertj.core.api.Assertions.{assertThat, assertThatThrownBy}`, static `org.mockito.ArgumentMatchers.any`, static `org.mockito.Mockito.{mock, never, times, verify, when}`; Step 7 uses the same list):

```java
class PaycheckCycleServiceTest {

  private static final Long USER_ID = 42L;
  private static final LocalDate EXPECTED = LocalDate.of(2026, 9, 25);
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 25, 9, 5);

  private PaycheckCycleRepository cycleRepository;
  private PaycheckStreamRepository streamRepository;
  private PaycheckEventRepository eventRepository;
  private PaycheckAutopilotProperties props;
  private PaycheckCycleService service;
  private User user;
  private InvestmentSchedule schedule;
  private PaycheckStream primary;
  private PaycheckCycle open;
  private PaycheckEvent deposit;

  @BeforeEach
  void setUp() {
    cycleRepository = mock(PaycheckCycleRepository.class);
    streamRepository = mock(PaycheckStreamRepository.class);
    eventRepository = mock(PaycheckEventRepository.class);
    InvestmentScheduleRepository scheduleRepository = mock(InvestmentScheduleRepository.class);
    UserRepository userRepository = mock(UserRepository.class);
    props = mock(PaycheckAutopilotProperties.class);
    when(props.isShadow()).thenReturn(true);
    when(props.getAffordabilityShare()).thenReturn(new BigDecimal("0.90"));
    service = new PaycheckCycleService(cycleRepository, streamRepository, eventRepository, scheduleRepository,
        userRepository, new CycleGateChain(List.of(new ShadowGate(), new KillSwitchGate())), props);

    user = new User("ada@example.com");
    user.setId(USER_ID);
    schedule = new InvestmentSchedule();
    schedule.setId(9L);
    schedule.setUser(user);
    schedule.setInvestmentAmount(new BigDecimal("150.00"));
    schedule.setPrimaryStreamId(7L);
    primary = new PaycheckStream();
    primary.setId(7L);
    primary.setUserId(USER_ID);
    primary.setPlaidAccountId("acc-1");
    primary.setPayerToken("ACME CORP");
    primary.setState(PaycheckStreamState.PROVISIONAL);
    primary.setRole(PaycheckStreamRole.PRIMARY);
    primary.setCadence(InvestmentFrequency.BIWEEKLY);
    primary.setCadenceSource(PaycheckCadenceSource.OBSERVED);
    primary.setAnchorDow((short) 5);
    primary.setAnchorParityDate(LocalDate.of(2026, 9, 11));
    primary.setTrailingMedian(new BigDecimal("2418.00"));
    primary.setTrailingCv(new BigDecimal("0.0500"));
    primary.setDepositsFound((short) 4);
    primary.setLastDate(LocalDate.of(2026, 9, 11));
    open = cycle(100L, USER_ID, OPEN, EXPECTED);
    deposit = deposit(500L, "acc-1", "ACME CORP PAYROLL 2418 PPD");

    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(schedule));
    when(streamRepository.findById(7L)).thenReturn(Optional.of(primary));
    when(eventRepository.findById(500L)).thenReturn(Optional.of(deposit));
    when(cycleRepository.findByUserIdAndState(USER_ID, OPEN)).thenReturn(List.of(open));
  }

  private static PaycheckCycle cycle(Long id, Long userId, PaycheckCycleState state, LocalDate expected) {
    PaycheckCycle c = new PaycheckCycle();
    c.setId(id);
    c.setUserId(userId);
    c.setStreamId(7L);
    c.setScheduleId(9L);
    c.setState(state);
    c.setPeriodKey(expected.toString());
    c.setExpectedDate(expected);
    c.setWindowStart(expected.minusDays(7));
    c.setWindowEnd(expected.plusDays(11));
    c.setCreatedAt(NOW.minusDays(14));
    return c;
  }

  private static PaycheckEvent deposit(Long id, String accountId, String descriptor) {
    PaycheckEvent e = new PaycheckEvent();
    e.setId(id);
    e.setUserId(USER_ID);
    e.setPlaidAccountId(accountId);
    e.setAmount(new BigDecimal("2418.00"));
    e.setRawAmount(new BigDecimal("-2418.00"));
    e.setPostedDate(EXPECTED);
    e.setStatus(PaycheckEventStatus.ACTIVE);
    e.setFirstSeenAt(LocalDateTime.of(2026, 9, 25, 9, 0));
    e.setRawDescriptor(descriptor);
    return e;
  }

  private List<PaycheckCycle> savedCycles(int times) {
    ArgumentCaptor<PaycheckCycle> saved = ArgumentCaptor.forClass(PaycheckCycle.class);
    verify(cycleRepository, times(times)).save(saved.capture());
    return saved.getAllValues();
  }

  private List<MatchDecision> apply(Optional<InvestmentSchedule> row, Long... ids) {
    return service.applyNewDeposits(user, row, primary, List.of(ids), NOW);
  }

  @Test
  void openCycleForPrimaryWritesOpenCycleAndMirror() {
    when(cycleRepository.findByUserIdAndState(USER_ID, OPEN)).thenReturn(List.of());
    CadenceResult cadence = PaycheckCycleService.cadenceOf(primary);
    LocalDate expected = PayCalendar.predictedNext(cadence, primary.getLastDate());
    var window = PayCalendar.window(InvestmentFrequency.BIWEEKLY, expected);

    PaycheckCycle c = service.openCycleForPrimary(USER_ID, 7L).orElseThrow();

    assertThat(c).extracting(PaycheckCycle::getState, PaycheckCycle::getStreamId, PaycheckCycle::getScheduleId,
            PaycheckCycle::getExpectedDate, PaycheckCycle::getPeriodKey, PaycheckCycle::getWindowStart, PaycheckCycle::getWindowEnd)
        .containsExactly(OPEN, 7L, 9L, expected, PayCalendar.periodKey(cadence, expected), window.start(), window.end());
    assertThat(schedule.getNextExpectedDate()).isEqualTo(expected);
    when(cycleRepository.findByUserIdAndState(USER_ID, OPEN)).thenReturn(List.of(c));
    assertThat(service.openCycleForPrimary(USER_ID, 7L)).contains(c); // the second call writes nothing
    assertThat(savedCycles(1)).containsExactly(c);
  }

  @Test
  void shadowSkipRecordsWouldHaveAmountFromManualRow() {
    assertThat(apply(Optional.of(schedule), 500L)).containsExactly(MatchDecision.CONFIRMABLE);
    assertThat(open).extracting(PaycheckCycle::getState, PaycheckCycle::getSkipReason, PaycheckCycle::getEventId,
        PaycheckCycle::getConfirmedAt).containsExactly(SKIPPED, PaycheckSkipReason.SHADOW_CONFIRMED, 500L, NOW);
    assertThat(open.getDepositAmount()).isEqualByComparingTo("2418.00");
    assertThat(open.getAmountToPull()).isEqualByComparingTo("150.00");
    LocalDate nextExpected = PayCalendar.predictedNext(PaycheckCycleService.cadenceOf(primary), EXPECTED);
    PaycheckCycle next = savedCycles(2).get(1);
    assertThat(next.getState()).isEqualTo(OPEN);
    assertThat(next.getExpectedDate()).isEqualTo(nextExpected);
    assertThat(schedule.getNextExpectedDate()).isEqualTo(nextExpected);
    assertThat(schedule.getHoldReason()).isNull();
    assertThat(schedule.getMode()).isEqualTo(ScheduleMode.MANUAL);
    assertThat(deposit.getStreamId()).isEqualTo(7L);
    assertThat(primary.getLastDate()).isEqualTo(EXPECTED);
  }

  @Test
  void shadowPrefillFromSurveyGoalWhenNoRow() {
    user.setMonthlyInvestment(650.0);
    apply(Optional.empty(), 500L);
    assertThat(open.getSkipReason()).isEqualTo(PaycheckSkipReason.SHADOW_CONFIRMED);
    assertThat(open.getAmountToPull()).isEqualByComparingTo("300");
    assertThat(savedCycles(2).get(1).getScheduleId()).isNull();
  }

  @Test
  void confirmedUnreachableWhilePullsDisabled() {
    when(props.isShadow()).thenReturn(false);
    apply(Optional.of(schedule), 500L);
    assertThat(open.getSkipReason()).isEqualTo(PaycheckSkipReason.KILL_SWITCH);
    assertThat(savedCycles(2)).noneMatch(c -> c.getState() == CONFIRMED);
  }

  @Test
  void noSkipIsAnIllegalStateInPhaseOne() {
    when(props.isShadow()).thenReturn(false);
    when(props.isPullsEnabled()).thenReturn(true);
    assertThatThrownBy(() -> apply(Optional.of(schedule), 500L))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("no CONFIRMED writer");
    assertThat(open.getState()).isEqualTo(OPEN);
    verify(cycleRepository, never()).save(any());
  }

  @Test
  void depositsOfOtherPayersAreIgnored() {
    PaycheckEvent otherAccount = deposit(501L, "acc-2", "ACME CORP PAYROLL 2418 PPD");
    when(eventRepository.findById(501L)).thenReturn(Optional.of(otherAccount));
    deposit.setRawDescriptor("VENMO CASHOUT JANE");
    assertThat(apply(Optional.of(schedule), 500L, 501L)).isEmpty();
    assertThat(deposit.getStreamId()).isNull();
    verify(cycleRepository, never()).save(any());
  }

  @Test
  void lateSeenClosesOpenCycleWithoutWouldHaveAmount() {
    deposit.setFirstSeenAt(LocalDateTime.of(2026, 10, 5, 8, 0));
    assertThat(apply(Optional.of(schedule), 500L)).containsExactly(MatchDecision.LATE_SEEN);
    assertThat(open.getSkipReason()).isEqualTo(PaycheckSkipReason.LATE_SEEN);
    assertThat(open.getEventId()).isEqualTo(500L);
    assertThat(open.getConfirmedAt()).isNull();
    assertThat(open.getAmountToPull()).isNull();
  }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PaycheckCycleServiceTest' --console=plain`. Expected: `compileTestJava` fails with `error: cannot find symbol` naming `class PaycheckCycleService`.

- [ ] **Step 6: PaycheckCycleService, part 1 (opening cycles and applying deposits)**

`.../service/PaycheckCycleService.java` (package `com.investingapp.backend.service`; imports, covering part 2 too: `com.investingapp.backend.config.PaycheckAutopilotProperties`, `M.{DetectionState, InvestmentSchedule, PaycheckCycle, PaycheckCycleState, PaycheckEvent, PaycheckEventStatus, PaycheckRetiredReason, PaycheckSkipReason, PaycheckStream, PaycheckStreamRole, PaycheckStreamState, User}`, `C.{CycleContext, CycleGateChain, CycleMatcher, MatchDecision, MissDetector, WouldHavePulled}`, `D.{CadenceResult, PayCalendar, PayerTokenizer}`, `R.{InvestmentScheduleRepository, PaycheckCycleRepository, PaycheckEventRepository, PaycheckStreamRepository, UserRepository}`, `com.investingapp.backend.util.EasternTime`, `org.slf4j.{Logger, LoggerFactory}`, `org.springframework.stereotype.Service`, `org.springframework.transaction.annotation.{Propagation, Transactional}`, `java.math.BigDecimal`, `java.time.{LocalDate, LocalDateTime}`, `java.util.{ArrayDeque, ArrayList, Comparator, Deque, EnumSet, List, Objects, Optional, Set}`, `java.util.stream.Collectors`):

```java
/** Spec 4.6, 4.7, 6.4 cycles and reconcile passes. MANDATORY writers; never status, mode, hold, notice or analytics. */
@Service
public class PaycheckCycleService {

  private static final Logger logger = LoggerFactory.getLogger(PaycheckCycleService.class);

  static final int OPEN_ATTEMPTS = 3;        // periods to step past one already taken
  static final int MAX_MISSES_PER_RUN = 60;  // per user per run, so an outage cannot loop

  private static final Set<PaycheckCycleState> CLOSED_WITH_DEPOSIT =
      EnumSet.of(PaycheckCycleState.SKIPPED, PaycheckCycleState.CONFIRMED, PaycheckCycleState.QUEUED);
  private static final Set<PaycheckStreamState> TRUSTED =
      EnumSet.of(PaycheckStreamState.PROVISIONAL, PaycheckStreamState.CONFIRMED, PaycheckStreamState.DORMANT);

  private final PaycheckCycleRepository cycleRepository;
  private final PaycheckStreamRepository streamRepository;
  private final PaycheckEventRepository eventRepository;
  private final InvestmentScheduleRepository scheduleRepository;
  private final UserRepository userRepository;
  private final CycleGateChain gateChain;
  private final PaycheckAutopilotProperties props;

  public PaycheckCycleService(PaycheckCycleRepository cycleRepository, PaycheckStreamRepository streamRepository,
                              PaycheckEventRepository eventRepository, InvestmentScheduleRepository scheduleRepository,
                              UserRepository userRepository, CycleGateChain gateChain, PaycheckAutopilotProperties props) {
    this.cycleRepository = cycleRepository;
    this.streamRepository = streamRepository;
    this.eventRepository = eventRepository;
    this.scheduleRepository = scheduleRepository;
    this.userRepository = userRepository;
    this.gateChain = gateChain;
    this.props = props;
  }

  public static CadenceResult cadenceOf(PaycheckStream stream) {
    return new CadenceResult(stream.getCadence(), stream.getCadenceSource(),
        stream.getAnchorDow() == null ? null : stream.getAnchorDow().intValue(),
        stream.getAnchorParityDate(), stream.getAnchorDay1(), stream.getAnchorDay2(), 0, true, 0);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<PaycheckCycle> openCycleForPrimary(Long userId, Long primaryStreamId) {
    Optional<PaycheckStream> primary = streamRepository.findById(primaryStreamId)
        .filter(s -> userId.equals(s.getUserId()) && TRUSTED.contains(s.getState())
            && s.getCadence() != null && s.getLastDate() != null);
    if (primary.isEmpty()) {
      return Optional.empty();
    }
    CadenceResult cadence = cadenceOf(primary.get());
    return openCycleIfNone(primary.get(), scheduleOf(userId), cadence,
        PayCalendar.predictedNext(cadence, primary.get().getLastDate()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<PaycheckCycle> openCycleIfNone(PaycheckStream primary, Optional<InvestmentSchedule> schedule,
                                                 CadenceResult cadence, LocalDate expected) {
    Optional<PaycheckCycle> open = openCycleOf(primary);
    if (open.isEmpty() && cadence.cadence() != null && expected != null) {
      open = createFirstFreePeriod(primary, schedule, cadence, expected);
    }
    open.ifPresent(cycle -> {
      primary.setPredictedNextDate(cycle.getExpectedDate());
      schedule.filter(s -> mirrors(s, primary)).ifPresent(s -> s.setNextExpectedDate(cycle.getExpectedDate()));
    });
    return open;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public List<MatchDecision> applyNewDeposits(Long userId, Long primaryStreamId, List<Long> eventIds, LocalDateTime nowET) {
    User user = userRepository.findById(userId).orElse(null);
    PaycheckStream primary = streamRepository.findById(primaryStreamId).orElse(null);
    if (user == null || primary == null || !userId.equals(primary.getUserId())) {
      return List.of();
    }
    return applyNewDeposits(user, scheduleRepository.findTopByUserOrderByCreatedAtDesc(user), primary, eventIds, nowET);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public List<MatchDecision> applyNewDeposits(User user, Optional<InvestmentSchedule> schedule, PaycheckStream primary,
                                              List<Long> eventIds, LocalDateTime nowET) {
    List<PaycheckEvent> events = new ArrayList<>();
    for (Long id : eventIds) {
      eventRepository.findById(id).ifPresent(events::add);
    }
    events.sort(Comparator.comparing(PaycheckEvent::getPostedDate).thenComparing(PaycheckEvent::getId));
    Set<String> ppdIds = knownPpdIds(primary);
    CadenceResult cadence = cadenceOf(primary);
    List<MatchDecision> decisions = new ArrayList<>();
    for (PaycheckEvent event : events) {
      if (!isPostedInflow(event) || !belongsTo(event, primary, ppdIds)) {
        continue;
      }
      List<PaycheckCycle> closed = closedCyclesOf(primary);
      if (closed.stream().anyMatch(c -> event.getId().equals(c.getEventId()))) {
        continue; // a replayed deposit already sits on a cycle
      }
      event.setStreamId(primary.getId());
      Optional<PaycheckCycle> open = openCycleOf(primary);
      MatchDecision decision = CycleMatcher.match(event, primary, open, lastConfirmed(closed), event.getFirstSeenAt());
      if (decision == MatchDecision.CONFIRMABLE) {
        decision = skipThroughGates(user, schedule, primary, cadence, open, event, nowET);
      } else if (decision == MatchDecision.LATE_SEEN) {
        open.filter(c -> CycleMatcher.inWindow(c, event.getPostedDate())).ifPresent(c -> {
          close(c, event, PaycheckSkipReason.LATE_SEEN, null, null, nowET);
          afterDeposit(schedule, primary, cadence, event, nowET);
        });
      }
      logger.debug("paycheck.cycle.match user={} event={} decision={}", user.getId(), event.getId(), decision);
      decisions.add(decision);
    }
    return decisions;
  }

  private MatchDecision skipThroughGates(User user, Optional<InvestmentSchedule> schedule, PaycheckStream primary,
                                         CadenceResult cadence, Optional<PaycheckCycle> open, PaycheckEvent event,
                                         LocalDateTime nowET) {
    LocalDate posted = event.getPostedDate();
    PaycheckCycle target = open.filter(c -> CycleMatcher.inWindow(c, posted)).orElse(null);
    if (target == null) { // rule 5: outside the window with the gap holding, the deposit opens its own period
      String key = PayCalendar.periodKey(cadence, posted);
      if (open.isPresent() && key.equals(open.get().getPeriodKey())) {
        target = open.get();
      } else if (cycleRepository.findByStreamIdAndPeriodKey(primary.getId(), key).isPresent()) {
        return MatchDecision.PERIOD_TAKEN;
      } else {
        target = newCycle(primary, schedule, cadence, posted, key, nowET);
      }
    }
    BigDecimal wouldPull = WouldHavePulled.amount(schedule, user, primary, props.getAffordabilityShare());
    PaycheckSkipReason reason = gateChain.firstSkip(new CycleContext(user, schedule, primary, event, wouldPull, props))
        .orElseThrow(() -> new IllegalStateException(
            "Phase 1 has no CONFIRMED writer: a cycle gate must skip (shadow or kill switch)"));
    close(target, event, reason, wouldPull, nowET, nowET);
    afterDeposit(schedule, primary, cadence, event, nowET);
    return MatchDecision.CONFIRMABLE;
  }

  private void close(PaycheckCycle cycle, PaycheckEvent event, PaycheckSkipReason reason, BigDecimal amountToPull,
                     LocalDateTime confirmedAt, LocalDateTime nowET) {
    cycle.setState(PaycheckCycleState.SKIPPED);
    cycle.setSkipReason(reason);
    cycle.setEventId(event.getId());
    cycle.setDepositAmount(event.getAmount());
    cycle.setConfirmedAt(confirmedAt);
    cycle.setAmountToPull(amountToPull);
    cycle.setUpdatedAt(nowET);
    cycleRepository.save(cycle);
    logger.info("paycheck.cycle.skipped reason={}", reason);
    logger.debug("paycheck.cycle.skipped user={} cycle={} stream={}", cycle.getUserId(), cycle.getId(), cycle.getStreamId());
  }

  /** deposits_found, median and CV stay with PaycheckDetectionService, which recomputes them from events. */
  private void afterDeposit(Optional<InvestmentSchedule> schedule, PaycheckStream primary, CadenceResult cadence,
                            PaycheckEvent event, LocalDateTime nowET) {
    LocalDate posted = event.getPostedDate();
    if (primary.getLastDate() == null || !posted.isBefore(primary.getLastDate())) {
      primary.setLastDate(posted);
      primary.setLastAmount(event.getAmount());
    }
    primary.setMissedCount((short) 0);
    if (primary.getState() == PaycheckStreamState.DORMANT) {
      primary.setState(PaycheckStreamState.PROVISIONAL);
    }
    primary.setUpdatedAt(nowET);
    streamRepository.save(primary);
    schedule.filter(s -> mirrors(s, primary)).ifPresent(s -> {
      s.setMissedCount((short) 0);
      s.setDormantSince(null);
      if (s.getDetectionState() == DetectionState.DORMANT) {
        s.setDetectionState(DetectionState.PROVISIONAL);
      }
    });
    openCycleIfNone(primary, schedule, cadence, PayCalendar.predictedNext(cadence, posted));
  }

  private Optional<PaycheckCycle> createFirstFreePeriod(PaycheckStream primary, Optional<InvestmentSchedule> schedule,
                                                        CadenceResult cadence, LocalDate expected) {
    LocalDate candidate = expected;
    for (int attempt = 0; attempt < OPEN_ATTEMPTS; attempt++) {
      String key = PayCalendar.periodKey(cadence, candidate);
      if (cycleRepository.findByStreamIdAndPeriodKey(primary.getId(), key).isEmpty()) {
        PaycheckCycle cycle = newCycle(primary, schedule, cadence, candidate, key, EasternTime.now());
        cycleRepository.save(cycle);
        return Optional.of(cycle);
      }
      candidate = PayCalendar.predictedNext(cadence, candidate);
    }
    logger.warn("paycheck.cycle.no_free_period attempts={}", OPEN_ATTEMPTS);
    return Optional.empty();
  }

  private static PaycheckCycle newCycle(PaycheckStream primary, Optional<InvestmentSchedule> schedule,
                                        CadenceResult cadence, LocalDate expected, String periodKey, LocalDateTime createdAt) {
    var window = PayCalendar.window(cadence.cadence(), expected);
    PaycheckCycle cycle = new PaycheckCycle();
    cycle.setUserId(primary.getUserId());
    cycle.setScheduleId(schedule.map(InvestmentSchedule::getId).orElse(null));
    cycle.setStreamId(primary.getId());
    cycle.setPeriodKey(periodKey);
    cycle.setState(PaycheckCycleState.OPEN);
    cycle.setExpectedDate(expected);
    cycle.setWindowStart(window.start());
    cycle.setWindowEnd(window.end());
    cycle.setCreatedAt(createdAt);
    return cycle;
  }

  private Optional<PaycheckCycle> openCycleOf(PaycheckStream stream) {
    return cycleRepository.findByUserIdAndState(stream.getUserId(), PaycheckCycleState.OPEN).stream()
        .filter(c -> c.getState() == PaycheckCycleState.OPEN && stream.getId().equals(c.getStreamId()))
        .min(Comparator.comparing(PaycheckCycle::getExpectedDate));
  }

  private List<PaycheckCycle> closedCyclesOf(PaycheckStream stream) {
    List<PaycheckCycle> closed = new ArrayList<>();
    for (PaycheckCycleState state : CLOSED_WITH_DEPOSIT) {
      cycleRepository.findByUserIdAndState(stream.getUserId(), state).stream()
          .filter(c -> stream.getId().equals(c.getStreamId()) && c.getEventId() != null)
          .forEach(closed::add);
    }
    return closed;
  }

  private Optional<LocalDate> lastConfirmed(List<PaycheckCycle> closed) {
    List<Long> eventIds = closed.stream().map(PaycheckCycle::getEventId).toList();
    if (eventIds.isEmpty()) {
      return Optional.empty();
    }
    return eventRepository.findAllById(eventIds).stream().map(PaycheckEvent::getPostedDate).max(Comparator.naturalOrder());
  }

  private Set<String> knownPpdIds(PaycheckStream primary) {
    return eventRepository.findByStreamIdAndStatusOrderByPostedDateDesc(primary.getId(), PaycheckEventStatus.ACTIVE)
        .stream().map(PaycheckEvent::getPpdId).filter(Objects::nonNull).collect(Collectors.toSet());
  }

  private static boolean isPostedInflow(PaycheckEvent e) {
    return e.getStatus() == PaycheckEventStatus.ACTIVE && !e.isPending()
        && e.getRawAmount() != null && e.getRawAmount().signum() < 0;
  }

  /** raw_descriptor is original_description else name (T07), so it stands for both token arguments. */
  private static boolean belongsTo(PaycheckEvent e, PaycheckStream primary, Set<String> ppdIds) {
    if (!Objects.equals(e.getPlaidAccountId(), primary.getPlaidAccountId())) {
      return false;
    }
    if (primary.getId().equals(e.getStreamId())) {
      return true;
    }
    if (e.getRawDescriptor() != null && primary.getPayerToken() != null
        && primary.getPayerToken().equals(PayerTokenizer.token(e.getRawDescriptor(), e.getRawDescriptor()))) {
      return true;
    }
    return e.getPpdId() != null && ppdIds.contains(e.getPpdId());
  }

  private Optional<InvestmentSchedule> scheduleOf(Long userId) {
    return userRepository.findById(userId).flatMap(scheduleRepository::findTopByUserOrderByCreatedAtDesc);
  }

  private static boolean mirrors(InvestmentSchedule schedule, PaycheckStream stream) {
    return schedule.getPrimaryStreamId() == null || schedule.getPrimaryStreamId().equals(stream.getId());
  }
}
```

Run the Step 5 command. Expected: `BUILD SUCCESSFUL`; count `tests="7" skipped="0" failures="0" errors="0"`.

- [ ] **Step 7: Failing tests for the reconcile passes and the guards**

Add to `PaycheckCycleServiceTest`, before its final closing brace:

```java
  @Test
  void missSetsDormantWithoutHoldOrStatusChange() {
    InvestmentSchedule.Status statusBefore = schedule.getStatus();
    assertThat(service.markMissesForUser(USER_ID, LocalDate.of(2026, 10, 14))).isEqualTo(1);
    assertThat(open.getState()).isEqualTo(MISSED);
    assertThat(primary.getState()).isEqualTo(PaycheckStreamState.DORMANT);
    assertThat(primary.getMissedCount()).isEqualTo((short) 1);
    assertThat(schedule).extracting(InvestmentSchedule::getDetectionState, InvestmentSchedule::getMissedCount,
            InvestmentSchedule::getDormantSince, InvestmentSchedule::getHoldReason, InvestmentSchedule::getStatus,
            InvestmentSchedule::getMode)
        .containsExactly(DetectionState.DORMANT, (short) 1, LocalDate.of(2026, 10, 6), null, statusBefore, ScheduleMode.MANUAL);
    PaycheckCycle next = savedCycles(2).get(1);
    assertThat(next.getState()).isEqualTo(OPEN);
    assertThat(next.getExpectedDate()).isEqualTo(PayCalendar.predictedNext(PaycheckCycleService.cadenceOf(primary), EXPECTED));
  }

  @Test
  void retiresAfter120DaysKeepsTokenAndCadence() {
    primary.setState(PaycheckStreamState.DORMANT);
    primary.setMissedCount((short) 9);
    primary.setLastDate(LocalDate.of(2026, 5, 1));
    PaycheckCycle miss = cycle(101L, USER_ID, MISSED, LocalDate.of(2026, 5, 15)); // window_end 2026-05-26
    when(cycleRepository.findByUserIdAndState(USER_ID, MISSED)).thenReturn(List.of(miss));
    when(streamRepository.findByUserIdAndState(USER_ID, PaycheckStreamState.DORMANT)).thenReturn(List.of(primary));
    assertThat(service.retireDormantForUser(USER_ID, LocalDate.of(2026, 9, 22))).isZero();
    assertThat(service.retireDormantForUser(USER_ID, LocalDate.of(2026, 9, 23))).isEqualTo(1);
    assertThat(primary).extracting(PaycheckStream::getState, PaycheckStream::getRetiredReason, PaycheckStream::getPayerToken,
            PaycheckStream::getCadence, PaycheckStream::getAnchorDow)
        .containsExactly(PaycheckStreamState.RETIRED, PaycheckRetiredReason.DORMANT_120, "ACME CORP",
            InvestmentFrequency.BIWEEKLY, (short) 5);
    assertThat(primary.getRetiredAt()).isNotNull();
    assertThat(primary.getTrailingMedian()).isEqualByComparingTo("2418.00");
    assertThat(schedule.getPrimaryStreamId()).isEqualTo(7L); // R6: stream state only
  }

  @Test
  void staleConfirmedBecomesSkippedStale() {
    PaycheckCycle confirmed = cycle(102L, USER_ID, CONFIRMED, EXPECTED);
    confirmed.setConfirmedAt(NOW.minusDays(8));
    when(cycleRepository.findByUserIdAndState(USER_ID, CONFIRMED)).thenReturn(List.of(confirmed));
    assertThat(service.markStaleForUser(USER_ID, NOW)).isEqualTo(1);
    assertThat(confirmed.getState()).isEqualTo(SKIPPED);
    assertThat(confirmed.getSkipReason()).isEqualTo(PaycheckSkipReason.STALE);
  }

  @Test
  void reconcileFindersReturnDistinctUsersAndPinCutoffs() {
    PaycheckCycle a = cycle(1L, 43L, OPEN, EXPECTED);
    PaycheckCycle b = cycle(2L, USER_ID, OPEN, EXPECTED);
    when(cycleRepository.findByStateAndWindowEndBefore(OPEN, LocalDate.of(2026, 10, 7))).thenReturn(List.of(a, b, a));
    assertThat(service.usersWithOverdueOpenCycles(LocalDate.of(2026, 10, 14))).containsExactly(42L, 43L);
    // 120 days on or before today means window_end before today minus 119.
    when(cycleRepository.findByStateAndWindowEndBefore(MISSED, LocalDate.of(2026, 5, 27))).thenReturn(List.of(b));
    assertThat(service.usersWithDormantStreamsOver(LocalDate.of(2026, 9, 23))).containsExactly(42L);
    when(cycleRepository.findByStateAndConfirmedAtBefore(CONFIRMED, NOW.minusDays(7))).thenReturn(List.of(a));
    assertThat(service.usersWithStaleConfirmed(NOW)).containsExactly(43L);
  }

  @Test
  void cycleCodeIsMandatoryAndTouchesNoHoldStatusModeNoticeOrVendor() throws IOException {
    for (Method m : PaycheckCycleService.class.getDeclaredMethods()) {
      Transactional tx = m.getAnnotation(Transactional.class);
      if (m.getName().startsWith("usersWith")) {
        assertThat(tx.readOnly()).as(m.getName()).isTrue();
      } else if (Modifier.isPublic(m.getModifiers()) && !m.getName().equals("cadenceOf")) {
        assertThat(tx.propagation()).as(m.getName()).isEqualTo(Propagation.MANDATORY);
      }
    }
    Path root = Path.of("src/main/java/com/investingapp/backend");
    List<Path> sources;
    try (Stream<Path> files = Files.walk(root.resolve("paycheck/cycle"))) {
      sources = new ArrayList<>(files.filter(p -> p.toString().endsWith(".java")).toList());
    }
    sources.add(root.resolve("service/PaycheckCycleService.java"));
    assertThat(sources).hasSize(10);
    // Concatenated so this test file never matches the source-walking invariance tests.
    List<String> forbidden = List.of("set" + "HoldReason(", "set" + "Status(", "set" + "Mode(", "getIs" + "Paused(",
        "isPausedFor" + "AnyReason(", "AnalyticsOutbox", "NotificationService", "EmailService", "PlaidApi", "Alpaca");
    for (Path source : sources) {
      String text = Files.readString(source);
      forbidden.forEach(token -> assertThat(text).as(source + " contains " + token).doesNotContain(token));
    }
  }
```

Run the Step 5 command. Expected: `compileTestJava` fails with `error: cannot find symbol` naming `method markMissesForUser(Long,LocalDate)` and the other five part 2 methods.

- [ ] **Step 8: PaycheckCycleService, part 2 (dormancy and the reconcile passes)**

Add to `PaycheckCycleService`, after the closing brace of `applyNewDeposits(User, ...)` and before `private MatchDecision skipThroughGates(`:

```java
  @Transactional(readOnly = true)
  public List<Long> usersWithOverdueOpenCycles(LocalDate todayET) {
    return distinctUsers(cycleRepository.findByStateAndWindowEndBefore(PaycheckCycleState.OPEN,
        todayET.minusDays(MissDetector.MISS_GRACE_DAYS)));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public int markMissesForUser(Long userId, LocalDate todayET) {
    Optional<InvestmentSchedule> schedule = scheduleOf(userId);
    Deque<PaycheckCycle> due = cycleRepository.findByUserIdAndState(userId, PaycheckCycleState.OPEN).stream()
        .filter(c -> MissDetector.missed(c, todayET))
        .sorted(Comparator.comparing(PaycheckCycle::getExpectedDate))
        .collect(Collectors.toCollection(ArrayDeque::new));
    LocalDateTime nowET = EasternTime.now();
    int marked = 0;
    while (!due.isEmpty() && marked < MAX_MISSES_PER_RUN) {
      PaycheckCycle cycle = due.poll();
      cycle.setState(PaycheckCycleState.MISSED);
      cycle.setUpdatedAt(nowET);
      cycleRepository.save(cycle);
      marked++;
      PaycheckStream stream = streamRepository.findById(cycle.getStreamId()).orElse(null);
      if (stream == null || !TRUSTED.contains(stream.getState())) {
        continue; // a RETIRED stream's leftover cycle closes without effect
      }
      stream.setMissedCount((short) Math.min(stream.getMissedCount() + 1, Short.MAX_VALUE));
      stream.setState(PaycheckStreamState.DORMANT);
      stream.setUpdatedAt(nowET);
      streamRepository.save(stream);
      schedule.filter(s -> mirrors(s, stream)).ifPresent(s -> {
        s.setDetectionState(DetectionState.DORMANT);
        s.setMissedCount(stream.getMissedCount());
        if (s.getDormantSince() == null) {
          s.setDormantSince(cycle.getWindowEnd());
        }
      });
      if (stream.getRole() == PaycheckStreamRole.PRIMARY) {
        CadenceResult cadence = cadenceOf(stream);
        openCycleIfNone(stream, schedule, cadence, PayCalendar.predictedNext(cadence, cycle.getExpectedDate()))
            .filter(next -> !due.contains(next) && MissDetector.missed(next, todayET))
            .ifPresent(due::add);
      }
    }
    if (marked > 0) {
      logger.info("paycheck.reconcile.misses marked={}", marked);
    }
    return marked;
  }

  @Transactional(readOnly = true)
  public List<Long> usersWithStaleConfirmed(LocalDateTime nowET) {
    return distinctUsers(cycleRepository.findByStateAndConfirmedAtBefore(PaycheckCycleState.CONFIRMED,
        nowET.minusDays(MissDetector.STALE_CONFIRMED_DAYS)));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public int markStaleForUser(Long userId, LocalDateTime nowET) {
    int stale = 0;
    for (PaycheckCycle cycle : cycleRepository.findByUserIdAndState(userId, PaycheckCycleState.CONFIRMED)) {
      if (MissDetector.stale(cycle, nowET)) {
        cycle.setState(PaycheckCycleState.SKIPPED);
        cycle.setSkipReason(PaycheckSkipReason.STALE);
        cycle.setUpdatedAt(nowET);
        cycleRepository.save(cycle);
        stale++;
      }
    }
    return stale;
  }

  @Transactional(readOnly = true)
  public List<Long> usersWithDormantStreamsOver(LocalDate todayET) {
    return distinctUsers(cycleRepository.findByStateAndWindowEndBefore(PaycheckCycleState.MISSED,
        todayET.minusDays(MissDetector.DORMANT_RETIRE_DAYS - 1)));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public int retireDormantForUser(Long userId, LocalDate todayET) {
    List<PaycheckCycle> missed = cycleRepository.findByUserIdAndState(userId, PaycheckCycleState.MISSED);
    LocalDateTime nowET = EasternTime.now();
    int retired = 0;
    for (PaycheckStream stream : streamRepository.findByUserIdAndState(userId, PaycheckStreamState.DORMANT)) {
      Optional<LocalDate> since = MissDetector.dormantSince(stream, missed);
      if (since.isPresent() && MissDetector.retireDue(since.get(), todayET)) {
        stream.setState(PaycheckStreamState.RETIRED);
        stream.setRetiredReason(PaycheckRetiredReason.DORMANT_120);
        stream.setRetiredAt(nowET);
        stream.setUpdatedAt(nowET);
        streamRepository.save(stream);
        retired++;
      }
    }
    if (retired > 0) {
      logger.info("paycheck.reconcile.retired count={}", retired);
    }
    return retired;
  }

  private static List<Long> distinctUsers(List<PaycheckCycle> cycles) {
    return cycles.stream().map(PaycheckCycle::getUserId).filter(Objects::nonNull).distinct().sorted().toList();
  }
```

Run the Step 5 command. Expected: `BUILD SUCCESSFUL`; count `tests="12" skipped="0" failures="0" errors="0"`.

- [ ] **Step 9: Failing wiring test, then wire PaycheckCycleService into PaycheckDetectionService**

`@InjectMocks` builds T11's service without restating its constructor (parameters without a mock get null; T11's body is never reached) and the spy stands in for T11's evaluation.

`PaycheckDetectionCycleWiringTest` (package `com.investingapp.backend.service`; imports `M.DetectionState`, `com.investingapp.backend.paycheck.*`, `C.MatchDecision`, `org.junit.jupiter.api.{BeforeEach, Test}`, `org.mockito.*`, `org.springframework.transaction.annotation.Transactional`, `java.time.*`, `java.util.*`, static `assertThat`, static `org.mockito.ArgumentMatchers.{any, anyLong, eq}`, static `org.mockito.Mockito.{doReturn, inOrder, never, spy, verify, when}`):

```java
class PaycheckDetectionCycleWiringTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);
  private static final EvaluationOutcome PROVISIONAL =
      new EvaluationOutcome(DetectionState.PROVISIONAL, 7L, true, false, Optional.empty());

  @Mock
  private PaycheckCycleService cycleService;
  @InjectMocks
  private PaycheckDetectionService real;
  private PaycheckDetectionService detection;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    detection = spy(real);
  }

  @Test
  void evaluateOpensCycleForNewPrimary() {
    doReturn(PROVISIONAL).when(detection).evaluateWithoutCycles(42L, EvaluationTrigger.FULL, TODAY);
    assertThat(detection.evaluate(42L, EvaluationTrigger.FULL, TODAY)).isEqualTo(PROVISIONAL);
    verify(cycleService).openCycleForPrimary(42L, 7L);
    EvaluationOutcome searching = new EvaluationOutcome(DetectionState.SEARCHING, null, false, false, Optional.empty());
    doReturn(searching).when(detection).evaluateWithoutCycles(43L, EvaluationTrigger.FULL, TODAY);
    assertThat(detection.evaluate(43L, EvaluationTrigger.FULL, TODAY)).isEqualTo(searching);
    EvaluationOutcome consent = new EvaluationOutcome(DetectionState.NEEDS_CONSENT, 7L, false, false, Optional.empty());
    doReturn(consent).when(detection).evaluateWithoutCycles(44L, EvaluationTrigger.FULL, TODAY);
    assertThat(detection.evaluate(44L, EvaluationTrigger.FULL, TODAY)).isEqualTo(consent); // T13 owns that mirror
    verify(cycleService, never()).openCycleForPrimary(eq(43L), any());
    verify(cycleService, never()).openCycleForPrimary(eq(44L), any());
  }

  @Test
  void evaluateAndApplyRunsIncrementalThenAppliesInOneTransaction() throws Exception {
    doReturn(PROVISIONAL).when(detection).evaluate(42L, EvaluationTrigger.INCREMENTAL, TODAY);
    when(cycleService.applyNewDeposits(eq(42L), eq(7L), eq(List.of(500L)), any(LocalDateTime.class)))
        .thenReturn(List.of(MatchDecision.CONFIRMABLE));
    assertThat(detection.evaluateAndApply(42L, List.of(500L), TODAY)).isEqualTo(PROVISIONAL);
    InOrder order = inOrder(detection, cycleService);
    order.verify(detection).evaluate(42L, EvaluationTrigger.INCREMENTAL, TODAY);
    order.verify(cycleService).applyNewDeposits(eq(42L), eq(7L), eq(List.of(500L)), any(LocalDateTime.class));
    assertThat(PaycheckDetectionService.class.getMethod("evaluateAndApply", Long.class, List.class, LocalDate.class)
        .getAnnotation(Transactional.class)).isNotNull();
  }

  @Test
  void appliedDepositClearsADormantOutcomeAndNoDepositsApplyNothing() {
    EvaluationOutcome dormant = new EvaluationOutcome(DetectionState.DORMANT, 7L, false, false, Optional.empty());
    doReturn(dormant).when(detection).evaluate(42L, EvaluationTrigger.INCREMENTAL, TODAY);
    when(cycleService.applyNewDeposits(eq(42L), eq(7L), eq(List.of(500L)), any(LocalDateTime.class)))
        .thenReturn(List.of(MatchDecision.CONFIRMABLE));
    assertThat(detection.evaluateAndApply(42L, List.of(500L), TODAY).state()).isEqualTo(DetectionState.PROVISIONAL);
    assertThat(detection.evaluateAndApply(42L, List.of(), TODAY)).isEqualTo(dormant);
    verify(cycleService, never()).applyNewDeposits(anyLong(), anyLong(), eq(List.of()), any(LocalDateTime.class));
  }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PaycheckDetectionCycleWiringTest' --console=plain`. Expected: `compileTestJava` fails with `error: cannot find symbol` naming `method evaluateWithoutCycles(Long,EvaluationTrigger,LocalDate)` and `method evaluateAndApply(Long,List<Long>,LocalDate)`.

Now edit `.../service/PaycheckDetectionService.java` (T11):

1. Imports: add whichever is absent of `M.DetectionState`, `C.MatchDecision`, `com.investingapp.backend.util.EasternTime`, `java.util.List`, `org.springframework.transaction.annotation.Transactional`.
2. Declare `private final PaycheckCycleService cycleService;` after the last `private final` field; append `PaycheckCycleService cycleService` as the constructor's last parameter and `this.cycleService = cycleService;` as its last statement (under Lombok `@RequiredArgsConstructor` the field alone suffices).
3. Replace these two lines, fixed by T11's contract and quoted at their real four-space indentation (any javadoc above them stays):

```text
    @Transactional
    public EvaluationOutcome evaluate(Long userId, EvaluationTrigger trigger, LocalDate todayET) {
```

with:

```java
  @Transactional
  public EvaluationOutcome evaluate(Long userId, EvaluationTrigger trigger, LocalDate todayET) {
    EvaluationOutcome outcome = evaluateWithoutCycles(userId, trigger, todayET);
    if (cyclesApply(outcome)) { // T11's applyGroup already keeps a missed stream DORMANT
      cycleService.openCycleForPrimary(userId, outcome.primaryStreamId());
    }
    return outcome;
  }

  /** No cycle work when excluded, without a primary, or under T13's NEEDS_CONSENT mirror. */
  private static boolean cyclesApply(EvaluationOutcome o) {
    return o.excluded().isEmpty() && o.primaryStreamId() != null && o.state() != DetectionState.NEEDS_CONSENT;
  }

  @Transactional
  public EvaluationOutcome evaluateAndApply(Long userId, List<Long> newPostedInflowEventIds, LocalDate todayET) {
    EvaluationOutcome outcome = evaluate(userId, EvaluationTrigger.INCREMENTAL, todayET);
    if (!cyclesApply(outcome) || newPostedInflowEventIds == null || newPostedInflowEventIds.isEmpty()) {
      return outcome;
    }
    List<MatchDecision> decisions = cycleService.applyNewDeposits(userId, outcome.primaryStreamId(),
        newPostedInflowEventIds, EasternTime.now());
    return outcome.state() == DetectionState.DORMANT && decisions.contains(MatchDecision.CONFIRMABLE)
        ? withCycleState(outcome, DetectionState.PROVISIONAL)
        : outcome;
  }

  private static EvaluationOutcome withCycleState(EvaluationOutcome outcome, DetectionState state) {
    return new EvaluationOutcome(state, outcome.primaryStreamId(), outcome.primaryChanged(), outcome.switched(),
        outcome.excluded());
  }

  /** T11's evaluation, unchanged. Package-private so the wiring test can stand in for it. */
  EvaluationOutcome evaluateWithoutCycles(Long userId, EvaluationTrigger trigger, LocalDate todayET) {
```

T11's body and closing brace stay as they are. `evaluateAndApply` calls `evaluate` on `this`; both are REQUIRED, so T11's `findByIdForUpdate` lock covers `applyNewDeposits`.

4. From the repository root run `grep -rn 'new PaycheckDetectionService(' backend/src/test`; in every hit add `mock(PaycheckCycleService.class)` as the last argument (plus `import static org.mockito.Mockito.mock;` if absent). No hit means no edit.

Run the wiring command again, then `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PaycheckDetectionServiceTest' --console=plain`. Expected: `BUILD SUCCESSFUL`; wiring count `tests="3" skipped="0" failures="0" errors="0"`; `PaycheckDetectionServiceTest` keeps T11's count (`tests="12"`, T11 Step 8) with `failures="0"`.

- [ ] **Step 10: Run everything this task touches, then the context boot on a scratch MySQL**

```
cd backend
./gradlew cleanTest test --tests 'com.investingapp.backend.paycheck.cycle.*' --tests 'com.investingapp.backend.service.PaycheckCycleServiceTest' --tests 'com.investingapp.backend.service.PaycheckDetectionCycleWiringTest' --tests 'com.investingapp.backend.service.PaycheckDetectionServiceTest' --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-*.xml
```

Expected: `BUILD SUCCESSFUL`; lines `tests="7"`, `"2"`, `"2"`, `"3"` (`paycheck.cycle`), `"12"`, `"3"`, then T11's and the invariance test's counts (its walk now covers this task's ten files), all `skipped="0" failures="0" errors="0"`.

Only `BackendApplicationTests` (`@RequiresCiDatabase`) proves the new beans resolve; it skips without a database, so read the XML, never the console:

```
cd backend
read -rs FRED_SCRATCH_PW            # the local MySQL root password; nothing is echoed
mysql -u root -p"$FRED_SCRATCH_PW" -e "CREATE DATABASE fred_t12_scratch"
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t12_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD="$FRED_SCRATCH_PW"
export PLAID_WEBHOOK_URL=https://ci.invalid/api/plaid/webhook PLAID_REDIRECT_URI=https://ci.invalid/plaid/oauth PLAID_LINK_CUSTOMIZATION=default
./gradlew cleanTest test --tests 'com.investingapp.backend.BackendApplicationTests' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml
mysql -u root -p"$FRED_SCRATCH_PW" -e "DROP DATABASE fred_t12_scratch"
unset FRED_SCRATCH_PW SPRING_DATASOURCE_PASSWORD SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME
```

Expected: `tests="1" skipped="0" failures="0" errors="0"`; `skipped="1"` means the database was not reached, so fix the connection and rerun.

Em dash guard, from the repository root (must list none of this task's files):

```
perl -CSD -ne 'print "$ARGV\n" if /\x{2014}/' $(git ls-files -mo --exclude-standard backend/src)
```

- [ ] **Step 11: Commit**

Hunk-verify `PaycheckDetectionService.java` and `PaycheckDetectionServiceTest.java` with `git diff HEAD -- <file>` (shared dirty tree; never `git checkout`, `git restore`, `git stash` or `git reset`) and check with `git status --short` that the two `paycheck/cycle` directories hold only this task's 13 files. Then:

```
git add backend/src/main/java/com/investingapp/backend/paycheck/cycle backend/src/test/java/com/investingapp/backend/paycheck/cycle backend/src/main/java/com/investingapp/backend/service/PaycheckCycleService.java backend/src/main/java/com/investingapp/backend/service/PaycheckDetectionService.java backend/src/test/java/com/investingapp/backend/service/PaycheckCycleServiceTest.java backend/src/test/java/com/investingapp/backend/service/PaycheckDetectionCycleWiringTest.java backend/src/test/java/com/investingapp/backend/service/PaycheckDetectionServiceTest.java
git commit -m "feat(paycheck): shadow-skipped cycles, the cycle gate chain and reconcile-side dormancy"
```

Leave `PaycheckDetectionServiceTest.java` out when Step 9's grep had no hit. Never stage `application-local.properties` or `ITPM/agent-memory/*`. End the message with the session's attribution lines.

## Verification

Step 10's commands, read with the count command, all `skipped="0" failures="0" errors="0"`: `CycleMatcherTest` 7, `MissDetectorTest` 2, `CycleGateChainTest` 2, `WouldHavePulledTest` 3, `PaycheckCycleServiceTest` 12, `PaycheckDetectionCycleWiringTest` 3, T11's and the invariance test's own counts; scratch-MySQL `BackendApplicationTests` 1; the em dash guard prints none of this task's files. This task adds 29 tests; the plan's final whole-suite scratch run (Step 10's exports, then `./gradlew cleanTest test`) totals the previous count plus 29.

## Produces

- `C.MatchDecision` (the eight values above); `CycleMatcher.match(PaycheckEvent, PaycheckStream, Optional<PaycheckCycle> open, Optional<LocalDate> lastConfirmed, LocalDateTime firstSeenET)` with `LATE_SEEN_DAYS = 7`, `AMOUNT_FLOOR = 0.60`, `AMOUNT_FLOOR_VOLATILE = 0.45`, `CV_VOLATILE = 0.35`, `GAP_FRACTION = 0.60`, `GAP_MIN_BUSINESS_DAYS = 5` (added: `TRUST_MIN_DEPOSITS`, `inWindow`).
- `CycleGate.skip(CycleContext)`; `record CycleContext(User, Optional<InvestmentSchedule>, PaycheckStream, PaycheckEvent, BigDecimal wouldPull, PaycheckAutopilotProperties props)`; beans `ShadowGate` `@Order(10)`, `KillSwitchGate` `@Order(20)`; `CycleGateChain(List<CycleGate>)` with `firstSkip(CycleContext)` (added: `gates()`).
- `WouldHavePulled.amount(Optional<InvestmentSchedule>, User, PaycheckStream, BigDecimal share)`; `MissDetector.missed(PaycheckCycle open, LocalDate todayET)`, `DORMANT_RETIRE_DAYS = 120` (added: `MISS_GRACE_DAYS`, `STALE_CONFIRMED_DAYS`, `stale`, `dormantSince`, `retireDue`).
- `PaycheckCycleService` (constructor as in Step 6): MANDATORY `openCycleIfNone(PaycheckStream, Optional<InvestmentSchedule>, CadenceResult, LocalDate)`, `List<MatchDecision> applyNewDeposits(User, Optional<InvestmentSchedule>, PaycheckStream, List<Long>, LocalDateTime)`, `int markMissesForUser(Long, LocalDate)`, `int markStaleForUser(Long, LocalDateTime)`, `int retireDormantForUser(Long, LocalDate)`; read-only `List<Long> usersWithOverdueOpenCycles(LocalDate)`, `usersWithStaleConfirmed(LocalDateTime)`, `usersWithDormantStreamsOver(LocalDate)`; added: MANDATORY `openCycleForPrimary(Long, Long)`, `applyNewDeposits(Long, Long, List<Long>, LocalDateTime)`, static `cadenceOf(PaycheckStream)`.
- `PaycheckDetectionService.evaluateAndApply(Long userId, List<Long> newPostedInflowEventIds, LocalDate todayET)` (`@Transactional`, T14 calls it); `evaluate` keeps its signature and now opens the primary's cycle; T11's body is package-private `evaluateWithoutCycles`; constructor gains `PaycheckCycleService` last.
- Phase 2 seam: more `CycleGate` beans, then `confirm(...)` replacing the `IllegalStateException` in `skipThroughGates`.
