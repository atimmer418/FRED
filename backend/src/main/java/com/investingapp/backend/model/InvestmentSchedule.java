package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import com.investingapp.backend.util.EasternTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "investment_schedules")
public class InvestmentSchedule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // Core investment schedule fields for cron processing
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "next_investment_date", nullable = false)
    private LocalDate nextInvestmentDate;

    @Column(name = "investment_amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal investmentAmount; // Amount to invest per execution

    @Column(name = "monthly_amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal monthlyAmount; // Reference monthly amount user specified

    @Column(name = "frequency", length = 20, nullable = false)
    private String frequency; // "WEEKLY", "BIWEEKLY", "MONTHLY"

    @Column(name = "is_paused", nullable = false)
    private Boolean isPaused = true;

    @Column(name = "day_of_week")
    private String dayOfWeek; // Stores the preferred day of week (e.g., "THURSDAY")

    @Column(name = "day_of_month")
    private Integer dayOfMonth; // Stores the preferred day of month (e.g., 1-31)

    @Column(name = "chosen_date")
    private LocalDate chosenDate; // Stores the exact date user picked (preserves preference across holidays)

    // ACH and system fields
    @Column(name = "ach_request_id", length = 255)
    private String achRequestId; // This will be set when ACH linking is confirmed

    // Paycheck Autopilot, Phase 0 (V6). Phase 0 never produces DETECTED.
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", length = 10, nullable = false)
    private ScheduleMode mode = ScheduleMode.MANUAL;

    /** The system's stop; null means none. is_paused stays the user's stop. */
    @Enumerated(EnumType.STRING)
    @Column(name = "hold_reason", length = 32)
    private HoldReason holdReason;

    @Column(name = "monthly_streak", nullable = false)
    private Integer monthlyStreak = 0;

    @Column(name = "last_streak_increment_date")
    private LocalDate lastStreakIncrementDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = EasternTime.now();
        updatedAt = EasternTime.now();

        // Set initial dates if not provided
        if (startDate == null) {
            startDate = EasternTime.today();
        }
        if (nextInvestmentDate == null) {
            nextInvestmentDate = calculateNextInvestmentDate(startDate);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = EasternTime.now();
    }

    /**
     * Calculate the next investment date based on frequency
     */
    public LocalDate calculateNextInvestmentDate(LocalDate fromDate) {
        if (fromDate == null)
            fromDate = EasternTime.today();

        LocalDate nextIdealDate;

        // Use chosenDate preferences if available, otherwise fallback to stored fields
        // or startDate
        java.time.DayOfWeek preferredDayOfWeek;
        int preferredDayOfMonth;

        if (chosenDate != null) {
            preferredDayOfWeek = chosenDate.getDayOfWeek();
            preferredDayOfMonth = chosenDate.getDayOfMonth();
        } else {
            preferredDayOfWeek = (dayOfWeek != null) ? java.time.DayOfWeek.valueOf(dayOfWeek)
                    : (startDate != null ? startDate.getDayOfWeek() : fromDate.getDayOfWeek());
            preferredDayOfMonth = (dayOfMonth != null) ? dayOfMonth
                    : (startDate != null ? startDate.getDayOfMonth() : fromDate.getDayOfMonth());
        }

        switch (frequency.toUpperCase()) {
            case "WEEKLY" -> {
                // Find next occurrence of the preferred day of week strictly after fromDate
                nextIdealDate = fromDate.with(java.time.temporal.TemporalAdjusters.next(preferredDayOfWeek));
            }
            case "BIWEEKLY" -> {
                // Find next occurrence of preferred day
                LocalDate candidate = fromDate.with(java.time.temporal.TemporalAdjusters.next(preferredDayOfWeek));

                // Check parity with startDate (or chosenDate if available) to ensure we stay on
                // the 2-week cycle
                LocalDate anchor = (chosenDate != null) ? chosenDate : startDate;
                if (anchor != null) {
                    long weeksBetween = java.time.temporal.ChronoUnit.WEEKS.between(anchor, candidate);
                    if (weeksBetween % 2 != 0) {
                        // If odd number of weeks, add one more week to get back in sync
                        candidate = candidate.plusWeeks(1);
                    }
                }
                nextIdealDate = candidate;
            }
            case "MONTHLY" -> {
                // Find next date with the preferred day of month
                LocalDate candidate = fromDate.plusMonths(1);

                // Handle end-of-month logic (e.g. if preferred is 31st, but next month has 30)
                int maxDays = candidate.lengthOfMonth();
                int targetDay = Math.min(preferredDayOfMonth, maxDays);

                nextIdealDate = candidate.withDayOfMonth(targetDay);
            }
            case "SEMI_MONTHLY" -> {
                // Semi-monthly: 1st and 15th
                LocalDate d1 = fromDate.withDayOfMonth(1);
                LocalDate d2 = fromDate.withDayOfMonth(15);
                LocalDate d3 = fromDate.plusMonths(1).withDayOfMonth(1);
                LocalDate d4 = fromDate.plusMonths(1).withDayOfMonth(15);

                if (d1.isAfter(fromDate))
                    nextIdealDate = d1;
                else if (d2.isAfter(fromDate))
                    nextIdealDate = d2;
                else if (d3.isAfter(fromDate))
                    nextIdealDate = d3;
                else
                    nextIdealDate = d4;
            }
            default -> nextIdealDate = fromDate.plusWeeks(1);
        }

        // Adjust for weekends and holidays
        LocalDate nextDate = adjustForBusinessDay(nextIdealDate);

        System.out.println("calculateNextInvestmentDate: fromDate=" + fromDate + ", frequency=" + frequency
                + ", preferredDay=" + preferredDayOfWeek + "/" + preferredDayOfMonth + ", nextIdeal=" + nextIdealDate
                + ", adjusted=" + nextDate);
        return nextDate;
    }

    /**
     * Adjust date to next business day if it falls on weekend or holiday
     */
    public LocalDate adjustForBusinessDay(LocalDate date) {
        LocalDate adjustedDate = date;

        // Skip weekends (Saturday = 6, Sunday = 7)
        while (adjustedDate.getDayOfWeek().getValue() >= 6) {
            adjustedDate = adjustedDate.plusDays(1);
        }

        // Check for common US holidays and adjust
        adjustedDate = adjustForHolidays(adjustedDate);

        return adjustedDate;
    }

    /**
     * Adjust for common US holidays
     */
    private LocalDate adjustForHolidays(LocalDate date) {
        if (isUSHoliday(date)) {
            // Move to next business day
            LocalDate nextDay = date.plusDays(1);
            // Recursively check if next day is also weekend/holiday
            return adjustForBusinessDay(nextDay);
        }
        return date;
    }

    /**
     * Check if date is a US federal holiday
     */
    private boolean isUSHoliday(LocalDate date) {
        int year = date.getYear();
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();

        // New Year's Day
        if (month == 1 && day == 1)
            return true;

        // Independence Day
        if (month == 7 && day == 4)
            return true;

        // Christmas Day
        if (month == 12 && day == 25)
            return true;

        // Martin Luther King Jr. Day (3rd Monday in January)
        if (month == 1 && isNthWeekdayOfMonth(date, java.time.DayOfWeek.MONDAY, 3))
            return true;

        // Presidents Day (3rd Monday in February)
        if (month == 2 && isNthWeekdayOfMonth(date, java.time.DayOfWeek.MONDAY, 3))
            return true;

        // Memorial Day (last Monday in May)
        if (month == 5 && isLastWeekdayOfMonth(date, java.time.DayOfWeek.MONDAY))
            return true;

        // Labor Day (1st Monday in September)
        if (month == 9 && isNthWeekdayOfMonth(date, java.time.DayOfWeek.MONDAY, 1))
            return true;

        // Columbus Day (2nd Monday in October)
        if (month == 10 && isNthWeekdayOfMonth(date, java.time.DayOfWeek.MONDAY, 2))
            return true;

        // Veterans Day (November 11)
        if (month == 11 && day == 11)
            return true;

        // Thanksgiving (4th Thursday in November)
        if (month == 11 && isNthWeekdayOfMonth(date, java.time.DayOfWeek.THURSDAY, 4))
            return true;

        return false;
    }

    /**
     * Check if date is the nth occurrence of a weekday in the month
     */
    private boolean isNthWeekdayOfMonth(LocalDate date, java.time.DayOfWeek weekday, int n) {
        if (date.getDayOfWeek() != weekday)
            return false;

        LocalDate firstOfMonth = date.withDayOfMonth(1);
        LocalDate nthWeekday = firstOfMonth;

        // Find first occurrence of the weekday
        while (nthWeekday.getDayOfWeek() != weekday) {
            nthWeekday = nthWeekday.plusDays(1);
        }

        // Add (n-1) weeks to get nth occurrence
        nthWeekday = nthWeekday.plusWeeks(n - 1);

        return date.equals(nthWeekday);
    }

    /**
     * Check if date is the last occurrence of a weekday in the month
     */
    private boolean isLastWeekdayOfMonth(LocalDate date, java.time.DayOfWeek weekday) {
        if (date.getDayOfWeek() != weekday)
            return false;

        LocalDate lastOfMonth = date.withDayOfMonth(date.lengthOfMonth());

        // Find last occurrence of the weekday
        while (lastOfMonth.getDayOfWeek() != weekday) {
            lastOfMonth = lastOfMonth.minusDays(1);
        }

        return date.equals(lastOfMonth);
    }

    /**
     * Update the next investment date after processing
     */
    public void advanceToNextInvestmentDate() {
        this.nextInvestmentDate = calculateNextInvestmentDate(this.nextInvestmentDate);
    }

    /** Holds that stop a MANUAL schedule from investing (spec 5.2); BANK_CHANGE is not one of them. */
    private static final EnumSet<HoldReason> BLOCKING_HOLDS = EnumSet.of(
            HoldReason.ACH_RETURN_UNAUTHORIZED, HoldReason.ACH_RETURN_ACCOUNT, HoldReason.MANUAL_REVIEW);

    /**
     * MANUAL eligibility for the given Eastern date (spec 5.2): not paused, ACH linked, no blocking
     * hold, and either the start date is today or the next investment date is today or past due.
     */
    public boolean isReadyForInvestment(LocalDate todayET) {
        if (!Boolean.FALSE.equals(isPaused) || achRequestId == null) {
            return false;
        }
        if (holdReason != null && BLOCKING_HOLDS.contains(holdReason)) {
            return false;
        }
        return (startDate != null && startDate.equals(todayET))
                || (nextInvestmentDate != null && !nextInvestmentDate.isAfter(todayET));
    }

    /**
     * Per-paycheck amount from the monthly amount, through {@link InvestmentFrequency}. An unknown
     * frequency keeps the old lenient default and returns the monthly amount unchanged.
     */
    public BigDecimal calculateInvestmentAmountFromMonthly() {
        if (monthlyAmount == null)
            return BigDecimal.ZERO;

        return InvestmentFrequency.tryParse(frequency)
                .map(parsed -> parsed.fromMonthly(monthlyAmount))
                .orElse(monthlyAmount);
    }

    /**
     * Generate a user-friendly schedule description
     */
    public String getScheduleDescription() {
        if (startDate == null || frequency == null) {
            return "Schedule not configured";
        }

        java.time.DayOfWeek dayOfWeek = startDate.getDayOfWeek();
        String dayName = dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH);

        return switch (frequency.toUpperCase()) {
            case "WEEKLY" -> String.format("%s, recurring every week on %s",
                    startDate.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")), dayName);
            case "BIWEEKLY" -> String.format("%s, recurring every 2 weeks on %s",
                    startDate.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")), dayName);
            case "SEMI_MONTHLY" -> {
                // Semi-monthly is typically 1st and 15th
                yield String.format("%s, recurring every month on the 1st and 15th",
                        startDate.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")));
            }
            case "MONTHLY" -> String.format("%s, recurring every month on the %s",
                    startDate.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")),
                    getOrdinalNumber(startDate.getDayOfMonth()));
            default -> "Custom schedule";
        };
    }

    /**
     * Helper method to convert numbers to ordinal (1st, 2nd, 3rd, etc.)
     */
    private String getOrdinalNumber(int number) {
        if (number >= 11 && number <= 13) {
            return number + "th";
        }
        return switch (number % 10) {
            case 1 -> number + "st";
            case 2 -> number + "nd";
            case 3 -> number + "rd";
            default -> number + "th";
        };
    }

}
