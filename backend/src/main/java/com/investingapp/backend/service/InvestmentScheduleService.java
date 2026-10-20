package com.investingapp.backend.service;

import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.service.PortfolioService;
import com.investingapp.backend.util.AnalyticsBands;
import com.investingapp.backend.util.EasternTime;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Transactional
public class InvestmentScheduleService {

    private static final Logger logger = LoggerFactory.getLogger(InvestmentScheduleService.class);

    private final InvestmentScheduleRepository investmentScheduleRepository;
    private final PortfolioService portfolioService;
    private final StreakService streakService;
    private final AnalyticsOutboxService analyticsOutboxService;

    @Autowired
    public InvestmentScheduleService(InvestmentScheduleRepository investmentScheduleRepository,
            PortfolioService portfolioService, StreakService streakService,
            AnalyticsOutboxService analyticsOutboxService) {
        this.investmentScheduleRepository = investmentScheduleRepository;
        this.portfolioService = portfolioService;
        this.streakService = streakService;
        this.analyticsOutboxService = analyticsOutboxService;
    }

    /**
     * Create or update investment schedule for a user (upsert operation)
     */
    public InvestmentSchedule createInvestmentSchedule(User user, BigDecimal investmentAmount,
            String frequency, LocalDate startDate, LocalDate nextInvestmentDate) {
        logger.info("Creating/updating investment schedule for user: {} with investment amount: {}",
                user.getEmail(), investmentAmount);

        // Check if user already has an investment schedule
        Optional<InvestmentSchedule> existingScheduleOpt = getCurrentSchedule(user);

        // Which branch this call took, remembered before the branch mutates anything. Analytics
        // emits on CREATE only; see the emit block at the end of the method.
        boolean isNewSchedule = existingScheduleOpt.isEmpty();

        InvestmentSchedule schedule;
        if (existingScheduleOpt.isPresent()) {
            // Update existing schedule
            schedule = existingScheduleOpt.get();
            logger.info("Updating existing investment schedule with ID: {} for user: {}",
                    schedule.getId(), user.getEmail());

            // Update core investment fields
            schedule.setInvestmentAmount(investmentAmount);
            schedule.setFrequency(frequency);

            // Calculate monthly amount from investment amount and frequency
            schedule.setMonthlyAmount(calculateMonthlyAmountFromInvestment(investmentAmount, frequency));

            // Update start date if provided
            if (startDate != null) {
                // Only update start date if it's not already set OR if the schedule hasn't
                // started yet (brand new)
                if (schedule.getStartDate() == null || EasternTime.today().isBefore(schedule.getStartDate())) {
                    schedule.setStartDate(startDate);
                }

                LocalDate today = EasternTime.today();
                LocalDate anchorDate = schedule.getStartDate();
                LocalDate intendedDate = null;

                // If brand new (before start date), derive from start date
                if (anchorDate != null && today.isBefore(anchorDate)) {
                    intendedDate = anchorDate;
                } else {
                    // Otherwise use nextInvestmentDate (for updates to active schedules)
                    intendedDate = nextInvestmentDate;
                }

                if (intendedDate != null) {
                    schedule.setChosenDate(intendedDate);
                    schedule.setDayOfWeek(intendedDate.getDayOfWeek().name());
                    schedule.setDayOfMonth(intendedDate.getDayOfMonth());
                }

                // If nextInvestmentDate is explicitly provided, use it but adjust for holidays
                if (nextInvestmentDate != null) {
                    // Only update if the date has actually changed OR if chosenDate is missing
                    // to avoid overwriting chosenDate with an adjusted date when the user is just updating the amount
                    boolean dateChanged = schedule.getNextInvestmentDate() == null
                            || !nextInvestmentDate.equals(schedule.getNextInvestmentDate())
                            || schedule.getChosenDate() == null;

                    if (dateChanged) {
                        // Update chosenDate to the user's selected date
                        schedule.setChosenDate(nextInvestmentDate);
                        schedule.setDayOfWeek(nextInvestmentDate.getDayOfWeek().name());
                        schedule.setDayOfMonth(nextInvestmentDate.getDayOfMonth());

                        // Adjust the provided date for business days/holidays
                        schedule.setNextInvestmentDate(schedule.adjustForBusinessDay(nextInvestmentDate));
                    }
                } else {
                    // Otherwise calculate from start date
                    schedule.setNextInvestmentDate(schedule.calculateNextInvestmentDate(schedule.getStartDate()));
                }
            }

        } else {
            // Create new investment schedule
            schedule = new InvestmentSchedule();
            schedule.setUser(user);
            schedule.setInvestmentAmount(investmentAmount);
            schedule.setFrequency(frequency);

            // Calculate monthly amount from investment amount and frequency
            schedule.setMonthlyAmount(calculateMonthlyAmountFromInvestment(investmentAmount, frequency));

            // Set dates
            LocalDate effectiveStartDate = startDate != null ? startDate : EasternTime.today();
            schedule.setStartDate(effectiveStartDate);

            LocalDate today = EasternTime.today();
            LocalDate preferenceSourceDate;

            // Rule: Derive from nextInvestmentDate, unless today is before start day
            if (today.isBefore(effectiveStartDate)) {
                preferenceSourceDate = effectiveStartDate;
            } else {
                preferenceSourceDate = (nextInvestmentDate != null) ? nextInvestmentDate : effectiveStartDate;
            }

            if (preferenceSourceDate != null) {
                schedule.setDayOfWeek(preferenceSourceDate.getDayOfWeek().name());
                schedule.setDayOfMonth(preferenceSourceDate.getDayOfMonth());
            }

            // Set chosenDate preferences
            LocalDate chosen = (nextInvestmentDate != null) ? nextInvestmentDate : effectiveStartDate;
            schedule.setChosenDate(chosen);
            schedule.setDayOfWeek(chosen.getDayOfWeek().name());
            schedule.setDayOfMonth(chosen.getDayOfMonth());

            if (nextInvestmentDate != null) {
                schedule.setNextInvestmentDate(schedule.adjustForBusinessDay(nextInvestmentDate));
            } else {
                schedule.setNextInvestmentDate(schedule.calculateNextInvestmentDate(effectiveStartDate));
            }

            schedule.setIsPaused(true); // Always start paused until ACH is confirmed

            logger.info("Creating new investment schedule for user: {}", user.getEmail());
        }

        InvestmentSchedule savedSchedule = investmentScheduleRepository.save(schedule);

        // Additional debug: Query the database directly to see what's actually stored
        InvestmentSchedule reloadedSchedule = investmentScheduleRepository.findById(savedSchedule.getId()).orElse(null);
        if (reloadedSchedule != null) {
            logger.info("DEBUG: Reloaded from DB - startDate: {}, nextInvestmentDate: {}",
                    reloadedSchedule.getStartDate(), reloadedSchedule.getNextInvestmentDate());
        }

        // Ensure user has a default portfolio (create if not exists)
        portfolioService.getOrCreatePortfolio(user);

        logger.info("Successfully saved investment schedule with ID: {} for user: {} (Next investment: {})",
                savedSchedule.getId(), user.getEmail(), savedSchedule.getNextInvestmentDate());

        // Analytics, CREATE branch only.
        //
        // Transaction boundary: this class carries a type-level @Transactional and this method is
        // public, so the call arrives through the Spring proxy inside one REQUIRED transaction that
        // also commits the schedule row. Nothing was added to create that boundary; the event and
        // the schedule therefore land together or not at all.
        //
        // Why only the create branch: /api/investment-schedule/create doubles as the edit endpoint,
        // and investment_schedules has a UNIQUE key on user_id, so a second call updates the one
        // schedule the user already has. There is no schedule_updated event in the taxonomy and
        // this must not invent one, so an edit emits nothing.
        if (isNewSchedule) {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("frequency", frequencySlug(savedSchedule.getFrequency()));
            props.put("amount_band", AnalyticsBands.amount(savedSchedule.getInvestmentAmount()));
            props.put("days_since_signup_band",
                    AnalyticsBands.daysBetween(user.getCreateDate(), LocalDateTime.now()));
            analyticsOutboxService.record(ServerAnalyticsEvent.INVESTMENT_SCHEDULE_CREATED, user, props);
        }

        return savedSchedule;
    }

