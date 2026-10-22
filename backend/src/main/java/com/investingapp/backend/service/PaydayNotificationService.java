package com.investingapp.backend.service;

import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
public class PaydayNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(PaydayNotificationService.class);

    /** MANUAL D-1 copy (spec section 10, PAYDAY_D1 MANUAL row). Push is the first sentence. */
    static final String D1_MANUAL_SUBJECT = "Your investment hits tomorrow";
    static final String D1_MANUAL_BODY = "FRED will invest %s tomorrow. Your Freedom Date moves closer.";
    static final String D1_MANUAL_PUSH_TITLE = "Investment tomorrow";
    static final String D1_MANUAL_PUSH_BODY = "FRED will invest %s tomorrow.";

    /**
     * Holds that stop the 11:55 PM cron from pulling a MANUAL schedule (spec 5.2 MANUAL clause).
     * A D-1 notice must not promise a transfer one of these holds will block. Informational holds
     * (BANK_CHANGE and the rest) still get the notice: they clear on their own before the pull.
     */
    static final Set<HoldReason> BLOCKING_HOLDS = EnumSet.of(
            HoldReason.ACH_RETURN_UNAUTHORIZED,
            HoldReason.ACH_RETURN_ACCOUNT,
            HoldReason.MANUAL_REVIEW);

    @Autowired
    private EmailService emailService;

    @Autowired
    private InvestmentScheduleRepository investmentScheduleRepository;

    @Autowired
    private InvestmentExecutionRepository investmentExecutionRepository;

    /**
     * D-1 notice for MANUAL schedules due tomorrow. {@code todayET} is the America/New_York date
     * computed once by InvestmentScheduler and passed down; this method never reads the clock.
     * The repository query already restricts to MANUAL, not paused rows.
     */
    public void sendDayBeforeNotifications(LocalDate todayET) {
        LocalDate tomorrow = todayET.plusDays(1);
        List<InvestmentSchedule> schedules = investmentScheduleRepository.findActiveByNextInvestmentDate(tomorrow);
        for (InvestmentSchedule schedule : schedules) {
            if (schedule.getNextInvestmentDate() == null) continue;
            HoldReason hold = schedule.getHoldReason();
            if (hold != null && BLOCKING_HOLDS.contains(hold)) {
                logger.info("Skipping D-1 notice for schedule {}: blocking hold {}", schedule.getId(), hold);
                continue;
            }
            User user = schedule.getUser();
            if (user == null) continue;
            String amount = String.format("$%.0f", schedule.getInvestmentAmount());
            emailService.sendEmail(user.getEmail(), D1_MANUAL_SUBJECT, String.format(D1_MANUAL_BODY, amount));
            sendPushIfTokenPresent(user, D1_MANUAL_PUSH_TITLE, String.format(D1_MANUAL_PUSH_BODY, amount));
        }
    }

    public void sendPaydayNotifications(LocalDate investmentDate) {
        List<InvestmentExecution> executions = investmentExecutionRepository.findByScheduledDate(investmentDate);
        for (InvestmentExecution execution : executions) {
            if (execution.getScheduledDate() == null) continue;
            User user = execution.getUser();
            if (user == null) continue;
            String amount = execution.getAmount() != null
                ? String.format("$%.0f", execution.getAmount())
                : "your scheduled amount";
            emailService.sendEmail(user.getEmail(),
                "💰 Investment complete",
                String.format("💰 %s invested. Your freedom date just moved closer.", amount));
            sendPushIfTokenPresent(user, "Invested 💰",
                String.format("%s invested. Your freedom date just moved closer.", amount));
        }
    }

    public void sendDayAfterNotifications(LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        List<InvestmentExecution> executions = investmentExecutionRepository.findByScheduledDate(yesterday);
        for (InvestmentExecution execution : executions) {
            if (execution.getScheduledDate() == null) continue;
            User user = execution.getUser();
            if (user == null) continue;
            emailService.sendEmail(user.getEmail(),
                "You moved closer to freedom",
                "You just got closer to financial freedom without lifting a finger. Keep it up!");
            sendPushIfTokenPresent(user, "Freedom update",
                "You just got closer to financial freedom without lifting a finger.");
        }
    }

    private void sendPushIfTokenPresent(User user, String title, String body) {
        String token = user.getDevicePushToken();
        if (token == null || token.isBlank()) return;
        // FCM push notification infrastructure: implement when Firebase project is configured.
        // Requires: google-services.json, firebase-admin SDK dependency, and FCM credentials.
        logger.info("[PUSH] Would send to token {} with title '{}' and body '{}'",
                token.substring(0, Math.min(8, token.length())), title, body);
    }
}
