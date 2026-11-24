package com.investingapp.backend.paycheck.detect;

import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.PaycheckCadenceSource;

import java.time.LocalDate;

/** Spec 4.4 cadence; week-based anchors or day-of-month anchors. */
public record CadenceResult(InvestmentFrequency cadence, PaycheckCadenceSource source, Integer anchorDow,
        LocalDate anchorParityDate, String anchorDay1, String anchorDay2, int intervals, boolean allInBand, int outsideBand) {
}
