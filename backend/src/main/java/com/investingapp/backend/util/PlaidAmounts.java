package com.investingapp.backend.util;

import java.math.BigDecimal;

/**
 * Plaid money arrives as a Double with Plaid's sign: positive is money out, negative is money in
 * (a paycheck is negative). FRED stores Plaid's value as raw_amount and the inflow as amount.
 */
public final class PlaidAmounts {

    private PlaidAmounts() {
    }

    /**
     * The inflow for a Plaid amount: -2418.37 becomes 2418.37. Null stays null.
     * BigDecimal.valueOf goes through Double.toString, so the cents survive exactly; the
     * BigDecimal(double) constructor would carry the binary expansion into a decimal(12,2) column.
     */
    public static BigDecimal inflow(Double plaidAmount) {
        return plaidAmount == null ? null : BigDecimal.valueOf(plaidAmount).negate();
    }
}
