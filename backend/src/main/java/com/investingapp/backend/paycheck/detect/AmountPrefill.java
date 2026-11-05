package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.InvestmentFrequency;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Spec 4.9 pre-fill; never writes User.monthlyInvestment. */
public final class AmountPrefill {

    public record Prefill(BigDecimal monthlyGoal, String goalSource, int suggestedAmount, Integer affordabilityCap, int prefillAmount) {
    }

    public static final int DEFAULT_GOAL = 500;
    public static final String GOAL_SOURCE_SURVEY = "survey";
    public static final String GOAL_SOURCE_DEFAULT = "default";

    public static Prefill compute(Double surveyMonthlyInvestment, InvestmentFrequency cadence, BigDecimal trailingMedian, BigDecimal share) {
        boolean hasGoal = surveyMonthlyInvestment != null && Double.isFinite(surveyMonthlyInvestment) && surveyMonthlyInvestment > 0;
        BigDecimal goal = BigDecimal.valueOf(hasGoal ? surveyMonthlyInvestment : DEFAULT_GOAL).setScale(2, RoundingMode.HALF_UP);
        int suggested = goal.divide(cadence.monthlyFactor(), 0, RoundingMode.HALF_UP).intValueExact();
        Integer cap = trailingMedian == null || share == null ? null
                : share.multiply(trailingMedian).setScale(0, RoundingMode.FLOOR).intValueExact();
        int prefill = Math.max(1, cap == null ? suggested : Math.min(suggested, cap));
        return new Prefill(goal, hasGoal ? GOAL_SOURCE_SURVEY : GOAL_SOURCE_DEFAULT, suggested, cap, prefill);
    }
}
