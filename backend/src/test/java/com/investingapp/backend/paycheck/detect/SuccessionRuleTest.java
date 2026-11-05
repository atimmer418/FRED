package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamRole;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.function.Consumer;

import static com.investingapp.backend.model.InvestmentFrequency.BIWEEKLY;
import static com.investingapp.backend.model.PaycheckStreamState.CANDIDATE;
import static com.investingapp.backend.model.PaycheckStreamState.CONFIRMED;
import static com.investingapp.backend.model.PaycheckStreamState.PROVISIONAL;
import static com.investingapp.backend.paycheck.detect.DetectFixtures.d;
import static com.investingapp.backend.paycheck.detect.DetectFixtures.stream;
import static org.assertj.core.api.Assertions.assertThat;

class SuccessionRuleTest {

    private static final LocalDate TODAY = d("2026-03-24");
    private static final Consumer<PaycheckStream> SAME = s -> { };
    private static final Consumer<PaycheckStream> MISSED = s -> s.setMissedCount((short) 1);

    private static boolean succeeds(Consumer<PaycheckStream> s1Change, Consumer<PaycheckStream> s2Change) {
        PaycheckStream s1 = stream(1, "ACME CORP", CONFIRMED, BIWEEKLY, 90, "2418", "0.02");
        s1.setLastDate(TODAY.minusDays(4));
        s1.setPlaidIsActive(true);
        s1.setPlaidStatus("MATURE");
        PaycheckStream s2 = stream(2, "GLOBEX", PROVISIONAL, BIWEEKLY, 60, "2600", "0.03");
        s2.setDepositsFound((short) 2);
        s1Change.accept(s1);
        s2Change.accept(s2);
        return SuccessionRule.succeeds(s1, s2, TODAY);
    }

    @Test
    void successionRequiresS1MissOrInactiveOrQuiet() {
        assertThat(succeeds(SAME, SAME)).isFalse();
        assertThat(succeeds(MISSED, SAME)).isTrue();
        assertThat(succeeds(s -> s.setPlaidIsActive(false), SAME)).isTrue();
        assertThat(succeeds(s -> s.setPlaidStatus("TOMBSTONED"), SAME)).isTrue();
        assertThat(succeeds(s -> s.setLastDate(TODAY.minusDays(22)), SAME)).isTrue();
        assertThat(succeeds(s -> s.setLastDate(TODAY.minusDays(21)), SAME)).isFalse();
    }

    @Test
    void s2MustBeTrustedNewPayerNotBenefits() {
        assertThat(succeeds(MISSED, s -> s.setState(CANDIDATE))).isFalse();
        assertThat(succeeds(MISSED, s -> s.setDepositsFound((short) 1))).isFalse();
        assertThat(succeeds(MISSED, s -> s.setRole(PaycheckStreamRole.INTERRUPTION))).isFalse();
        assertThat(succeeds(MISSED, s -> s.setPayerToken("ACME CORP"))).isFalse();
    }

    @Test
    void recencyExceptionNotImplementedInPhaseOne() {
        assertThat(succeeds(s -> s.setFirstDate(TODAY.minusDays(240)), s -> s.setFirstDate(TODAY.minusDays(18)) /* S1 old and paying, S2 newer */)).isFalse();
    }
}
