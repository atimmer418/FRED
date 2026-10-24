package com.investingapp.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.AnalyticsOutboxEntry;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.AnalyticsOutboxRepository;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.util.AnalyticsPrivacyGuard;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes server-side analytics events into the outbox.
 *
 * Called from inside the transaction that commits the state change, so the event and the fact it
 * describes land together or not at all. There is no propagation annotation here on purpose: this
 * joins whatever transaction the caller already has, and refuses to write if there is not one.
 *
 * Refusing is the deliberate choice. A row written outside a transaction can describe something
 * that was rolled back, and Checkpoint G reads this table as the source of truth for money and
 * compliance. A wrong ledger is worse than a missing row, and a missing row is far better than a
 * broken user flow, which is why this logs and returns rather than throwing.
 */
@Service
public class AnalyticsOutboxService {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsOutboxService.class);

    private final AnalyticsOutboxRepository outboxRepository;
    private final InvestmentScheduleRepository scheduleRepository;
    private final InvestmentExecutionRepository executionRepository;
    private final ObjectMapper objectMapper;

    public AnalyticsOutboxService(AnalyticsOutboxRepository outboxRepository,
                                  InvestmentScheduleRepository scheduleRepository,
                                  InvestmentExecutionRepository executionRepository,
                                  ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.scheduleRepository = scheduleRepository;
        this.executionRepository = executionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Records one event for a user.
     *
     * @param eventName one of ServerAnalyticsEvent's names
     * @param user      the user it happened to; its public_id becomes the distinct id
     * @param properties scalar values only, already banded. Never an amount, a name, an email, an
     *                   account number or an institution. Enforced, not assumed: see
     *                   AnalyticsPrivacyGuard, which strips a forbidden property and says so.
     * @return the row written, or null when nothing was written
     */
    public AnalyticsOutboxEntry record(String eventName, User user, Map<String, Object> properties) {
        try {
            if (!ServerAnalyticsEvent.ALL.contains(eventName)) {
                logger.error("analytics: refusing to record unknown event '{}'", eventName);
                return null;
            }
            if (user == null) {
                logger.error("analytics: refusing to record {} with no user", eventName);
                return null;
            }
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                // See the class comment: an untransacted row can outlive a rollback and lie.
                logger.error("analytics: refusing to record {} outside a transaction; the caller "
                        + "must be @Transactional for the event to be atomic with its state change",
                        eventName);
                return null;
            }

            AnalyticsOutboxEntry entry = new AnalyticsOutboxEntry();
            entry.setEventId(UUID.randomUUID().toString());
            entry.setEventName(eventName);
            entry.setPublicId(user.getPublicId());
            entry.setUserId(user.getId());
            // The last thing before the row is written. Everything above this line is the
            // caller's map, assembled next to the access tokens and the Social Security number
            // that the same service has in scope; nothing in the type system would object to one
            // of them being put in it. A forbidden property is stripped and logged, and the event
            // still lands: it is the ledger of money moving, and throwing it away to protect a
            // property we already removed would lose a real accounting row for nothing.
            entry.setProperties(toJson(AnalyticsPrivacyGuard.scrub(
                    eventName, "properties", properties == null ? Map.of() : properties)));
            entry.setPersonProperties(toJson(AnalyticsPrivacyGuard.scrub(
                    eventName, "person_properties", personProperties(user))));
            entry.setOccurredAt(LocalDateTime.now());
            entry.setStatus(AnalyticsOutboxEntry.STATUS_PENDING);
            entry.setConsentOptOutAtWrite(Boolean.TRUE.equals(user.getAnalyticsOptOut()));
            return outboxRepository.save(entry);
        } catch (Exception e) {
            // Analytics must never break a user flow. The state change still commits; the event is
            // lost and said so loudly.
            logger.error("analytics: failed to record {}", eventName, e);
            return null;
        }
    }

    /** True when this user already has one of these, used for the is_first_* properties. */
    public boolean hasRecorded(User user, String eventName) {
        if (user == null || user.getId() == null) {
            return false;
        }
        return outboxRepository.existsByUserIdAndEventName(user.getId(), eventName);
    }

    public long countRecorded(User user, String eventName) {
        if (user == null || user.getId() == null) {
            return 0;
        }
        return outboxRepository.countByUserIdAndEventName(user.getId(), eventName);
    }

    /**
     * The $set payload: a few low-cardinality facts about the user, refreshed on every event so a
     * person record never drifts from the funnel. Booleans and enums only, by the rules.
     */
    Map<String, Object> personProperties(User user) {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("tier", tier(user));
        set.put("kyc_status", kycStatus(user));
        set.put("has_linked_bank", user.getPlaidAccessToken() != null);
        set.put("has_schedule", safely(() -> scheduleRepository.existsByUser(user), false));
        set.put("has_funded",
                safely(() -> executionRepository.existsByUserAndFundingCompletedAtIsNotNull(user), false));
        return set;
    }

    /**
     * The tier, narrowed to the three real values. The column is an unvalidated varchar that any
     * client can write, and 'piggy' is a legacy name for the same thing core is now, so anything
     * unrecognised becomes null rather than a new dimension value nobody can interpret.
     */
    static String tier(User user) {
        String raw = user.getSelectedTier();
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase();
        switch (value) {
            case "core":
            case "plus":
            case "pro":
                return value;
            case "piggy":
                return "core";
            default:
                return null;
        }
    }

    /**
     * KYC state as a closed vocabulary. The broker's own statuses are richer than the funnel needs,
     * and several of them mean the same thing to a product question.
     */
    static String kycStatus(User user) {
        String status = user.getAccountStatus();
        if (status == null || status.isBlank()) {
            return "not_started";
        }
        switch (status.trim().toUpperCase()) {
            case "SUBMITTED":
            case "APPROVAL_PENDING":
                return "pending";
            case "APPROVED":
            case "ACTIVE":
                return "approved";
            case "REJECTED":
                return "rejected";
            case "ACTION_REQUIRED":
                return "action_required";
            default:
                return "other";
        }
    }

    private String toJson(Map<String, Object> value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }

    private <T> T safely(java.util.function.Supplier<T> supplier, T fallback) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            logger.warn("analytics: person property lookup failed; sending the safe default", e);
            return fallback;
        }
    }
}
