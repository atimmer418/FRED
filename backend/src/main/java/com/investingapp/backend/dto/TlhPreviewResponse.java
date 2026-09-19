package com.investingapp.backend.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-only harvest preview: what the next cycle would do for this user,
 * with per-symbol veto reasons. No orders, no persistence.
 */
public record TlhPreviewResponse(
        String ledgerStatus,
        List<CandidateDto> candidates,
        List<VetoDto> vetoes) {

    public record CandidateDto(
            String symbol,
            BigDecimal qty,
            BigDecimal estLoss,
            BigDecimal estLossPercent,
            String replacementSymbol,
            String policy) {
    }

    public record VetoDto(String symbol, String reason) {
    }
}
