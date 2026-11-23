package com.investingapp.backend.model.goals;

import java.math.BigDecimal;

public record EmergencyFundConfig(BigDecimal monthlyExpenses, Integer months, Integer targetYear) {
}
