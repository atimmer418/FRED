### Task 3: frequency-enum

One backend `InvestmentFrequency` enum replaces the three live factor copies (WEEKLY 4.33, BIWEEKLY 2.17, SEMI_MONTHLY 2, MONTHLY 1) and `FrequencyFactorParityTest` pins it against the client table in `investment-frequency.utils.ts` by reading the TypeScript source, the same way `AnalyticsBandsParityTest` (`backend/src/test/java/com/investingapp/backend/util/AnalyticsBandsParityTest.java:30-35`) guards the analytics bands. `InvestmentSchedule.frequency` stays a `VARCHAR(20)` String column; the enum is parsing and money math only. Independent of T01/T02; T04 and T13 consume the enum.

Spec reconciliation: spec 4.4 (second paragraph) and 13.1 count four backend copies, the fourth being `PaycheckDetectionService.factorFor`. That class was deleted in commit 90a8cc1 and exists in the live tree nowhere (`grep -rn PaycheckDetectionService backend/src` is empty); T15 recovers it under `FREDdocs/archive/` as reference only, never compiled. So Phase 0 has exactly three readers to rewire: `InvestmentScheduleService.java:322-324`, `InvestmentSchedule.java:327-329`, `MonthlyFreedomUpdateService.java:915-920`.

Scope note: three onboarding components carry display-only `paychecksPerMonth` literals in their option lists (`frontend/src/app/recurring-investments/recurring-investments.page.ts:85,91`, `frontend/src/app/components/investment-schedule/investment-schedule.component.ts:99,106`, `frontend/src/app/components/investmentconfirmation/investmentconfirmation.component.ts:290-291,520,528-534`). They are not the client table the spec names and are not touched by this task; leave them alone.

**Files:**
- Create: `backend/src/main/java/com/investingapp/backend/model/InvestmentFrequency.java`
- Create: `backend/src/test/java/com/investingapp/backend/model/FrequencyFactorParityTest.java` (new test dir `model/`; today the test tree has only `config controller metrics scheduler security service support util`)
- Create: `backend/src/test/java/com/investingapp/backend/service/InvestmentScheduleServiceMonthlyAmountTest.java`
- Modify: `backend/src/main/java/com/investingapp/backend/service/InvestmentScheduleService.java:3` and `:17` (imports), `:215-234` (frequencySlug javadoc tail + method), `:314-328` (calculateMonthlyAmountFromInvestment)
- Modify: `backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java:319-337` (calculateInvestmentAmountFromMonthly)
- Modify: `backend/src/main/java/com/investingapp/backend/service/MonthlyFreedomUpdateService.java:4` (import), `:907-925` (calculateMonthlyEquivalent)
- Modify: `frontend/src/app/utils/investment-frequency.utils.ts` (whole 31-line file replaced)
- Test: `backend/src/test/java/com/investingapp/backend/service/MonthlyFreedomUpdateServiceTest.java:144-154` (`@Test` at :144 through the closing brace at :154; MONTHLY assertion tightened to scale 2, two lenient-path assertions added), `backend/src/test/java/com/investingapp/backend/service/InvestmentScheduleServiceAnalyticsTest.java:167` (one assertion added after it), `frontend/src/app/utils/investment-frequency.utils.spec.ts` (whole 40-line file replaced)

**Interfaces:**
- Consumes (existing, unchanged signatures): `MonthlyFreedomUpdateService.calculateMonthlyEquivalent(BigDecimal amount, String frequency)` package-private at `MonthlyFreedomUpdateService.java:910` (callers `:288`, `:299`, pinned by `MonthlyFreedomUpdateServiceTest.java:145-154`); `static String InvestmentScheduleService.frequencySlug(String)` at `InvestmentScheduleService.java:218` (caller `:196`, pinned by `InvestmentScheduleServiceAnalyticsTest.java:162-173`); `private BigDecimal InvestmentScheduleService.calculateMonthlyAmountFromInvestment(BigDecimal, String)` at `:317` (callers `:71`, `:130`, `:416`); `public BigDecimal InvestmentSchedule.calculateInvestmentAmountFromMonthly()` at `InvestmentSchedule.java:322` (no production caller; `grep -rn calculateInvestmentAmountFromMonthly backend/src frontend/src` hits only the definition); `InvestmentFrequencyUtils.toMonthlyEquivalent(amount: number, frequency: string | null | undefined): number` at `investment-frequency.utils.ts:15` (callers `goal-add-sheet.component.ts:94`, `my-profile.page.ts:436,929`, `freedom-stats.service.ts:336`).
- Produces: `public enum com.investingapp.backend.model.InvestmentFrequency { WEEKLY("4.33",52), BIWEEKLY("2.17",26), SEMI_MONTHLY("2",24), MONTHLY("1",12); BigDecimal monthlyFactor(); int perYear(); BigDecimal toMonthly(BigDecimal perPeriod); BigDecimal fromMonthly(BigDecimal monthly); static InvestmentFrequency parse(String); static Optional<InvestmentFrequency> tryParse(String) }`; `export type InvestmentFrequencyKey = 'WEEKLY' | 'BIWEEKLY' | 'SEMI_MONTHLY' | 'MONTHLY'`; `export const FREQUENCY_FACTOR_HUNDREDTHS: Record<InvestmentFrequencyKey, number>`; `export const PAYCHECKS_PER_YEAR: Record<InvestmentFrequencyKey, number>`; `static InvestmentFrequencyUtils.parse(frequency: string | null | undefined): InvestmentFrequencyKey | null`.
- Behaviour preserved for every reader: unknown or blank frequency keeps the old lenient default (amount returned unchanged, slug null). Three deliberate widenings: the SEMIMONTHLY alias is now accepted by all four readers, a null frequency no longer throws NullPointerException in the three backend readers (each called `frequency.toUpperCase()` unguarded), and every converted amount comes back at scale 2 (the DB columns are `decimal(10,2)`, `V1__baseline.sql:153,156`, so stored values do not change).