    /**
     * The schedule frequency as a closed, lowercase snake_case vocabulary for analytics.
     *
     * <p>The column is an unvalidated varchar(20) fed straight from the request body, which only
     * carries {@code @NotBlank}, so any string a client sends can reach it. Narrowing here follows
     * the precedent of {@link AnalyticsOutboxService#tier(User)}: a value this application does not
     * act on becomes null rather than arriving in PostHog as a new dimension value nobody can
     * interpret, and free text must never be sent at all.
     *
     * <p>The handled values are the members of {@link InvestmentFrequency}: WEEKLY, BIWEEKLY,
     * SEMI_MONTHLY (also under its legacy SEMIMONTHLY spelling) and MONTHLY.
     */
    static String frequencySlug(String frequency) {
        return InvestmentFrequency.tryParse(frequency)
                .map(parsed -> parsed.name().toLowerCase(Locale.ROOT))
                .orElse(null);
    }

    /**
     * Bank change step 2 (spec 7.8): drop the old relationship id and hold the schedule. is_paused is
     * not written; the hold is cleared by AchRelationshipService.adopt when the new relationship lands.
     */
    public void resetForBankChange(User user) {
        investmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(user).ifPresent(schedule -> {
            schedule.setAchRequestId(null);
            schedule.setHoldReason(HoldReason.BANK_CHANGE);
            investmentScheduleRepository.save(schedule);
            logger.info("Investment schedule held for bank change, user {}", user.getEmail());
        });
    }

