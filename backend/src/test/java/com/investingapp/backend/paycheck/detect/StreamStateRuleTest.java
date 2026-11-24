package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import org.junit.jupiter.api.Test;

import static com.investingapp.backend.model.InvestmentFrequency.BIWEEKLY;
import static com.investingapp.backend.model.PaycheckStreamState.CANDIDATE;
import static com.investingapp.backend.model.PaycheckStreamState.CONFIRMED;
import static com.investingapp.backend.model.PaycheckStreamState.PROVISIONAL;
import static com.investingapp.backend.paycheck.detect.DetectFixtures.cadence;
import static com.investingapp.backend.paycheck.detect.DetectFixtures.dates;
import static com.investingapp.backend.paycheck.detect.StreamStateRule.state;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreamStateRuleTest {

    private static PaycheckAutopilotProperties thresholds(int provisional, int confirmed) {
        PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
        when(props.getScoreProvisional()).thenReturn(provisional);
        when(props.getScoreConfirmed()).thenReturn(confirmed);
        return props;
    }

    private static final PaycheckAutopilotProperties PROPS = thresholds(55, 70);

    @Test
    void twoOnCadenceDepositsMakeProvisional() {
        assertThat(state(55, 2, false, false, PROPS)).isEqualTo(PROVISIONAL);
        assertThat(state(54, 2, false, false, PROPS)).isEqualTo(CANDIDATE);
        assertThat(state(90, 1, true, true, PROPS)).isEqualTo(CANDIDATE);
    }

    @Test
    void confirmedNeedsPlaidMatureOrFourDeposits() {
        assertThat(state(70, 3, true, true, PROPS)).isEqualTo(CONFIRMED);
        assertThat(state(70, 3, false, true, PROPS)).isEqualTo(PROVISIONAL);
        assertThat(state(69, 3, true, true, PROPS)).isEqualTo(PROVISIONAL);
        assertThat(state(70, 3, false, false, PROPS)).isEqualTo(PROVISIONAL);
        assertThat(state(70, 4, false, false, PROPS)).isEqualTo(CONFIRMED);
        assertThat(state(75, 3, true, true, thresholds(55, 80))).isEqualTo(PROVISIONAL);
    }

    @Test
    void onCadenceCountUsesTheBand() {
        assertThat(StreamStateRule.onCadenceCount(dates("2026-01-09", "2026-01-23", "2026-02-06", "2026-02-10", "2026-02-20"),
                cadence(BIWEEKLY, 0))).isEqualTo(3);
    }
}