- [ ] **Step 1: Write the failing parity test (enum pins + client table read from source)**

Create `backend/src/test/java/com/investingapp/backend/model/FrequencyFactorParityTest.java`:

```java
package com.investingapp.backend.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One InvestmentFrequency enum feeds every backend reader and the client table mirrors it.
 *
 * <p>The unified Freedom Date depends on both stacks converting a per-paycheck amount to the same
 * monthly number, so the factors are pinned here as literals and the TypeScript table is read from
 * source, the same way AnalyticsBandsParityTest guards the analytics bands.
 */
class FrequencyFactorParityTest {

    @Test
    @DisplayName("the four cadences carry the documented monthly factors")
    void monthlyFactors() {
        assertEquals(new BigDecimal("4.33"), InvestmentFrequency.WEEKLY.monthlyFactor());
        assertEquals(new BigDecimal("2.17"), InvestmentFrequency.BIWEEKLY.monthlyFactor());
        assertEquals(new BigDecimal("2"), InvestmentFrequency.SEMI_MONTHLY.monthlyFactor());
        assertEquals(new BigDecimal("1"), InvestmentFrequency.MONTHLY.monthlyFactor());
    }

    @Test
    @DisplayName("paychecks per year are 52, 26, 24 and 12")
    void perYear() {
        assertEquals(52, InvestmentFrequency.WEEKLY.perYear());
        assertEquals(26, InvestmentFrequency.BIWEEKLY.perYear());
        assertEquals(24, InvestmentFrequency.SEMI_MONTHLY.perYear());
        assertEquals(12, InvestmentFrequency.MONTHLY.perYear());
    }

    @Test
    @DisplayName("parse trims, ignores case and accepts the SEMIMONTHLY alias")
    void parseAndAlias() {
        assertEquals(InvestmentFrequency.SEMI_MONTHLY, InvestmentFrequency.parse("SEMIMONTHLY"));
        assertEquals(InvestmentFrequency.SEMI_MONTHLY, InvestmentFrequency.parse("semimonthly"));
        assertEquals(InvestmentFrequency.SEMI_MONTHLY, InvestmentFrequency.parse("SEMI_MONTHLY"));
        assertEquals(InvestmentFrequency.MONTHLY, InvestmentFrequency.parse(" monthly "));
        assertEquals(InvestmentFrequency.BIWEEKLY, InvestmentFrequency.parse("Biweekly"));
        assertThrows(IllegalArgumentException.class, () -> InvestmentFrequency.parse("QUARTERLY"));
        assertThrows(IllegalArgumentException.class, () -> InvestmentFrequency.parse(""));
        assertThrows(IllegalArgumentException.class, () -> InvestmentFrequency.parse(null));
    }

    @Test
    @DisplayName("tryParse never throws and is empty for null, blank or unknown input")
    void tryParseIsLenient() {
        assertEquals(Optional.of(InvestmentFrequency.WEEKLY), InvestmentFrequency.tryParse("weekly"));
        assertEquals(Optional.of(InvestmentFrequency.SEMI_MONTHLY), InvestmentFrequency.tryParse("SEMIMONTHLY"));
        assertEquals(Optional.empty(), InvestmentFrequency.tryParse(null));
        assertEquals(Optional.empty(), InvestmentFrequency.tryParse(""));
        assertEquals(Optional.empty(), InvestmentFrequency.tryParse("   "));
        assertEquals(Optional.empty(), InvestmentFrequency.tryParse("every other tuesday"));
    }

    @Test
    @DisplayName("toMonthly rounds to cents HALF_UP, matching the client's integer-cents arithmetic")
    void toMonthlyRoundsHalfUp() {
        // PARITY: the same cases live in investment-frequency.utils.spec.ts.
        assertEquals(new BigDecimal("67.12"), InvestmentFrequency.WEEKLY.toMonthly(new BigDecimal("15.50")));
        assertEquals(new BigDecimal("433.00"), InvestmentFrequency.WEEKLY.toMonthly(new BigDecimal("100")));
        assertEquals(new BigDecimal("499.99"), InvestmentFrequency.WEEKLY.toMonthly(new BigDecimal("115.47")));
        assertEquals(new BigDecimal("27.13"), InvestmentFrequency.BIWEEKLY.toMonthly(new BigDecimal("12.50")));
        assertEquals(new BigDecimal("500.00"), InvestmentFrequency.SEMI_MONTHLY.toMonthly(new BigDecimal("250")));
        assertEquals(new BigDecimal("500.00"), InvestmentFrequency.MONTHLY.toMonthly(new BigDecimal("500")));
    }

    @Test
    @DisplayName("fromMonthly divides at scale 2 HALF_UP")
    void fromMonthlyDividesAtScaleTwo() {
        assertEquals(new BigDecimal("100.00"), InvestmentFrequency.WEEKLY.fromMonthly(new BigDecimal("433.00")));
        assertEquals(new BigDecimal("230.41"), InvestmentFrequency.BIWEEKLY.fromMonthly(new BigDecimal("500")));
        assertEquals(new BigDecimal("250.00"), InvestmentFrequency.SEMI_MONTHLY.fromMonthly(new BigDecimal("500")));
        assertEquals(new BigDecimal("500.00"), InvestmentFrequency.MONTHLY.fromMonthly(new BigDecimal("500")));
    }

    /** The client copy, read from source so a drift on either side fails the build. */
    private static String clientSource() throws IOException {
        Path path = Paths.get("..", "frontend", "src", "app", "utils", "investment-frequency.utils.ts")
                .toAbsolutePath().normalize();
        assertTrue(Files.exists(path),
                "cannot find the client frequency table at " + path + "; if the frontend moved, this "
                        + "test must move with it rather than be deleted");
        return Files.readString(path);
    }

    /** The number on the KEY: number row for one cadence inside the named exported const object. */
    private static int clientValue(String source, String constName, InvestmentFrequency frequency) {
        Matcher table = Pattern
                .compile("export const " + constName + "[^=]*=\\s*\\{(.*?)\\};", Pattern.DOTALL)
                .matcher(source);
        assertTrue(table.find(), constName + " is no longer declared in investment-frequency.utils.ts");
        Matcher row = Pattern.compile("\\b" + frequency.name() + ":\\s*([0-9]+)").matcher(table.group(1));
        assertTrue(row.find(), constName + " has no row for " + frequency.name());
        return Integer.parseInt(row.group(1));
    }

    @Test
    @DisplayName("the client table carries the same factors in hundredths and the same paychecks per year")
    void clientTableMatchesTheEnum() throws IOException {
        String source = clientSource();
        for (InvestmentFrequency frequency : InvestmentFrequency.values()) {
            int expectedHundredths = frequency.monthlyFactor().movePointRight(2).intValueExact();
            assertEquals(expectedHundredths, clientValue(source, "FREQUENCY_FACTOR_HUNDREDTHS", frequency),
                    frequency.name() + " factor drifted between InvestmentFrequency.java and investment-frequency.utils.ts");
            assertEquals(frequency.perYear(), clientValue(source, "PAYCHECKS_PER_YEAR", frequency),
                    frequency.name() + " paychecks per year drifted");
        }
    }
}
```

