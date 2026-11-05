package com.investingapp.backend.paycheck.cycle;

import com.investingapp.backend.model.PaycheckSkipReason;

import java.util.Optional;

public interface CycleGate {
    Optional<PaycheckSkipReason> skip(CycleContext ctx);
}
