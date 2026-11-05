package com.investingapp.backend.service;

import com.investingapp.backend.dto.EmailMessage;
import com.investingapp.backend.model.EmailLog;
import com.investingapp.backend.repository.EmailLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Facade for all outbound email. Every attempt is recorded in email_log
 * (bodies never stored). Dispatch goes through the active EmailSender;
 * with app.email.enabled=false (current state) sends are MOCKED — logged
 * and recorded, never dispatched — so the whole pipeline is exercisable
 * before a real provider exists.
 */
@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    public static final String TYPE_LEGACY = "LEGACY";

    private final EmailLogRepository emailLogRepository;
    private final EmailSender emailSender;
    private final boolean emailEnabled;
    private final String fromEmail;

    public EmailService(EmailLogRepository emailLogRepository,
            EmailSender emailSender,
            @Value("${app.email.enabled:false}") boolean emailEnabled,
            @Value("${app.email.from:noreply@investingapp.com}") String fromEmail) {
        this.emailLogRepository = emailLogRepository;
        this.emailSender = emailSender;
        this.emailEnabled = emailEnabled;
        this.fromEmail = fromEmail;
    }

    /** Legacy plain-text path — existing callers keep this exact signature. */
    public void sendEmail(String to, String subject, String text) {
        sendEmail(new EmailMessage(to, null, subject, null, text), null, TYPE_LEGACY, null);
    }

    /**
     * Send (or mock-send) an email and record the outcome. Never throws —
     * failures are recorded as FAILED rows and callers rely on hasBeenSent /
     * catch-up jobs for retry semantics.
     */
    public void sendEmail(EmailMessage message, Long userId, String emailType, String periodKey) {
        if (message.getFrom() == null) {
            message.setFrom(fromEmail);
        }
        if (!emailEnabled) {
            // The recipient address stays out of the log: an email address is personal data, and the
            // email_log row written below is the record of who it was for.
            logger.info("Email disabled. Would send to user {}: '{}' [type={}, period={}]",
                    userId, message.getSubject(), emailType, periodKey);
            record(userId, message.getTo(), message.getSubject(), emailType, periodKey,
                    EmailLog.STATUS_MOCKED, null);
            return;
        }
        try {
            emailSender.send(message);
            record(userId, message.getTo(), message.getSubject(), emailType, periodKey,
                    EmailLog.STATUS_SENT, null);
        } catch (Exception e) {
            logger.error("Email send failed to {} [type={}]: {}", message.getTo(), emailType, e.getMessage());
            record(userId, message.getTo(), message.getSubject(), emailType, periodKey,
                    EmailLog.STATUS_FAILED, e.getMessage());
        }
    }

    /** True when a SENT or MOCKED row exists for this (type, user, period). */
    public boolean hasBeenSent(String emailType, Long userId, String periodKey) {
        return emailLogRepository.findByEmailTypeAndUserIdAndPeriodKey(emailType, userId, periodKey)
                .map(l -> EmailLog.STATUS_SENT.equals(l.getStatus()) || EmailLog.STATUS_MOCKED.equals(l.getStatus()))
                .orElse(false);
    }

    /** Record a FAILED attempt that never reached dispatch (e.g. compose/data errors). */
    public void recordFailedAttempt(Long userId, String recipient, String emailType,
            String periodKey, String error) {
        record(userId, recipient, null, emailType, periodKey, EmailLog.STATUS_FAILED, error);
    }

    /**
     * Upsert the audit row. For period emails a FAILED row is updated in place on
     * retry — the unique index would reject a second insert for the same
     * (type, user, period). One-off emails (null periodKey) always insert.
     */
    private void record(Long userId, String recipient, String subject, String emailType,
            String periodKey, String status, String errorMessage) {
        try {
            EmailLog log = null;
            if (periodKey != null && userId != null) {
                Optional<EmailLog> existing =
                        emailLogRepository.findByEmailTypeAndUserIdAndPeriodKey(emailType, userId, periodKey);
                if (existing.isPresent()) {
                    log = existing.get();
                    log.setStatus(status);
                    log.setErrorMessage(errorMessage);
                    if (subject != null) {
                        log.setSubject(subject);
                    }
                }
            }
            if (log == null) {
                log = new EmailLog(userId, recipient, subject, emailType, periodKey, status, errorMessage);
            }
            emailLogRepository.save(log);
        } catch (Exception e) {
            // Auditing must never break the send path (or a scheduler batch)
            logger.error("Failed to record email_log row [type={}, user={}, period={}]: {}",
                    emailType, userId, periodKey, e.getMessage());
        }
    }
}
