package com.investingapp.backend.model.goals;

import java.math.BigDecimal;

/**
 * Shared by HOUSE and CAR. A null downPaymentPct means the full price is the target. loanRate is a
 * percent (6.5 means 6.5%); downPaymentAmount is dollars. Both are null on older goals.
 */
public record HouseCarConfig(BigDecimal price, Integer downPaymentPct, Integer targetYear,
                             BigDecimal loanRate, BigDecimal downPaymentAmount) {
}
