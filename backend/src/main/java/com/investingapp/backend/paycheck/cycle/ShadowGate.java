package com.investingapp.backend.paycheck.cycle;

import com.investingapp.backend.model.PaycheckSkipReason;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Order(10)
public class ShadowGate implements CycleGate {
    @Override
    public Optional<PaycheckSkipReason> skip(CycleContext ctx) {
        return ctx.props().isShadow() ? Optional.of(PaycheckSkipReason.SHADOW_CONFIRMED) : Optional.empty();
    }
}
