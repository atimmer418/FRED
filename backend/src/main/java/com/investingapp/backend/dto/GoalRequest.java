package com.investingapp.backend.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.investingapp.backend.model.GoalType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class GoalRequest {

    @NotNull
    private GoalType type;

    @NotBlank
    @Size(max = 50)
    private String name;

    @NotBlank
    @Size(max = 30)
    private String icon;

    @NotNull
    private JsonNode config;

    @NotNull
    @DecimalMin("0")
    private BigDecimal currentBalance;

    private boolean completed;

    @NotNull
    @DecimalMin("0")
    private BigDecimal anchorMonthly;

    /** The last day paydays still credit this goal's tally; a plan figure written beside anchorMonthly. */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate creditsUntil;

    /** The unit's savings target as the client computed it; null when the goal has none. */
    private BigDecimal target;

    @NotNull
    @DecimalMin("0.0001")
    @DecimalMax("0.5")
    private BigDecimal planRate;

    @NotNull
    @Pattern(regexp = "market|cash")
    private String planRateKind;
}
