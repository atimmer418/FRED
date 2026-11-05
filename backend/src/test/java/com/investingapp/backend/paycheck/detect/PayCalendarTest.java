package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.paycheck.detect.PayCalendar.CycleWindow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.investingapp.backend.model.InvestmentFrequency.BIWEEKLY;
import static com.investingapp.backend.model.InvestmentFrequency.MONTHLY;
import static com.investingapp.backend.model.InvestmentFrequency.SEMI_MONTHLY;
import static com.investingapp.backend.model.InvestmentFrequency.WEEKLY;
import static com.investingapp.backend.model.PaycheckCadenceSource.OBSERVED;
import static com.investingapp.backend.paycheck.detect.CadenceDeriver.ANCHOR_LAST_BD;
import static com.investingapp.backend.paycheck.detect.DetectFixtures.d;
import static com.investingapp.backend.paycheck.detect.PayCalendar.periodKey;
import static com.investingapp.backend.paycheck.detect.PayCalendar.predictedNext;
import static com.investingapp.backend.paycheck.detect.PayCalendar.window;
import static org.assertj.core.api.Assertions.assertThat;

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
