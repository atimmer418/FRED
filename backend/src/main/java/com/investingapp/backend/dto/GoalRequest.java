package com.investingapp.backend.dto;

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

    @NotNull
    @DecimalMin("0.0001")
    @DecimalMax("0.5")
    private BigDecimal planRate;

    @NotNull
    @Pattern(regexp = "market|cash")
    private String planRateKind;
}
