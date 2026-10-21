package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.service.PortfolioService;
import com.investingapp.backend.util.AnalyticsBands;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
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
                if (schedule.getStartDate() == null || LocalDate.now().isBefore(schedule.getStartDate())) {
                    schedule.setStartDate(startDate);
                }

                LocalDate today = LocalDate.now();
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
            LocalDate effectiveStartDate = startDate != null ? startDate : LocalDate.now();
            schedule.setStartDate(effectiveStartDate);

            LocalDate today = LocalDate.now();
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

            // Always start paused until ACH is confirmed.
            //
            // DELIBERATELY NO schedule_paused EVENT HERE, and please do not "fix" that. This is
            // not a pause: there is no earlier running state it moved away from. It is how a
            // schedule is born, and investment_schedule_created (emitted at the end of this
            // method) already records it. Emitting a pause here would make every new user look
            // like immediate churn in v_schedule_churn. The real pause transitions are in
            // pauseSchedule (user) and resetForBankChange (system).
            schedule.setStatus(InvestmentSchedule.Status.PENDING_BANK);

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
     * <p>The four handled values are the four every switch in this service and in
     * {@link InvestmentSchedule} acts on: WEEKLY, BIWEEKLY, SEMI_MONTHLY and MONTHLY.
     */
    static String frequencySlug(String frequency) {
        if (frequency == null) {
            return null;
        }
        switch (frequency.trim().toUpperCase()) {
            case "WEEKLY":
                return "weekly";
            case "BIWEEKLY":
                return "biweekly";
            case "SEMI_MONTHLY":
                return "semi_monthly";
            case "MONTHLY":
                return "monthly";
            default:
                return null;
        }
    }

    /**
     * Clear ACH data and re-pause the schedule when a user changes their bank account.
     * A new ACH relationship will be created by attemptAchCreation after this.
     */
    public void resetForBankChange(User user) {
        Optional<InvestmentSchedule> scheduleOpt = investmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(user);
        if (scheduleOpt.isEmpty()) {
            logger.info("No investment schedule found for user {} — nothing to reset", user.getEmail());
            return;
        }
        InvestmentSchedule schedule = scheduleOpt.get();
        // Read the current state BEFORE the flip: only a schedule that was actually running is
        // being paused by this call.
        // A bank change takes a RUNNING schedule back to waiting for its bank. A schedule the
        // user paused stays paused: their intent outlives a bank change, and this is what stops
        // the ACH path from quietly restarting it.
        boolean wasActive = schedule.getStatus() == InvestmentSchedule.Status.ACTIVE;

        schedule.setAchRequestId(null);
        if (wasActive) {
            schedule.setStatus(InvestmentSchedule.Status.PENDING_BANK);
        }
        investmentScheduleRepository.save(schedule);
        logger.info("Investment schedule re-paused for bank account change — user {}", user.getEmail());

        // No analytics event. Under the status model this is not a pause: the schedule has gone
        // back to waiting for a bank, which is a lifecycle step, and schedule_paused now means
        // exactly one thing, a user switching a running schedule off. Emitting here would put
        // Plaid outages back into the churn number.
    }

    /**
     * Update investment schedule with ACH request ID
     */
    public InvestmentSchedule updateWithAchRequestId(User user, String achRequestId) {
        logger.info("Updating investment schedule with ACH request ID: {} for user: {}",
                achRequestId, user.getEmail());

        Optional<InvestmentSchedule> scheduleOpt = investmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(user);

        if (scheduleOpt.isEmpty()) {
            logger.warn("No investment schedule found for user: {} — ACH saved to user profile, schedule will pick it up when created", user.getEmail());
            return null;
        }

        InvestmentSchedule schedule = scheduleOpt.get();
        // Read the current state BEFORE the flip. This method is also the "ACH already exists"
        // path (AccountStatusService.attemptAchCreation calls it with an existing relationship id)
        // and the ACH-retry scheduler reaches it too, so it can run against a schedule that is
        // already live.
        // The ACH path does ONE thing: it starts a schedule that was waiting for a bank. It must
        // never touch PAUSED_BY_USER, or the retry scheduler would override a deliberate pause.
        boolean wasPendingBank = schedule.getStatus() == InvestmentSchedule.Status.PENDING_BANK;

        schedule.setAchRequestId(achRequestId);
        if (wasPendingBank) {
            schedule.setStatus(InvestmentSchedule.Status.ACTIVE);
        }

        InvestmentSchedule updatedSchedule = investmentScheduleRepository.save(schedule);
        logger.info("Successfully updated investment schedule ID: {} with ACH request ID: {} and unpaused it",
                updatedSchedule.getId(), achRequestId);

        // No analytics event. This is a schedule starting for the first time, not resuming, and
        // investment_schedule_created already records that it exists. schedule_resumed means a
        // user turning their own schedule back on.

        return updatedSchedule;
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
     * Get all schedules ready for investment execution (for cron processing).
     * Only returns schedules where:
     *   - isPaused == false (the user has not paused the schedule), AND
     *   - user.accountStatus == "ACTIVE" (the Alpaca account is fully active)
     * Both conditions must be true for a trade to be placed.
     */
    @Transactional(readOnly = true)
    public List<InvestmentSchedule> getSchedulesReadyForInvestment() {
        logger.info("Finding investment schedules ready for execution...");

        List<InvestmentSchedule> readySchedules = investmentScheduleRepository
                .findByUser_AccountStatus("ACTIVE")
                .stream()
                .filter(InvestmentSchedule::isReadyForInvestment)
                .toList();

        logger.info("Found {} schedules ready for investment execution", readySchedules.size());
        return readySchedules;
    }

    /**
     * Calculate monthly amount from investment amount and frequency
     */
    private BigDecimal calculateMonthlyAmountFromInvestment(BigDecimal investmentAmount, String frequency) {
        if (investmentAmount == null)
            return BigDecimal.ZERO;

        return switch (frequency.toUpperCase()) {
            case "WEEKLY" -> investmentAmount.multiply(new BigDecimal("4.33")); // ~4.33 weeks per month
            case "BIWEEKLY" -> investmentAmount.multiply(new BigDecimal("2.17")); // ~2.17 biweekly periods per month
            case "SEMI_MONTHLY" -> investmentAmount.multiply(new BigDecimal("2")); // Exactly 2 times per month
            case "MONTHLY" -> investmentAmount;
            default -> investmentAmount;
        };
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
        // Read the current state BEFORE the flip. Both callers of this method (the REST endpoint
        // and FredChatActionService) can be asked to pause a schedule that is already paused.
        // Only a RUNNING schedule can be paused by the user. Pausing one that is waiting for its
        // bank is not churn and must not be counted as such.
        boolean wasActive = schedule.getStatus() == InvestmentSchedule.Status.ACTIVE;

        if (wasActive) {
            schedule.setStatus(InvestmentSchedule.Status.PAUSED_BY_USER);
        }
        schedule.setMonthlyStreak(0);

        InvestmentSchedule updatedSchedule = investmentScheduleRepository.save(schedule);
        logger.info("Successfully paused investment schedule ID: {}", scheduleId);

        // Analytics: the genuine user pause, on a real transition only. Pausing an already-paused
        // schedule is a no-op, and emitting for it would inflate the churn count.
        if (wasActive) {
            recordScheduleTransition(user, ServerAnalyticsEvent.SCHEDULE_PAUSED,
                    ServerAnalyticsEvent.INITIATOR_USER, ServerAnalyticsEvent.REASON_USER_REQUEST);
        }

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
        // Read the current state BEFORE the flip. Nothing above this line has changed state, so
        // the ACCOUNT_NOT_ACTIVE throw and the not-found throw both leave without emitting.
        // Only a schedule the user paused can be resumed by them. One waiting for its bank is
        // started by the ACH path, not by this.
        boolean wasPausedByUser = schedule.getStatus() == InvestmentSchedule.Status.PAUSED_BY_USER;

        if (wasPausedByUser) {
            schedule.setStatus(InvestmentSchedule.Status.ACTIVE);
        }

        InvestmentSchedule updatedSchedule = investmentScheduleRepository.save(schedule);
        logger.info("Successfully resumed investment schedule ID: {}", scheduleId);

        // Analytics: the genuine user resume, on a real transition only. Resuming a schedule that
        // is already running is a no-op.
        if (wasPausedByUser) {
            recordScheduleTransition(user, ServerAnalyticsEvent.SCHEDULE_RESUMED,
                    ServerAnalyticsEvent.INITIATOR_USER, ServerAnalyticsEvent.REASON_USER_REQUEST);
        }

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

    // --- Analytics ------------------------------------------------------------------------------

    /**
     * Records one schedule state transition, in whatever transaction the caller already has.
     *
     * <p>Transaction boundary: this class carries a type-level {@code @Transactional} and every
     * emitting method here is public, so the call arrives through the Spring proxy inside one
     * REQUIRED transaction that also commits the schedule row. Nothing was added or widened for
     * analytics. {@link AnalyticsOutboxService} refuses to write without an active transaction, by
     * design, so the event and the state change land together or not at all.
     *
     * <p>Every one of these events carries exactly two properties, in this order: who caused it and
     * why. No amount, no frequency, no bank, no id, no dates. Churn is a count of transitions, and
     * the two closed vocabularies are all that is needed to tell churn ("the user stopped it") from
     * lifecycle ("we stopped it while the bank changed").
     *
     * <p>CALLERS MUST HAVE ALREADY ESTABLISHED THAT THE STATE REALLY CHANGED, by reading the pause
     * flag before writing it. A repeated no-op must emit nothing: these events exist to count
     * churn, and a no-op counted as a transition inflates it.
     */
    private void recordScheduleTransition(User user, String event, String initiator, String reason) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("initiator", initiator);
        props.put("reason", reason);
        analyticsOutboxService.record(event, user, props);
    }

}