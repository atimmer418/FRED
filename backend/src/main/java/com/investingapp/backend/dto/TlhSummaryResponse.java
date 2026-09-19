package com.investingapp.backend.dto;

import java.math.BigDecimal;

/**
 * The my-profile "saved this year" feed. harvestedLossYtd sums ONLY
 * allowedLoss (realized − wash-disallowed) of LIVE harvests in the tax year;
 * estimatedSavings = harvestedLossYtd × estimateRate, labeled an estimate.
 */
public record TlhSummaryResponse(
        int year,
        BigDecimal harvestedLossYtd,
        BigDecimal disallowedYtd,
        BigDecimal estimatedSavings,
        BigDecimal estimateRate,
        int eventCount,
        String disclaimer) {
}
