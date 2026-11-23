package com.investingapp.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class GoalImportRequest {

    @NotNull
    @Size(max = 50)
    private List<@Valid ImportedGoal> goals;

    @Data
    public static class ImportedGoal {
        @NotBlank
        @Size(max = 36)
        private String clientId;

        @NotBlank
        @Size(max = 50)
        private String name;

        @NotBlank
        @Size(max = 30)
        private String icon;

        @NotNull
        private BigDecimal targetAmount;

        @NotNull
        private Integer targetYear;

        @NotNull
        private BigDecimal startingValue;

        private boolean completed;
    }
}
