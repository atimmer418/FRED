package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.dto.EmailMessage;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;
import com.investingapp.backend.util.EmailTemplateRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The single sender for Paycheck Autopilot notices (spec 10), keyed in email_log by (email_type,
 * user_id, period_key). No lock: two concurrent attempts may both try, and the V1 unique index
 * uq_email_type_user_period keeps the ledger single (spec 10's one-transaction insert waits for
 * Phase 2's real sender). Callers invoke it after their commit; this class opens no transaction.
 * Logs carry the type and a closed reason only: never recipient, bank, copy, user id or key.
 */
@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    /** email_log.period_key and email_type widths. */
    public static final int PERIOD_KEY_MAX = 10;
    static final int EMAIL_TYPE_MAX = 40;

    public static final String TYPE_RECONNECT = "RECONNECT";

    static final String RECONNECT_SUBJECT = "Reconnect your bank account";
    /** Spec 10 RECONNECT copy; %s is the institution name, or "bank". */
    static final String RECONNECT_LINE_MANUAL = "Reconnect your %s account so your investing keeps running.";
    static final String RECONNECT_LINE_DETECTED = "Reconnect your %s account so FRED can keep spotting your paycheck."
            + " Paycheck investing is paused until then.";
    static final String BANK_FALLBACK = "bank";
    static final String FAILED_COMPOSE = "compose_failed";

    private final EmailService emailService;
    private final InvestmentScheduleRepository investmentScheduleRepository;
    private final NotificationGate notificationGate;
    private final PaycheckAutopilotProperties props;

    public NotificationService(EmailService emailService,
            InvestmentScheduleRepository investmentScheduleRepository,
            NotificationGate notificationGate,
            PaycheckAutopilotProperties props) {
        this.emailService = emailService;
        this.investmentScheduleRepository = investmentScheduleRepository;
        this.notificationGate = notificationGate;
        this.props = props;
    }

    /**
     * At most once per (emailType, user, periodKey). True when handed to EmailService, false when
     * skipped. Only a null builder, or a type or key that cannot fit the ledger, throws. The builder supplies subject
     * and bodies; the recipient is always the user's own address.
     */
    public boolean sendOnce(User user, String emailType, String periodKey, Supplier<EmailMessage> builder) {
        requireKey(emailType, periodKey);
        Objects.requireNonNull(builder, "builder");
        if (user == null || user.getId() == null || user.getEmail() == null || user.getEmail().isBlank()) {
            logger.info("paycheck.notice.skipped type={} reason=no_recipient", emailType);
            return false;
        }
        boolean alreadySent;
        try {
            alreadySent = emailService.hasBeenSent(emailType, user.getId(), periodKey);
        } catch (RuntimeException e) {
            logger.warn("paycheck.notice.skipped type={} reason=ledger_unavailable error_class={}",
                    emailType, e.getClass().getSimpleName());
            return false;
        }
        if (alreadySent) {
            logger.info("paycheck.notice.skipped type={} reason=already_sent", emailType);
            return false;
        }
        EmailMessage message;
        try {
            message = builder.get();
        } catch (RuntimeException e) {
            message = null;
            logger.warn("paycheck.notice.compose_failed type={} error_class={}", emailType, e.getClass().getSimpleName());
        }
        if (message == null) {
            emailService.recordFailedAttempt(user.getId(), user.getEmail(), emailType, periodKey, FAILED_COMPOSE);
            return false;
        }
        message.setTo(user.getEmail());
        emailService.sendEmail(message, user.getId(), emailType, periodKey);
        logger.info("paycheck.notice.sent type={}", emailType);
        return true;
    }

    /**
     * RECONNECT (spec 10, both modes), keyed by the incident's ET date. Nothing when disabled
     * (12.1), REJECTED or without a schedule row (R18). An opt-out or a broken Item does not stop
     * it, and the gate is not consulted: RECONNECT is not a DETECTED-only notice.
     */
    public boolean sendReconnect(User user, LocalDate incidentDate) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(incidentDate, "incidentDate");
        Optional<ExclusionReason> excluded = DetectionEligibility.exclusion(user, props);
        if (excluded.isPresent()
                && (excluded.get() == ExclusionReason.DISABLED || excluded.get() == ExclusionReason.ACCOUNT_REJECTED)) {
            logger.info("paycheck.notice.skipped type={} reason={}", TYPE_RECONNECT,
                    excluded.get().name().toLowerCase(Locale.ROOT));
            return false;
        }
        Optional<InvestmentSchedule> schedule = investmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(user);
        if (schedule.isEmpty()) {
            logger.info("paycheck.notice.skipped type={} reason=no_schedule", TYPE_RECONNECT);
            return false;
        }
        ScheduleMode mode = schedule.get().getMode() == null ? ScheduleMode.MANUAL : schedule.get().getMode();
        String bank = bankLabel(user.getPlaidInstitutionName());
        return sendOnce(user, TYPE_RECONNECT, incidentDate.toString(), () -> composeReconnect(bank, mode));
    }

    /** Every DETECTED-only notice of spec 10: key checked first, then the gate. No Phase 1 caller. */
    public boolean sendDetectedOnce(User user, InvestmentSchedule schedule, String emailType, String periodKey,
            Supplier<EmailMessage> builder) {
        requireKey(emailType, periodKey);
        if (notificationGate.suppressed(user, schedule)) {
            return false;
        }
        return sendOnce(user, emailType, periodKey, builder);
    }

    /** The RECONNECT message without a recipient; sendOnce fills it. The bank name is escaped for HTML. */
    static EmailMessage composeReconnect(String bank, ScheduleMode mode) {
        String line = String.format(mode == ScheduleMode.DETECTED ? RECONNECT_LINE_DETECTED : RECONNECT_LINE_MANUAL, bank);
        String html = EmailTemplateRenderer.render("reconnect.html", Map.of("message", HtmlUtils.htmlEscape(line)));
        String text = EmailTemplateRenderer.render("reconnect.txt", Map.of("message", line));
        return new EmailMessage(null, null, RECONNECT_SUBJECT, html, text);
    }

    static String bankLabel(String institutionName) {
        return institutionName == null || institutionName.isBlank() ? BANK_FALLBACK : institutionName.trim();
    }

    private static void requireKey(String emailType, String periodKey) {
        if (emailType == null || emailType.isBlank() || emailType.length() > EMAIL_TYPE_MAX) {
            throw new IllegalArgumentException("emailType must be 1 to " + EMAIL_TYPE_MAX
                    + " characters to fit email_log.email_type");
        }
        if (periodKey == null || periodKey.isBlank() || periodKey.length() > PERIOD_KEY_MAX) {
            throw new IllegalArgumentException("periodKey must be 1 to " + PERIOD_KEY_MAX
                    + " characters to fit email_log.period_key: an ISO date, a YYYY-MM bucket or an id");
        }
    }
}
