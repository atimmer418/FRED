package com.investingapp.backend.dto;

import com.investingapp.backend.model.InvestmentTrade;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What a signed-in user may see of one trade inside one of their investments.
 *
 * <p>The amounts and times mirror the broker's order (symbol, qty, notional, filled_qty,
 * filled_avg_price, submitted_at, filled_at, canceled_at, failed_at in Alpaca's Broker API order
 * object). Left out on purpose: the parent execution (which leads to the user record), the
 * broker's order id, the numeric user id and the raw error text.
 */
public record InvestmentTradeResponse(
        Long id,
        String symbol,
        BigDecimal quantity,
        BigDecimal notionalAmount,
        BigDecimal filledQuantity,
        BigDecimal filledAvgPrice,
        String status,
        LocalDateTime submittedAt,
        LocalDateTime filledAt,
        LocalDateTime canceledAt,
        LocalDateTime failedAt) {

    public static InvestmentTradeResponse of(InvestmentTrade trade) {
        return new InvestmentTradeResponse(
                trade.getId(),
                trade.getSymbol(),
                trade.getQuantity(),
                trade.getNotionalAmount(),
                trade.getFilledQuantity(),
                trade.getFilledAvgPrice(),
                trade.getStatus() == null ? null : trade.getStatus().name(),
                trade.getSubmittedAt(),
                trade.getFilledAt(),
                trade.getCanceledAt(),
                trade.getFailedAt());
    }
}
