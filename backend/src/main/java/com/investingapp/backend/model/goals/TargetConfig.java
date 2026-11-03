package com.investingapp.backend.model.goals;

import java.math.BigDecimal;

/** Shared by WEDDING and CUSTOM. */
public record TargetConfig(BigDecimal targetAmount, Integer targetYear) {
}
