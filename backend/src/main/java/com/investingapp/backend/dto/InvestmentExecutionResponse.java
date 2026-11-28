package com.investingapp.backend.dto;

import com.investingapp.backend.model.InvestmentExecution;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What a signed-in user may see of one of their investments.
 *
 * <p>A fixed list of fields, copied by hand, so nothing is ever sent just because the entity grew a
 * column. Left out on purpose: the user record, the schedule, the brokerage account id, the
 * transfer id, the internal de-duplication keys and the raw error text from the broker.
 */
public record InvestmentExecutionResponse(
        Long id,
        BigDecimal amount,
        String status,
        String investmentType,
        String targetSymbol,
        String fundingSource,
        LocalDateTime scheduledDate,
        LocalDateTime executionDate,
        LocalDateTime fundingCompletedAt,
        LocalDateTime tradingCompletedAt,
        LocalDateTime createdAt) {

    public static InvestmentExecutionResponse of(InvestmentExecution execution) {
        return new InvestmentExecutionResponse(
                execution.getId(),
                execution.getAmount(),
                execution.getStatus() == null ? null : execution.getStatus().name(),
                execution.getInvestmentType(),
                execution.getTargetSymbol(),
                execution.getFundingSource(),
                execution.getScheduledDate(),
                execution.getExecutionDate(),
                execution.getFundingCompletedAt(),
                execution.getTradingCompletedAt(),
                execution.getCreatedAt());
    }
}
