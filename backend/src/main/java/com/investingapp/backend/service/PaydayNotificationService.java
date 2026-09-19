package com.investingapp.backend.service;

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
import java.util.List;

@Service
public class PaydayNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(PaydayNotificationService.class);

    @Autowired
    private EmailService emailService;

    @Autowired
    private InvestmentScheduleRepository investmentScheduleRepository;

    @Autowired
    private InvestmentExecutionRepository investmentExecutionRepository;

    public void sendDayBeforeNotifications(LocalDate today) {
        LocalDate tomorrow = today.plusDays(1);
        List<InvestmentSchedule> schedules = investmentScheduleRepository.findActiveByNextInvestmentDate(tomorrow);
        for (InvestmentSchedule schedule : schedules) {
            if (schedule.getNextInvestmentDate() == null) continue;
            User user = schedule.getUser();
            if (user == null) continue;
            String amount = String.format("$%.0f", schedule.getInvestmentAmount());
            emailService.sendEmail(user.getEmail(),
                "Your investment hits tomorrow",
                String.format("Your paycheck hits tomorrow. FRED will auto-invest %s. Your Freedom Date moves closer.", amount));
            sendPushIfTokenPresent(user, "Investment tomorrow",
                String.format("FRED will auto-invest %s tomorrow. Your Freedom Date moves closer.", amount));
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
        logger.info("[PUSH] Would send to token {}: {} — {}", token.substring(0, Math.min(8, token.length())), title, body);
    }
}
