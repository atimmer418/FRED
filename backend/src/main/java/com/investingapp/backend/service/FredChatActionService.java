package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.investingapp.backend.model.ChatActionAudit;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.ChatActionAuditRepository;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase B agentic actions: the ONLY place FRED-proposed mutations execute.
 *
 * Invariants:
 *  - the model proposes; execution happens only on an explicit user Confirm tap
 *    hitting the authenticated endpoint (ChatController POST /api/chat/action)
 *  - the action allowlist below is the source of truth — anything else is REJECTED
 *  - the User is always the authenticated principal
 *  - every confirm tap writes an audit row, success or failure
 */
@Service
public class FredChatActionService {

    private static final Logger logger = LoggerFactory.getLogger(FredChatActionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final Set<String> ALLOWED_ACTIONS = Set.of(
            "update_investment_amount",
            "pause_investing",
            "resume_investing");

    private static final int MAX_ACTIONS_PER_MINUTE = 6;

    private final InvestmentScheduleService scheduleService;
    private final ChatActionAuditRepository auditRepository;

    /** Per-user sliding window: the endpoint is directly callable by any
     *  authenticated client, so bound the mutation rate server-side. */
    private final Cache<Long, AtomicInteger> actionRateWindow = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();

    @Value("${investment.min.amount:1.00}")
    private BigDecimal minAmount;

    @Value("${investment.max.amount:50000.00}")
    private BigDecimal maxAmount;

    public FredChatActionService(InvestmentScheduleService scheduleService,
            ChatActionAuditRepository auditRepository) {
        this.scheduleService = scheduleService;
        this.auditRepository = auditRepository;
    }

    public record ActionResult(boolean success, String message) {
    }

    public ActionResult execute(User user, String action, JsonNode params, String sessionId) {
        String paramsJson = params != null ? params.toString() : "{}";
        String safeAction = truncate(String.valueOf(action), 60); // column is length=60

        if (action == null || !ALLOWED_ACTIONS.contains(action)) {
            audit(user, sessionId, safeAction, paramsJson, "REJECTED", "Unknown action");
            return new ActionResult(false, "That action isn't something I can do.");
        }

        AtomicInteger window = actionRateWindow.get(user.getId(), k -> new AtomicInteger());
        if (window.incrementAndGet() > MAX_ACTIONS_PER_MINUTE) {
            audit(user, sessionId, safeAction, paramsJson, "REJECTED", "Rate limited");
            return new ActionResult(false,
                    "Let's slow down. That's a lot of changes at once. Try again in a minute.");
        }

        // Intent-first audit: if the intent row can't be written, don't act.
        // A mutation must never exist without an audit trail.
        ChatActionAudit auditRow;
        try {
            auditRow = auditRepository.save(new ChatActionAudit(user.getId(), sessionId, safeAction,
                    paramsJson, "PENDING", null));
        } catch (Exception e) {
            logger.error("Could not write action intent row for user {} — refusing to execute", user.getId(), e);
            return new ActionResult(false,
                    "I couldn't record this change safely, so nothing was executed. Try again in a bit.");
        }

        ActionResult result;
        try {
            result = switch (action) {
                case "update_investment_amount" -> updateInvestmentAmount(user, params);
                case "pause_investing" -> pauseInvesting(user);
                case "resume_investing" -> resumeInvesting(user);
                default -> new ActionResult(false, "That action isn't something I can do.");
            };
        } catch (IllegalStateException e) {
            logger.warn("Chat action {} blocked for user {}: {}", action, user.getId(), e.getMessage());
            result = new ActionResult(false, e.getMessage() != null && e.getMessage().contains("ACCOUNT_NOT_ACTIVE")
                    ? "Your account isn't fully active yet. Finish the remaining setup steps in the app first."
                    : "That change isn't possible right now. Nothing was changed.");
        } catch (Exception e) {
            logger.error("Chat action {} failed for user {}", action, user.getId(), e);
            result = new ActionResult(false,
                    "Something went wrong executing that. Nothing was changed. Try again in a bit.");
        }

        try {
            auditRow.setStatus(result.success() ? "EXECUTED" : "FAILED");
            auditRow.setResultSummary(result.message());
            auditRepository.save(auditRow);
        } catch (Exception e) {
            // The PENDING intent row remains as evidence — log loudly, don't block
            logger.error("AUDIT GAP: outcome update failed for audit row {} (user {}, action {}, result: {})",
                    auditRow.getId(), user.getId(), action, result.message(), e);
        }
        return result;
    }

    private static String truncate(String value, int max) {
        return value != null && value.length() > max ? value.substring(0, max) : value;
    }

    private ActionResult updateInvestmentAmount(User user, JsonNode params) {
        JsonNode amountNode = params != null ? params.get("amountPerRun") : null;
        if (amountNode == null || !amountNode.isNumber()) {
            return new ActionResult(false, "I couldn't read the new amount. Nothing was changed.");
        }
        BigDecimal amount = amountNode.decimalValue().setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(minAmount) < 0 || amount.compareTo(maxAmount) > 0) {
            return new ActionResult(false, String.format(
                    "That amount is outside the allowed range ($%s to $%s). Nothing was changed.",
                    minAmount.toPlainString(), maxAmount.toPlainString()));
        }

        Optional<InvestmentSchedule> current = scheduleService.getCurrentSchedule(user);
        if (current.isEmpty()) {
            return new ActionResult(false,
                    "There's no investing schedule set up yet. Create one in the Invest tab first.");
        }

        InvestmentSchedule schedule = current.get();
        BigDecimal before = schedule.getInvestmentAmount();
        InvestmentSchedule updated = scheduleService.updateSchedule(
                user, schedule.getId(), amount, schedule.getFrequency());

        return new ActionResult(true, String.format(
                "Done. Your %s investment is now $%s per run (was $%s). Monthly pace: about $%s.",
                updated.getFrequency() != null ? updated.getFrequency().toLowerCase() : "recurring",
                money(amount), money(before), money(updated.getMonthlyAmount())));
    }

