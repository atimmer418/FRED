package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.util.EasternTime;
import com.investingapp.backend.util.ExecutionKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * One schedule, one transaction (spec 5.3). The 11:55 coordinator lists eligible ids and calls
 * processOne for each; REQUIRES_NEW means a unique-key violation or any other failure rolls back
 * one user's night, never the whole night. A separate bean on purpose: a REQUIRES_NEW method
 * called from inside InvestmentExecutionService would bypass the transaction proxy.
 */
@Service
public class ScheduledInvestmentProcessor {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledInvestmentProcessor.class);

    /** Load-bearing: the 11:59 batch selects rows whose errorMessage contains "Queued for end-of-day". */
    static final String RECURRING_BATCH_MARKER = "Queued for end-of-day batch processing (recurring investment)";

    public enum ProcessOutcome { QUEUED, NOT_DUE, DUPLICATE_SOURCE_KEY, DUPLICATE_NIGHT_KEY, NOT_ELIGIBLE, FAILED }

    private final InvestmentScheduleRepository investmentScheduleRepository;
    private final InvestmentExecutionRepository executionRepository;

    public ScheduledInvestmentProcessor(InvestmentScheduleRepository investmentScheduleRepository,
            InvestmentExecutionRepository executionRepository) {
        this.investmentScheduleRepository = investmentScheduleRepository;
        this.executionRepository = executionRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProcessOutcome processOne(Long scheduleId, LocalDate todayET) {
        InvestmentSchedule schedule = investmentScheduleRepository.findById(scheduleId).orElse(null);
        if (schedule == null) {
            logger.warn("Schedule {} vanished between listing and processing", scheduleId);
            return ProcessOutcome.NOT_ELIGIBLE;
        }
        // Phase 0 knows MANUAL only. DETECTED due-ness is a confirmed cycle, not a date (spec 5.3
        // step 2); until Phase 2 wires that, a DETECTED row never queues from here.
        if (schedule.getMode() != ScheduleMode.MANUAL) {
            logger.info("Skipping schedule {}: mode {} is not processed in Phase 0", scheduleId, schedule.getMode());
            return ProcessOutcome.NOT_ELIGIBLE;
        }
        User user = schedule.getUser();

        if (!isDueOn(schedule, todayET)) {
            return ProcessOutcome.NOT_DUE;
        }
        // Re-checked inside this transaction: a pause, a lost ACH link or a status change since
        // the coordinator listed the schedule must win. T10 adds the hold clause inside isReadyForInvestment.
        if (user == null || !"ACTIVE".equals(user.getAccountStatus()) || !schedule.isReadyForInvestment(todayET)) {
            logger.info("Skipping schedule {}: no longer eligible", scheduleId);
            return ProcessOutcome.NOT_ELIGIBLE;
        }

        String sourceKey = ExecutionKeys.scheduled(scheduleId, todayET);
        if (executionRepository.existsBySourceKey(sourceKey)) {
            logger.warn("Schedule {} already has an execution for {} ({})", scheduleId, todayET, sourceKey);
            return ProcessOutcome.DUPLICATE_SOURCE_KEY;
        }
        String nightKey = ExecutionKeys.night(user.getId(), todayET);
        if (executionRepository.existsByNightKey(nightKey)) {
            logger.warn("User {} already has a recurring execution for night {} ({})", user.getId(), todayET, nightKey);
            return ProcessOutcome.DUPLICATE_NIGHT_KEY;
        }

        InvestmentExecution execution = createInvestmentExecution(schedule, sourceKey, nightKey);
        logger.info("Recurring investment {} queued for end-of-day processing (schedule {}, user {})",
                execution.getId(), scheduleId, user.getEmail());
        updateScheduleAfterExecution(schedule, todayET);
        return ProcessOutcome.QUEUED;
    }

    /** MANUAL keeps both branches: start date today (first run) or next date due (spec 5.3 step 2). */
    private static boolean isDueOn(InvestmentSchedule schedule, LocalDate todayET) {
        return (schedule.getStartDate() != null && schedule.getStartDate().equals(todayET))
                || (schedule.getNextInvestmentDate() != null && !schedule.getNextInvestmentDate().isAfter(todayET));
    }

    /** Moved from InvestmentExecutionService; now stamps schedule, source_key and night_key (spec 5.3 step 4). */
    private InvestmentExecution createInvestmentExecution(InvestmentSchedule schedule, String sourceKey, String nightKey) {
        InvestmentExecution execution = new InvestmentExecution();
        execution.setUser(schedule.getUser());
        execution.setSchedule(schedule);
        execution.setAmount(schedule.getInvestmentAmount());
        execution.setScheduledDate(EasternTime.now());
        execution.setStatus(InvestmentExecution.ExecutionStatus.SCHEDULED);
        execution.setInvestmentType("recurring");
        execution.setFundingSource("bank");
        execution.setSourceKey(sourceKey);
        execution.setNightKey(nightKey);
        execution.setErrorMessage(RECURRING_BATCH_MARKER);
        return executionRepository.save(execution);
    }

    /** Moved from InvestmentExecutionService; MANUAL unchanged (spec 5.3 step 5), clock replaced by todayET. */
    private void updateScheduleAfterExecution(InvestmentSchedule schedule, LocalDate todayET) {
        LocalDate currentNextDate = schedule.getNextInvestmentDate();
        LocalDate newNextDate = schedule.calculateNextInvestmentDate(todayET);
        schedule.setNextInvestmentDate(newNextDate);

        LocalDate lastIncrement = schedule.getLastStreakIncrementDate();
        boolean alreadyIncrementedThisMonth = lastIncrement != null
                && lastIncrement.getYear() == todayET.getYear()
                && lastIncrement.getMonth() == todayET.getMonth();
        if (!alreadyIncrementedThisMonth) {
            int current = schedule.getMonthlyStreak() != null ? schedule.getMonthlyStreak() : 0;
            schedule.setMonthlyStreak(current + 1);
            schedule.setLastStreakIncrementDate(todayET);
            logger.info("Monthly streak incremented to {} for schedule {}", current + 1, schedule.getId());
        }

        investmentScheduleRepository.save(schedule);
        logger.info("Updated schedule {} next investment date from {} to {}", schedule.getId(), currentNextDate, newNextDate);
    }
}
