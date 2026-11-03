package com.investingapp.backend.model.goals;

import java.math.BigDecimal;

/** Shared by HOUSE and CAR. A null downPaymentPct means the full price is the target. */
public record HouseCarConfig(BigDecimal price, Integer downPaymentPct, Integer targetYear) {
}
