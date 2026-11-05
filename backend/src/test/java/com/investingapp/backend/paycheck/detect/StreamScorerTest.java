package com.investingapp.backend.paycheck.detect;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.investingapp.backend.model.InvestmentFrequency.BIWEEKLY;
import static com.investingapp.backend.paycheck.detect.DetectFixtures.amounts;
import static com.investingapp.backend.paycheck.detect.DetectFixtures.biweekly;
import static com.investingapp.backend.paycheck.detect.DetectFixtures.cadence;
import static com.investingapp.backend.paycheck.detect.DetectFixtures.deposit;
import static org.assertj.core.api.Assertions.assertThat;

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
