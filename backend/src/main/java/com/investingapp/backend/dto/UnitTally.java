package com.investingapp.backend.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One saving unit's "Saved so far" figures, computed by GoalLedgerService on read. The frontend builds
 * every status word and band fragment from these; it does no payday math.
 *
 * @param tally            the running total the server keeps for the unit
 * @param creditedThrough  the last day already rolled forward
 * @param creditsUntil     the last day paydays still credit; null means no end
 * @param nextCreditDate   the next payday that will credit, or null when the unit is finished
 * @param nextCreditAmount what that payday will add (0 when there is none)
 * @param net              Doubled up minus Missed one since the later of the plan anchor and the last set
 * @param netSince         when net is negative, the date of the oldest missed payday not yet made up
 * @param startedCrediting whether any payday has credited the unit yet
 */
public record UnitTally(
        BigDecimal tally,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate creditedThrough,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate creditsUntil,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate nextCreditDate,
        BigDecimal nextCreditAmount,
        BigDecimal net,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate netSince,
        boolean startedCrediting) {
}
