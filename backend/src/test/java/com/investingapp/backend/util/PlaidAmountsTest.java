package com.investingapp.backend.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plaid sends money as a Double with its own sign: positive is money out, negative is money in.
 * A paycheck of 2418.37 arrives as -2418.37 and must be stored as exactly 2418.37.
 */
class PlaidAmountsTest {

    @Test
    void inflowNegatesPlaidSignExactly() {
        assertThat(PlaidAmounts.inflow(-2418.37).toPlainString()).isEqualTo("2418.37");
        assertThat(PlaidAmounts.inflow(54.10)).isEqualByComparingTo("-54.10");
        // Why valueOf: the double constructor keeps the binary expansion of the literal.
        assertThat(new BigDecimal(-2418.37).negate().toPlainString()).isNotEqualTo("2418.37");
    }

    @Test
    void nullStaysNull() {
        assertThat(PlaidAmounts.inflow(null)).isNull();
    }
}
