package com.investingapp.backend.paycheck.cycle;

import com.investingapp.backend.model.PaycheckSkipReason;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class CycleGateChain {

    private final List<CycleGate> gates;

    public CycleGateChain(List<CycleGate> gates) {
        List<CycleGate> sorted = new ArrayList<>(gates);
        AnnotationAwareOrderComparator.sort(sorted); // a hand-built list cannot run out of order
        this.gates = List.copyOf(sorted);
    }

    public Optional<PaycheckSkipReason> firstSkip(CycleContext ctx) {
        for (CycleGate gate : gates) {
            Optional<PaycheckSkipReason> reason = gate.skip(ctx);
            if (reason.isPresent()) {
                return reason;
            }
        }
        return Optional.empty();
    }

    public List<CycleGate> gates() {
        return gates;
    }
}