    /** Money renders as 2dp everywhere — schedule math carries scale-4 values internally */
    private static String money(BigDecimal value) {
        return value == null ? "?" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private ActionResult pauseInvesting(User user) {
        Optional<InvestmentSchedule> current = scheduleService.getCurrentSchedule(user);
        if (current.isEmpty()) {
            return new ActionResult(false, "There's no investing schedule to pause.");
        }
        if (Boolean.TRUE.equals(current.get().getIsPaused())) {
            return new ActionResult(false, "Your investing is already paused, so there's nothing to change.");
        }
        scheduleService.pauseSchedule(user, current.get().getId());
        return new ActionResult(true,
                "Done. Automatic investing is paused. Resume any time; the mud will wait.");
    }

    private ActionResult resumeInvesting(User user) {
        Optional<InvestmentSchedule> current = scheduleService.getCurrentSchedule(user);
        if (current.isEmpty()) {
            return new ActionResult(false, "There's no investing schedule to resume.");
        }
        if (!Boolean.TRUE.equals(current.get().getIsPaused())) {
            return new ActionResult(false, "Your investing is already active, so there's nothing to change.");
        }
        InvestmentSchedule resumed = scheduleService.resumeSchedule(user, current.get().getId());
        // A long pause can leave nextInvestmentDate in the past — the scheduler
        // picks those up on its next pass, so don't report a stale date
        String nextRun = "with the next scheduler pass (within about a day)";
        if (resumed.getNextInvestmentDate() != null && !resumed.getNextInvestmentDate().isBefore(EasternTime.today())) {
            nextRun = resumed.getNextInvestmentDate().toString();
        }
        return new ActionResult(true, "Done. Automatic investing is back on. Next run: " + nextRun + ".");
    }

    /** Terminal-state audit for requests rejected before an intent row exists. */
    private void audit(User user, String sessionId, String action, String paramsJson,
            String status, String resultSummary) {
        try {
            auditRepository.save(new ChatActionAudit(user.getId(), sessionId, action, paramsJson,
                    status, resultSummary));
        } catch (Exception e) {
            logger.error("Failed to write chat action audit for user {} (action {}, status {})",
                    user.getId(), action, status, e);
        }
    }
}
