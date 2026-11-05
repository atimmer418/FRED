package com.investingapp.backend.dto.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Spec 12.4 / R13 shadow report. Keyed by public_id; no email, descriptor, deposit amount or trailing median. */
public record ShadowReport(LocalDate since, LocalDate generatedOn, boolean shadow, Summary summary, List<UserRow> users) {
    public record Summary(int users, int provisional, int confirmed, int undetectable, int shadowCycles,
            double meanConfirmLagDays) {}
    public record UserRow(String publicId, String detectionState, String itemStatus, String primaryPayerTokenHash,
            Integer score, Map<String, Integer> breakdown, String cadence, int depositsFound, int misses,
            boolean lowConfidence, List<CycleRow> shadowCycles) {}
    /** confirmLagDays is -1 when an Item break purged the event. */
    public record CycleRow(String periodKey, LocalDate expectedDate, LocalDate postedDate, LocalDate confirmedOnET,
            int confirmLagDays, BigDecimal wouldHavePulled, String pullShareBand, boolean passesAffordability) {}
}
