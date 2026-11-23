package com.investingapp.backend.dto;

import com.investingapp.backend.model.GoalLedgerEntry.LedgerKind;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;

/** One correction to a goal unit's "Saved so far" tally: Missed one, Doubled up, or an exact figure. */
@Data
public class GoalLedgerRequest {

    /** {@code "goal"}, or {@code "kid:<index>:<plan529|brokerage|firstYear>"}. */
    @NotBlank
    private String unit;

    /** Lower case on the wire. A payday credit is never posted by the client. */
    @NotBlank
    @Pattern(regexp = "missed|extra|set", message = "must be missed, extra or set")
    private String kind;

    /** Required for {@code set}; optional for {@code missed} and {@code extra}, which move the share when it is absent. */
    @DecimalMin("0")
    @DecimalMax("100000000")
    @Digits(integer = 9, fraction = 2)
    private BigDecimal amount;

    public LedgerKind toLedgerKind() {
        return LedgerKind.valueOf(kind.toUpperCase(java.util.Locale.ROOT));
    }
}
