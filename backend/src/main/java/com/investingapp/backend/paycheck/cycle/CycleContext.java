package com.investingapp.backend.paycheck.cycle;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.User;

import java.math.BigDecimal;
import java.util.Optional;

public record CycleContext(User user, Optional<InvestmentSchedule> schedule, PaycheckStream stream, PaycheckEvent event,
                BigDecimal wouldPull, PaycheckAutopilotProperties props) {
}
