package com.investingapp.backend.paycheck.detect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreBreakdownTest {

    @Test
    void toJsonCarriesEveryRowInOrder() {
        assertThat(new ScoreBreakdown(40, 20, 15, 10, 6, 10, 100).toJson())
                .isEqualTo("{\"pfc\":40,\"plaidStatus\":20,\"median\":15,\"cadenceFit\":10,\"stability\":6,\"payrollToken\":10,\"total\":100}");
    }
}
