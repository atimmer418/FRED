package com.investingapp.backend.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PerformancePeriodCalculatorTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Map<String, Object> findPeriod(String label, List<Map<String, Object>> rows) {
        return rows.stream()
                .filter(r -> label.equals(r.get("period")))
                .findFirst()
                .orElse(null);
    }

    // ── findStartValue ───────────────────────────────────────────────────────

    @Test
    void exactPointResolution() {
        List<String>     dates  = Arrays.asList("2026-05-01", "2026-06-01", "2026-07-01");
        List<BigDecimal> values = Arrays.asList(bd("100"), bd("110"), bd("120"));

        Optional<BigDecimal> result = PerformancePeriodCalculator.findStartValue(
                dates, values, LocalDate.of(2026, 6, 1));

        assertTrue(result.isPresent());
        assertEquals(bd("110"), result.get(), "Exact date match should return that entry's equity");
    }

    @Test
    void closestPriorPointResolution() {
        // Gap between May 30 and Jul 1 — window start Jun 1 falls in the gap
        List<String>     dates  = Arrays.asList("2026-05-01", "2026-05-30", "2026-07-01");
        List<BigDecimal> values = Arrays.asList(bd("100"), bd("108"), bd("120"));

        Optional<BigDecimal> result = PerformancePeriodCalculator.findStartValue(
                dates, values, LocalDate.of(2026, 6, 1));

        assertTrue(result.isPresent());
        assertEquals(bd("108"), result.get(), "Closest prior point (May 30) should be returned");
    }

    @Test
    void windowOlderThanAllHistoryReturnsEmpty() {
        // Window start May 31 — all history starts Jun 1 or later
        List<String>     dates  = Arrays.asList("2026-06-01", "2026-07-01");
        List<BigDecimal> values = Arrays.asList(bd("100"), bd("120"));

        Optional<BigDecimal> result = PerformancePeriodCalculator.findStartValue(
                dates, values, LocalDate.of(2026, 5, 31));

        assertTrue(result.isEmpty(), "Window older than earliest history point must return empty");
    }

    @Test
    void windowStartEqualsEarliestDate() {
        List<String>     dates  = Arrays.asList("2026-06-01", "2026-07-01");
        List<BigDecimal> values = Arrays.asList(bd("100"), bd("120"));

        Optional<BigDecimal> result = PerformancePeriodCalculator.findStartValue(
                dates, values, LocalDate.of(2026, 6, 1));

        assertTrue(result.isPresent());
        assertEquals(bd("100"), result.get());
    }

    // ── computePercent ───────────────────────────────────────────────────────

    @Test
    void zeroStartPercentGuard() {
        BigDecimal result = PerformancePeriodCalculator.computePercent(bd("5"), BigDecimal.ZERO);
        assertEquals(BigDecimal.ZERO, result, "Zero startValue should yield 0% (not divide-by-zero)");
    }

    @Test
    void nearZeroStartPercentGuard() {
        BigDecimal result = PerformancePeriodCalculator.computePercent(bd("5"), bd("0.005"));
        assertEquals(BigDecimal.ZERO, result, "Near-zero startValue (below threshold) should yield 0%");
    }

    @Test
    void normalPercentComputation() {
        // 5 / 100 * 100 = 5.0000
        BigDecimal result = PerformancePeriodCalculator.computePercent(bd("5"), bd("100"));
        assertEquals(new BigDecimal("5.0000"), result);
    }

    @Test
    void negativeReturnPercent() {
        // -10 / 200 * 100 = -5.0000
        BigDecimal result = PerformancePeriodCalculator.computePercent(bd("-10"), bd("200"));
        assertEquals(new BigDecimal("-5.0000"), result);
    }

    // ── computePeriods ───────────────────────────────────────────────────────

    @Test
    void emptyHistoryDegradation() {
        List<Map<String, Object>> rows = PerformancePeriodCalculator.computePeriods(
                Collections.emptyList(), Collections.emptyList(),
                bd("100"), LocalDate.of(2026, 7, 6));

        assertTrue(rows.isEmpty(), "Empty history must return empty row list");
    }

    @Test
    void nullHistoryDegradation() {
        List<Map<String, Object>> rows = PerformancePeriodCalculator.computePeriods(
                null, null, bd("100"), LocalDate.of(2026, 7, 6));

        assertTrue(rows.isEmpty(), "Null history must return empty row list");
    }

    @Test
    void windowsOlderThanHistoryOmitted() {
        // History starts Jun 1 — 3M (Apr 6) and YTD (Jan 1) windows predate it and must be omitted
        List<String>     dates  = Arrays.asList("2026-06-01", "2026-06-15", "2026-07-01");
        List<BigDecimal> values = Arrays.asList(bd("100"), bd("105"), bd("110"));
        LocalDate today = LocalDate.of(2026, 7, 6);

        List<Map<String, Object>> rows = PerformancePeriodCalculator.computePeriods(
                dates, values, bd("115"), today);

        assertNotNull(findPeriod("1W", rows), "1W should be present");
        assertNotNull(findPeriod("1M", rows), "1M should be present");
        assertNull(findPeriod("3M", rows),    "3M window (Apr 6) predates history — must be omitted");
        assertNull(findPeriod("6M", rows),    "6M window (Jan 6) predates history — must be omitted");
        assertNull(findPeriod("YTD", rows),   "YTD window (Jan 1) predates history — must be omitted");
        assertNull(findPeriod("1Y", rows),    "1Y window (Jul 2025) predates history — must be omitted");
    }

    @Test
    void responseOrderIsOneW_OneM_ThreeM_SixM_YTD_OneY() {
        // History goes back past July 2025 so all 6 windows are resolvable
        List<String> dates = Arrays.asList(
                "2025-07-01", "2026-01-01", "2026-04-01", "2026-06-06", "2026-06-29");
        List<BigDecimal> values = Arrays.asList(
                bd("80"), bd("90"), bd("95"), bd("100"), bd("105"));
        LocalDate today = LocalDate.of(2026, 7, 6);

        List<Map<String, Object>> rows = PerformancePeriodCalculator.computePeriods(
                dates, values, bd("110"), today);

        assertEquals(6, rows.size(), "All six period rows should be present");
        assertEquals("1W",  rows.get(0).get("period"));
        assertEquals("1M",  rows.get(1).get("period"));
        assertEquals("3M",  rows.get(2).get("period"));
        assertEquals("6M",  rows.get(3).get("period"));
        assertEquals("YTD", rows.get(4).get("period"));
        assertEquals("1Y",  rows.get(5).get("period"));
    }

    @Test
    void sixMonthPresentButOneYearOmittedForMidAgeAccount() {
        // Account opened Dec 1, 2025 (~7 months before "today"): the 6M window
        // (Jan 6) resolves via the closest-prior point, but 1Y (Jul 2025) predates
        // all history and must be omitted.
        List<String>     dates  = Arrays.asList("2025-12-01", "2026-03-01", "2026-07-01");
        List<BigDecimal> values = Arrays.asList(bd("100"), bd("108"), bd("118"));
        LocalDate today = LocalDate.of(2026, 7, 6);

        List<Map<String, Object>> rows = PerformancePeriodCalculator.computePeriods(
                dates, values, bd("120"), today);

        Map<String, Object> sixM = findPeriod("6M", rows);
        assertNotNull(sixM, "6M should be present for a ~7-month-old account");
        assertEquals(bd("100"), sixM.get("startValue"),
                "6M start (Jan 6) resolves to closest prior point (Dec 1)");
        assertNull(findPeriod("1Y", rows), "1Y predates all history — must be omitted");
    }

    @Test
    void ytdClampRuleOmitsWhenAccountOpenedAfterJanFirst() {
        // Account opened Feb 1, 2026 — earliest history is Feb 1, Jan 1 predates it
        List<String>     dates  = Arrays.asList("2026-02-01", "2026-06-01", "2026-07-01");
        List<BigDecimal> values = Arrays.asList(bd("100"), bd("110"), bd("120"));
        LocalDate today = LocalDate.of(2026, 7, 6);

        List<Map<String, Object>> rows = PerformancePeriodCalculator.computePeriods(
                dates, values, bd("125"), today);

        assertNull(findPeriod("YTD", rows),
                "YTD must be omitted when account was opened after Jan 1 of the current year");
    }

    @Test
    void periodRowFieldShape() {
        // Verify all five required fields are present and values are consistent
        List<String>     dates  = Arrays.asList("2026-06-01", "2026-06-29");
        List<BigDecimal> values = Arrays.asList(bd("100"), bd("105"));
        BigDecimal endValue = bd("110");
        LocalDate today = LocalDate.of(2026, 7, 6);

        List<Map<String, Object>> rows = PerformancePeriodCalculator.computePeriods(
                dates, values, endValue, today);

        Map<String, Object> oneW = findPeriod("1W", rows);
        assertNotNull(oneW, "1W row must exist");
        assertTrue(oneW.containsKey("period"),             "period field required");
        assertTrue(oneW.containsKey("startValue"),         "startValue field required");
        assertTrue(oneW.containsKey("endValue"),           "endValue field required");
        assertTrue(oneW.containsKey("totalReturn"),        "totalReturn field required");
        assertTrue(oneW.containsKey("totalReturnPercent"), "totalReturnPercent field required");

        // 1W: windowStart = Jun 29, closest entry = Jun 29 → startValue = 105
        assertEquals(bd("105"),  oneW.get("startValue"));
        assertEquals(endValue,   oneW.get("endValue"));
        assertEquals(bd("5"),    oneW.get("totalReturn"),
                "totalReturn = endValue − startValue");
        // 5 / 105 = 0.0476 (4-decimal intermediate, HALF_UP), × 100 = 4.7600
        assertEquals(new BigDecimal("4.7600"), oneW.get("totalReturnPercent"));
    }

    // ── utility ──────────────────────────────────────────────────────────────

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
