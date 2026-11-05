package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.PaycheckStream;

import java.time.LocalDate;
import java.util.Optional;

/** Plaid's catalog view of a stream as T10 stored it; absent until matched. */
public record StreamFacts(String plaidStatus, String plaidFrequency, Boolean plaidIsActive, String plaidPfcDetailed,
        String plaidPfcConfidence, LocalDate plaidPredictedNextDate) {

    public static Optional<StreamFacts> of(PaycheckStream stream) {
        if (stream == null || stream.getPlaidStreamId() == null) { return Optional.empty(); }
        return Optional.of(new StreamFacts(stream.getPlaidStatus(), stream.getPlaidFrequency(), stream.getPlaidIsActive(),
                stream.getPlaidPfcDetailed(), stream.getPlaidPfcConfidence(), stream.getPlaidPredictedNextDate()));
    }

    public boolean mature() {
        return "MATURE".equals(plaidStatus);
    }
}
