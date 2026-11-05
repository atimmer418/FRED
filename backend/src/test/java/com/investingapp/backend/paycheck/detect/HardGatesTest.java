package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.PaycheckRejectReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static com.investingapp.backend.model.PaycheckRejectReason.BENEFITS_SIGNAL;
import static com.investingapp.backend.model.PaycheckRejectReason.CADENCE_UNSUPPORTED;
import static com.investingapp.backend.model.PaycheckRejectReason.DESCRIPTOR_EXCLUDED;
import static com.investingapp.backend.model.PaycheckRejectReason.LOW_MEDIAN;
import static com.investingapp.backend.model.PaycheckRejectReason.NOT_INFLOW;
import static com.investingapp.backend.model.PaycheckRejectReason.PFC_EXCLUDED;
import static com.investingapp.backend.model.PaycheckRejectReason.PLAID_TOMBSTONED;
import static com.investingapp.backend.model.PaycheckRejectReason.STALE;
import static com.investingapp.backend.paycheck.detect.DetectFixtures.biweekly;
import static com.investingapp.backend.paycheck.detect.DetectFixtures.d;
import static org.assertj.core.api.Assertions.assertThat;

class HardGatesTest {

    private static final String PAYROLL = "ACME CORP PAYROLL";

    private static Optional<PaycheckRejectReason> gates(DepositGroup g, String status, String today) {
        return HardGates.evaluate(g, Optional.ofNullable(status).map(s -> new StreamFacts(s, null, true, null, null, null)),
                CadenceDeriver.derive(g.postedDatesAsc(), Optional.empty()), DepositStats.trailingMedian(g.lastSixAmounts()), d(today));
    }

    private static Optional<PaycheckRejectReason> gates(DepositGroup g) {
        return gates(g, null, "2026-03-24");
    }

    @Test
    void cleanPayrollAndSingleDepositPass() {
        assertThat(gates(biweekly(6, "2418.00", PAYROLL), "MATURE", "2026-03-24")).isEmpty();
        assertThat(gates(biweekly(1, "2418.00", PAYROLL))).isEmpty(); // gate 9: CANDIDATE, not REJECTED
    }

    @Test
    void notInflowRejected() {
        DepositGroup g = biweekly(3, "2418.00", PAYROLL);
        g.depositsAsc().get(1).setRawAmount(new BigDecimal("2418.00"));
        assertThat(gates(g)).contains(NOT_INFLOW);
    }

    @Test
    void tombstonedOrUnknownRejected() {
        assertThat(List.of("TOMBSTONED", "UNKNOWN")).allMatch(s -> gates(biweekly(6, "2418.00", PAYROLL), s, "2026-03-24").equals(Optional.of(PLAID_TOMBSTONED)));
    }

    @Test
    void benefitsSignalAtOneDeposit() {
        assertThat(gates(biweekly(1, "1800.00", "CA EDD UI BENEFIT"))).contains(BENEFITS_SIGNAL);
    }

    @Test
    void pfcTransferAndPaymentAppRejected() {
        DepositGroup g = biweekly(6, "2418.00", PAYROLL);
        g.depositsAsc().forEach(e -> e.setPfcDetailed("INCOME_TAX_REFUND"));
        assertThat(gates(g)).contains(PFC_EXCLUDED);
        g.depositsAsc().forEach(e -> { e.setPfcDetailed(null); e.setPfcPrimary("TRANSFER_IN"); e.setPfcConfidence("VERY_HIGH"); });
        assertThat(gates(g)).contains(PFC_EXCLUDED);
        g.depositsAsc().forEach(e -> e.setPfcConfidence("MEDIUM"));
        assertThat(gates(g)).isEmpty();
        g.depositsAsc().forEach(e -> e.setCounterpartyType("payment_app"));
        assertThat(gates(g)).contains(PFC_EXCLUDED);
    }

    @Test
    void nonPayrollChannelDescriptorExcluded() {
        assertThat(gates(biweekly(6, "2418.00", "ZELLE FROM JOHN DOE"))).contains(DESCRIPTOR_EXCLUDED);
    }

    @Test
    void unsupportedCadence() {
        DepositGroup g = biweekly(4, "2418.00", PAYROLL);
        for (int i = 0; i < 4; i++) { g.depositsAsc().get(i).setPostedDate(d("2026-01-02").plusDays(28L * i)); }
        assertThat(gates(g, null, "2026-03-30")).contains(CADENCE_UNSUPPORTED);
    }

    @Test
    void lowMedianPerCadence() {
        assertThat(gates(biweekly(6, "183.99", PAYROLL))).contains(LOW_MEDIAN);
        assertThat(gates(biweekly(6, "184.00", PAYROLL))).isEmpty();
        assertThat(List.of(InvestmentFrequency.values())).extracting(f -> HardGates.lowMedianFloor(f).intValue()).containsExactly(92, 184, 200, 400);
    }

    @Test
    void staleAfterTwoAndAHalfPeriods() {
        assertThat(gates(biweekly(6, "2418.00", PAYROLL), null, "2026-04-24")).isEmpty();
        assertThat(gates(biweekly(6, "2418.00", PAYROLL), null, "2026-04-25")).contains(STALE);
    }
}