    /**
     * Get the current active investment schedule for a user
     */
    @Transactional(readOnly = true)
    public Optional<InvestmentSchedule> getCurrentSchedule(User user) {
        return investmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(user);
    }

    /**
     * Get all investment schedules for a user
     */
    @Transactional(readOnly = true)
    public List<InvestmentSchedule> getAllSchedules(User user) {
        return investmentScheduleRepository.findByUserOrderByCreatedAtDesc(user);
    }

    /**
     * Schedules the 11:55 cron may queue tonight: user ACTIVE (both modes), then the per-mode clause.
     */
    @Transactional(readOnly = true)
    public List<InvestmentSchedule> getSchedulesReadyForInvestment(LocalDate todayET) {
        List<InvestmentSchedule> readySchedules = investmentScheduleRepository
                .findByUser_AccountStatus("ACTIVE")
                .stream()
                .filter(s -> isReady(s, todayET))
                .toList();
        logger.info("Found {} schedules ready for investment execution on {}", readySchedules.size(), todayET);
        return readySchedules;
    }

    /** Mode switch (spec 5.2). Phase 0 never produces DETECTED rows; the branch exists so the switch is exhaustive. */
    static boolean isReady(InvestmentSchedule s, LocalDate todayET) {
        ScheduleMode mode = s.getMode() == null ? ScheduleMode.MANUAL : s.getMode();
        return switch (mode) {
            case MANUAL -> s.isReadyForInvestment(todayET);
            case DETECTED -> false;
        };
    }

    /**
     * Monthly amount from the per-paycheck amount, through {@link InvestmentFrequency} so the row
     * holds the same cents the client computes. An unknown frequency keeps the old lenient default
     * and stores the per-paycheck amount unchanged.
     */
    private BigDecimal calculateMonthlyAmountFromInvestment(BigDecimal investmentAmount, String frequency) {
        if (investmentAmount == null)
            return BigDecimal.ZERO;

        return InvestmentFrequency.tryParse(frequency)
                .map(parsed -> parsed.toMonthly(investmentAmount))
                .orElse(investmentAmount);
    }