- [ ] **Step 2: Run it and watch it fail to compile**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.model.FrequencyFactorParityTest'`

Expected: `compileTestJava` fails with `error: cannot find symbol` repeated for every reference, reporting `symbol: variable InvestmentFrequency` on the `InvestmentFrequency.WEEKLY...` lines and `symbol: class InvestmentFrequency` on the `for (InvestmentFrequency frequency : ...)` line and the `clientValue` parameter, then `BUILD FAILED`. No test runs yet.

- [ ] **Step 3: Create the enum**

Create `backend/src/main/java/com/investingapp/backend/model/InvestmentFrequency.java`:

```java
package com.investingapp.backend.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The one place the paycheck cadence factors live.
 *
 * <p>Every backend reader (InvestmentScheduleService, InvestmentSchedule,
 * MonthlyFreedomUpdateService) delegates here, and the client table in
 * frontend/src/app/utils/investment-frequency.utils.ts mirrors these values in hundredths.
 * FrequencyFactorParityTest pins both sides.
 *
 * <p>InvestmentSchedule.frequency stays a VARCHAR(20) String column holding one of these names.
 * This enum is for parsing and money math only, never a mapped column type.
 */
public enum InvestmentFrequency {
    WEEKLY("4.33", 52),
    BIWEEKLY("2.17", 26),
    SEMI_MONTHLY("2", 24),
    MONTHLY("1", 12);

    /** Money is stored at scale 2 (investment_amount and monthly_amount are DECIMAL(10,2)). */
    private static final int MONEY_SCALE = 2;

    /** Legacy spelling still sent by older clients and stored on older rows. */
    private static final String SEMI_MONTHLY_ALIAS = "SEMIMONTHLY";

    private final BigDecimal monthlyFactor;
    private final int perYear;

    InvestmentFrequency(String monthlyFactor, int perYear) {
        this.monthlyFactor = new BigDecimal(monthlyFactor);
        this.perYear = perYear;
    }

    /** Paychecks per month: 4.33, 2.17, 2 or 1. */
    public BigDecimal monthlyFactor() {
        return monthlyFactor;
    }

    /** Paychecks per year: 52, 26, 24 or 12. */
    public int perYear() {
        return perYear;
    }

    /** Per-paycheck amount to its monthly equivalent, rounded to cents HALF_UP. */
    public BigDecimal toMonthly(BigDecimal perPeriod) {
        Objects.requireNonNull(perPeriod, "perPeriod");
        return perPeriod.multiply(monthlyFactor).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Monthly amount to its per-paycheck equivalent, rounded to cents HALF_UP. */
    public BigDecimal fromMonthly(BigDecimal monthly) {
        Objects.requireNonNull(monthly, "monthly");
        return monthly.divide(monthlyFactor, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Strict parse: trims, upper-cases, maps the SEMIMONTHLY alias, throws on anything else. */
    public static InvestmentFrequency parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Investment frequency is null");
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        if (SEMI_MONTHLY_ALIAS.equals(key)) {
            key = SEMI_MONTHLY.name();
        }
        try {
            return valueOf(key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown investment frequency: " + raw, e);
        }
    }

    /** Lenient parse: empty for null, blank or unknown input, never throws. */
    public static Optional<InvestmentFrequency> tryParse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 4: Run again; the enum pins pass, the client-table test still fails**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.model.FrequencyFactorParityTest'`

