package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.paycheck.detect.AmountPrefill.Prefill;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.investingapp.backend.model.InvestmentFrequency.BIWEEKLY;
import static com.investingapp.backend.model.InvestmentFrequency.MONTHLY;
import static com.investingapp.backend.paycheck.detect.AmountPrefill.GOAL_SOURCE_DEFAULT;
import static com.investingapp.backend.paycheck.detect.AmountPrefill.GOAL_SOURCE_SURVEY;
import static com.investingapp.backend.paycheck.detect.AmountPrefill.compute;
import static org.assertj.core.api.Assertions.assertThat;

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