    /**
     * Pause an investment schedule
     */
    public InvestmentSchedule pauseSchedule(User user, Long scheduleId) {
        logger.info("Pausing investment schedule ID: {} for user: {}", scheduleId, user.getEmail());

        Optional<InvestmentSchedule> scheduleOpt = investmentScheduleRepository.findByIdAndUser(scheduleId, user);

        if (scheduleOpt.isEmpty()) {
            logger.error("Investment schedule ID: {} not found for user: {}", scheduleId, user.getEmail());
            throw new RuntimeException("Investment schedule not found");
        }

        InvestmentSchedule schedule = scheduleOpt.get();
        schedule.setIsPaused(true);
        schedule.setMonthlyStreak(0);

        InvestmentSchedule updatedSchedule = investmentScheduleRepository.save(schedule);
        logger.info("Successfully paused investment schedule ID: {}", scheduleId);

        // Reset the user's consecutive-month streak
        streakService.resetStreak(user);

        return updatedSchedule;
    }

    /**
     * Resume an investment schedule.
     * The user's Alpaca account must be ACTIVE before a schedule can be resumed.
     *
     * @throws IllegalStateException with message "ACCOUNT_NOT_ACTIVE" if the account
     *                               is not yet active — the controller converts this to a 403.
     */
    public InvestmentSchedule resumeSchedule(User user, Long scheduleId) {
        logger.info("Resuming investment schedule ID: {} for user: {}", scheduleId, user.getEmail());

        // Gate: account must be ACTIVE before schedules can be resumed
        if (!"ACTIVE".equals(user.getAccountStatus())) {
            logger.warn("Resume blocked for user {}: accountStatus is '{}', not ACTIVE",
                    user.getEmail(), user.getAccountStatus());
            throw new IllegalStateException("ACCOUNT_NOT_ACTIVE");
        }

        Optional<InvestmentSchedule> scheduleOpt = investmentScheduleRepository.findByIdAndUser(scheduleId, user);

        if (scheduleOpt.isEmpty()) {
            logger.error("Investment schedule ID: {} not found for user: {}", scheduleId, user.getEmail());
            throw new RuntimeException("Investment schedule not found");
        }

        InvestmentSchedule schedule = scheduleOpt.get();
        schedule.setIsPaused(false);

        InvestmentSchedule updatedSchedule = investmentScheduleRepository.save(schedule);
        logger.info("Successfully resumed investment schedule ID: {}", scheduleId);

        return updatedSchedule;
    }

    /**
     * Check if user has any active investment schedules
     */
    @Transactional(readOnly = true)
    public boolean hasActiveSchedules(User user) {
        return investmentScheduleRepository.hasActiveSchedules(user);
    }

    /**
     * Update investment schedule details
     */
    public InvestmentSchedule updateSchedule(User user, Long scheduleId, BigDecimal investmentAmount,
            String frequency) {
        logger.info("Updating investment schedule ID: {} for user: {}", scheduleId, user.getEmail());

        Optional<InvestmentSchedule> scheduleOpt = investmentScheduleRepository.findByIdAndUser(scheduleId, user);

        if (scheduleOpt.isEmpty()) {
            logger.error("Investment schedule ID: {} not found for user: {}", scheduleId, user.getEmail());
            throw new RuntimeException("Investment schedule not found");
        }

        InvestmentSchedule schedule = scheduleOpt.get();
        schedule.setInvestmentAmount(investmentAmount);
        schedule.setFrequency(frequency);

        // Recalculate monthly amount from investment amount and frequency
        schedule.setMonthlyAmount(calculateMonthlyAmountFromInvestment(investmentAmount, frequency));

        InvestmentSchedule updatedSchedule = investmentScheduleRepository.save(schedule);
        logger.info("Successfully updated investment schedule ID: {}", scheduleId);

        return updatedSchedule;
    }

}