package com.investingapp.backend.model.goals;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AccountState(
        BigDecimal balance,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime balanceUpdatedAt,
        BigDecimal anchorBalance,
        BigDecimal anchorMonthly) {
}