Expected: 7 tests run, 6 pass, exactly one failure: `FrequencyFactorParityTest > clientTableMatchesTheEnum() FAILED` with `org.opentest4j.AssertionFailedError: FREQUENCY_FACTOR_HUNDREDTHS is no longer declared in investment-frequency.utils.ts ==> expected: <true> but was: <false>` (the file exists at HEAD, but the exported table does not yet), and `BUILD FAILED`.

- [ ] **Step 5: Write the failing frontend spec**

Replace the whole of `frontend/src/app/utils/investment-frequency.utils.spec.ts` (40 lines at HEAD) with:

```ts
import {
  FREQUENCY_FACTOR_HUNDREDTHS,
  InvestmentFrequencyUtils,
  PAYCHECKS_PER_YEAR,
} from './investment-frequency.utils';

describe('InvestmentFrequencyUtils', () => {

  // PARITY: FrequencyFactorParityTest (backend/src/test/java/com/investingapp/backend/model)
  // reads these two tables from source and compares them with InvestmentFrequency.java.
  // If you change one, change both.
  it('pins the client factor table in hundredths', () => {
    expect(FREQUENCY_FACTOR_HUNDREDTHS).toEqual({
      WEEKLY: 433,
      BIWEEKLY: 217,
      SEMI_MONTHLY: 200,
      MONTHLY: 100,
    });
  });

  it('pins paychecks per year', () => {
    expect(PAYCHECKS_PER_YEAR).toEqual({
      WEEKLY: 52,
      BIWEEKLY: 26,
      SEMI_MONTHLY: 24,
      MONTHLY: 12,
    });
  });

  it('parses trimmed, case-insensitive keys and the SEMIMONTHLY alias', () => {
    expect(InvestmentFrequencyUtils.parse(' weekly ')).toBe('WEEKLY');
    expect(InvestmentFrequencyUtils.parse('Biweekly')).toBe('BIWEEKLY');
    expect(InvestmentFrequencyUtils.parse('semimonthly')).toBe('SEMI_MONTHLY');
    expect(InvestmentFrequencyUtils.parse('SEMI_MONTHLY')).toBe('SEMI_MONTHLY');
    expect(InvestmentFrequencyUtils.parse('QUARTERLY')).toBeNull();
    expect(InvestmentFrequencyUtils.parse('')).toBeNull();
    expect(InvestmentFrequencyUtils.parse(null)).toBeNull();
    expect(InvestmentFrequencyUtils.parse(undefined)).toBeNull();
  });

  it('converts WEEKLY with the backend factor x4.33', () => {
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(100, 'WEEKLY')).toBe(433);
  });

  it('converts BIWEEKLY with x2.17 (backend parity, not the old my-profile 2.16)', () => {
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(100, 'BIWEEKLY')).toBe(217);
  });

  it('converts semi-monthly with x2, accepting both spellings', () => {
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(250, 'SEMI_MONTHLY')).toBe(500);
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(250, 'semimonthly')).toBe(500);
  });

  it('passes monthly through unchanged and is case-insensitive', () => {
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(500, 'monthly')).toBe(500);
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(500, 'weekly')).toBe(2165);
  });

  it('rounds to cents like the backend setScale(2, HALF_UP)', () => {
    // 115.47 x 4.33 = 499.9851, rounded to 499.99
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(115.47, 'WEEKLY')).toBe(499.99);
    // Halfway-cent boundary: 15.5 x 4.33 = 67.115, HALF_UP gives 67.12
    // (naive float Math.round gives 67.11, the exact bug this guards)
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(15.5, 'WEEKLY')).toBe(67.12);
    // Halfway-cent boundary for biweekly: 12.5 x 2.17 = 27.125, rounded to 27.13
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(12.5, 'BIWEEKLY')).toBe(27.13);
  });

  it('returns 0 for zero/negative amounts; unknown or missing frequency defaults to monthly', () => {
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(0, 'WEEKLY')).toBe(0);
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(-5, 'WEEKLY')).toBe(0);
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(500, 'QUARTERLY')).toBe(500);
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(500, undefined)).toBe(500);
    expect(InvestmentFrequencyUtils.toMonthlyEquivalent(500, null)).toBe(500);
  });
});
```

- [ ] **Step 6: Run the spec and watch it fail to compile**

Run: `cd frontend && npx ng test --include='**/investment-frequency.utils.spec.ts' --watch=false`

Expected: the build step fails before any test runs with `error TS2305: Module '"./investment-frequency.utils"' has no exported member 'FREQUENCY_FACTOR_HUNDREDTHS'.` (and the same for `PAYCHECKS_PER_YEAR`) plus `error TS2339: Property 'parse' does not exist on type 'typeof InvestmentFrequencyUtils'.`; the command exits non-zero.

- [ ] **Step 7: Replace the client util with the exported table**

Replace the whole of `frontend/src/app/utils/investment-frequency.utils.ts` (31 lines at HEAD) with:

