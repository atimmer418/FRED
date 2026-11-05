package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.PaycheckStream;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.investingapp.backend.model.InvestmentFrequency.BIWEEKLY;
import static com.investingapp.backend.model.InvestmentFrequency.WEEKLY;
import static com.investingapp.backend.model.PaycheckStreamState.CONFIRMED;
import static com.investingapp.backend.model.PaycheckStreamState.PROVISIONAL;
import static com.investingapp.backend.paycheck.detect.DetectFixtures.stream;
import static org.assertj.core.api.Assertions.assertThat;

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
