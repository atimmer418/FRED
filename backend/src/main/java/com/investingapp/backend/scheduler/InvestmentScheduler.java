package com.investingapp.backend.scheduler;

import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.service.DripService;
import com.investingapp.backend.service.InvestmentExecutionService;
import com.investingapp.backend.service.JobLockService;
import com.investingapp.backend.service.JobLocks;
import com.investingapp.backend.service.PaydayNotificationService;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

@Component
public class InvestmentScheduler {
    
    private static final Logger logger = LoggerFactory.getLogger(InvestmentScheduler.class);
    
    @Autowired
    private InvestmentExecutionService investmentExecutionService;

    @Autowired
    private DripService dripService;

    @Autowired
    private PaydayNotificationService paydayNotificationService;

    @Autowired
    private SchedulerGate schedulerGate;

    @Autowired
    private JobLockService jobLockService;

    /**
     * Process scheduled investments every day at 11:55 PM ET
     * This runs before the 11:59 PM batch process, allowing users to update their
     * investment schedule to "today" at any point during the day and still have it picked up.
     * Cron: "0 55 23 * * *" (Daily at 11:55 PM)
     */
    @Scheduled(cron = "0 55 23 * * *", zone = "America/New_York")
    public void processScheduledInvestments() {
        if (!schedulerGate.isEnabled()) return;
        if (!jobLockService.tryAcquire(JobLocks.PROCESS_SCHEDULED, Duration.ofMinutes(10))) {
            logger.warn("Skipping scheduled investment processing: job lock {} not taken (held by another run or job_locks unreachable)",
                    JobLocks.PROCESS_SCHEDULED);
            return;
        }
        LocalDate todayET = EasternTime.today();
        logger.info("Starting scheduled investment processing job for {}", todayET);
        try {
            investmentExecutionService.processScheduledInvestments(todayET);
            logger.info("Completed scheduled investment processing job successfully");
        } catch (Exception e) {
            logger.error("Error in scheduled investment processing job", e);
        } finally {
            jobLockService.release(JobLocks.PROCESS_SCHEDULED);
        }
    }
    
    /**
     * Check funding status every 30 minutes during funding hours
     * ACH transfers process on business days roughly 8 AM - 6 PM ET
     * Cron: every 30 min, hours 8-17, Mon-Fri ET
     */
    @Scheduled(cron = "0 */30 8-17 * * MON-FRI", zone = "America/New_York")
    public void checkFundingStatus() {
        if (!schedulerGate.isEnabled()) return;
        logger.info("Starting funding status check job");
        try {
            investmentExecutionService.checkFundingStatus();
            logger.info("Completed funding status check job successfully");
        } catch (Exception e) {
            logger.error("Error in funding status check job", e);
        }
    }

    /**
     * Check trading status every 15 minutes during US market hours
     * US equity markets: 9:30 AM - 4:00 PM ET, Mon-Fri
     * Cron: every 15 min, hours 9-15, Mon-Fri ET (covers 9:00-15:45)
     */
    @Scheduled(cron = "0 */15 9-15 * * MON-FRI", zone = "America/New_York")
    public void checkTradingStatus() {
        if (!schedulerGate.isEnabled()) return;
        logger.info("Starting trading status check job");
        try {
            investmentExecutionService.checkTradingStatus();
            logger.info("Completed trading status check job successfully");
        } catch (Exception e) {
            logger.error("Error in trading status check job", e);
        }
    }    // ==================== DRIP (Dividend Reinvestment) ====================

    /**
     * Process DRIP dividends on Tuesdays and Thursdays at 10:00 AM ET.
     * Scans all DRIP-eligible users for new cash dividend (CDIV) activities
     * and places notional buy orders to reinvest dividends back into the
     * original stock.
     */
    @Scheduled(cron = "0 0 10 * * TUE,THU", zone = "America/New_York")
    public void processDripDividends() {
        if (!schedulerGate.isEnabled()) return;
        logger.info("Starting DRIP dividend processing job");
        try {
            dripService.processAllDividends();
            logger.info("Completed DRIP dividend processing job successfully");
        } catch (Exception e) {
            logger.error("Error in DRIP dividend processing job", e);
        }
    }

    /**
     * Check DRIP order statuses every 15 minutes during market hours (Mon-Fri).
     * Verifies whether reinvestment buy orders have been filled or failed.
     */
    @Scheduled(cron = "0 */15 10-15 * * MON-FRI", zone = "America/New_York")
    public void checkDripOrderStatus() {
        if (!schedulerGate.isEnabled()) return;
        logger.info("Starting DRIP order status check job");
        try {
            dripService.checkDripOrderStatus();
            logger.info("Completed DRIP order status check job successfully");
        } catch (Exception e) {
            logger.error("Error in DRIP order status check job", e);
        }
    }

    /**
     * For testing purposes - run every minute (disable in production)
     * Uncomment and use this for testing the investment flow
     */
    // @Scheduled(fixedRate = 60000) // 1 minute for testing
    // public void testInvestmentProcessing() {
    //     logger.info("Running test investment processing");
    //     try {
    //         investmentExecutionService.processScheduledInvestments();
    //     } catch (Exception e) {
    //         logger.error("Error in test investment processing", e);
    //     }
    // }

    @Scheduled(cron = "0 0 7 * * *", zone = "America/New_York")
    public void sendDayBeforePaydayNotifications() {
        if (!schedulerGate.isEnabled()) return;
        LocalDate todayET = EasternTime.today();
        logger.info("Sending D-1 payday notifications for {}", todayET);
        try {
            paydayNotificationService.sendDayBeforeNotifications(todayET);
        } catch (Exception e) {
            logger.error("Error sending D-1 payday notifications", e);
        }
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "America/New_York")
    public void sendPaydayNotifications() {
        if (!schedulerGate.isEnabled()) return;
        LocalDate todayET = EasternTime.today();
        logger.info("Sending D+0 payday notifications for {}", todayET.minusDays(1));
        try {
            paydayNotificationService.sendPaydayNotifications(todayET.minusDays(1));
        } catch (Exception e) {
            logger.error("Error sending D+0 payday notifications", e);
        }
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "America/New_York")
    public void sendDayAfterPaydayNotifications() {
        if (!schedulerGate.isEnabled()) return;
        LocalDate todayET = EasternTime.today();
        logger.info("Sending D+1 payday notifications for {}", todayET);
        try {
            paydayNotificationService.sendDayAfterNotifications(todayET);
        } catch (Exception e) {
            logger.error("Error sending D+1 payday notifications", e);
        }
    }
}
