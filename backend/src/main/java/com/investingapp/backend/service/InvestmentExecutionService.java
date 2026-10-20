package com.investingapp.backend.service;

import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.model.*;
import com.investingapp.backend.repository.*;
import com.investingapp.backend.util.AnalyticsBands;
import com.investingapp.backend.util.EasternTime;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class InvestmentExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(InvestmentExecutionService.class);
    private static final ZoneId MARKET_TIMEZONE = EasternTime.ZONE;

    // Configuration for lump sum batching behavior
    // Queue all lump sum investments until end of day for single batch processing
    // This avoids Alpaca's "1 batch transfer per day" limit
    private static final boolean QUEUE_LUMP_SUMS_UNTIL_EOD = true;
    private static final String EOD_BATCH_PROCESSING_TIME = "23:59"; // HH:mm format (ET)

    @Autowired
    private InvestmentScheduleService investmentScheduleService;

    @Autowired
    private InvestmentScheduleRepository investmentScheduleRepository;

    @Autowired
    private InvestmentExecutionRepository executionRepository;

    @Autowired
    private InvestmentTradeRepository tradeRepository;

    @Autowired
    private AlpacaService alpacaService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private PortfolioRepository portfolioRepository;

    @Autowired
    private PortfolioDashboardService portfolioDashboardService;

    @Autowired
    private TaskScheduler taskScheduler;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private WashSaleGuardService washSaleGuardService;

    @Autowired
    private AnalyticsOutboxService analyticsOutboxService;

    @Autowired
    private SchedulerGate schedulerGate;

    @Autowired
    private JobLockService jobLockService;

    /**
     * Check if markets are currently open (US Eastern Time)
     * Market hours: Monday-Friday 9:30 AM - 4:00 PM ET
     */
    private boolean isMarketOpen() {
        LocalDateTime now = LocalDateTime.now(MARKET_TIMEZONE);
        DayOfWeek dayOfWeek = now.getDayOfWeek();

        // Weekend check
        if (dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY) {
            return false;
        }

        // Market hours: 9:30 AM - 4:00 PM ET
        LocalTime marketOpen = LocalTime.of(9, 30);
        LocalTime marketClose = LocalTime.of(16, 0);
        LocalTime currentTime = now.toLocalTime();

        return currentTime.isAfter(marketOpen) && currentTime.isBefore(marketClose);
    }

    /**
     * Process a single investment execution immediately (for lump sum investments)
     * This is called directly from the API controller to initiate ACH transfer
     * immediately
     */
    public void processInvestmentExecutionImmediately(InvestmentExecution execution) {
        logger.info("Processing lump sum investment execution {} immediately for user {}",
                execution.getId(), execution.getUser().getEmail());

        // TLH wash-sale guard, REQUEST time: an explicit stock buy inside an
        // active window is rejected here — before any money is pulled — with a
        // dated, user-comprehensible message. Once money is already funded the
        // buy is allowed instead (see initiateTradingForExecution) because the
        // IRS disallows the loss, not the trade.
        if ("stock".equals(execution.getInvestmentType()) && execution.getTargetSymbol() != null) {
            WashSaleWindow window = washSaleGuardService.blockingWindow(
                    execution.getUser().getId(), execution.getTargetSymbol(),
                    LocalDate.now(MARKET_TIMEZONE));
            if (window != null) {
                throw new IllegalArgumentException("Buying " + execution.getTargetSymbol()
                        + " right now would undo the tax savings from your recent tax-loss harvest. "
                        + "You can buy it again after " + window.getEndDate().plusDays(1) + ".");
            }
        }

        try {
            // Check if this is a buying power execution
            if ("buying_power".equals(execution.getFundingSource())) {
                processBuyingPowerExecution(execution);
                return;
            }

            // Queue all bank-funded lump sum investments for end-of-day batch processing
            // This ensures we only create 1 batch ACH transfer per day (Alpaca's limit)
            if (QUEUE_LUMP_SUMS_UNTIL_EOD) {
                execution.setStatus(InvestmentExecution.ExecutionStatus.SCHEDULED);
                execution.setErrorMessage("Queued for end-of-day batch processing");
                executionRepository.save(execution);

                logger.info("Lump sum investment {} queued for end-of-day batch processing at {}",
                        execution.getId(), EOD_BATCH_PROCESSING_TIME);
                return;
            }

            // Fallback: immediate processing (if end-of-day queueing is disabled)
            processInvestmentExecution(execution);

        } catch (Exception e) {
            logger.error("Failed to process lump sum investment execution {}: {}", execution.getId(), e.getMessage(),
                    e);
            throw e; // Re-throw so controller can handle the error appropriately
        }
    }

    /**
     * Process batched investments with a single ACH transfer for the total amount
     */
    private void processBatchedInvestments(User user, List<InvestmentExecution> batchExecutions) {
        logger.info("Processing {} batched investments for user {}", batchExecutions.size(), user.getEmail());

        // Calculate total amount for all batched investments
        BigDecimal totalAmount = batchExecutions.stream()
                .map(InvestmentExecution::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        logger.info("Total amount for batched investments: {}", totalAmount);

        try {
            // Decrypt sensitive IDs before passing to Alpaca API
            String alpacaAccountId = encryptionService.decrypt(user.getAlpacaAccountId());
            String plaidRelationshipId = encryptionService.decrypt(user.getPlaidRelationshipId());

            // Initiate single ACH transfer for TOTAL amount
            AlpacaService.AlpacaTransferResponse transferResponse = alpacaService.initiateAchTransfer(
                    alpacaAccountId,
                    plaidRelationshipId,
                    totalAmount);

            if (transferResponse.isSuccess()) {
                LocalDateTime now = LocalDateTime.now(MARKET_TIMEZONE);

                // Update ALL executions in the batch with the SAME transfer ID
                // This links them all to the single ACH event
                for (InvestmentExecution execution : batchExecutions) {
                    execution.setAlpacaTransferId(transferResponse.id);
                    execution.setStatus(InvestmentExecution.ExecutionStatus.FUNDING_INITIATED);
                    execution.setExecutionDate(now);
                    execution.setAlpacaAccountId(encryptionService.encrypt(alpacaAccountId)); // snapshot: encrypted at rest
                    // Clear the "queued" error message
                    execution.setErrorMessage(null);
                    executionRepository.save(execution);
                }

                logger.info("Successfully initiated batch ACH transfer {} for total amount {} covering {} investments",
                        transferResponse.id, totalAmount, batchExecutions.size());
            } else {
                // Mark all batched executions as failed
                for (InvestmentExecution batchExec : batchExecutions) {
                    batchExec.setStatus(InvestmentExecution.ExecutionStatus.FUNDING_FAILED);
                    batchExec.setErrorMessage("Batch ACH transfer failed: " + transferResponse.errorMessage);
                    executionRepository.save(batchExec);
                }

                logger.error("Batch ACH transfer failed for user {}: {}", user.getEmail(),
                        transferResponse.errorMessage);
                throw new RuntimeException("Batch ACH transfer failed: " + transferResponse.errorMessage);
            }

        } catch (Exception e) {
            logger.error("Error processing batched investments for user {}: {}", user.getEmail(), e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Process all queued lump sum investments at end of day (11:59 PM ET)
     * Runs daily to consolidate all pending investments into a single batch ACH
     * transfer
     */
    @Scheduled(cron = "0 59 23 * * *", zone = "America/New_York")
    public void processEndOfDayBatchTransfers() {
        if (!schedulerGate.isEnabled()) return;
        if (!jobLockService.tryAcquire(JobLocks.EOD_BATCH, Duration.ofMinutes(30))) {
            logger.warn("Skipping end-of-day batch transfer processing: job lock {} not taken (held by another run or job_locks unreachable)",
                    JobLocks.EOD_BATCH);
            return;
        }
        logger.info("Starting end-of-day batch transfer processing");

        try {
            // Find all users with scheduled executions from today
            LocalDate today = LocalDate.now(MARKET_TIMEZONE);
            List<InvestmentExecution> scheduledExecutions = executionRepository
                    .findByStatus(InvestmentExecution.ExecutionStatus.SCHEDULED)
                    .stream()
                    .filter(exec -> exec.getCreatedAt().toLocalDate().equals(today))
                    .filter(exec -> exec.getErrorMessage() != null &&
                            exec.getErrorMessage().contains("Queued for end-of-day"))
                    .toList();

            // Group by user
            Map<User, List<InvestmentExecution>> executionsByUser = scheduledExecutions.stream()
                    .collect(Collectors.groupingBy(InvestmentExecution::getUser));

            logger.info("Found {} users with queued investments for end-of-day processing",
                    executionsByUser.size());

            // Process each user's batch
            for (Map.Entry<User, List<InvestmentExecution>> entry : executionsByUser.entrySet()) {
                User user = entry.getKey();
                List<InvestmentExecution> userExecutions = entry.getValue();

                try {
                    logger.info("Processing {} queued investments for user {}",
                            userExecutions.size(), user.getEmail());
                    processBatchedInvestments(user, userExecutions);
                } catch (Exception e) {
                    logger.error("Failed to process end-of-day batch for user {}: {}",
                            user.getEmail(), e.getMessage(), e);
                }
            }

            logger.info("Completed end-of-day batch transfer processing");

        } catch (Exception e) {
            logger.error("Error in end-of-day batch transfer processing: {}", e.getMessage(), e);
        } finally {
            jobLockService.release(JobLocks.EOD_BATCH);
        }
    }

    /**
     * Process all scheduled investments for the given Eastern calendar day.
     *
     * <p>{@code todayET} is computed once by InvestmentScheduler and passed down so a run that
     * straddles midnight keeps one calendar day (spec 5.8).
     */
    public void processScheduledInvestments(LocalDate todayET) {
        logger.info("Starting scheduled investment processing for {}", todayET);

        // Get all investment schedules that are ready for execution
        // This includes schedules where either:
        // 1. nextInvestmentDate is today or past due (recurring investments)
        // 2. startDate is today (first-time investments starting today)
        List<InvestmentSchedule> readySchedules = investmentScheduleService.getSchedulesReadyForInvestment(todayET);

        logger.info("Found {} investment schedules ready for execution", readySchedules.size());

        for (InvestmentSchedule schedule : readySchedules) {
            try {
                User user = schedule.getUser();

                // Check if this schedule should be processed today
                boolean shouldProcessToday = false;
                String reason = "";

                if (schedule.getStartDate() != null && schedule.getStartDate().equals(todayET)) {
                    shouldProcessToday = true;
                    reason = "start date is today";
                } else if (schedule.getNextInvestmentDate() != null &&
                        !schedule.getNextInvestmentDate().isAfter(todayET)) {
                    shouldProcessToday = true;
                    reason = "next investment date is due";
                }

                if (!shouldProcessToday) {
                    logger.debug("Skipping schedule {} for user {} - not due today",
                            schedule.getId(), user.getEmail());
                    continue;
                }

                logger.info("Queueing recurring investment schedule {} for user {} - {}",
                        schedule.getId(), user.getEmail(), reason);

                // Create new investment execution
                InvestmentExecution execution = createInvestmentExecution(schedule);

                // Queue for end-of-day batch processing
                // This ensures recurring + lump sum investments are batched together
                execution.setStatus(InvestmentExecution.ExecutionStatus.SCHEDULED);
                execution.setErrorMessage("Queued for end-of-day batch processing (recurring investment)");
                executionRepository.save(execution);

                logger.info("Recurring investment {} queued for end-of-day processing", execution.getId());

                // Update the schedule's next investment date for next cycle
                updateScheduleAfterExecution(schedule, todayET);

            } catch (Exception e) {
                logger.error("Error processing investment schedule {} for user {}",
                        schedule.getId(), schedule.getUser().getEmail(), e);

                // Could add notification logic here for schedule processing failures
            }
        }

        logger.info("Completed scheduled investment processing");
    }

    /**
     * Create a new InvestmentExecution from an InvestmentSchedule
     */
    private InvestmentExecution createInvestmentExecution(InvestmentSchedule schedule) {
        InvestmentExecution execution = new InvestmentExecution();
        execution.setUser(schedule.getUser());
        execution.setAmount(schedule.getInvestmentAmount());
        execution.setScheduledDate(EasternTime.now());
        execution.setStatus(InvestmentExecution.ExecutionStatus.SCHEDULED);
        execution.setInvestmentType("recurring");
        execution.setFundingSource("bank");
        // Note: InvestmentExecution doesn't have a direct link back to schedule

        return executionRepository.save(execution);
    }

    /**
     * Update schedule's next investment date after successful execution, relative to todayET
     */
    private void updateScheduleAfterExecution(InvestmentSchedule schedule, LocalDate todayET) {
        LocalDate currentNextDate = schedule.getNextInvestmentDate();
        LocalDate newNextDate = schedule.calculateNextInvestmentDate(todayET);

        schedule.setNextInvestmentDate(newNextDate);
        // Note: We don't update startDate - it remains as the original start date

        // Increment monthly streak if we have not already done so this calendar month
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

        // Save the updated schedule
        investmentScheduleRepository.save(schedule);

        logger.info("Updated schedule {} next investment date from {} to {}",
                schedule.getId(), currentNextDate, newNextDate);
    }

    /**
     * Process an investment execution using existing buying power
     */
    private void processBuyingPowerExecution(InvestmentExecution execution) {
        logger.info("Processing buying power investment execution {} for user {}",
                execution.getId(), execution.getUser().getEmail());

        User user = execution.getUser();

        // Validate user has required Alpaca information
        if (user.getAlpacaAccountId() == null) {
            throw new RuntimeException("User missing required Alpaca account ID");
        }

        // Decrypt the Alpaca account ID before using it with the API
        String alpacaAccountId = encryptionService.decrypt(user.getAlpacaAccountId());

        // Check buying power
        BigDecimal buyingPower = alpacaService.getBuyingPower(alpacaAccountId);

        if (buyingPower.compareTo(execution.getAmount()) >= 0) {
            // Sufficient funds
            logger.info("User has sufficient buying power (${}) for execution {} (${})",
                    buyingPower, execution.getId(), execution.getAmount());

            // No analytics event here on purpose: this path funds from cash already sitting at the
            // broker, so no ACH transfer settled and no money left a bank. Counting it would
            // inflate the ACH funnel and corrupt the per-user transfer ordinal.
            execution.setStatus(InvestmentExecution.ExecutionStatus.FUNDING_COMPLETED);
            execution.setFundingCompletedAt(LocalDateTime.now(MARKET_TIMEZONE));
            execution.setAlpacaAccountId(encryptionService.encrypt(alpacaAccountId)); // snapshot: encrypted at rest
            executionRepository.save(execution);

            // Initiate trading immediately
            initiateTradingForExecution(execution);
        } else {
            // Insufficient funds
            logger.warn("User has insufficient buying power (${}) for execution {} (${})",
                    buyingPower, execution.getId(), execution.getAmount());

            execution.setStatus(InvestmentExecution.ExecutionStatus.FUNDING_FAILED);
            execution.setErrorMessage(
                    "Insufficient buying power. Available: $" + buyingPower + ", Required: $" + execution.getAmount());
            executionRepository.save(execution);

            throw new RuntimeException("Insufficient buying power. Available: $" + buyingPower);
        }
    }

    /**
     * Process a single investment execution
     */
    private void processInvestmentExecution(InvestmentExecution execution) {
        logger.info("Processing investment execution {} for user {}",
                execution.getId(), execution.getUser().getId());

        User user = execution.getUser();

        // Validate user has required Alpaca information
        if (user.getAlpacaAccountId() == null || user.getPlaidRelationshipId() == null) {
            throw new RuntimeException("User missing required Alpaca account ID or Plaid relationship ID");
        }

        // Decrypt sensitive IDs before passing to Alpaca API
        String alpacaAccountId = encryptionService.decrypt(user.getAlpacaAccountId());
        String plaidRelationshipId = encryptionService.decrypt(user.getPlaidRelationshipId());

        // Update execution status and date
        execution.setStatus(InvestmentExecution.ExecutionStatus.FUNDING_INITIATED);
        execution.setExecutionDate(LocalDateTime.now(MARKET_TIMEZONE));
        execution.setAlpacaAccountId(encryptionService.encrypt(alpacaAccountId)); // snapshot: encrypted at rest
        executionRepository.save(execution);

        // Initiate ACH funding
        AlpacaService.AlpacaTransferResponse transferResponse = alpacaService.initiateAchTransfer(
                alpacaAccountId,
                plaidRelationshipId,
                execution.getAmount());

        if (transferResponse.isSuccess()) {
            execution.setAlpacaTransferId(transferResponse.id);
            executionRepository.save(execution);

            logger.info("Successfully initiated ACH transfer {} for execution {}",
                    transferResponse.id, execution.getId());
        } else {
            execution.setStatus(InvestmentExecution.ExecutionStatus.FUNDING_FAILED);
            execution.setErrorMessage("ACH transfer failed: " + transferResponse.errorMessage);
            executionRepository.save(execution);

            notifyUserOfFailure(execution, "Funding Failed", transferResponse.errorMessage);
            throw new RuntimeException("ACH transfer failed: " + transferResponse.errorMessage);
        }

        // Note: Next investment date will be updated when the schedule is processed
        // after successful execution via updateScheduleAfterExecution()
    }

    /**
     * Check funding status for pending executions
     */
    public void checkFundingStatus() {
        logger.info("Checking funding status for pending executions");

        // Check executions that have been waiting for funding for at least 10 minutes
        LocalDateTime cutoffTime = LocalDateTime.now(MARKET_TIMEZONE).minusMinutes(10);
        List<InvestmentExecution> pendingExecutions = executionRepository
                .findExecutionsAwaitingFundingCheck(
                        InvestmentExecution.ExecutionStatus.FUNDING_INITIATED,
                        cutoffTime);

        logger.info("Found {} executions awaiting funding check", pendingExecutions.size());

        for (InvestmentExecution execution : pendingExecutions) {
            try {
                checkExecutionFundingStatus(execution);
            } catch (Exception e) {
                logger.error("Error checking funding status for execution {}", execution.getId(), e);
            }
        }
    }

    /**
     * Check funding status for a specific execution.
     * Uses GET /accounts/{id}/transfers to look up the transfer by ID directly
     * rather than heuristic CSD activity matching.
     */
    private void checkExecutionFundingStatus(InvestmentExecution execution) {
        logger.info("Checking funding status for execution {}", execution.getId());

        // Find ALL executions sharing this transfer ID (batch siblings)
        List<InvestmentExecution> batchedExecutions = List.of(execution);
        if (execution.getAlpacaTransferId() != null) {
            List<InvestmentExecution> peerExecutions = executionRepository.findByAlpacaTransferId(execution.getAlpacaTransferId());
            if (peerExecutions.size() > 1) {
                batchedExecutions = peerExecutions;
                logger.info("Execution {} is part of a batch of {} items sharing transfer {}",
                    execution.getId(), peerExecutions.size(), execution.getAlpacaTransferId());
            }
        }

        // Query Alpaca's transfers endpoint — matches by transfer ID, no amount heuristics needed
        AlpacaService.AlpacaTransferResponse transferStatus = alpacaService.checkTransferStatus(
                encryptionService.decrypt(execution.getAlpacaAccountId()),
                execution.getAlpacaTransferId());

        if (transferStatus.isComplete()) {
            logger.info("Funding completed for transfer {}. Updating {} associated executions.",
                execution.getAlpacaTransferId(), batchedExecutions.size());

            // Analytics, read BEFORE anything is marked: one ACH transfer can fund several
            // executions, and the event describes the TRANSFER, so only the first sibling to
            // settle reports it. A sibling that already carries fundingCompletedAt means this
            // transfer has been reported once already and this pass is a retry, or a continuation
            // after a trading call abandoned an earlier pass part-way through the batch.
            boolean transferAlreadyReported = batchedExecutions.stream()
                    .anyMatch(sibling -> sibling.getFundingCompletedAt() != null);
            BigDecimal settledAmount = settledTransferAmount(transferStatus, batchedExecutions);
            boolean reportedThisPass = false;

            // Mark ALL executions in this batch as funded
            for (InvestmentExecution batchedExec : batchedExecutions) {
                // Double check status to avoid re-triggering logic if already processed by a parallel check
                if (InvestmentExecution.ExecutionStatus.FUNDING_INITIATED.equals(batchedExec.getStatus())) {
                    batchedExec.setStatus(InvestmentExecution.ExecutionStatus.FUNDING_COMPLETED);
                    batchedExec.setFundingCompletedAt(LocalDateTime.now(MARKET_TIMEZONE));
                    executionRepository.save(batchedExec);

                    // Emitted here, after a real state change and before initiateTradingForExecution
                    // can throw, so a mid-batch trading failure cannot cost us the event.
                    if (!transferAlreadyReported && !reportedThisPass) {
                        recordAchTransferSettled(execution.getUser(), settledAmount);
                        reportedThisPass = true;
                    }

                    logger.info("Funding confirmed for execution {}, initiating trading logic", batchedExec.getId());
                    initiateTradingForExecution(batchedExec);
                }
            }

            // Check for any queued executions for the same user that can now be processed
            processQueuedExecutionsForUser(execution.getUser());

        } else if (transferStatus.isFailed()) {
            // REJECTED, CANCELED, or RETURNED — fail ALL batch siblings
            String failReason = transferStatus.reason != null
                    ? transferStatus.status + ": " + transferStatus.reason
                    : transferStatus.status;

            String userMessage;
            if ("RETURNED".equalsIgnoreCase(transferStatus.status)) {
                userMessage = "Your bank returned the ACH transfer. This typically means insufficient funds or an account issue. Please verify your bank details and try again.";
            } else if ("CANCELED".equalsIgnoreCase(transferStatus.status)) {
                userMessage = "The ACH transfer was canceled. Please try again or contact support.";
            } else {
                userMessage = "Your investment could not be processed due to insufficient funds or banking issues.";
            }

            for (InvestmentExecution batchedExec : batchedExecutions) {
                if (InvestmentExecution.ExecutionStatus.FUNDING_INITIATED.equals(batchedExec.getStatus())) {
                    batchedExec.setStatus(InvestmentExecution.ExecutionStatus.FUNDING_FAILED);
                    batchedExec.setErrorMessage("ACH transfer failed: " + failReason);
                    executionRepository.save(batchedExec);

                    notifyUserOfFailure(batchedExec, "Funding Failed", userMessage);
                }
            }
        } else if (transferStatus.isPending()) {
            // Still in progress — log current Alpaca status for visibility
            logger.info("Transfer {} still pending with Alpaca status: {}",
                    execution.getAlpacaTransferId(), transferStatus.status);

            // Staleness timeout: if funding has been pending for more than 5 business days, flag it
            if (execution.getExecutionDate() != null &&
                    execution.getExecutionDate().isBefore(LocalDateTime.now(MARKET_TIMEZONE).minusDays(7))) {
                logger.warn("Transfer {} for execution {} has been pending for over 7 days (initiated {}). " +
                        "Alpaca status: {}. May require manual review.",
                        execution.getAlpacaTransferId(), execution.getId(),
                        execution.getExecutionDate(), transferStatus.status);
            }
        } else {
            // UNKNOWN or ERROR from our API call — log but don't change execution status
            logger.warn("Unexpected transfer status '{}' for transfer {} (error: {}). Will retry next cycle.",
                    transferStatus.status, execution.getAlpacaTransferId(), transferStatus.errorMessage);
        }
    }

    /**
     * The dollar figure this ACH transfer actually settled for.
     *
     * Alpaca's own number is preferred: it is the authority on what moved, and a batch transfer is
     * raised for the total of the executions it funds. The sum of those executions is the local
     * fallback for a response that omits the amount, which is the same number computed from our
     * side of the ledger.
     */
    private BigDecimal settledTransferAmount(AlpacaService.AlpacaTransferResponse transferStatus,
                                             List<InvestmentExecution> batchedExecutions) {
        if (transferStatus != null && transferStatus.amount != null && transferStatus.amount.signum() > 0) {
            return transferStatus.amount;
        }
        return batchedExecutions.stream()
                .map(InvestmentExecution::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Records that one ACH transfer settled, for analytics.
     *
     * Called from inside checkExecutionFundingStatus, which runs in the transaction opened by the
     * public checkFundingStatus() on this @Transactional class, so the event row commits with the
     * FUNDING_COMPLETED status change or not at all.
     *
     * "First" is read from the outbox itself rather than from a new flag: the outbox is never
     * deleted from and is the ledger the funnel is built on, so it is the one place that can answer
     * the question durably. The alternative local signal, "has this user any execution with
     * fundingCompletedAt set", cannot work here: the execution we just marked is already one, and
     * it would count buying-power fundings, which move no money through a bank.
     *
     * Nothing here may break the money path, so every lookup is guarded. record() already swallows
     * its own failures; the reads around it do not.
     */
    private void recordAchTransferSettled(User user, BigDecimal settledAmount) {
        try {
            Map<String, Object> properties = new LinkedHashMap<>();

            if (!analyticsOutboxService.hasRecorded(user, ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED)) {
                properties.put("amount_band", AnalyticsBands.amount(settledAmount));
                // LocalDateTime.now() and not MARKET_TIMEZONE on purpose: this is compared against
                // InvestmentSchedule.createdAt, which is written with the system default zone, and
                // two different clock bases can make a same-day settlement look negative.
                properties.put("days_since_schedule_band",
                        AnalyticsBands.daysBetween(firstScheduleCreatedAt(user), LocalDateTime.now()));
                analyticsOutboxService.record(
                        ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED, user, properties);
                return;
            }

            // The ordinal of this transfer, counted from the ledger: one first_ach_transfer_completed
            // plus every ach_transfer_completed after it, plus this one.
            long transferNumber =
                    analyticsOutboxService.countRecorded(user, ServerAnalyticsEvent.FIRST_ACH_TRANSFER_COMPLETED)
                            + analyticsOutboxService.countRecorded(user, ServerAnalyticsEvent.ACH_TRANSFER_COMPLETED)
                            + 1;
            properties.put("transfer_number_band", AnalyticsBands.count(transferNumber));
            analyticsOutboxService.record(ServerAnalyticsEvent.ACH_TRANSFER_COMPLETED, user, properties);

        } catch (RuntimeException e) {
            logger.error("analytics: failed to record the settled ACH transfer for user {}",
                    user != null ? user.getId() : null, e);
        }
    }

    /**
     * When this user first committed to a recurring plan, which is what days_since_schedule
     * measures from: the question is how long it took from setting up a plan to the first money
     * actually landing, so the OLDEST schedule is the anchor and a later edit must not shorten it.
     * The schedule's user column is unique today, so there is only ever one row; taking the oldest
     * keeps the number honest if that ever stops being true.
     *
     * Null for a user with no schedule at all, such as a one-off lump sum. AnalyticsBands reports
     * that as "unknown", which is the truth, rather than as zero days.
     */
    private LocalDateTime firstScheduleCreatedAt(User user) {
        List<InvestmentSchedule> schedules = investmentScheduleRepository.findByUserOrderByCreatedAtDesc(user);
        if (schedules == null || schedules.isEmpty()) {
            return null;
        }
        return schedules.get(schedules.size() - 1).getCreatedAt();
    }

    /**
     * Process any queued executions for a user (called when ACH funding completes)
     */
    private void processQueuedExecutionsForUser(User user) {
        logger.info("Checking for queued executions for user {}", user.getEmail());

        // Find scheduled executions that were queued due to ACH limits or batching
        List<InvestmentExecution> queuedExecutions = executionRepository.findByUserIdAndStatus(
                user.getId(), InvestmentExecution.ExecutionStatus.SCHEDULED);

        // Filter for executions that were queued today (have error message indicating
        // batching)
        List<InvestmentExecution> todaysQueuedExecutions = queuedExecutions.stream()
                .filter(exec -> exec.getErrorMessage() != null &&
                        (exec.getErrorMessage().contains("Queued for batch processing") ||
                                exec.getErrorMessage().contains("Waiting for batch ACH transfer")))
                .toList();

        if (todaysQueuedExecutions.isEmpty()) {
            logger.info("No queued executions found for user {}", user.getEmail());
            return;
        }

        logger.info("Found {} queued executions for user {}, processing them now",
                todaysQueuedExecutions.size(), user.getEmail());

        for (InvestmentExecution queuedExecution : todaysQueuedExecutions) {
            try {
                // For batch executions, we need to restore the original amount
                // (it might have been modified during batching)
                restoreOriginalInvestmentAmount(queuedExecution);

                // Clear the queue message and process directly to trading.
                // No analytics event here on purpose: these executions ride the transfer that just
                // settled in checkExecutionFundingStatus, which already reported it. No second
                // transfer completed, so a second event would double count the same money.
                queuedExecution.setErrorMessage(null);
                queuedExecution.setStatus(InvestmentExecution.ExecutionStatus.FUNDING_COMPLETED);
                queuedExecution.setFundingCompletedAt(LocalDateTime.now(MARKET_TIMEZONE));
                executionRepository.save(queuedExecution);

                logger.info("Processing queued execution {} for user {} with amount {}",
                        queuedExecution.getId(), user.getEmail(), queuedExecution.getAmount());

                initiateTradingForExecution(queuedExecution);

            } catch (Exception e) {
                logger.error("Error processing queued execution {} for user {}: {}",
                        queuedExecution.getId(), user.getEmail(), e.getMessage(), e);

                queuedExecution.setStatus(InvestmentExecution.ExecutionStatus.TRADING_FAILED);
                queuedExecution.setErrorMessage("Failed to process queued execution: " + e.getMessage());
                executionRepository.save(queuedExecution);
            }
        }
    }

    /**
     * Restore the original investment amount for a batched execution
     * This is needed because the primary execution amount was modified to include
     * the batch total
     */
    private void restoreOriginalInvestmentAmount(InvestmentExecution execution) {
        // Check if this execution has its original amount stored in the error message
        if (execution.getErrorMessage() != null &&
                execution.getErrorMessage().contains("Primary execution for batch ACH transfer")) {

            // Extract the original amount from the error message
            // Format: "Primary execution for batch ACH transfer $800.00. Original amount:
            // $500.00"
            String errorMessage = execution.getErrorMessage();
            int originalAmountIndex = errorMessage.indexOf("Original amount: $");

            if (originalAmountIndex != -1) {
                try {
                    String amountStr = errorMessage.substring(originalAmountIndex + 18); // Skip "Original amount: $"
                    int endIndex = amountStr.indexOf(".");
                    if (endIndex != -1) {
                        endIndex += 3; // Include ".00"
                        String originalAmountStr = amountStr.substring(0, endIndex);
                        BigDecimal originalAmount = new BigDecimal(originalAmountStr);

                        logger.info("Restoring original amount ${} for primary execution {} (was ${})",
                                originalAmount, execution.getId(), execution.getAmount());

                        execution.setAmount(originalAmount);

                    }
                } catch (Exception e) {
                    logger.error("Failed to parse original amount from error message for execution {}: {}",
                            execution.getId(), e.getMessage());
                }
            } else {
                logger.warn("Primary batch execution {} does not have original amount in error message",
                        execution.getId());
            }
        }
    }

    /**
     * Initiate trading for a funded execution
     */
    private void initiateTradingForExecution(InvestmentExecution execution) {
        logger.info("Initiating trading for execution {}", execution.getId());

        // Check if market is open before placing trades
        if (!isMarketOpen()) {
            logger.info("Market is closed, delaying trading for execution {} until market opens", execution.getId());
            // Keep status as FUNDING_COMPLETED - trading will be picked up when market
            // opens
            return;
        }

        User user = execution.getUser();

        execution.setStatus(InvestmentExecution.ExecutionStatus.TRADING_INITIATED);
        executionRepository.save(execution);

        boolean hasFailures = false;

        // Handle different investment types
        String investmentType = execution.getInvestmentType() != null ? execution.getInvestmentType() : "portfolio";



        if ("stock".equals(investmentType)) {
            // Individual stock investment
            String targetSymbol = execution.getTargetSymbol();
            if (targetSymbol == null || targetSymbol.trim().isEmpty()) {
                execution.setStatus(InvestmentExecution.ExecutionStatus.TRADING_FAILED);
                execution.setErrorMessage("Missing target symbol for stock investment");
                executionRepository.save(execution);

                notifyUserOfFailure(execution, "Trading Failed", "Missing stock symbol information");
                return;
            }

            logger.info("Creating individual stock trade for symbol {} with amount {}", targetSymbol,
                    execution.getAmount());

            if (execution.getAmount().compareTo(BigDecimal.ONE) < 0) {
                logger.error("Investment amount {} is less than minimum $1.00 for stock {}", execution.getAmount(), targetSymbol);
                execution.setStatus(InvestmentExecution.ExecutionStatus.TRADING_FAILED);
                execution.setErrorMessage("Investment amount must be at least $1.00");
                executionRepository.save(execution);
                notifyUserOfFailure(execution, "Amount Too Low", "The investment amount is below the minimum required $1.00.");
                return;
            }

            // TLH: a wash-sale window can appear between request and funding.
            // The user's explicit, already-funded order is honored — nightly
            // reconciliation applies the §1091 disallowance accounting.
            WashSaleWindow lumpSumWindow = washSaleGuardService.blockingWindow(
                    user.getId(), targetSymbol, LocalDate.now(MARKET_TIMEZONE));
            if (lumpSumWindow != null) {
                logger.warn("TLH_ALERT user lump-sum buy of {} inside wash window (until {}) for user {} — "
                        + "disallowance will reconcile", targetSymbol, lumpSumWindow.getEndDate(), user.getId());
            }

            try {
                // Create single trade for the target stock
                InvestmentTrade trade = new InvestmentTrade(execution, targetSymbol, execution.getAmount());
                trade = tradeRepository.save(trade);

                // Place order with Alpaca (handling fractional vs whole shares)
                AlpacaService.AlpacaOrderResponse orderResponse = placeOrderWithFractionalCheck(
                        encryptionService.decrypt(execution.getAlpacaAccountId()), targetSymbol, execution.getAmount());

                if (orderResponse.isSuccess()) {
                    trade.setAlpacaOrderId(orderResponse.id);
                    trade.setStatus(InvestmentTrade.TradeStatus.SUBMITTED);
                    trade.setSubmittedAt(LocalDateTime.now(MARKET_TIMEZONE));
                    tradeRepository.save(trade);

                    logger.info("Successfully placed order {} for stock {} with amount {}",
                            orderResponse.id, targetSymbol, execution.getAmount());
                } else {
                    trade.setStatus(InvestmentTrade.TradeStatus.FAILED);
                    trade.setErrorMessage(orderResponse.errorMessage);
                    trade.setFailedAt(LocalDateTime.now(MARKET_TIMEZONE));
                    tradeRepository.save(trade);

                    hasFailures = true;
                    logger.error("Failed to place order for stock {}: {}", targetSymbol, orderResponse.errorMessage);
                }

            } catch (Exception e) {
                logger.error("Error placing trade for stock {} in execution {}", targetSymbol, execution.getId(), e);
                hasFailures = true;
            }

        } else {
            // Portfolio investment (default behavior)
            logger.info("Creating portfolio trades based on user's allocation");

            Map<String, BigDecimal> portfolioAllocation = getUserPortfolioAllocation(user);

            // Create trades for each allocation
            for (Map.Entry<String, BigDecimal> allocation : portfolioAllocation.entrySet()) {
                String symbol = allocation.getKey();
                BigDecimal percentage = allocation.getValue();
                BigDecimal amount = execution.getAmount().multiply(percentage).divide(BigDecimal.valueOf(100), 2,
                        RoundingMode.HALF_UP);

                if (amount.compareTo(BigDecimal.ONE) < 0) {
                    logger.warn("Calculated investment amount {} for symbol {} is below Alpaca minimum of $1.00. Skipping trade.", amount, symbol);
                    continue;
                }

                // TLH wash-sale guard: a slice of a harvested symbol is
                // redirected to its replacement (asset-class exposure and
                // dollar deployment preserved) or skipped to cash, per config.
                // PortfolioItem weights are never mutated.
                String buySymbol = symbol;
                WashSaleGuardService.GuardDecision guardDecision = washSaleGuardService.checkBuy(
                        user.getId(), symbol, WashSaleGuardService.TradeSource.SCHEDULED_BUY,
                        LocalDate.now(MARKET_TIMEZONE));
                if (guardDecision.action() == WashSaleGuardService.GuardAction.SKIP) {
                    logger.info("TLH wash-sale window active for {} — slice rests as cash (user {})",
                            symbol, user.getId());
                    continue;
                }
                if (guardDecision.action() == WashSaleGuardService.GuardAction.REDIRECT) {
                    logger.info("TLH wash-sale window active for {} — slice redirected to {} (user {})",
                            symbol, guardDecision.redirectSymbol(), user.getId());
                    buySymbol = guardDecision.redirectSymbol();
                }

                try {
                    // Create trade record (records what is actually bought)
                    InvestmentTrade trade = new InvestmentTrade(execution, buySymbol, amount);
                    trade = tradeRepository.save(trade);

                    // Place order with Alpaca (handling fractional vs whole shares)
                    AlpacaService.AlpacaOrderResponse orderResponse = placeOrderWithFractionalCheck(
                            encryptionService.decrypt(execution.getAlpacaAccountId()), buySymbol, amount);

                    if (orderResponse.isSuccess()) {
                        trade.setAlpacaOrderId(orderResponse.id);
                        trade.setStatus(InvestmentTrade.TradeStatus.SUBMITTED);
                        trade.setSubmittedAt(LocalDateTime.now(MARKET_TIMEZONE));
                        tradeRepository.save(trade);

                        logger.info("Successfully placed order {} for symbol {} with amount {}",
                                orderResponse.id, buySymbol, amount);
                    } else {
                        trade.setStatus(InvestmentTrade.TradeStatus.FAILED);
                        trade.setErrorMessage(orderResponse.errorMessage);
                        trade.setFailedAt(LocalDateTime.now(MARKET_TIMEZONE));
                        tradeRepository.save(trade);

                        hasFailures = true;
                        logger.error("Failed to place order for symbol {}: {}", buySymbol, orderResponse.errorMessage);
                    }

                } catch (Exception e) {
                    logger.error("Error placing trade for symbol {} in execution {}", buySymbol, execution.getId(), e);
                    hasFailures = true;
                }
            }
        }

        if (hasFailures) {
            execution.setStatus(InvestmentExecution.ExecutionStatus.TRADING_FAILED);
            execution.setErrorMessage("Some trades failed to execute");
            executionRepository.save(execution);

            notifyUserOfFailure(execution, "Trading Issues",
                    "Some of your investment orders could not be placed. Please check your investment history.");
        }
    }

    /**
     * Check trading status for executions
     */
    public void checkTradingStatus() {
        logger.info("Checking trading status for pending executions");

        // First, initiate trading for any funded executions waiting for market to open
        if (isMarketOpen()) {
            initiateDelayedTrading();
        }

        // Check executions that have been in trading state for at least 5 minutes
        LocalDateTime cutoffTime = LocalDateTime.now(MARKET_TIMEZONE).minusMinutes(5);
        List<InvestmentExecution> tradingExecutions = executionRepository
                .findExecutionsAwaitingTradingCheck(
                        InvestmentExecution.ExecutionStatus.TRADING_INITIATED,
                        cutoffTime);

        logger.info("Found {} executions awaiting trading check", tradingExecutions.size());

        for (InvestmentExecution execution : tradingExecutions) {
            try {
                checkExecutionTradingStatus(execution);
            } catch (Exception e) {
                logger.error("Error checking trading status for execution {}", execution.getId(), e);
            }
        }
    }

    /**
     * Initiate trading for executions that completed funding but were waiting for
     * market to open
     */
    private void initiateDelayedTrading() {
        logger.info("Checking for funded executions waiting for market to open");

        // Find all executions that are funded but haven't started trading yet
        List<InvestmentExecution> fundedExecutions = executionRepository
                .findByStatus(InvestmentExecution.ExecutionStatus.FUNDING_COMPLETED);

        logger.info("Found {} funded executions waiting to start trading", fundedExecutions.size());

        for (InvestmentExecution execution : fundedExecutions) {
            try {
                initiateTradingForExecution(execution);
            } catch (Exception e) {
                logger.error("Error initiating delayed trading for execution {}", execution.getId(), e);
            }
        }
    }

    /**
     * Check trading status for a specific execution
     */
    private void checkExecutionTradingStatus(InvestmentExecution execution) {
        logger.info("Checking trading status for execution {}", execution.getId());

        List<InvestmentTrade> trades = tradeRepository.findByInvestmentExecutionId(execution.getId());
        boolean allComplete = true;
        boolean hasFailures = false;

        for (InvestmentTrade trade : trades) {
            if (trade.getStatus() == InvestmentTrade.TradeStatus.SUBMITTED) {
                // Check order status
                AlpacaService.AlpacaOrderResponse orderStatus = alpacaService.checkOrderStatus(
                        encryptionService.decrypt(execution.getAlpacaAccountId()), trade.getAlpacaOrderId());

                if (orderStatus.isFilled()) {
                    trade.setStatus(InvestmentTrade.TradeStatus.FILLED);
                    trade.setFilledQuantity(orderStatus.filledQuantity);
                    trade.setFilledAvgPrice(orderStatus.filledAvgPrice);
                    trade.setFilledAt(LocalDateTime.now(MARKET_TIMEZONE));
                    tradeRepository.save(trade);

                } else if ("rejected".equalsIgnoreCase(orderStatus.status) ||
                        "canceled".equalsIgnoreCase(orderStatus.status)) {
                    trade.setStatus(InvestmentTrade.TradeStatus.FAILED);
                    trade.setErrorMessage("Order status: " + orderStatus.status);
                    trade.setFailedAt(LocalDateTime.now(MARKET_TIMEZONE));
                    tradeRepository.save(trade);
                    hasFailures = true;

                } else if (orderStatus.isPartiallyFilled()) {
                    trade.setStatus(InvestmentTrade.TradeStatus.PARTIALLY_FILLED);
                    trade.setFilledQuantity(orderStatus.filledQuantity);
                    trade.setFilledAvgPrice(orderStatus.filledAvgPrice);
                    tradeRepository.save(trade);
                    allComplete = false;
                }
            }

            if (trade.getStatus() != InvestmentTrade.TradeStatus.FILLED) {
                allComplete = false;
            }
            if (trade.getStatus() == InvestmentTrade.TradeStatus.FAILED) {
                hasFailures = true;
            }
        }

        // Update execution status
        if (allComplete && !hasFailures) {
            execution.setStatus(InvestmentExecution.ExecutionStatus.COMPLETED);
            execution.setTradingCompletedAt(LocalDateTime.now(MARKET_TIMEZONE));
            executionRepository.save(execution);

            notifyUserOfSuccess(execution);

        } else if (hasFailures) {
            execution.setStatus(InvestmentExecution.ExecutionStatus.TRADING_FAILED);
            execution.setErrorMessage("Some trades failed");
            executionRepository.save(execution);

            notifyUserOfFailure(execution, "Trading Failed",
                    "Some of your investment orders could not be completed.");
        }
    }

    /**
     * Get user's portfolio allocation from their actual saved portfolio
     */
    private Map<String, BigDecimal> getUserPortfolioAllocation(User user) {
        Map<String, BigDecimal> allocation = new HashMap<>();

        logger.info("Getting portfolio allocation for user {}", user.getId());

        // Get user's actual portfolio from database
        Optional<Portfolio> portfolioOpt = portfolioRepository.findByUser(user);

        if (portfolioOpt.isPresent()) {
            Portfolio portfolio = portfolioOpt.get();
            logger.info("Found portfolio '{}' for user {} with {} items",
                    portfolio.getName(), user.getId(), portfolio.getPortfolioItems().size());

            // Convert portfolio items to allocation map
            for (PortfolioItem item : portfolio.getPortfolioItems()) {
                allocation.put(item.getSymbol(), item.getPercentage());
                logger.info("Portfolio item: {} = {}%", item.getSymbol(), item.getPercentage());
            }

            // Validate that percentages add up to 100%
            BigDecimal totalPercentage = allocation.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (totalPercentage.compareTo(new BigDecimal("100")) != 0) {
                logger.warn("Portfolio percentages for user {} do not add up to 100% (actual: {}%)",
                        user.getId(), totalPercentage);
            }

        } else {
            // Fallback to default allocation if no portfolio found
            logger.warn("No portfolio found for user {}, using default balanced allocation", user.getId());
            allocation.put("VTI", new BigDecimal("75"));
            allocation.put("VXUS", new BigDecimal("20"));
            allocation.put("VBR", new BigDecimal("5"));
        }

        logger.info("Final portfolio allocation for user {}: {}", user.getId(), allocation);
        return allocation;
    }

    private void notifyUserOfSuccess(InvestmentExecution execution) {
        try {
            String subject = "Investment Executed Successfully";
            String message = String.format(
                    "Your investment of $%s has been successfully executed on %s.",
                    execution.getAmount(),
                    execution.getTradingCompletedAt().format(DateTimeFormatter.ofPattern("MMM dd, yyyy")));

            emailService.sendEmail(execution.getUser().getEmail(), subject, message);
        } catch (Exception e) {
            logger.error("Failed to send success notification for execution {}", execution.getId(), e);
        }
    }

    private void notifyUserOfFailure(InvestmentExecution execution, String reason, String details) {
        try {
            String subject = "Investment Execution Failed";
            String message = String.format(
                    "Your scheduled investment of $%s could not be executed.\n\nReason: %s\n\nDetails: %s\n\nPlease check your account settings and ensure sufficient funds are available.",
                    execution.getAmount(),
                    reason,
                    details);

            emailService.sendEmail(execution.getUser().getEmail(), subject, message);
        } catch (Exception e) {
            logger.error("Failed to send failure notification for execution {}", execution.getId(), e);
        }
    }

    /**
     * Helper to place a buy order, handling fractional share support
     */
    private AlpacaService.AlpacaOrderResponse placeOrderWithFractionalCheck(String accountId, String symbol, BigDecimal amount) {
        // Check if asset allows fractional shares
        boolean isFractionable = alpacaService.isFractionable(symbol);

        if (isFractionable) {
            return alpacaService.placeBuyOrder(accountId, symbol, amount);
        }
        
        // If not fractionable, calculate whole shares
        logger.info("Asset {} is not fractionable, calculating whole shares for amount {}", symbol, amount);
        
        // Try to get Ask Price first for better buy accuracy, fall back to Last Trade Price
        BigDecimal calculationPrice = portfolioDashboardService.getAskPrice(symbol);
        if (calculationPrice.compareTo(BigDecimal.ZERO) <= 0) {
            logger.debug("Ask price not available for {}, falling back to last trade price", symbol);
            calculationPrice = portfolioDashboardService.getCurrentPrice(symbol);
        }
        
        if (calculationPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return new AlpacaService.AlpacaOrderResponse(
                    null, symbol, "FAILED", null, amount, 
                    BigDecimal.ZERO, null, null, 
                    "Could not fetch price for " + symbol + " to calculate quantity");
        }
        
        // Calculate shares: floor(amount / price)
        BigDecimal quantity = amount.divide(calculationPrice, 0, RoundingMode.DOWN);
        
        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return new AlpacaService.AlpacaOrderResponse(
                    null, symbol, "FAILED", null, amount, 
                    BigDecimal.ZERO, null, null, 
                    "Amount $" + amount + " is too small to buy 1 share of " + symbol + " (Price: $" + calculationPrice + ") which does not support fractional shares.");
        }
        
        return alpacaService.placeBuyOrderWithQuantity(accountId, symbol, quantity);
    }
}
