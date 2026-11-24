package com.investingapp.backend.paycheck.cycle;

import com.investingapp.backend.model.*;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;

import static com.investingapp.backend.paycheck.cycle.MatchDecision.*;
import static org.assertj.core.api.Assertions.assertThat;

class CycleMatcherTest {

    private static final LocalDate EXPECTED = LocalDate.of(2026, 9, 25);
    private PaycheckStream stream;
    private PaycheckCycle open;

    @BeforeEach
    void setUp() {
        stream = new PaycheckStream();
        stream.setState(PaycheckStreamState.PROVISIONAL);
        stream.setCadence(InvestmentFrequency.BIWEEKLY);
        stream.setTrailingMedian(new BigDecimal("2000.00"));
        stream.setTrailingCv(new BigDecimal("0.0500"));
        stream.setDepositsFound((short) 3);
        open = new PaycheckCycle();
        open.setState(PaycheckCycleState.OPEN);
        open.setExpectedDate(EXPECTED);
        open.setWindowStart(LocalDate.of(2026, 9, 18));
        open.setWindowEnd(LocalDate.of(2026, 10, 6));
    }

    private static PaycheckEvent deposit(LocalDate posted, String amount) {
        PaycheckEvent e = new PaycheckEvent();
        e.setPostedDate(posted);
        e.setAmount(new BigDecimal(amount));
        e.setRawAmount(new BigDecimal(amount).negate());
        e.setStatus(PaycheckEventStatus.ACTIVE);
        e.setFirstSeenAt(posted.atTime(9, 0));
        return e;
    }

    private MatchDecision match(LocalDate posted, String amount, Optional<LocalDate> lastConfirmed) {
        PaycheckEvent e = deposit(posted, amount);
        return CycleMatcher.match(e, stream, Optional.of(open), lastConfirmed, e.getFirstSeenAt());
    }

    @Test
    void rule1StreamStateLogsOnly() {
        assertThat(match(EXPECTED, "2000.00", Optional.of(LocalDate.of(2026, 9, 11)))).isEqualTo(CONFIRMABLE);
        stream.setState(PaycheckStreamState.CANDIDATE);
        assertThat(match(EXPECTED, "2000.00", Optional.empty())).isEqualTo(LOGGED_STREAM_STATE);
        stream.setState(PaycheckStreamState.RETIRED);
        assertThat(match(EXPECTED, "2000.00", Optional.empty())).isEqualTo(LOGGED_STREAM_STATE);
        stream.setState(PaycheckStreamState.DORMANT); // DORMANT resumes on a qualifying deposit
        assertThat(match(EXPECTED, "2000.00", Optional.empty())).isEqualTo(CONFIRMABLE);
    }

    @Test
    void rule2LateSeenOverSevenEtDays() {
        PaycheckEvent e = deposit(EXPECTED, "2000.00");
        assertThat(CycleMatcher.match(e, stream, Optional.of(open), Optional.empty(), LocalDateTime.of(2026, 10, 3, 8, 0)))
                .isEqualTo(LATE_SEEN);
        assertThat(CycleMatcher.match(e, stream, Optional.of(open), Optional.empty(), LocalDateTime.of(2026, 10, 2, 23, 59)))
                .isEqualTo(CONFIRMABLE);
    }

    @Test
    void rule3AmountFloorIsAsymmetric() {
        assertThat(match(EXPECTED, "1199.99", Optional.empty())).isEqualTo(AMOUNT_MISMATCH);
        assertThat(match(EXPECTED, "1200.00", Optional.empty())).isEqualTo(CONFIRMABLE);
        assertThat(match(EXPECTED, "9000.00", Optional.empty())).isEqualTo(CONFIRMABLE);
        stream.setTrailingCv(new BigDecimal("0.4000"));
        assertThat(match(EXPECTED, "900.00", Optional.empty())).isEqualTo(CONFIRMABLE);
        assertThat(match(EXPECTED, "899.99", Optional.empty())).isEqualTo(AMOUNT_MISMATCH);
    }

    @Test
    void rule4GapTooShort() {
        Optional<LocalDate> last = Optional.of(LocalDate.of(2026, 9, 14));
        assertThat(match(LocalDate.of(2026, 9, 22), "2000.00", last)).isEqualTo(GAP_TOO_SHORT); // 8 < ceil(8.4)
        assertThat(match(LocalDate.of(2026, 9, 23), "2000.00", last)).isEqualTo(CONFIRMABLE);
    }

    @Test
    void rule5OutsideWindowIsSecondaryUnlessGapHolds() {
        assertThat(match(LocalDate.of(2026, 10, 8), "2000.00", Optional.empty())).isEqualTo(OUTSIDE_WINDOW_SECONDARY);
        assertThat(match(LocalDate.of(2026, 10, 8), "2000.00", Optional.of(LocalDate.of(2026, 9, 14)))).isEqualTo(CONFIRMABLE);
        assertThat(match(LocalDate.of(2026, 9, 16), "2000.00", Optional.of(LocalDate.of(2026, 9, 11))))
                .isEqualTo(OUTSIDE_WINDOW_SECONDARY);
    }

    @Test
    void rule6PeriodTakenWithoutOpenCycle() {
        PaycheckEvent e = deposit(EXPECTED, "2000.00");
        assertThat(CycleMatcher.match(e, stream, Optional.empty(), Optional.empty(), e.getFirstSeenAt())).isEqualTo(PERIOD_TAKEN);
        open.setState(PaycheckCycleState.SKIPPED);
        assertThat(match(EXPECTED, "2000.00", Optional.empty())).isEqualTo(PERIOD_TAKEN);
    }

    @Test
    void rule7StreamTrustNeedsTwoDepositsCountingThisOne() {
        stream.setDepositsFound((short) 1);
        assertThat(match(EXPECTED, "2000.00", Optional.empty())).isEqualTo(STREAM_UNTRUSTED);
        stream.setDepositsFound((short) 2);
        assertThat(match(EXPECTED, "2000.00", Optional.empty())).isEqualTo(CONFIRMABLE);
    }
}
