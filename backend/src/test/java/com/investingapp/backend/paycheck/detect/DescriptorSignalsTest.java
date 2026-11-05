package com.investingapp.backend.paycheck.detect;

import org.junit.jupiter.api.Test;

import static com.investingapp.backend.paycheck.detect.DescriptorSignals.hasBenefitsToken;
import static com.investingapp.backend.paycheck.detect.DescriptorSignals.hasNonPayrollChannelToken;
import static com.investingapp.backend.paycheck.detect.DescriptorSignals.hasPayrollToken;
import static org.assertj.core.api.Assertions.assertThat;

class DescriptorSignalsTest {

    @Test
    void wholeWordsAndStemsOnly() {
        assertThat(hasBenefitsToken("SSA TREAS 310 XXSOC SEC")).isTrue();
        assertThat(hasBenefitsToken("STATE UNEMPLOYMENT INS")).isTrue();
        assertThat(hasBenefitsToken("MASSACHUSETTS MUTUAL PAYROLL")).isFalse();
        assertThat(hasPayrollToken("PAYROLLX REFUND")).isFalse();
        assertThat(hasPayrollToken("ACME CORP DIR DEP")).isTrue();
        assertThat(hasNonPayrollChannelToken("CASH-APP*JOHN")).isTrue();
        assertThat(hasNonPayrollChannelToken("XFER ACME CORP PAYROLL")).isFalse();
        assertThat(hasBenefitsToken("TRANSFER FROM ACME CORP")).isFalse();
    }
}
