package com.investingapp.backend.paycheck;

import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;

import java.util.Optional;

/**
 * What one evaluation decided. state and primaryStreamId are what was mirrored onto the schedule
 * row when one exists (R1). excluded is present when R8 skipped the user, and then nothing was
 * written and both are null. switched is true only when basic succession (spec 6.1, R5) replaced
 * the primary in this evaluation.
 */
public record EvaluationOutcome(DetectionState state, Long primaryStreamId, boolean primaryChanged,
                                boolean switched, Optional<ExclusionReason> excluded) {

    public static EvaluationOutcome skipped(ExclusionReason reason) {
        return new EvaluationOutcome(null, null, false, false, Optional.of(reason));
    }
}