```ts
/**
 * investment-frequency.utils.ts
 *
 * The client copy of the backend InvestmentFrequency enum
 * (backend/src/main/java/com/investingapp/backend/model/InvestmentFrequency.java).
 *
 * PARITY: FrequencyFactorParityTest reads FREQUENCY_FACTOR_HUNDREDTHS and PAYCHECKS_PER_YEAR
 * from this file and compares them with the enum; investment-frequency.utils.spec.ts pins the
 * same values here. If you change one, change both. The unified Freedom Date depends on both
 * stacks converting a per-paycheck amount to the same monthly number.
 *
 * Factors are stored in hundredths (4.33 becomes 433) so the cents arithmetic in
 * toMonthlyEquivalent stays exact. Assumes cents-quantized amounts (the DB stores
 * investment_amount at scale 2); sub-cent inputs are quantized to cents before conversion.
 */
export type InvestmentFrequencyKey = 'WEEKLY' | 'BIWEEKLY' | 'SEMI_MONTHLY' | 'MONTHLY';

/** Paychecks per month, in hundredths: WEEKLY 4.33, BIWEEKLY 2.17, SEMI_MONTHLY 2, MONTHLY 1. */
export const FREQUENCY_FACTOR_HUNDREDTHS: Record<InvestmentFrequencyKey, number> = {
  WEEKLY: 433,
  BIWEEKLY: 217,
  SEMI_MONTHLY: 200,
  MONTHLY: 100,
};

/** Paychecks per year. */
export const PAYCHECKS_PER_YEAR: Record<InvestmentFrequencyKey, number> = {
  WEEKLY: 52,
  BIWEEKLY: 26,
  SEMI_MONTHLY: 24,
  MONTHLY: 12,
};

/** Legacy spelling still present on older rows and older clients. */
const SEMI_MONTHLY_ALIAS = 'SEMIMONTHLY';

export class InvestmentFrequencyUtils {

  /**
   * Normalizes a raw frequency string to a table key: trims, upper-cases and maps the
   * SEMIMONTHLY alias. Returns null for null, blank or unknown input (mirrors
   * InvestmentFrequency.tryParse on the backend).
   */
  static parse(frequency: string | null | undefined): InvestmentFrequencyKey | null {
    if (!frequency) return null;
    const upper = frequency.trim().toUpperCase();
    const key = upper === SEMI_MONTHLY_ALIAS ? 'SEMI_MONTHLY' : upper;
    return Object.prototype.hasOwnProperty.call(FREQUENCY_FACTOR_HUNDREDTHS, key)
      ? (key as InvestmentFrequencyKey)
      : null;
  }

  /**
   * Converts a per-execution investment amount to its monthly equivalent.
   * Unknown or missing frequency falls back to MONTHLY (factor 1), as before.
   */
  static toMonthlyEquivalent(amount: number, frequency: string | null | undefined): number {
    if (!amount || amount <= 0) return 0;

    const key = InvestmentFrequencyUtils.parse(frequency) ?? 'MONTHLY';
    const factorHundredths = FREQUENCY_FACTOR_HUNDREDTHS[key];

    // Exact integer-cents arithmetic reproduces the backend's
    // BigDecimal setScale(2, HALF_UP) without binary floating-point drift
    // (15.5 x 4.33 = 67.115 must round up to 67.12).
    const cents = Math.round(amount * 100);
    const monthlyCents = Math.floor((cents * factorHundredths + 50) / 100);
    return monthlyCents / 100;
  }
}
```

- [ ] **Step 8: Run the spec to green**

Run: `cd frontend && npx ng test --include='**/investment-frequency.utils.spec.ts' --watch=false`

Expected: `Executed 9 of 9 SUCCESS`, exit code 0. (Karma type-checks the whole app even with `--include`; an unrelated compile error from another session's WIP is not this task's failure.)

- [ ] **Step 9: Re-run the backend parity test to green**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.model.FrequencyFactorParityTest'`

Expected: 7 tests, 0 failures, `BUILD SUCCESSFUL`. `clientTableMatchesTheEnum` now finds `WEEKLY: 433` ... `MONTHLY: 12` in the TypeScript source.

- [ ] **Step 10: Tighten the MonthlyFreedomUpdateService pin so it fails on the old switch**

In `backend/src/test/java/com/investingapp/backend/service/MonthlyFreedomUpdateServiceTest.java` replace lines 144-154 (the `@Test` annotation at :144 through the method's closing brace at :154; the file's final class brace at :155 stays) with:

```java
    @Test
    void monthlyEquivalentFactorsMatchFrontendUtil() {
        // PARITY: the factors live in InvestmentFrequency and are pinned against
        // frontend/src/app/utils/investment-frequency.utils.ts by FrequencyFactorParityTest.
        // Every result comes back at scale 2, MONTHLY included.
        assertEquals(new BigDecimal("433.00"), service.calculateMonthlyEquivalent(new BigDecimal("100"), "WEEKLY"));
        assertEquals(new BigDecimal("217.00"), service.calculateMonthlyEquivalent(new BigDecimal("100"), "BIWEEKLY"));
        assertEquals(new BigDecimal("499.99"), service.calculateMonthlyEquivalent(new BigDecimal("115.47"), "WEEKLY"));
        assertEquals(new BigDecimal("500.00"), service.calculateMonthlyEquivalent(new BigDecimal("250"), "SEMI_MONTHLY"));
        assertEquals(new BigDecimal("500.00"), service.calculateMonthlyEquivalent(new BigDecimal("250"), "SEMIMONTHLY"));
        assertEquals(new BigDecimal("500.00"), service.calculateMonthlyEquivalent(new BigDecimal("500"), "MONTHLY"));
        assertEquals(BigDecimal.ZERO, service.calculateMonthlyEquivalent(null, "WEEKLY"));
        assertEquals(new BigDecimal("500"), service.calculateMonthlyEquivalent(new BigDecimal("500"), "QUARTERLY"));
    }
```

