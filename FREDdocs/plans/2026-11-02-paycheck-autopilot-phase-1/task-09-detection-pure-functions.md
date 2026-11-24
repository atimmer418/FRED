### Task 9: detection-pure-functions

Spec 4.2 to 4.6, 4.9 and the basic 6.1 succession rule (R5) as pure static functions in the new package `com.investingapp.backend.paycheck.detect`: no repository, Plaid, Alpaca, transaction, outbox, logger or clock, so the spec's examples are the fixtures and shadow tuning (12.4) edits numbers here only. Callers: T11 `evaluate`, T12 cycles, T16 status. `D/` is `backend/src/main/java/com/investingapp/backend/paycheck/detect/`, `TD/` is the same path under `backend/src/test/java/`. HEAD 137998c; every file is new.

**Facts relied on** (handoff, rulings, T02 and T03):
- Nothing here moves money or touches `InvestmentSchedule.status` (PENDING_BANK, ACTIVE, PAUSED_BY_USER; never `is_paused` or `getIsPaused()`), `mode`, `holdReason` or `isReadyForInvestment`, writes a row, emits analytics, alerts or logs, so no payer name, descriptor, amount or date can leak. T03's `PhaseOneMoneyInvarianceTest` walks `paycheck/**`. No migration (V10 is T01's), no property (`application-local.properties` needs no edit), no event.
- T07 stores `amount` positive, Plaid's `raw_amount` as sent (negative is money in), depository inflows only, no LOW-confidence counterparty; Plaid enums are `getValue()` strings (plaid-java 46.0.0 sources: `MATURE`, `TOMBSTONED`, `UNKNOWN`, `ENUM_UNKNOWN`, `SEMI_MONTHLY`, `income_source`, `payment_app`, `VERY_HIGH`).
- Scores 55 and 70 come from `PaycheckAutopilotProperties`; the 0.90 share is the caller's argument.

