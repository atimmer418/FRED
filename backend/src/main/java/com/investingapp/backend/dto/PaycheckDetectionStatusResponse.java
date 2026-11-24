package com.investingapp.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Spec 8.1. "isPaused" on the wire; a component named isPaused would give Jackson an is-getter. */
public record PaycheckDetectionStatusResponse(
        String mode, String state, String holdReason, String itemStatus,
        @JsonProperty("isPaused") boolean paused,
        boolean shadow, boolean optOut, LocalDateTime detectionStartedAt, boolean historyComplete,
        String fundingAccountLabel, String institutionName, BigDecimal monthlyGoal, String goalSource,
        PrimaryStreamView primary, BigDecimal savedAmount, String amountSource, Object authorization,
        ManualPrefillView manualPrefill) {

    public record PrimaryStreamView(
            Long streamId, String payerDisplayName, String rawDescriptor, int depositsFound,
            String cadence, String cadenceLabel, Integer paychecksPerYear, BigDecimal factor,
            BigDecimal lastDepositAmount, LocalDate lastDepositDate, LocalDate nextExpectedDate,
            Integer affordabilityCap, int suggestedAmount, int prefillAmount,
            boolean lowConfidence, boolean needsChooser) {
    }

    public record ManualPrefillView(String frequency, LocalDate nextInvestmentDate, BigDecimal amount) {
    }
}