The file already imports `java.math.BigDecimal` (:6) and `assertEquals` (:9); no import changes.

- [ ] **Step 11: Run it and see the MONTHLY scale fail**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.MonthlyFreedomUpdateServiceTest'`

Expected: one failure, `monthlyEquivalentFactorsMatchFrontendUtil() FAILED` with `AssertionFailedError: expected: <500.00> but was: <500>` (the old `case "MONTHLY": return amount;` returns the scale-0 input; `BigDecimal.equals` is scale-sensitive; the five assertions above it pass on the old code, so this sixth one is the first to fail). `BUILD FAILED`.

- [ ] **Step 12: Delegate calculateMonthlyEquivalent to the enum**

In `backend/src/main/java/com/investingapp/backend/service/MonthlyFreedomUpdateService.java` add one import after line 4 (`import com.investingapp.backend.dto.MonthlyFreedomUpdateDTO.MilestoneDTO;`):

```java
import com.investingapp.backend.model.InvestmentFrequency;
```

Then replace lines 907-925 (HEAD numbering; the javadoc `/** Convert per-execution investment amount to monthly equivalent. */` through the closing brace of the switch method; after the import insertion they sit at 908-926) with:

```java
    /**
     * Convert per-execution investment amount to monthly equivalent through
     * {@link InvestmentFrequency}. An unknown frequency returns the amount unchanged.
     */
    BigDecimal calculateMonthlyEquivalent(BigDecimal amount, String frequency) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;

        return InvestmentFrequency.tryParse(frequency)
                .map(parsed -> parsed.toMonthly(amount))
                .orElse(amount);
    }
```

`java.math.RoundingMode` (:19) stays imported; it is still used at `:263`, `:326` and `:944`.

- [ ] **Step 13: Run to green**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.MonthlyFreedomUpdateServiceTest'`

Expected: all tests pass, `BUILD SUCCESSFUL`.

- [ ] **Step 14: Write the failing InvestmentScheduleService tests (slug alias + stored monthly_amount scale)**

(a) In `backend/src/test/java/com/investingapp/backend/service/InvestmentScheduleServiceAnalyticsTest.java`, inside `frequencySlugIsAClosedVocabulary`, add this line directly after line 167 (`assertEquals("monthly", InvestmentScheduleService.frequencySlug(" monthly "));`):

```java
        assertEquals("semi_monthly", InvestmentScheduleService.frequencySlug("SEMIMONTHLY"));
```

(b) Create `backend/src/test/java/com/investingapp/backend/service/InvestmentScheduleServiceMonthlyAmountTest.java`. It drives the real create branch of `createInvestmentSchedule` with the same two stubs `InvestmentScheduleServiceAnalyticsTest.unknownFrequencyIsSentAsNullNotAsFreeText` (:177-183) already proves sufficient under Mockito strict stubs (`findById(null)` on the mock returns `Optional.empty()`, `portfolioService.getOrCreatePortfolio` returns null, both harmless):

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * monthly_amount is derived from the per-paycheck amount through InvestmentFrequency, so the
 * stored value is rounded to cents exactly like the client's InvestmentFrequencyUtils.
 */
@ExtendWith(MockitoExtension.class)
class InvestmentScheduleServiceMonthlyAmountTest {

    @Mock
    private InvestmentScheduleRepository investmentScheduleRepository;
    @Mock
    private PortfolioService portfolioService;
    @Mock
    private StreakService streakService;
    @Mock
    private AnalyticsOutboxService analyticsOutboxService;

