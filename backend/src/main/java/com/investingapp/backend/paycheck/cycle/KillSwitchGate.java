package com.investingapp.backend.paycheck.cycle;

import com.investingapp.backend.model.PaycheckSkipReason;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Order(20)
public class KillSwitchGate implements CycleGate {
    @Override
    public Optional<PaycheckSkipReason> skip(CycleContext ctx) {
        return ctx.props().isPullsEnabled() ? Optional.empty() : Optional.of(PaycheckSkipReason.KILL_SWITCH);
    }
}
