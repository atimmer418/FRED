package com.investingapp.backend.paycheck.cycle;

import com.investingapp.backend.model.*;
import com.investingapp.backend.paycheck.detect.AmountPrefill;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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
