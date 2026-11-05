package com.investingapp.backend.paycheck.cycle;

import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.User;
import com.investingapp.backend.paycheck.detect.AmountPrefill;

import java.math.BigDecimal;
import java.util.Optional;

public final class WouldHavePulled {

    private WouldHavePulled() {
    }

    public static BigDecimal amount(Optional<InvestmentSchedule> schedule, User user, PaycheckStream stream, BigDecimal share) {
        Optional<BigDecimal> manual = schedule.map(InvestmentSchedule::getInvestmentAmount);
        if (manual.isPresent()) {
            return manual.get();
        }
        Double goal = user.getMonthlyInvestment(); // new User(email) starts at 0.0: no goal, as T11's monthlyGoal reads it
        var prefill = AmountPrefill.compute(goal == null || goal <= 0 ? null : goal, stream.getCadence(),
                stream.getTrailingMedian(), share);
        return BigDecimal.valueOf(prefill.prefillAmount());
    }
}
