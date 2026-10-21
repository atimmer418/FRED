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
}