    @InjectMocks
    private InvestmentScheduleService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(78L);
        user.setEmail("monthly-amount@example.invalid");
        user.setCreateDate(LocalDateTime.now().minusDays(1));
        when(investmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());
        when(investmentScheduleRepository.save(any(InvestmentSchedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private BigDecimal monthlyAmountFor(String investmentAmount, String frequency) {
        InvestmentSchedule saved = service.createInvestmentSchedule(user, new BigDecimal(investmentAmount),
                frequency, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6));
        return saved.getMonthlyAmount();
    }

    @Test
    void weeklyMonthlyAmountIsRoundedToCentsHalfUp() {
        // 15.50 x 4.33 = 67.115, which the client rounds to 67.12; the row must hold the same number.
        assertEquals(new BigDecimal("67.12"), monthlyAmountFor("15.50", "WEEKLY"));
    }

    @Test
    void biweeklyAndSemiMonthlyUseTheEnumFactors() {
        assertEquals(new BigDecimal("542.50"), monthlyAmountFor("250.00", "BIWEEKLY"));
        assertEquals(new BigDecimal("500.00"), monthlyAmountFor("250.00", "SEMI_MONTHLY"));
        assertEquals(new BigDecimal("500.00"), monthlyAmountFor("250.00", "SEMIMONTHLY"));
    }

    @Test
    void unknownFrequencyKeepsTheLenientDefault() {
        // The column is an unvalidated varchar; an unknown value stores the per-paycheck amount as
        // the monthly amount, exactly as before, so the analytics null-slug path keeps working.
        assertEquals(new BigDecimal("25.00"), monthlyAmountFor("25.00", "FORTNIGHTLY-ISH"));
    }
}
```

- [ ] **Step 15: Run both and see the old switches fail**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.InvestmentScheduleServiceAnalyticsTest' --tests 'com.investingapp.backend.service.InvestmentScheduleServiceMonthlyAmountTest'`

Expected: `frequencySlugIsAClosedVocabulary() FAILED` with `expected: <semi_monthly> but was: <null>`; `weeklyMonthlyAmountIsRoundedToCentsHalfUp() FAILED` with `expected: <67.12> but was: <67.1150>` (the old multiply has no setScale); `biweeklyAndSemiMonthlyUseTheEnumFactors() FAILED` with `expected: <542.50> but was: <542.5000>`; `unknownFrequencyKeepsTheLenientDefault` passes; the other four analytics tests pass. `BUILD FAILED`.

- [ ] **Step 16: Delegate frequencySlug and calculateMonthlyAmountFromInvestment**

In `backend/src/main/java/com/investingapp/backend/service/InvestmentScheduleService.java`:

(a) Add after line 3 (`import com.investingapp.backend.model.InvestmentSchedule;`):

```java
import com.investingapp.backend.model.InvestmentFrequency;
```

and after line 17 (`import java.util.List;`):

```java
import java.util.Locale;
```

(b) Replace HEAD lines 215-234 (from the javadoc paragraph `     * <p>The four handled values are the four every switch in this service and in` through the closing brace of `frequencySlug`; lines 205-214 of the javadoc above it stay) with:

```java
     * <p>The handled values are the members of {@link InvestmentFrequency}: WEEKLY, BIWEEKLY,
     * SEMI_MONTHLY (also under its legacy SEMIMONTHLY spelling) and MONTHLY.
     */
    static String frequencySlug(String frequency) {
        return InvestmentFrequency.tryParse(frequency)
                .map(parsed -> parsed.name().toLowerCase(Locale.ROOT))
                .orElse(null);
    }
```

(c) Replace HEAD lines 314-328 (the javadoc `/** Calculate monthly amount from investment amount and frequency */` through the closing brace of the switch method) with:

```java
    /**
     * Monthly amount from the per-paycheck amount, through {@link InvestmentFrequency} so the row
     * holds the same cents the client computes. An unknown frequency keeps the old lenient default
     * and stores the per-paycheck amount unchanged.
     */
    private BigDecimal calculateMonthlyAmountFromInvestment(BigDecimal investmentAmount, String frequency) {
        if (investmentAmount == null)
            return BigDecimal.ZERO;

        return InvestmentFrequency.tryParse(frequency)
                .map(parsed -> parsed.toMonthly(investmentAmount))
                .orElse(investmentAmount);
    }
```

(Line numbers in (b) and (c) are HEAD numbers; after the two import insertions in (a) they sit two lines lower. Match on the quoted text. The `InvestmentSchedule` import at :3 stays; the class still uses the type throughout.)

- [ ] **Step 17: Run both to green**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.InvestmentScheduleServiceAnalyticsTest' --tests 'com.investingapp.backend.service.InvestmentScheduleServiceMonthlyAmountTest'`

Expected: all 5 analytics tests and all 3 monthly-amount tests pass, `BUILD SUCCESSFUL`. `unknownFrequencyIsSentAsNullNotAsFreeText` still passes because `tryParse("FORTNIGHTLY-ISH")` is empty and the slug stays null.

- [ ] **Step 18: Write the failing entity test**

Append to `backend/src/test/java/com/investingapp/backend/model/FrequencyFactorParityTest.java`, before the final closing brace of the class (no new import: the test and `InvestmentSchedule` share the package `com.investingapp.backend.model`):

```java
    private static InvestmentSchedule scheduleWith(String monthlyAmount, String frequency) {
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setMonthlyAmount(monthlyAmount == null ? null : new BigDecimal(monthlyAmount));
        schedule.setFrequency(frequency);
        return schedule;
    }

    @Test
    @DisplayName("InvestmentSchedule.calculateInvestmentAmountFromMonthly reads the enum")
    void scheduleEntityReadsTheEnum() {
        assertEquals(new BigDecimal("100.00"), scheduleWith("433.00", "WEEKLY").calculateInvestmentAmountFromMonthly());
        assertEquals(new BigDecimal("230.41"), scheduleWith("500", "BIWEEKLY").calculateInvestmentAmountFromMonthly());
        // The alias and MONTHLY go through the enum too: both come back at scale 2.
        assertEquals(new BigDecimal("250.00"), scheduleWith("500", "SEMIMONTHLY").calculateInvestmentAmountFromMonthly());
        assertEquals(new BigDecimal("500.00"), scheduleWith("500", "MONTHLY").calculateInvestmentAmountFromMonthly());
        // Unknown frequency keeps the old lenient default: the monthly amount itself.
        assertEquals(new BigDecimal("500"), scheduleWith("500", "QUARTERLY").calculateInvestmentAmountFromMonthly());
        assertEquals(BigDecimal.ZERO, scheduleWith(null, "WEEKLY").calculateInvestmentAmountFromMonthly());
    }
```

- [ ] **Step 19: Run and see the entity switch fail**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.model.FrequencyFactorParityTest'`

Expected: `scheduleEntityReadsTheEnum() FAILED` with `expected: <250.00> but was: <500>` (the first two assertions pass on the old divide-at-scale-2 code; the old switch has no SEMIMONTHLY case, so the alias falls to `default -> monthlyAmount`). The other 7 tests pass. `BUILD FAILED`.

- [ ] **Step 20: Delegate the entity method**

In `backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java` replace lines 319-337 (the javadoc `/** Calculate investment amount based on frequency relative to monthly amount */` through the closing brace of the switch method, including the wrapped `// Exactly // 2 // times // per // month` comment lines at :329-333) with:

```java
    /**
     * Per-paycheck amount from the monthly amount, through {@link InvestmentFrequency}. An unknown
     * frequency keeps the old lenient default and returns the monthly amount unchanged.
     */
    public BigDecimal calculateInvestmentAmountFromMonthly() {
        if (monthlyAmount == null)
            return BigDecimal.ZERO;

        return InvestmentFrequency.tryParse(frequency)
                .map(parsed -> parsed.fromMonthly(monthlyAmount))
                .orElse(monthlyAmount);
    }
```

No import needed: the enum is in the same package. `calculateNextInvestmentDate` (`:91-169`) keeps its string switch; it is date arithmetic, not a factor copy.

- [ ] **Step 21: Run to green**

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.model.FrequencyFactorParityTest'`

Expected: 8 tests, 0 failures, `BUILD SUCCESSFUL`.

- [ ] **Step 22: Prove no factor copy remains and run the whole named set**

Run: `grep -rn '"4\.33"\|"2\.17"' backend/src/main`

Expected: exactly two hits, both in `backend/src/main/java/com/investingapp/backend/model/InvestmentFrequency.java` (`WEEKLY("4.33", 52)` and `BIWEEKLY("2.17", 26)`). No hit in `InvestmentScheduleService.java`, `InvestmentSchedule.java` or `MonthlyFreedomUpdateService.java`.

Run: `grep -n 'factorHundredths = [0-9]' frontend/src/app/utils/investment-frequency.utils.ts`

Expected: no output, exit code 1 (the old inline assignments `factorHundredths = 433` / `217` / `200` / `100` are gone; the only remaining assignment reads the exported table).

Run: `grep -n '433\|217\|200\|100' frontend/src/app/utils/investment-frequency.utils.ts`

Expected: exactly four hits, the four rows of `FREQUENCY_FACTOR_HUNDREDTHS` (`WEEKLY: 433,`, `BIWEEKLY: 217,`, `SEMI_MONTHLY: 200,`, `MONTHLY: 100,`) plus the header comment line `(4.33 becomes 433)`.

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.model.FrequencyFactorParityTest' --tests 'com.investingapp.backend.service.MonthlyFreedomUpdateServiceTest' --tests 'com.investingapp.backend.service.InvestmentScheduleServiceAnalyticsTest' --tests 'com.investingapp.backend.service.InvestmentScheduleServiceMonthlyAmountTest'`

Expected: `BUILD SUCCESSFUL`, 0 failures.

Run: `cd frontend && npx ng test --include='**/investment-frequency.utils.spec.ts' --watch=false`

Expected: `Executed 9 of 9 SUCCESS`.

- [ ] **Step 23: Hunk-verify and commit**

This checkout is shared with other sessions. Before staging, confirm each modified file carries only this task's hunks:

```
git diff HEAD -- backend/src/main/java/com/investingapp/backend/service/InvestmentScheduleService.java backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java backend/src/main/java/com/investingapp/backend/service/MonthlyFreedomUpdateService.java backend/src/test/java/com/investingapp/backend/service/MonthlyFreedomUpdateServiceTest.java backend/src/test/java/com/investingapp/backend/service/InvestmentScheduleServiceAnalyticsTest.java frontend/src/app/utils/investment-frequency.utils.ts frontend/src/app/utils/investment-frequency.utils.spec.ts
```

Expected: only the import lines, the three replaced methods, the one replaced test method, the one added assertion, and the two rewritten frontend files. If a foreign hunk appears, leave it unstaged (`git add -p` on that file) and never checkout, restore, stash or reset.

Then:

```
git add backend/src/main/java/com/investingapp/backend/model/InvestmentFrequency.java backend/src/test/java/com/investingapp/backend/model/FrequencyFactorParityTest.java backend/src/test/java/com/investingapp/backend/service/InvestmentScheduleServiceMonthlyAmountTest.java backend/src/main/java/com/investingapp/backend/service/InvestmentScheduleService.java backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java backend/src/main/java/com/investingapp/backend/service/MonthlyFreedomUpdateService.java backend/src/test/java/com/investingapp/backend/service/MonthlyFreedomUpdateServiceTest.java backend/src/test/java/com/investingapp/backend/service/InvestmentScheduleServiceAnalyticsTest.java frontend/src/app/utils/investment-frequency.utils.ts frontend/src/app/utils/investment-frequency.utils.spec.ts
git commit -m "refactor(schedule): one InvestmentFrequency enum behind every frequency factor"
```

Expected: `git status --short` afterwards shows none of the ten files as modified; `git show --stat HEAD` lists exactly those ten paths.