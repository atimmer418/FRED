package com.investingapp.backend.model.goals;

import java.math.BigDecimal;

public record VacationConfig(Integer tripsPerYear, BigDecimal budgetPerTrip, BigDecimal savedThisYear, Integer savedYear) {
}