**Rules settled where the spec leaves room** (each pinned by a test):
1. Cadence: from 3 gaps, one gap may be up to 4 days outside the band. SEMI_MONTHLY is two day-of-month anchors in turn, each date within 2 business days of its anchor (days 1 to 3 are `1`, 28 to 31 or the last business day `LAST_BD`, others the cluster's modal day); alternating gaps exclude BIWEEKLY. OBSERVED needs 3+ gaps fitting one cadence; a BIWEEKLY or SEMI_MONTHLY tie goes to the fit with no outlier, then Plaid, then BIWEEKLY, sourced PLAID. A lone 28-day gap is constant spacing.
2. Periods 7, 14, 15, 30 days; the SEMI_MONTHLY window widens 2 business days a side; keys use the nominal pay date.
3. Gates: a per-deposit signal counts when over half the group carries it; PFC is the latest deposit's, else the Plaid stream's; `NOT_DEPOSITORY` never fires (T07 stores none); `UNKNOWN` is rejected, `ENUM_UNKNOWN` is not.
4. Same-payer reads `payer_token` only (V6 streams have no `ppd_id`). Succession (1) reads state PROVISIONAL or CONFIRMED and `depositsFound >= 2`, (3) reads `role != INTERRUPTION`. `goalSource` is `survey` or `default`.

**Files.** Create in `D/`: `BusinessDays`, `DescriptorSignals`, `PayerTokenizer`, `DepositGroup`, `DepositGrouper`, `DepositStats`, `CadenceResult`, `PayCalendar`, `CadenceDeriver`, `StreamFacts`, `HardGates`, `ScoreBreakdown`, `StreamScorer`, `StreamStateRule`, `AmountPrefill`, `PrimarySelector`, `SuccessionRule`. Test in `TD/`: `DetectFixtures` and a `<Class>Test` for each class except the records `DepositGroup` and `CadenceResult` (covered by `DepositGrouperTest`, `CadenceDeriverTest`). Modify: nothing.

**Interfaces.** Consumes: T02 `PaycheckEvent`, `PaycheckStream` accessors as used below (`getCadence()` is `InvestmentFrequency`; `getScore`, `getDepositsFound`, `getMissedCount` `short`; `getPlaidIsActive` `Boolean`), enums `PaycheckStreamState`, `PaycheckStreamRole`, `PaycheckCadenceSource`, `PaycheckRejectReason`, `util.ColumnText.fit(String, int)`; T03 `PaycheckAutopilotProperties.getScoreProvisional()`, `getScoreConfirmed()` (mocked); `InvestmentFrequency.java` `monthlyFactor()` :41, `perYear()` :46, `toMonthly` :51, `tryParse` :79; `InvestmentSchedule.java` `public LocalDate adjustForBusinessDay(LocalDate date)` (:204 at HEAD, moved by T02; test only), the rules of `private boolean isUSHoliday`. Produces: the recap below.

**Conventions.** Files start with `package com.investingapp.backend.paycheck.detect;`. Imports, the one mechanical repetition left out, are exactly the types each file names (from `java.math`, `java.time[.format|.temporal]`, `java.util[.concurrent.atomic|.function|.stream]`, `com.investingapp.backend.model|config|util`, `org.junit.jupiter.api`); a bare static in a test is `assertThat`, `mock`, `when`, an enum constant, `ANCHOR_LAST_BD`, or a member of the class under test or `DetectFixtures`. None is `@RequiresCiDatabase`. A failing step expects `BUILD FAILED` at `:compileTestJava` with `error: cannot find symbol` naming the classes listed. A passing step expects `BUILD SUCCESSFUL` and reads the count from the XML, never the console: from `backend/`, `grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.paycheck.detect.<Class>.xml`, every one `skipped="0" failures="0"`.

- [ ] **Step 1: Failing test for BusinessDays.** Create `TD/BusinessDaysTest.java`:

```java
class BusinessDaysTest {

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    void parityWithScheduleAdjuster() {
        for (LocalDate day = d("2025-01-01"); day.isBefore(d("2029-01-01")); day = day.plusDays(1)) {
            LocalDate next = day;
            while (!BusinessDays.isBusinessDay(next)) { next = next.plusDays(1); }
            assertThat(new InvestmentSchedule().adjustForBusinessDay(day)).as("adjusting %s", day).isEqualTo(next);
        }
    }

    @Test
    void plusMinusBetween() {
        assertThat(BusinessDays.plus(d("2026-11-25"), 1)).isEqualTo(d("2026-11-27")); // Thanksgiving
        assertThat(BusinessDays.minus(d("2026-11-30"), 2)).isEqualTo(d("2026-11-25"));
        assertThat(BusinessDays.between(d("2026-02-09"), d("2026-02-06"))).isEqualTo(-1);
    }

    @Test
    void adjustBackwardMovesEarlyNeverLate() {
        assertThat(BusinessDays.adjustBackward(d("2026-02-15"))).isEqualTo(d("2026-02-13"));
        assertThat(BusinessDays.adjustBackward(d("2026-01-01"))).isEqualTo(d("2025-12-31"));
        assertThat(BusinessDays.adjustBackward(d("2026-01-19"))).isEqualTo(d("2026-01-16")); // MLK Day
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.BusinessDaysTest' --console=plain`. Expected: missing `BusinessDays`.

- [ ] **Step 2: BusinessDays.** Create `D/BusinessDays.java`:

```java
/** Weekdays off InvestmentSchedule.isUSHoliday's table (pinned by parityWithScheduleAdjuster). */
public final class BusinessDays {

    public static boolean isBusinessDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !isHoliday(date);
    }

    public static LocalDate plus(LocalDate date, int n) {
        return step(date, n, n < 0 ? -1 : 1);
    }

    public static LocalDate minus(LocalDate date, int n) {
        return step(date, n, n < 0 ? 1 : -1);
    }

    private static LocalDate step(LocalDate date, int n, int direction) {
        LocalDate d = date;
        for (int left = Math.abs(n); left > 0; ) {
            d = d.plusDays(direction);
            if (isBusinessDay(d)) { left--; }
        }
        return d;
    }

    public static int between(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) { return -between(to, from); }
        int count = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (isBusinessDay(d)) { count++; }
        }
        return count;
    }

    public static LocalDate adjustBackward(LocalDate date) {
        LocalDate d = date;
        while (!isBusinessDay(d)) { d = d.minusDays(1); }
        return d;
    }

    static boolean isHoliday(LocalDate date) {
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        boolean monday = date.getDayOfWeek() == DayOfWeek.MONDAY;
        int nth = (day - 1) / 7 + 1;
        return (month == 1 && day == 1) || (month == 7 && day == 4) || (month == 12 && day == 25) || (month == 11 && day == 11)
                || (monday && month == 1 && nth == 3) || (monday && month == 2 && nth == 3)
                || (monday && month == 5 && day + 7 > date.lengthOfMonth())
                || (monday && month == 9 && nth == 1) || (monday && month == 10 && nth == 2)
                || (month == 11 && date.getDayOfWeek() == DayOfWeek.THURSDAY && nth == 4);
    }
}
```

Run the Step 1 command. Expected: `tests="3"`.

- [ ] **Step 3: Failing tests, signals and payer token.** Create `TD/DescriptorSignalsTest.java`:

```java
class DescriptorSignalsTest {

    @Test
    void wholeWordsAndStemsOnly() {
        assertThat(hasBenefitsToken("SSA TREAS 310 XXSOC SEC")).isTrue();
        assertThat(hasBenefitsToken("STATE UNEMPLOYMENT INS")).isTrue();
        assertThat(hasBenefitsToken("MASSACHUSETTS MUTUAL PAYROLL")).isFalse();
        assertThat(hasPayrollToken("PAYROLLX REFUND")).isFalse();
        assertThat(hasPayrollToken("ACME CORP DIR DEP")).isTrue();
        assertThat(hasNonPayrollChannelToken("CASH-APP*JOHN")).isTrue();
        assertThat(hasNonPayrollChannelToken("XFER ACME CORP PAYROLL")).isFalse();
        assertThat(hasBenefitsToken("TRANSFER FROM ACME CORP")).isFalse();
    }
}
```

and `PayerTokenizerTest.java` (the 4.3 examples):

```java
class PayerTokenizerTest {

    @Test
    void specExamplesShareOneToken() {
        assertThat(token("ACME CORP PAYROLL 2418 PPD", null)).isEqualTo("ACME CORP");
        assertThat(token("ACME CORP DIR DEP", null)).isEqualTo("ACME CORP");
        assertThat(token("ACME CORP DES:PAYROLL ID:000123456 INDN:DOE", null)).isEqualTo("ACME CORP");
        assertThat(token("BIG 401 CO PAYROLL", null)).isEqualTo("BIG 401");
    }

    @Test
    void fallbacks() {
        assertThat(token(null, "Gusto Payroll")).isEqualTo("GUSTO");
        assertThat(token(null, null)).isEqualTo(UNKNOWN);
        assertThat(token("PAYROLL DEPOSIT 12345", null)).isEqualTo("PAYROLL DEPOSIT");
    }

    @Test
    void displayNameOrder() {
        assertThat(displayName("Acme Corporation", "income_source", "ACME", "ACME CORP")).isEqualTo("Acme Corporation");
        assertThat(displayName("Acme Corporation", "merchant", "Acme Merchant", "ACME CORP")).isEqualTo("Acme Merchant");
        assertThat(displayName(null, null, null, "ACME CORP")).isEqualTo("Acme Corp");
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.DescriptorSignalsTest' --console=plain`. Expected: missing `DescriptorSignals`, `PayerTokenizer`.

- [ ] **Step 4: DescriptorSignals and PayerTokenizer.** Create `D/DescriptorSignals.java`:

```java
/** Spec 4.2 (6), 4.3, 4.5 token lists, matched on whole words; UNEMPLOY and DISAB are stems. */
public final class DescriptorSignals {

    public static final List<String> BENEFITS_TOKENS = List.of("UI BENEFIT", "UNEMPLOY", "EDD", "TREAS 310", "SSA",
            "VA BENEFIT", "DISAB", "PFL", "WORKERS COMP", "CHILD SUPPORT", "IRS");
    public static final List<String> NON_PAYROLL_CHANNEL_TOKENS = List.of("VENMO", "ZELLE", "PAYPAL", "CASH APP", "CASHAPP",
            "MOBILE DEPOSIT", "ATM", "INSTANT PAY", "CASHOUT");
    public static final List<String> PAYROLL_TOKENS = List.of("ADP", "GUSTO", "PAYCHEX", "WORKDAY", "DFAS", "PAYROLL",
            "DIR DEP", "DIRECT DEP", "SALARY", "PAYRL", "PAYCHK");
    public static final List<String> PAYER_STRIP_WORDS = List.of("DIRECT DEPOSIT", "DIRECT DEP", "DIR DEP", "PAYROLL",
            "DEPOSIT", "DEP", "DES", "ACH", "CREDIT", "TRANSFER", "XFER", "PPD", "ID", "CO", "INC", "LLC");
    private static final List<String> STEMS = List.of("UNEMPLOY", "DISAB");

    public static boolean hasBenefitsToken(String descriptor) {
        return containsAny(descriptor, BENEFITS_TOKENS);
    }

    public static boolean hasNonPayrollChannelToken(String descriptor) {
        return containsAny(descriptor, NON_PAYROLL_CHANNEL_TOKENS);
    }

    public static boolean hasPayrollToken(String descriptor) {
        return containsAny(descriptor, PAYROLL_TOKENS);
    }

    static String normalize(String descriptor) {
        return descriptor == null ? "" : descriptor.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
    }

    static boolean containsToken(String descriptor, String token) {
        return (" " + normalize(descriptor) + " ").contains(" " + token + (STEMS.contains(token) ? "" : " "));
    }

    private static boolean containsAny(String descriptor, List<String> tokens) {
        return tokens.stream().anyMatch(token -> containsToken(descriptor, token));
    }
}
```

and `PayerTokenizer.java`:

```java
/** Spec 4.3 payer token (64 characters) and display name (120). */
public final class PayerTokenizer {

    public static final String UNKNOWN = "UNKNOWN";

    public static String token(String originalDescription, String name) {
        String source = isBlank(originalDescription) ? name : originalDescription;
        if (isBlank(source)) { return UNKNOWN; }
        String text = DescriptorSignals.normalize(source).replaceAll("\\d{4,}", " ").replaceAll("\\s+", " ").trim();
        String padded = " " + text + " ";
        for (String word : DescriptorSignals.PAYER_STRIP_WORDS) {
            while (padded.contains(" " + word + " ")) { padded = padded.replace(" " + word + " ", " "); }
        }
        String words = padded.isBlank() ? text : padded.trim();
        if (words.isEmpty()) { return UNKNOWN; }
        String[] parts = words.split(" ");
        return ColumnText.fit(parts.length == 1 ? parts[0] : parts[0] + " " + parts[1], 64);
    }

    public static String displayName(String counterpartyName, String counterpartyType, String merchantName, String token) {
        String chosen = !isBlank(counterpartyName) && "income_source".equals(counterpartyType) ? counterpartyName.trim()
                : !isBlank(merchantName) ? merchantName.trim() : titleCase(token);
        return ColumnText.fit(chosen, 120);
    }

    static String titleCase(String token) {
        if (isBlank(token)) { return "Unknown"; }
        StringBuilder out = new StringBuilder();
        for (String word : token.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            out.append(out.length() > 0 ? " " : "").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
```

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.DescriptorSignalsTest' --console=plain
cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.PayerTokenizerTest' --console=plain
```
Expected: `tests="1"`, then `tests="3"`.

- [ ] **Step 5: Fixtures, failing tests for grouping and statistics.** Create `TD/DetectFixtures.java` (Steps 11 and 15 add a method each):

```java
final class DetectFixtures {

    private static final AtomicLong IDS = new AtomicLong(1);

    static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    static List<LocalDate> dates(String... isoDates) {
        return Arrays.stream(isoDates).map(LocalDate::parse).toList();
    }

    static List<BigDecimal> amounts(String... values) {
        return Arrays.stream(values).map(BigDecimal::new).toList();
    }

    static PaycheckEvent deposit(String account, String isoDate, String amount, String descriptor) {
        PaycheckEvent e = new PaycheckEvent();
        e.setId(IDS.getAndIncrement());
        e.setPlaidAccountId(account);
        e.setAmount(new BigDecimal(amount));
        e.setRawAmount(new BigDecimal(amount).negate());
        e.setPostedDate(d(isoDate));
        e.setRawDescriptor(descriptor);
        return e;
    }

    static DepositGroup biweekly(int count, String amount, String descriptor) {
        List<PaycheckEvent> deposits = new ArrayList<>();
        for (int i = 0; i < count; i++) { deposits.add(deposit("acc-1", d("2026-01-09").plusDays(14L * i).toString(), amount, descriptor)); }
        return new DepositGroup("acc-1", PayerTokenizer.token(descriptor, null), List.copyOf(deposits), Set.of());
    }
}
```

`DepositGrouperTest.java`:

```java
class DepositGrouperTest {

    @Test
    void groupsByAccountAndPayerTokenOldestFirst() {
        PaycheckEvent first = deposit("acc-1", "2026-01-02", "2418.00", "ACME CORP PAYROLL 2418 PPD");
        PaycheckEvent second = deposit("acc-1", "2026-01-16", "2418.00", "ACME CORP DIR DEP");
        first.setPpdId("1234567890");
        second.setPpdId("1234567890");
        List<DepositGroup> groups = DepositGrouper.group(List.of(second, deposit("acc-2", "2026-01-09", "500.00", "ACME CORP PAYROLL"),
                first, deposit("acc-1", "2026-01-05", "300.00", "GUSTO PAYROLL")));

        assertThat(groups).extracting(g -> g.plaidAccountId() + "/" + g.payerToken()).containsExactly("acc-1/ACME CORP", "acc-1/GUSTO", "acc-2/ACME CORP");
        assertThat(groups.get(0).postedDatesAsc()).isEqualTo(dates("2026-01-02", "2026-01-16"));
        assertThat(groups.get(0).ppdIds()).containsExactly("1234567890");
        assertThat(biweekly(8, "1000.00", "ACME CORP PAYROLL").lastSixAmounts()).hasSize(6);
    }
}
```

and `DepositStatsTest.java`:

```java
class DepositStatsTest {

    @Test
    void medianOfTheLastSix() {
        assertThat(DepositStats.trailingMedian(amounts("2418.10", "2500", "2400", "2418.30"))).isEqualByComparingTo("2418.20");
        assertThat(DepositStats.trailingMedian(amounts("9000", "9000", "1000", "1000", "1000", "1000", "1000", "1100"))).isEqualByComparingTo("1000");
        assertThat(DepositStats.trailingMedian(List.of())).isNull();
    }

    @Test
    void cvAndMonthlyNormalized() {
        assertThat(DepositStats.trailingCv(amounts("900", "1100"))).isEqualByComparingTo("0.1000");
        assertThat(DepositStats.trailingCv(amounts("1000"))).isEqualByComparingTo("0");
        assertThat(DepositStats.monthlyNormalized(new BigDecimal("2418"), InvestmentFrequency.BIWEEKLY)).isEqualByComparingTo("5247.06");
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.DepositGrouperTest' --console=plain`. Expected: missing `DepositGroup`, `DepositGrouper`, `DepositStats`.

- [ ] **Step 6: DepositGroup, DepositGrouper, DepositStats.** Create `D/DepositGroup.java`:

```java
/** One (plaid_account_id, payer_token) group, oldest first (spec 4.3). */
public record DepositGroup(String plaidAccountId, String payerToken, List<PaycheckEvent> depositsAsc, Set<String> ppdIds) {

    public List<LocalDate> postedDatesAsc() {
        return depositsAsc.stream().map(PaycheckEvent::getPostedDate).toList();
    }

    public List<BigDecimal> lastSixAmounts() {
        int from = Math.max(0, depositsAsc.size() - DepositStats.TRAILING_COUNT);
        return depositsAsc.subList(from, depositsAsc.size()).stream().map(PaycheckEvent::getAmount).toList();
    }
}
```

`DepositGrouper.java`:

```java
/** Spec 4.3 grouping; drops nothing; ppd_id never merges groups in Phase 1. */
public final class DepositGrouper {

    private static final Comparator<PaycheckEvent> OLDEST_FIRST = Comparator.comparing(PaycheckEvent::getPostedDate)
            .thenComparing(PaycheckEvent::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    public static List<DepositGroup> group(List<PaycheckEvent> events) {
        Map<String, Map<String, List<PaycheckEvent>>> byAccount = new TreeMap<>();
        for (PaycheckEvent e : events) {
            byAccount.computeIfAbsent(e.getPlaidAccountId(), k -> new TreeMap<>())
                    .computeIfAbsent(PayerTokenizer.token(e.getRawDescriptor(), e.getMerchantName()), k -> new ArrayList<>())
                    .add(e);
        }
        List<DepositGroup> groups = new ArrayList<>();
        byAccount.forEach((account, byToken) -> byToken.forEach((token, list) -> {
            list.sort(OLDEST_FIRST);
            TreeSet<String> ppdIds = new TreeSet<>();
            list.stream().map(PaycheckEvent::getPpdId).filter(p -> p != null && !p.isBlank()).forEach(ppdIds::add);
            groups.add(new DepositGroup(account, token, List.copyOf(list), Collections.unmodifiableSet(ppdIds)));
        }));
        return groups;
    }
}
```

and `DepositStats.java`:

```java
/** Trailing statistics over the last six amounts. */
public final class DepositStats {

    public static final int TRAILING_COUNT = 6;

    public static BigDecimal trailingMedian(List<BigDecimal> lastSix) {
        List<BigDecimal> sorted = lastSixOf(lastSix).stream().sorted().toList();
        int n = sorted.size();
        if (n == 0) { return null; }
        BigDecimal median = n % 2 == 1 ? sorted.get(n / 2)
                : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        return median.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal trailingCv(List<BigDecimal> lastSix) {
        List<BigDecimal> values = lastSixOf(lastSix);
        double mean = values.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        if (values.size() < 2 || mean <= 0) { return BigDecimal.ZERO.setScale(4); }
        double variance = values.stream().mapToDouble(v -> Math.pow(v.doubleValue() - mean, 2)).sum() / values.size();
        return BigDecimal.valueOf(Math.sqrt(variance) / mean).setScale(4, RoundingMode.HALF_UP);
    }

    public static BigDecimal monthlyNormalized(BigDecimal perPaycheck, InvestmentFrequency cadence) {
        return cadence.toMonthly(perPaycheck);
    }

    private static List<BigDecimal> lastSixOf(List<BigDecimal> values) {
        return values == null ? List.of() : values.subList(Math.max(0, values.size() - TRAILING_COUNT), values.size());
    }
}
```

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.DepositGrouperTest' --console=plain
cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.DepositStatsTest' --console=plain
```
Expected: `tests="1"`, then `tests="2"`.

- [ ] **Step 7: Failing tests, cadence and calendar.** Create `TD/CadenceDeriverTest.java`:

```java
class CadenceDeriverTest {

    private static Optional<CadenceResult> derive(String plaid, String... isoDates) {
        return CadenceDeriver.derive(dates(isoDates), Optional.ofNullable(plaid));
    }

    @Test
    void weeklyFridays() {
        assertThat(derive(null, "2026-01-02", "2026-01-09", "2026-01-16", "2026-01-23", "2026-01-30", "2026-02-06"))
                .contains(new CadenceResult(WEEKLY, OBSERVED, 5, d("2026-02-06"), null, null, 5, true, 0));
    }

    @Test
    void biweeklyFridays() {
        assertThat(derive(null, "2026-01-09", "2026-01-23", "2026-02-06", "2026-02-20", "2026-03-06", "2026-03-20"))
                .contains(new CadenceResult(BIWEEKLY, OBSERVED, 5, d("2026-03-20"), null, null, 5, true, 0));
    }

    @Test
    void semiMonthlyFifteenthAndLastBusinessDay() {
        assertThat(derive(null, "2026-04-15", "2026-04-30", "2026-05-15", "2026-05-29", "2026-06-15", "2026-06-30"))
                .contains(new CadenceResult(SEMI_MONTHLY, OBSERVED, null, null, "15", ANCHOR_LAST_BD, 5, true, 0));
    }

    @Test
    void monthlyLastBusinessDay() {
        assertThat(derive(null, "2026-01-30", "2026-02-27", "2026-03-31", "2026-04-30"))
                .contains(new CadenceResult(MONTHLY, OBSERVED, null, null, ANCHOR_LAST_BD, null, 3, true, 0));
    }

    @Test
    void constantTwentyEightDaySpacingIsRejectedNotMonthly() {
        assertThat(derive("MONTHLY", "2026-01-02", "2026-01-30", "2026-02-27", "2026-03-27")).isEmpty();
        assertThat(derive("MONTHLY", "2026-01-30", "2026-02-27")).isEmpty(); // one anchor: only the 28-day rule rejects
    }

    @Test
    void plaidFrequencyBreaksABiweeklyOrSemiMonthlyTie() {
        String[] tie = {"2026-01-15", "2026-01-30", "2026-02-13"};
        assertThat(derive("SEMI_MONTHLY", tie)).contains(new CadenceResult(SEMI_MONTHLY, PLAID, null, null, "15", ANCHOR_LAST_BD, 2, true, 0));
        assertThat(derive(null, tie).orElseThrow().cadence()).isEqualTo(BIWEEKLY);
    }

    @Test
    void observedOnlyOnceThreeIntervalsAgree() {
        assertThat(derive(null, "2026-01-02", "2026-01-09", "2026-01-16").orElseThrow().source()).isEqualTo(PLAID);
        assertThat(derive(null, "2026-01-02", "2026-01-09", "2026-01-16", "2026-01-23").orElseThrow().source()).isEqualTo(OBSERVED);
    }

    @Test
    void oneLateDepositIsOneOutside() {
        assertThat(derive(null, "2026-01-09", "2026-01-23", "2026-02-06", "2026-02-20", "2026-03-06", "2026-03-23"))
                .contains(new CadenceResult(BIWEEKLY, OBSERVED, 5, d("2026-03-06"), null, null, 5, false, 1));
    }
}
```

and `PayCalendarTest.java`:

```java
class PayCalendarTest {

    private static final CadenceResult WEEK = new CadenceResult(WEEKLY, OBSERVED, 5, d("2026-02-06"), null, null, 5, true, 0);
    private static final CadenceResult FORTNIGHT = new CadenceResult(BIWEEKLY, OBSERVED, 5, d("2026-12-11"), null, null, 5, true, 0);
    private static final CadenceResult SEMI = dayBased(SEMI_MONTHLY, "15", ANCHOR_LAST_BD);
    private static final CadenceResult FIRST = dayBased(MONTHLY, "1", null);

    private static CadenceResult dayBased(InvestmentFrequency f, String a1, String a2) {
        return new CadenceResult(f, OBSERVED, null, null, a1, a2, 5, true, 0);
    }

    @Test
    void predictedNextMovesBackToABusinessDay() {
        assertThat(predictedNext(WEEK, d("2026-02-06"))).isEqualTo(d("2026-02-13"));
        assertThat(predictedNext(FORTNIGHT, d("2026-12-11"))).isEqualTo(d("2026-12-24")); // Christmas Friday
        assertThat(predictedNext(FORTNIGHT, d("2026-12-24"))).isEqualTo(d("2027-01-08"));
        assertThat(predictedNext(SEMI, d("2026-01-30"))).isEqualTo(d("2026-02-13"));
        assertThat(predictedNext(FIRST, d("2025-12-31"))).isEqualTo(d("2026-01-30"));
    }

    @Test
    void periodKeys() {
        assertThat(List.of(periodKey(WEEK, d("2026-02-13")), periodKey(FORTNIGHT, d("2026-12-24")), periodKey(SEMI, d("2026-02-13")),
                periodKey(SEMI, d("2026-02-27")), periodKey(FIRST, d("2026-01-30"))))
                .containsExactly("2026-02-13", "2026-12-25", "2026-02-A", "2026-02-B", "2026-02");
    }

    @Test
    void windows() {
        assertThat(window(BIWEEKLY, d("2026-02-13"))).isEqualTo(new CycleWindow(d("2026-02-06"), d("2026-02-25")));
        assertThat(window(SEMI_MONTHLY, d("2026-02-13"))).isEqualTo(new CycleWindow(d("2026-02-04"), d("2026-02-27")));
    }

    @Test
    void cadenceLabels() {
        assertThat(List.of(WEEK, FORTNIGHT, SEMI, FIRST, dayBased(MONTHLY, "22", null))).extracting(PayCalendar::cadenceLabel).containsExactly(
                "Every Friday", "Every other Friday", "Twice a month (15th and last business day)", "Monthly on the 1st", "Monthly on the 22nd");
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.CadenceDeriverTest' --console=plain`. Expected: missing `CadenceResult`, `CadenceDeriver`, `PayCalendar`.

- [ ] **Step 8: CadenceResult, PayCalendar, CadenceDeriver** (mutually dependent). Create `D/CadenceResult.java`:

```java
/** Spec 4.4 cadence; week-based anchors or day-of-month anchors. */
public record CadenceResult(InvestmentFrequency cadence, PaycheckCadenceSource source, Integer anchorDow,
        LocalDate anchorParityDate, String anchorDay1, String anchorDay2, int intervals, boolean allInBand, int outsideBand) {
}
```

`PayCalendar.java`:

```java
/** Spec 4.4 next date and labels, 4.6 keys and windows; display data, never a trigger. */
public final class PayCalendar {

    public record CycleWindow(LocalDate start, LocalDate end) {
    }

    public static final int WINDOW_BEFORE_BUSINESS_DAYS = 5;
    public static final int WINDOW_AFTER_BUSINESS_DAYS = 7;
    public static final int SEMI_MONTHLY_WIDEN_BUSINESS_DAYS = 2;

    public static int periodDays(InvestmentFrequency cadence) {
        return switch (cadence) {
            case WEEKLY -> 7;
            case BIWEEKLY -> 14;
            case SEMI_MONTHLY -> 15;
            case MONTHLY -> 30;
        };
    }

    public static LocalDate predictedNext(CadenceResult cadence, LocalDate lastDate) {
        LocalDate guard = lastDate.plusDays(periodDays(cadence.cadence()) / 2);
        LocalDate next = null;
        if (isWeekBased(cadence)) {
            next = cadence.anchorParityDate() != null ? cadence.anchorParityDate() : lastDate;
            while (!next.isAfter(guard)) { next = next.plusDays(periodDays(cadence.cadence())); }
        } else {
            for (int m = 0; m <= 2; m++) {
                for (String anchor : anchors(cadence)) {
                    LocalDate candidate = nominal(YearMonth.from(lastDate).plusMonths(m), anchor);
                    if (candidate.isAfter(guard) && (next == null || candidate.isBefore(next))) { next = candidate; }
                }
            }
        }
        return BusinessDays.adjustBackward(next);
    }

    public static String periodKey(CadenceResult cadence, LocalDate expected) {
        if (isWeekBased(cadence)) {
            int dow = cadence.anchorDow() != null ? cadence.anchorDow() : expected.getDayOfWeek().getValue();
            LocalDate nominal = expected.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(dow)));
            boolean offParity = cadence.cadence() == InvestmentFrequency.BIWEEKLY && cadence.anchorParityDate() != null
                    && Math.floorMod(ChronoUnit.DAYS.between(cadence.anchorParityDate(), nominal), 14) != 0;
            return (offParity ? nominal.plusDays(7) : nominal).toString();
        }
        List<String> anchors = anchors(cadence);
        LocalDate best = null;
        int bestIndex = 0;
        for (int m = -1; m <= 1; m++) {
            for (int i = 0; i < anchors.size(); i++) {
                LocalDate candidate = nominal(YearMonth.from(expected).plusMonths(m), anchors.get(i));
                if (!BusinessDays.adjustBackward(candidate).isBefore(expected) && (best == null || candidate.isBefore(best))) {
                    best = candidate;
                    bestIndex = i;
                }
            }
        }
        String suffix = cadence.cadence() == InvestmentFrequency.SEMI_MONTHLY ? (bestIndex == 0 ? "-A" : "-B") : "";
        return YearMonth.from(best) + suffix;
    }

    public static CycleWindow window(InvestmentFrequency cadence, LocalDate expected) {
        int widen = cadence == InvestmentFrequency.SEMI_MONTHLY ? SEMI_MONTHLY_WIDEN_BUSINESS_DAYS : 0;
        return new CycleWindow(BusinessDays.minus(expected, WINDOW_BEFORE_BUSINESS_DAYS + widen),
                BusinessDays.plus(expected, WINDOW_AFTER_BUSINESS_DAYS + widen));
    }

    public static String cadenceLabel(CadenceResult cadence) {
        return switch (cadence.cadence()) {
            case WEEKLY -> "Every " + weekday(cadence);
            case BIWEEKLY -> "Every other " + weekday(cadence);
            case SEMI_MONTHLY -> "Twice a month (" + dayLabel(cadence.anchorDay1()) + " and " + dayLabel(cadence.anchorDay2()) + ")";
            case MONTHLY -> "Monthly on the " + dayLabel(cadence.anchorDay1());
        };
    }

    static LocalDate nominal(YearMonth month, String anchor) {
        if (CadenceDeriver.ANCHOR_LAST_BD.equals(anchor)) { return BusinessDays.adjustBackward(month.atEndOfMonth()); }
        return month.atDay(Math.min(Integer.parseInt(anchor), month.lengthOfMonth()));
    }

    private static boolean isWeekBased(CadenceResult cadence) {
        return cadence.cadence() == InvestmentFrequency.WEEKLY || cadence.cadence() == InvestmentFrequency.BIWEEKLY;
    }

    private static List<String> anchors(CadenceResult cadence) {
        return Stream.of(cadence.anchorDay1(), cadence.anchorDay2()).filter(a -> a != null).toList();
    }

    private static String weekday(CadenceResult cadence) {
        return DayOfWeek.of(cadence.anchorDow()).getDisplayName(TextStyle.FULL, Locale.US);
    }

    private static String dayLabel(String anchor) {
        if (CadenceDeriver.ANCHOR_LAST_BD.equals(anchor)) { return "last business day"; }
        int day = Integer.parseInt(anchor);
        return day + (day >= 11 && day <= 13 ? "th" : switch (day % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; });
    }
}
```

and `CadenceDeriver.java`:

```java
/** Spec 4.4 cadence from the last six posted dates. */
public final class CadenceDeriver {

    public static final int MAX_DEPOSITS = 6;
    public static final int OUTLIER_MAX_DAYS = 4;
    public static final int OBSERVED_FROM_INTERVALS = 3;
    public static final int ANCHOR_TOLERANCE_BUSINESS_DAYS = 2;
    public static final int CLUSTER_SPREAD_DAYS = 4;
    public static final int MONTH_START_THROUGH_DAY = 3;
    public static final int MONTH_END_FROM_DAY = 28;
    public static final int CONSTANT_SPACING_DAYS = 28;
    public static final String ANCHOR_FIRST = "1";
    public static final String ANCHOR_LAST_BD = "LAST_BD";

    private record Fit(InvestmentFrequency cadence, Integer dow, LocalDate parity, String a1, String a2, int outside) {
    }

    /** {band low, band high, median low, median high} in days. */
    static int[] band(InvestmentFrequency cadence) {
        return switch (cadence) {
            case WEEKLY -> new int[] {5, 9, 6, 8};
            case BIWEEKLY -> new int[] {12, 16, 13, 15};
            case SEMI_MONTHLY -> new int[] {10, 20, 13, 17};
            case MONTHLY -> new int[] {26, 35, 28, 31};
        };
    }

    public static Optional<CadenceResult> derive(List<LocalDate> postedDatesAsc, Optional<String> plaidFrequencyWire) {
        if (postedDatesAsc == null || postedDatesAsc.size() < 2) { return Optional.empty(); }
        List<LocalDate> dates = postedDatesAsc.subList(Math.max(0, postedDatesAsc.size() - MAX_DEPOSITS), postedDatesAsc.size());
        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < dates.size(); i++) { gaps.add((int) ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i))); }
        Optional<List<String>> labels = anchorLabels(dates);
        List<Fit> fits = new ArrayList<>();
        for (InvestmentFrequency f : InvestmentFrequency.values()) {
            int outside = outsideCount(gaps, f);
            if (outside < 0) { continue; }
            if (f == InvestmentFrequency.WEEKLY || (f == InvestmentFrequency.BIWEEKLY && !alternates(gaps))) {
                LocalDate parity = parityDate(dates);
                fits.add(new Fit(f, parity.getDayOfWeek().getValue(), parity, null, null, outside));
            } else if (f == InvestmentFrequency.SEMI_MONTHLY && labels.isPresent() && takenInTurn(labels.get())) {
                List<String> anchors = distinctAnchors(labels.get());
                fits.add(new Fit(f, null, null, anchors.get(0), anchors.get(1), outside));
            } else if (f == InvestmentFrequency.MONTHLY && labels.isPresent() && distinctAnchors(labels.get()).size() == 1
                    && !gaps.stream().allMatch(g -> g == CONSTANT_SPACING_DAYS)) {
                fits.add(new Fit(f, null, null, labels.get().get(0), null, outside));
            }
        }
        if (fits.isEmpty()) { return Optional.empty(); }
        List<Fit> clean = fits.stream().filter(fit -> fit.outside() == 0).toList();
        List<Fit> pool = clean.isEmpty() ? fits : clean;
        Optional<InvestmentFrequency> plaid = plaidFrequencyWire.flatMap(InvestmentFrequency::tryParse);
        Fit chosen = pool.stream().filter(fit -> plaid.isPresent() && fit.cadence() == plaid.get()).findFirst()
                .or(() -> pool.stream().filter(fit -> fit.cadence() == InvestmentFrequency.BIWEEKLY).findFirst())
                .orElse(pool.get(0));
        PaycheckCadenceSource source = gaps.size() >= OBSERVED_FROM_INTERVALS && pool.size() == 1
                ? PaycheckCadenceSource.OBSERVED : PaycheckCadenceSource.PLAID;
        return Optional.of(new CadenceResult(chosen.cadence(), source, chosen.dow(), chosen.parity(), chosen.a1(), chosen.a2(),
                gaps.size(), chosen.outside() == 0, chosen.outside()));
    }

    private static int outsideCount(List<Integer> gaps, InvestmentFrequency f) {
        int[] b = band(f);
        List<Integer> s = gaps.stream().sorted().toList();
        int n = s.size();
        double median = n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
        if (median < b[2] || median > b[3]) { return -1; }
        int outside = 0;
        for (int g : gaps) {
            int distance = g < b[0] ? b[0] - g : Math.max(0, g - b[1]);
            if (distance > OUTLIER_MAX_DAYS) { return -1; }
            if (distance > 0) { outside++; }
        }
        return outside <= (gaps.size() >= 3 ? 1 : 0) ? outside : -1;
    }

    static boolean alternates(List<Integer> gaps) {
        if (gaps.size() < 3) { return false; }
        for (int i = 1; i < gaps.size(); i++) {
            int diff = gaps.get(i) - gaps.get(i - 1);
            if (Math.abs(diff) < 2 || (i > 1 && Integer.signum(diff) == Integer.signum(gaps.get(i - 1) - gaps.get(i - 2)))) {
                return false;
            }
        }
        return true;
    }

    static Optional<List<String>> anchorLabels(List<LocalDate> dates) {
        List<Integer> numericDays = dates.stream().filter(d -> fixedClass(d) == null).map(LocalDate::getDayOfMonth).toList();
        Map<Integer, String> numericAnchor = numericAnchors(numericDays);
        List<String> labels = new ArrayList<>();
        for (LocalDate d : dates) {
            String label = fixedClass(d) != null ? fixedClass(d) : numericAnchor.get(d.getDayOfMonth());
            LocalDate target = BusinessDays.adjustBackward(PayCalendar.nominal(YearMonth.from(d), label));
            if (Math.abs(BusinessDays.between(target, d)) > ANCHOR_TOLERANCE_BUSINESS_DAYS) { return Optional.empty(); }
            labels.add(label);
        }
        return Optional.of(labels);
    }

    private static String fixedClass(LocalDate d) {
        if (d.getDayOfMonth() <= MONTH_START_THROUGH_DAY) { return ANCHOR_FIRST; }
        boolean monthEnd = d.getDayOfMonth() >= MONTH_END_FROM_DAY || d.equals(PayCalendar.nominal(YearMonth.from(d), ANCHOR_LAST_BD));
        return monthEnd ? ANCHOR_LAST_BD : null;
    }

    private static Map<Integer, String> numericAnchors(List<Integer> days) {
        Map<Integer, String> anchorOf = new HashMap<>();
        List<Integer> sorted = days.stream().distinct().sorted().toList();
        for (int i = 0; i < sorted.size(); ) {
            int start = sorted.get(i);
            List<Integer> cluster = new ArrayList<>();
            while (i < sorted.size() && sorted.get(i) - start <= CLUSTER_SPREAD_DAYS) { cluster.add(sorted.get(i++)); }
            int modal = cluster.get(0);
            long best = -1;
            for (int day : cluster) {
                long count = days.stream().filter(x -> x == day).count();
                if (count >= best) { best = count; modal = day; }
            }
            for (int day : cluster) { anchorOf.put(day, Integer.toString(modal)); }
        }
        return anchorOf;
    }

    private static boolean takenInTurn(List<String> labels) {
        for (int i = 1; i < labels.size(); i++) {
            if (labels.get(i).equals(labels.get(i - 1))) { return false; }
        }
        return distinctAnchors(labels).size() == 2;
    }

    static List<String> distinctAnchors(List<String> labels) {
        return labels.stream().distinct()
                .sorted(Comparator.comparingInt(a -> ANCHOR_LAST_BD.equals(a) ? 99 : Integer.parseInt(a))).toList();
    }

    /** The latest date on the weekday seen most often. */
    private static LocalDate parityDate(List<LocalDate> dates) {
        int[] counts = new int[8];
        dates.forEach(d -> counts[d.getDayOfWeek().getValue()]++);
        int max = Arrays.stream(counts).max().orElse(0);
        for (int i = dates.size() - 1; i > 0; i--) {
            if (counts[dates.get(i).getDayOfWeek().getValue()] == max) { return dates.get(i); }
        }
        return dates.get(0);
    }
}
```

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.CadenceDeriverTest' --console=plain
cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.PayCalendarTest' --console=plain
```
Expected: `tests="8"`, then `tests="4"`.

- [ ] **Step 9: Failing tests, Plaid facts and gates.** Create `TD/StreamFactsTest.java`:

```java
class StreamFactsTest {

    @Test
    void emptyUntilMatched() {
        PaycheckStream s = new PaycheckStream();
        s.setPlaidStatus("MATURE");
        assertThat(StreamFacts.of(null)).isEmpty();
        assertThat(StreamFacts.of(s)).isEmpty();
        s.setPlaidStreamId("ps-1");
        assertThat(StreamFacts.of(s)).contains(new StreamFacts("MATURE", null, null, null, null, null));
        assertThat(StreamFacts.of(s).orElseThrow().mature()).isTrue();
    }
}
```

and `HardGatesTest.java` (one case per 4.2 gate):

```java
class HardGatesTest {

    private static final String PAYROLL = "ACME CORP PAYROLL";

    private static Optional<PaycheckRejectReason> gates(DepositGroup g, String status, String today) {
        return HardGates.evaluate(g, Optional.ofNullable(status).map(s -> new StreamFacts(s, null, true, null, null, null)),
                CadenceDeriver.derive(g.postedDatesAsc(), Optional.empty()), DepositStats.trailingMedian(g.lastSixAmounts()), d(today));
    }

    private static Optional<PaycheckRejectReason> gates(DepositGroup g) {
        return gates(g, null, "2026-03-24");
    }

    @Test
    void cleanPayrollAndSingleDepositPass() {
        assertThat(gates(biweekly(6, "2418.00", PAYROLL), "MATURE", "2026-03-24")).isEmpty();
        assertThat(gates(biweekly(1, "2418.00", PAYROLL))).isEmpty(); // gate 9: CANDIDATE, not REJECTED
    }

    @Test
    void notInflowRejected() {
        DepositGroup g = biweekly(3, "2418.00", PAYROLL);
        g.depositsAsc().get(1).setRawAmount(new BigDecimal("2418.00"));
        assertThat(gates(g)).contains(NOT_INFLOW);
    }

    @Test
    void tombstonedOrUnknownRejected() {
        assertThat(List.of("TOMBSTONED", "UNKNOWN")).allMatch(s -> gates(biweekly(6, "2418.00", PAYROLL), s, "2026-03-24").equals(Optional.of(PLAID_TOMBSTONED)));
    }

    @Test
    void benefitsSignalAtOneDeposit() {
        assertThat(gates(biweekly(1, "1800.00", "CA EDD UI BENEFIT"))).contains(BENEFITS_SIGNAL);
    }

    @Test
    void pfcTransferAndPaymentAppRejected() {
        DepositGroup g = biweekly(6, "2418.00", PAYROLL);
        g.depositsAsc().forEach(e -> e.setPfcDetailed("INCOME_TAX_REFUND"));
        assertThat(gates(g)).contains(PFC_EXCLUDED);
        g.depositsAsc().forEach(e -> { e.setPfcDetailed(null); e.setPfcPrimary("TRANSFER_IN"); e.setPfcConfidence("VERY_HIGH"); });
        assertThat(gates(g)).contains(PFC_EXCLUDED);
        g.depositsAsc().forEach(e -> e.setPfcConfidence("MEDIUM"));
        assertThat(gates(g)).isEmpty();
        g.depositsAsc().forEach(e -> e.setCounterpartyType("payment_app"));
        assertThat(gates(g)).contains(PFC_EXCLUDED);
    }

    @Test
    void nonPayrollChannelDescriptorExcluded() {
        assertThat(gates(biweekly(6, "2418.00", "ZELLE FROM JOHN DOE"))).contains(DESCRIPTOR_EXCLUDED);
    }

    @Test
    void unsupportedCadence() {
        DepositGroup g = biweekly(4, "2418.00", PAYROLL);
        for (int i = 0; i < 4; i++) { g.depositsAsc().get(i).setPostedDate(d("2026-01-02").plusDays(28L * i)); }
        assertThat(gates(g, null, "2026-03-30")).contains(CADENCE_UNSUPPORTED);
    }

    @Test
    void lowMedianPerCadence() {
        assertThat(gates(biweekly(6, "183.99", PAYROLL))).contains(LOW_MEDIAN);
        assertThat(gates(biweekly(6, "184.00", PAYROLL))).isEmpty();
        assertThat(List.of(InvestmentFrequency.values())).extracting(f -> HardGates.lowMedianFloor(f).intValue()).containsExactly(92, 184, 200, 400);
    }

    @Test
    void staleAfterTwoAndAHalfPeriods() {
        assertThat(gates(biweekly(6, "2418.00", PAYROLL), null, "2026-04-24")).isEmpty();
        assertThat(gates(biweekly(6, "2418.00", PAYROLL), null, "2026-04-25")).contains(STALE);
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.HardGatesTest' --console=plain`. Expected: missing `StreamFacts`, `HardGates`.

- [ ] **Step 10: StreamFacts, HardGates.** Create `D/StreamFacts.java`:

```java
/** Plaid's catalog view of a stream as T10 stored it; absent until matched. */
public record StreamFacts(String plaidStatus, String plaidFrequency, Boolean plaidIsActive, String plaidPfcDetailed,
        String plaidPfcConfidence, LocalDate plaidPredictedNextDate) {

    public static Optional<StreamFacts> of(PaycheckStream stream) {
        if (stream == null || stream.getPlaidStreamId() == null) { return Optional.empty(); }
        return Optional.of(new StreamFacts(stream.getPlaidStatus(), stream.getPlaidFrequency(), stream.getPlaidIsActive(),
                stream.getPlaidPfcDetailed(), stream.getPlaidPfcConfidence(), stream.getPlaidPredictedNextDate()));
    }

    public boolean mature() {
        return "MATURE".equals(plaidStatus);
    }
}
```

and `HardGates.java`:

```java
/** Spec 4.2 gates in order; one deposit past the first five is a CANDIDATE, not REJECTED. */
public final class HardGates {

    public static final double STALE_PERIODS = 2.5;
    public static final BigDecimal LOW_MEDIAN_WEEKLY = new BigDecimal("92");
    public static final BigDecimal LOW_MEDIAN_BIWEEKLY = new BigDecimal("184");
    public static final BigDecimal LOW_MEDIAN_SEMI_MONTHLY = new BigDecimal("200");
    public static final BigDecimal LOW_MEDIAN_MONTHLY = new BigDecimal("400");
    public static final Set<String> EXCLUDED_PFC_DETAILED = Set.of("INCOME_UNEMPLOYMENT", "INCOME_RETIREMENT_PENSION",
            "INCOME_LONG_TERM_DISABILITY", "INCOME_CHILD_SUPPORT", "INCOME_RENTAL", "INCOME_TAX_REFUND", "INCOME_DIVIDENDS",
            "INCOME_INTEREST_EARNED");
    static final Set<String> HIGH_CONFIDENCE = Set.of("HIGH", "VERY_HIGH");

    public static Optional<PaycheckRejectReason> evaluate(DepositGroup group, Optional<StreamFacts> facts,
            Optional<CadenceResult> cadence, BigDecimal median, LocalDate todayET) {
        List<PaycheckEvent> deposits = group.depositsAsc();
        if (deposits.isEmpty() || deposits.stream().anyMatch(e -> e.getRawAmount() == null || e.getRawAmount().signum() >= 0)) {
            return Optional.of(PaycheckRejectReason.NOT_INFLOW);
        }
        String status = facts.map(StreamFacts::plaidStatus).orElse(null);
        if ("TOMBSTONED".equals(status) || "UNKNOWN".equals(status)) { return Optional.of(PaycheckRejectReason.PLAID_TOMBSTONED); }
        if (majority(deposits, e -> DescriptorSignals.hasBenefitsToken(e.getRawDescriptor()))) {
            return Optional.of(PaycheckRejectReason.BENEFITS_SIGNAL);
        }
        if (EXCLUDED_PFC_DETAILED.contains(String.valueOf(latestPfcDetailed(deposits, facts)))
                || majority(deposits, e -> "TRANSFER_IN".equals(e.getPfcPrimary()) && e.getPfcConfidence() != null
                        && HIGH_CONFIDENCE.contains(e.getPfcConfidence().toUpperCase(Locale.ROOT)))
                || majority(deposits, e -> "payment_app".equals(e.getCounterpartyType()))) {
            return Optional.of(PaycheckRejectReason.PFC_EXCLUDED);
        }
        if (majority(deposits, e -> DescriptorSignals.hasNonPayrollChannelToken(e.getRawDescriptor()))) {
            return Optional.of(PaycheckRejectReason.DESCRIPTOR_EXCLUDED);
        }
        if (deposits.size() < 2) { return Optional.empty(); }
        if (cadence.isEmpty()) { return Optional.of(PaycheckRejectReason.CADENCE_UNSUPPORTED); }
        InvestmentFrequency f = cadence.get().cadence();
        if (median == null || median.compareTo(lowMedianFloor(f)) < 0) { return Optional.of(PaycheckRejectReason.LOW_MEDIAN); }
        long sinceLast = ChronoUnit.DAYS.between(deposits.get(deposits.size() - 1).getPostedDate(), todayET);
        return sinceLast > STALE_PERIODS * PayCalendar.periodDays(f) ? Optional.of(PaycheckRejectReason.STALE) : Optional.empty();
    }

    public static BigDecimal lowMedianFloor(InvestmentFrequency cadence) {
        return switch (cadence) {
            case WEEKLY -> LOW_MEDIAN_WEEKLY;
            case BIWEEKLY -> LOW_MEDIAN_BIWEEKLY;
            case SEMI_MONTHLY -> LOW_MEDIAN_SEMI_MONTHLY;
            case MONTHLY -> LOW_MEDIAN_MONTHLY;
        };
    }

    /** The latest deposit's PFC detailed value (Plaid recategorizes), else the Plaid stream's. */
    static String latestPfcDetailed(List<PaycheckEvent> deposits, Optional<StreamFacts> facts) {
        for (int i = deposits.size() - 1; i >= 0; i--) {
            if (deposits.get(i).getPfcDetailed() != null) { return deposits.get(i).getPfcDetailed(); }
        }
        return facts.map(StreamFacts::plaidPfcDetailed).orElse(null);
    }

    static boolean majority(List<PaycheckEvent> deposits, Predicate<PaycheckEvent> signal) {
        return deposits.stream().filter(signal).count() * 2 > deposits.size();
    }
}
```

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.StreamFactsTest' --console=plain
cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.HardGatesTest' --console=plain
```
Expected: `tests="1"`, then `tests="9"`.

- [ ] **Step 11: Failing tests, score and state.** In `DetectFixtures`, add before the closing brace:

```java
    static CadenceResult cadence(InvestmentFrequency frequency, int outsideBand) {
        return new CadenceResult(frequency, PaycheckCadenceSource.OBSERVED, 5, d("2026-03-20"), null, null, 5, outsideBand == 0, outsideBand);
    }
```

Create `TD/ScoreBreakdownTest.java`:

```java
class ScoreBreakdownTest {

    @Test
    void toJsonCarriesEveryRowInOrder() {
        assertThat(new ScoreBreakdown(40, 20, 15, 10, 6, 10, 100).toJson())
                .isEqualTo("{\"pfc\":40,\"plaidStatus\":20,\"median\":15,\"cadenceFit\":10,\"stability\":6,\"payrollToken\":10,\"total\":100}");
    }
}
```

`StreamScorerTest.java` (one case per 4.5 row):

```java
class StreamScorerTest {

    private static final DepositGroup PAYROLL = biweekly(6, "2418.00", "ACME CORP PAYROLL");

    private static ScoreBreakdown score(DepositGroup g, String status, int outsideBand) {
        List<BigDecimal> six = g.lastSixAmounts();
        return StreamScorer.score(g, Optional.ofNullable(status).map(s -> new StreamFacts(s, null, true, "INCOME_SALARY", null, null)),
                cadence(BIWEEKLY, outsideBand), DepositStats.trailingMedian(six), DepositStats.trailingCv(six));
    }

    @Test
    void pfcRow() {
        var plain = List.of(deposit("acc-1", "2026-01-09", "2418.00", "ACME CORP PAYROLL"));
        assertThat(Arrays.asList("INCOME_SALARY", "INCOME_WAGES", "INCOME_MILITARY", "INCOME_CONTRACTOR", "INCOME_GIG_ECONOMY", "INCOME_OTHER", null))
                .extracting(p -> StreamScorer.pfcPoints(p, plain)).containsExactly(40, 40, 10, 20, 20, 10, 10);
        assertThat(StreamScorer.pfcPoints("INCOME_MILITARY", List.of(deposit("acc-1", "2026-01-09", "2418.00", "DFAS-CLEVELAND")))).isEqualTo(40);
        assertThat(score(PAYROLL, "MATURE", 0).pfc()).isEqualTo(40);
    }

    @Test
    void plaidStatusRow() {
        assertThat(Arrays.asList("MATURE", "EARLY_DETECTION", "ENUM_UNKNOWN", null)).extracting(s -> score(PAYROLL, s, 0).plaidStatus())
                .containsExactly(20, 5, 0, 0);
    }

    @Test
    void medianRow() {
        assertThat(amounts("700", "400", "200", "184")).extracting(m -> StreamScorer.medianPoints(BIWEEKLY.toMonthly(m))).containsExactly(15, 10, 5, 0);
    }

    @Test
    void cadenceFitRow() {
        assertThat(List.of(0, 1, 2)).extracting(outside -> score(PAYROLL, null, outside).cadenceFit()).containsExactly(10, 5, 0);
    }

    @Test
    void stabilityRow() {
        assertThat(amounts("0.10", "0.30", "0.50", "0.70")).extracting(cv -> StreamScorer.stabilityPoints(cv, amounts("2400", "2418")))
                .containsExactly(10, 7, 3, 0);
        assertThat(StreamScorer.stabilityPoints(BigDecimal.ZERO, amounts("500.00", "500.00"))).isEqualTo(6);
        assertThat(StreamScorer.stabilityPoints(BigDecimal.ZERO, amounts("1234.56", "1234.56"))).isEqualTo(10);
    }

    @Test
    void payrollTokenAndCappedTotal() {
        assertThat(score(PAYROLL, "MATURE", 0)).isEqualTo(new ScoreBreakdown(40, 20, 15, 10, 10, 10, 100));
        assertThat(score(biweekly(6, "2418.00", "ACME CORP"), "MATURE", 0).total()).isEqualTo(95);
    }
}
```

and `StreamStateRuleTest.java`:

```java
class StreamStateRuleTest {

    private static PaycheckAutopilotProperties thresholds(int provisional, int confirmed) {
        PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
        when(props.getScoreProvisional()).thenReturn(provisional);
        when(props.getScoreConfirmed()).thenReturn(confirmed);
        return props;
    }

    private static final PaycheckAutopilotProperties PROPS = thresholds(55, 70);

    @Test
    void twoOnCadenceDepositsMakeProvisional() {
        assertThat(state(55, 2, false, false, PROPS)).isEqualTo(PROVISIONAL);
        assertThat(state(54, 2, false, false, PROPS)).isEqualTo(CANDIDATE);
        assertThat(state(90, 1, true, true, PROPS)).isEqualTo(CANDIDATE);
    }

    @Test
    void confirmedNeedsPlaidMatureOrFourDeposits() {
        assertThat(state(70, 3, true, true, PROPS)).isEqualTo(CONFIRMED);
        assertThat(state(70, 3, false, true, PROPS)).isEqualTo(PROVISIONAL);
        assertThat(state(69, 3, true, true, PROPS)).isEqualTo(PROVISIONAL);
        assertThat(state(70, 3, false, false, PROPS)).isEqualTo(PROVISIONAL);
        assertThat(state(70, 4, false, false, PROPS)).isEqualTo(CONFIRMED);
        assertThat(state(75, 3, true, true, thresholds(55, 80))).isEqualTo(PROVISIONAL);
    }

    @Test
    void onCadenceCountUsesTheBand() {
        assertThat(StreamStateRule.onCadenceCount(dates("2026-01-09", "2026-01-23", "2026-02-06", "2026-02-10", "2026-02-20"),
                cadence(BIWEEKLY, 0))).isEqualTo(3);
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.StreamScorerTest' --console=plain`. Expected: missing `ScoreBreakdown`, `StreamScorer`, `StreamStateRule`.

- [ ] **Step 12: ScoreBreakdown, StreamScorer, StreamStateRule.** Create `D/ScoreBreakdown.java`:

```java
/** Spec 4.5 score rows; toJson() fills score_breakdown_json. */
public record ScoreBreakdown(int pfc, int plaidStatus, int median, int cadenceFit, int stability, int payrollToken, int total) {

    public String toJson() {
        return "{\"pfc\":" + pfc + ",\"plaidStatus\":" + plaidStatus + ",\"median\":" + median + ",\"cadenceFit\":" + cadenceFit
                + ",\"stability\":" + stability + ",\"payrollToken\":" + payrollToken + ",\"total\":" + total + "}";
    }
}
```

`StreamScorer.java`:

```java
/** Spec 4.5 score, capped at 100; shadow tuning edits these numbers. */
public final class StreamScorer {

    public static final int MAX_SCORE = 100;
    static final BigDecimal ROUND_UNIT = new BigDecimal("50");

    public static ScoreBreakdown score(DepositGroup group, Optional<StreamFacts> facts, CadenceResult cadence,
            BigDecimal median, BigDecimal cv) {
        List<PaycheckEvent> deposits = group.depositsAsc();
        int pfc = pfcPoints(HardGates.latestPfcDetailed(deposits, facts), deposits);
        String status = facts.map(StreamFacts::plaidStatus).orElse("");
        int plaid = "MATURE".equals(status) ? 20 : "EARLY_DETECTION".equals(status) ? 5 : 0;
        int med = medianPoints(median == null ? null : DepositStats.monthlyNormalized(median, cadence.cadence()));
        int fit = cadence.allInBand() ? 10 : cadence.outsideBand() == 1 ? 5 : 0;
        int stability = stabilityPoints(cv, group.lastSixAmounts());
        int payroll = deposits.stream().anyMatch(e -> DescriptorSignals.hasPayrollToken(e.getRawDescriptor())
                || DescriptorSignals.hasPayrollToken(e.getMerchantName())) ? 10 : 0;
        return new ScoreBreakdown(pfc, plaid, med, fit, stability, payroll, Math.min(MAX_SCORE, pfc + plaid + med + fit + stability + payroll));
    }

    static int pfcPoints(String detailed, List<PaycheckEvent> deposits) {
        return switch (detailed == null ? "" : detailed) {
            case "INCOME_SALARY", "INCOME_WAGES" -> 40;
            case "INCOME_MILITARY" -> deposits.stream().anyMatch(e -> DescriptorSignals.containsToken(e.getRawDescriptor(), "DFAS")) ? 40 : 10;
            case "INCOME_CONTRACTOR", "INCOME_GIG_ECONOMY" -> 20;
            default -> 10;
        };
    }

    static int medianPoints(BigDecimal monthly) {
        if (monthly == null) { return 0; }
        return monthly.compareTo(new BigDecimal("1500")) >= 0 ? 15 : monthly.compareTo(new BigDecimal("800")) >= 0 ? 10
                : monthly.compareTo(new BigDecimal("400")) >= 0 ? 5 : 0;
    }

    static int stabilityPoints(BigDecimal cv, List<BigDecimal> lastSix) {
        int points = cv == null ? 0 : cv.compareTo(new BigDecimal("0.15")) <= 0 ? 10 : cv.compareTo(new BigDecimal("0.35")) <= 0 ? 7
                : cv.compareTo(new BigDecimal("0.60")) <= 0 ? 3 : 0;
        boolean pennyRound = lastSix.size() >= 2 && lastSix.stream().allMatch(a -> a.compareTo(lastSix.get(0)) == 0)
                && lastSix.get(0).remainder(ROUND_UNIT).signum() == 0;
        return pennyRound ? Math.max(0, points - 4) : points;
    }
}
```

and `StreamStateRule.java`:

```java
/** Spec 4.5 CANDIDATE, PROVISIONAL or CONFIRMED; other states are decided elsewhere. */
public final class StreamStateRule {

    public static PaycheckStreamState state(int score, int onCadenceDeposits, boolean plaidMature, boolean plaidStreamKnown,
            PaycheckAutopilotProperties props) {
        if (onCadenceDeposits < 2 || score < props.getScoreProvisional()) { return PaycheckStreamState.CANDIDATE; }
        boolean evidence = (onCadenceDeposits >= 3 && plaidMature) || (onCadenceDeposits >= 4 && !plaidStreamKnown);
        return score >= props.getScoreConfirmed() && evidence ? PaycheckStreamState.CONFIRMED : PaycheckStreamState.PROVISIONAL;
    }

    public static int onCadenceCount(List<LocalDate> postedDatesAsc, CadenceResult cadence) {
        if (postedDatesAsc == null || postedDatesAsc.isEmpty()) { return 0; }
        int[] band = CadenceDeriver.band(cadence.cadence());
        int count = 1;
        for (int i = 1; i < postedDatesAsc.size(); i++) {
            long gap = ChronoUnit.DAYS.between(postedDatesAsc.get(i - 1), postedDatesAsc.get(i));
            if (gap >= band[0] && gap <= band[1]) { count++; }
        }
        return count;
    }
}
```

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.ScoreBreakdownTest' --console=plain
cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.StreamScorerTest' --console=plain
cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.StreamStateRuleTest' --console=plain
```
Expected: `tests="1"`, `tests="6"`, `tests="3"`.

- [ ] **Step 13: Failing test for the pre-fill.** Create `TD/AmountPrefillTest.java` (4.9: goal 650 biweekly gives 300; median 2418 caps at 2176; no goal gives 500 `default`):

```java
class AmountPrefillTest {

    private static final BigDecimal SHARE = new BigDecimal("0.90");
    private static final BigDecimal MEDIAN = new BigDecimal("2418");

    @Test
    void goalSixFiftyBiweeklyGivesThreeHundred() {
        assertThat(compute(650.0, BIWEEKLY, MEDIAN, SHARE)).isEqualTo(new Prefill(new BigDecimal("650.00"), GOAL_SOURCE_SURVEY, 300, 2176, 300));
    }

    @Test
    void medianTwentyFourEighteenCapsAtTwentyOneSeventySix() {
        assertThat(compute(5000.0, MONTHLY, MEDIAN, SHARE).prefillAmount()).isEqualTo(2176);
    }

    @Test
    void nullOrZeroGoalUsesTheFiveHundredDefault() {
        assertThat(compute(null, BIWEEKLY, MEDIAN, SHARE)).isEqualTo(new Prefill(new BigDecimal("500.00"), GOAL_SOURCE_DEFAULT, 230, 2176, 230));
        assertThat(compute(0.0, MONTHLY, MEDIAN, SHARE).goalSource()).isEqualTo(GOAL_SOURCE_DEFAULT);
    }

    @Test
    void prefillIsAtLeastOneAndNoMedianMeansNoCap() {
        assertThat(compute(650.0, BIWEEKLY, BigDecimal.ONE, SHARE).prefillAmount()).isEqualTo(1);
        assertThat(compute(650.0, BIWEEKLY, null, SHARE)).isEqualTo(new Prefill(new BigDecimal("650.00"), GOAL_SOURCE_SURVEY, 300, null, 300));
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.AmountPrefillTest' --console=plain`. Expected: missing `AmountPrefill` and `compute`.

- [ ] **Step 14: AmountPrefill.** Create `D/AmountPrefill.java`:

```java
/** Spec 4.9 pre-fill; never writes User.monthlyInvestment. */
public final class AmountPrefill {

    public record Prefill(BigDecimal monthlyGoal, String goalSource, int suggestedAmount, Integer affordabilityCap, int prefillAmount) {
    }

    public static final int DEFAULT_GOAL = 500;
    public static final String GOAL_SOURCE_SURVEY = "survey";
    public static final String GOAL_SOURCE_DEFAULT = "default";

    public static Prefill compute(Double surveyMonthlyInvestment, InvestmentFrequency cadence, BigDecimal trailingMedian, BigDecimal share) {
        boolean hasGoal = surveyMonthlyInvestment != null && Double.isFinite(surveyMonthlyInvestment) && surveyMonthlyInvestment > 0;
        BigDecimal goal = BigDecimal.valueOf(hasGoal ? surveyMonthlyInvestment : DEFAULT_GOAL).setScale(2, RoundingMode.HALF_UP);
        int suggested = goal.divide(cadence.monthlyFactor(), 0, RoundingMode.HALF_UP).intValueExact();
        Integer cap = trailingMedian == null || share == null ? null
                : share.multiply(trailingMedian).setScale(0, RoundingMode.FLOOR).intValueExact();
        int prefill = Math.max(1, cap == null ? suggested : Math.min(suggested, cap));
        return new Prefill(goal, hasGoal ? GOAL_SOURCE_SURVEY : GOAL_SOURCE_DEFAULT, suggested, cap, prefill);
    }
}
```

Run the Step 13 command. Expected: `tests="4"`.

- [ ] **Step 15: Failing tests, selection and succession.** In `DetectFixtures`, add before the closing brace:

```java
    static PaycheckStream stream(long id, String token, PaycheckStreamState state, InvestmentFrequency cadence, int score, String median, String cv) {
        PaycheckStream s = new PaycheckStream();
        s.setId(id);
        s.setPayerToken(token);
        s.setState(state);
        s.setCadence(cadence);
        s.setScore((short) score);
        s.setTrailingMedian(new BigDecimal(median));
        s.setTrailingCv(new BigDecimal(cv));
        return s;
    }
```

Create `TD/PrimarySelectorTest.java`:

```java
class PrimarySelectorTest {

    private static final BigDecimal GOAL = new BigDecimal("500");

    @Test
    void samePayerMoreFrequentCadenceWins() {
        List<PaycheckStream> acme = List.of(stream(1, "ACME CORP", PROVISIONAL, BIWEEKLY, 80, "2000", "0.05"),
                stream(2, "ACME CORP", PROVISIONAL, WEEKLY, 60, "500", "0.30"), stream(3, "ACME CORP", PROVISIONAL, WEEKLY, 90, "600", "0.40"));
        assertThat(PrimarySelector.select(acme, GOAL)).isEqualTo(new PrimarySelector.Selection(2L, List.of(1L, 3L), false, false));
    }

    @Test
    void differentPayersHighestScoreThenMedian() {
        PaycheckStream acme = stream(1, "ACME CORP", PROVISIONAL, BIWEEKLY, 65, "1500", "0.10");
        PaycheckStream gusto = stream(2, "GUSTO", PROVISIONAL, BIWEEKLY, 75, "900", "0.10");
        assertThat(PrimarySelector.select(List.of(acme, gusto), GOAL).primaryStreamId()).isEqualTo(2L);
        gusto.setScore((short) 65);
        assertThat(PrimarySelector.select(List.of(acme, gusto), GOAL).primaryStreamId()).isEqualTo(1L);
    }

    @Test
    void twoConfirmedPayersPickLargerMedianAndChooser() {
        List<PaycheckStream> both = List.of(stream(1, "ACME CORP", CONFIRMED, BIWEEKLY, 95, "1500", "0.02"),
                stream(2, "GLOBEX", CONFIRMED, BIWEEKLY, 72, "2400", "0.02"));
        assertThat(PrimarySelector.select(both, GOAL)).isEqualTo(new PrimarySelector.Selection(2L, List.of(1L), true, false));
    }

    @Test
    void lowConfidenceAndEmptySelection() {
        PaycheckStream small = stream(1, "ACME CORP", PROVISIONAL, BIWEEKLY, 60, "300", "0.05"); // 651 a month, under 800
        assertThat(PrimarySelector.select(List.of(small), null).lowConfidence()).isTrue(); // null goal: 500
        small.setTrailingMedian(new BigDecimal("400"));
        assertThat(PrimarySelector.select(List.of(small), GOAL).lowConfidence()).isFalse();
        assertThat(PrimarySelector.select(List.of(), GOAL)).isEqualTo(new PrimarySelector.Selection(null, List.of(), false, false));
    }
}
```

and `SuccessionRuleTest.java`:

```java
class SuccessionRuleTest {

    private static final LocalDate TODAY = d("2026-03-24");
    private static final Consumer<PaycheckStream> SAME = s -> { };
    private static final Consumer<PaycheckStream> MISSED = s -> s.setMissedCount((short) 1);

    private static boolean succeeds(Consumer<PaycheckStream> s1Change, Consumer<PaycheckStream> s2Change) {
        PaycheckStream s1 = stream(1, "ACME CORP", CONFIRMED, BIWEEKLY, 90, "2418", "0.02");
        s1.setLastDate(TODAY.minusDays(4));
        s1.setPlaidIsActive(true);
        s1.setPlaidStatus("MATURE");
        PaycheckStream s2 = stream(2, "GLOBEX", PROVISIONAL, BIWEEKLY, 60, "2600", "0.03");
        s2.setDepositsFound((short) 2);
        s1Change.accept(s1);
        s2Change.accept(s2);
        return SuccessionRule.succeeds(s1, s2, TODAY);
    }

    @Test
    void successionRequiresS1MissOrInactiveOrQuiet() {
        assertThat(succeeds(SAME, SAME)).isFalse();
        assertThat(succeeds(MISSED, SAME)).isTrue();
        assertThat(succeeds(s -> s.setPlaidIsActive(false), SAME)).isTrue();
        assertThat(succeeds(s -> s.setPlaidStatus("TOMBSTONED"), SAME)).isTrue();
        assertThat(succeeds(s -> s.setLastDate(TODAY.minusDays(22)), SAME)).isTrue();
        assertThat(succeeds(s -> s.setLastDate(TODAY.minusDays(21)), SAME)).isFalse();
    }

    @Test
    void s2MustBeTrustedNewPayerNotBenefits() {
        assertThat(succeeds(MISSED, s -> s.setState(CANDIDATE))).isFalse();
        assertThat(succeeds(MISSED, s -> s.setDepositsFound((short) 1))).isFalse();
        assertThat(succeeds(MISSED, s -> s.setRole(PaycheckStreamRole.INTERRUPTION))).isFalse();
        assertThat(succeeds(MISSED, s -> s.setPayerToken("ACME CORP"))).isFalse();
    }

    @Test
    void recencyExceptionNotImplementedInPhaseOne() {
        assertThat(succeeds(s -> s.setFirstDate(TODAY.minusDays(240)), s -> s.setFirstDate(TODAY.minusDays(18)) /* S1 old and paying, S2 newer */)).isFalse();
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.PrimarySelectorTest' --console=plain`. Expected: `package PrimarySelector does not exist`, missing `PrimarySelector`, `SuccessionRule`.

- [ ] **Step 16: PrimarySelector, SuccessionRule.** Create `D/PrimarySelector.java`:

```java
/** Spec 4.5 primary selection; every tie ends on the lower id. */
public final class PrimarySelector {

    public record Selection(Long primaryStreamId, List<Long> secondaryStreamIds, boolean needsChooser, boolean lowConfidence) {
    }

    public static final BigDecimal LOW_CONFIDENCE_SHARE = new BigDecimal("0.40");
    public static final BigDecimal LOW_CONFIDENCE_GOAL_MULTIPLE = new BigDecimal("4");

    private static final Comparator<PaycheckStream> BY_ID = Comparator.comparing(PaycheckStream::getId);
    private static final Comparator<PaycheckStream> BY_MEDIAN_DESC =
            Comparator.comparing(PaycheckStream::getTrailingMedian, Comparator.nullsLast(Comparator.reverseOrder()));
    private static final Comparator<PaycheckStream> SAME_PAYER = Comparator
            .comparing((PaycheckStream s) -> s.getCadence() == null ? 0 : s.getCadence().perYear(), Comparator.reverseOrder())
            .thenComparing(PaycheckStream::getTrailingCv, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(BY_ID);
    private static final Comparator<PaycheckStream> BY_SCORE = Comparator
            .comparing((PaycheckStream s) -> (int) s.getScore(), Comparator.reverseOrder()).thenComparing(BY_MEDIAN_DESC).thenComparing(BY_ID);

    public static Selection select(List<PaycheckStream> qualifying, BigDecimal monthlyGoal) {
        if (qualifying == null || qualifying.isEmpty()) { return new Selection(null, List.of(), false, false); }
        Map<String, List<PaycheckStream>> byPayer = new TreeMap<>();
        qualifying.forEach(s -> byPayer.computeIfAbsent(s.getPayerToken(), k -> new ArrayList<>()).add(s));
        List<PaycheckStream> winners = byPayer.values().stream().map(l -> l.stream().min(SAME_PAYER).orElseThrow()).toList();
        List<PaycheckStream> confirmed = winners.stream().filter(s -> s.getState() == PaycheckStreamState.CONFIRMED).toList();
        boolean needsChooser = confirmed.size() >= 2;
        PaycheckStream primary = needsChooser ? confirmed.stream().min(BY_MEDIAN_DESC.thenComparing(BY_ID)).orElseThrow()
                : winners.stream().min(BY_SCORE).orElseThrow();
        List<Long> secondary = qualifying.stream().map(PaycheckStream::getId).filter(id -> !id.equals(primary.getId())).sorted().toList();
        return new Selection(primary.getId(), secondary, needsChooser, lowConfidence(primary, monthlyGoal));
    }

    static boolean lowConfidence(PaycheckStream primary, BigDecimal monthlyGoal) {
        if (primary.getCadence() == null || primary.getTrailingMedian() == null) { return false; }
        BigDecimal goal = monthlyGoal == null || monthlyGoal.signum() <= 0 ? BigDecimal.valueOf(AmountPrefill.DEFAULT_GOAL) : monthlyGoal;
        BigDecimal threshold = goal.multiply(LOW_CONFIDENCE_GOAL_MULTIPLE).multiply(LOW_CONFIDENCE_SHARE);
        return DepositStats.monthlyNormalized(primary.getTrailingMedian(), primary.getCadence()).compareTo(threshold) < 0;
    }
}
```

and `SuccessionRule.java`:

```java
/** Spec 6.1 conditions (1) to (3) without the recency exception (R5). */
public final class SuccessionRule {

    public static final double QUIET_PERIODS = 1.5;

    public static boolean succeeds(PaycheckStream s1, PaycheckStream s2, LocalDate todayET) {
        if (s1 == null || s2 == null || Objects.equals(s1.getPayerToken(), s2.getPayerToken())) { return false; }
        boolean trusted = (s2.getState() == PaycheckStreamState.PROVISIONAL || s2.getState() == PaycheckStreamState.CONFIRMED)
                && s2.getDepositsFound() >= 2 && s2.getRole() != PaycheckStreamRole.INTERRUPTION;
        return trusted && s1Gone(s1, todayET);
    }

    static boolean s1Gone(PaycheckStream s1, LocalDate todayET) {
        if (s1.getMissedCount() >= 1 || Boolean.FALSE.equals(s1.getPlaidIsActive()) || "TOMBSTONED".equals(s1.getPlaidStatus())) {
            return true;
        }
        return s1.getLastDate() != null && s1.getCadence() != null
                && ChronoUnit.DAYS.between(s1.getLastDate(), todayET) > QUIET_PERIODS * PayCalendar.periodDays(s1.getCadence());
    }
}
```

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.PrimarySelectorTest' --console=plain
cd backend && ./gradlew test --tests 'com.investingapp.backend.paycheck.detect.SuccessionRuleTest' --console=plain
```
Expected: `tests="4"`, then `tests="3"`.

- [ ] **Step 17: Verify, then commit.** Run the Verification block; every line must match. All 33 files are new; stage nothing else (never `ITPM/agent-memory/*` or `application-local.properties`).
```
git add backend/src/main/java/com/investingapp/backend/paycheck/detect/ backend/src/test/java/com/investingapp/backend/paycheck/detect/
git status --short backend/src
git commit -m "feat(paycheck): detection pure functions for grouping, cadence, gates, score, selection and prefill"
```
`git status` must show exactly 17 `A` lines under `main/` and 16 under `test/`, all in `paycheck/detect/`, before the commit.

### Verification

From `backend/`:
```
for c in BusinessDays DescriptorSignals PayerTokenizer DepositGrouper DepositStats CadenceDeriver PayCalendar StreamFacts HardGates ScoreBreakdown StreamScorer StreamStateRule AmountPrefill PrimarySelector SuccessionRule; do
  ./gradlew test --tests "com.investingapp.backend.paycheck.detect.${c}Test" --console=plain -q
  printf '%s ' "$c"; grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' "build/test-results/test/TEST-com.investingapp.backend.paycheck.detect.${c}Test.xml"
done
./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain
grep -rnE 'setStatus\(|setMode\(|getIsPaused\(|isPausedForAnyReason\(|setHoldReason\(|Repository|PlaidApi|Transactional|AnalyticsOutbox|Logger|LocalDate\.now|EasternTime' src/main/java/com/investingapp/backend/paycheck/detect
LC_ALL=C grep -rn $'\xe2\x80\x94' src/main/java/com/investingapp/backend/paycheck src/test/java/com/investingapp/backend/paycheck
```
Expected: 15 lines, 53 tests, each `skipped="0" failures="0"`: BusinessDays 3, DescriptorSignals 1, PayerTokenizer 3, DepositGrouper 1, DepositStats 2, CadenceDeriver 8, PayCalendar 4, StreamFacts 1, HardGates 9, ScoreBreakdown 1, StreamScorer 6, StreamStateRule 3, AmountPrefill 4, PrimarySelector 4, SuccessionRule 3. `PhaseOneMoneyInvarianceTest` passes, XML `failures="0" skipped="0"` (no case added here). Both greps print nothing: no state write, repository, Plaid, transaction, outbox, log or clock, no em dash.

### Produces

Public in `com.investingapp.backend.paycheck.detect`, as in the blocks: `BusinessDays` (`isBusinessDay`, `plus`, `minus`, `between`, `adjustBackward`); `DescriptorSignals` (`hasBenefitsToken`, `hasNonPayrollChannelToken`, `hasPayrollToken`, `BENEFITS_TOKENS`, `NON_PAYROLL_CHANNEL_TOKENS`, `PAYROLL_TOKENS`, `PAYER_STRIP_WORDS`); `PayerTokenizer` (`token`, `displayName`); `DepositGroup` (record, `postedDatesAsc`, `lastSixAmounts`); `DepositGrouper.group`; `DepositStats` (`trailingMedian`, `trailingCv`, `monthlyNormalized`); `CadenceResult` (record); `CadenceDeriver.derive`; `PayCalendar` (`predictedNext`, `periodDays`, `periodKey`, `window`, `cadenceLabel`, `CycleWindow`); `StreamFacts` (record, `of`, `mature`); `HardGates` (`evaluate`, `STALE_PERIODS`, the four `LOW_MEDIAN_*`, `lowMedianFloor`); `ScoreBreakdown` (record, `toJson`); `StreamScorer.score`; `StreamStateRule` (`state`, `onCadenceCount`); `AmountPrefill` (`compute`, `Prefill`, `DEFAULT_GOAL`); `PrimarySelector` (`select`, `Selection`); `SuccessionRule.succeeds`.
