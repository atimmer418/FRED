package com.investingapp.backend.paycheck.detect;

import org.junit.jupiter.api.Test;

import static com.investingapp.backend.paycheck.detect.PayerTokenizer.UNKNOWN;
import static com.investingapp.backend.paycheck.detect.PayerTokenizer.displayName;
import static com.investingapp.backend.paycheck.detect.PayerTokenizer.token;
import static org.assertj.core.api.Assertions.assertThat;

class PayerTokenizerTest {

    @Test
    void specExamplesShareOneToken() {
        assertThat(token("ACME CORP PAYROLL 2418 PPD", null)).isEqualTo("ACME CORP");
        assertThat(token("ACME CORP DIR DEP", null)).isEqualTo("ACME CORP");
        assertThat(token("ACME CORP DES:PAYROLL ID:000123456 INDN:DOE", null)).isEqualTo("ACME CORP");
        assertThat(token("BIG 401 CO PAYROLL", null)).isEqualTo("BIG 401");
    }

    @Test
    void fallbacks() {
        assertThat(token(null, "Gusto Payroll")).isEqualTo("GUSTO");
        assertThat(token(null, null)).isEqualTo(UNKNOWN);
        assertThat(token("PAYROLL DEPOSIT 12345", null)).isEqualTo("PAYROLL DEPOSIT");
    }

    @Test
    void displayNameOrder() {
        assertThat(displayName("Acme Corporation", "income_source", "ACME", "ACME CORP")).isEqualTo("Acme Corporation");
        assertThat(displayName("Acme Corporation", "merchant", "Acme Merchant", "ACME CORP")).isEqualTo("Acme Merchant");
        assertThat(displayName(null, null, null, "ACME CORP")).isEqualTo("Acme Corp");
    }
}
