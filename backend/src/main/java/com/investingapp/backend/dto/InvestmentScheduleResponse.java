package com.investingapp.backend.dto;

import com.investingapp.backend.model.InvestmentSchedule;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor

public class InvestmentScheduleResponse {

    private Long id;
    private BigDecimal monthlyAmount;
    private BigDecimal investmentAmount;
    private String frequency;
    private LocalDate startDate;
    private LocalDate nextInvestmentDate;
    private Boolean isPaused;
    private LocalDate chosenDate; // The user's preferred anchor date
    private String dayOfWeek;
    private Integer dayOfMonth;
    private String scheduleDescription; // User-friendly schedule description
    private Integer monthlyStreak;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Custom constructor from entity
    public InvestmentScheduleResponse(InvestmentSchedule schedule) {
        this.id = schedule.getId();
        this.monthlyAmount = schedule.getMonthlyAmount();
        this.investmentAmount = schedule.getInvestmentAmount();
        this.frequency = schedule.getFrequency();
        this.startDate = schedule.getStartDate();
        this.nextInvestmentDate = schedule.getNextInvestmentDate();
        this.chosenDate = schedule.getChosenDate();
        this.dayOfWeek = schedule.getDayOfWeek();
        this.dayOfMonth = schedule.getDayOfMonth();
        // The schedule's achRequestId is the broker's bank-link (ACH relationship) id. The app
        // never needed it, so it stays on the server.
        this.isPaused = schedule.getIsPaused();
        this.scheduleDescription = schedule.getScheduleDescription(); // Add schedule description
        this.monthlyStreak = schedule.getMonthlyStreak() != null ? schedule.getMonthlyStreak() : 0;
        this.createdAt = schedule.getCreatedAt();
        this.updatedAt = schedule.getUpdatedAt();
    }
}