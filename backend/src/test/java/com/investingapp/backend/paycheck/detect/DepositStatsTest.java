package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.InvestmentFrequency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.investingapp.backend.paycheck.detect.DetectFixtures.amounts;
import static org.assertj.core.api.Assertions.assertThat;

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
