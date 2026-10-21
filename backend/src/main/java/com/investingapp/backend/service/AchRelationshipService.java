package com.investingapp.backend.service;

import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.EasternTime;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single writer of User.alpacaAchRelationshipId, User.alpacaAchStatus and
 * InvestmentSchedule.achRequestId (spec 7.8). Every Alpaca call is wrapped so that a broker outage
 * never escapes as an exception: cancelAllActive runs inside the Plaid exchange transaction
 * (PlaidService.exchangePublicTokenAndLinkUser calls AccountStatusService.handleBankAccountChange
 * with no try/catch) and a thrown RuntimeException there would roll back the user's bank link.
 */
@Service
public class AchRelationshipService {

    private static final Logger logger = LoggerFactory.getLogger(AchRelationshipService.class);

    /** Alpaca relationship statuses that still move money. */
    static final List<String> ACTIVE_STATUSES = List.of("QUEUED", "APPROVED", "PENDING");
    /** The statuses the 15-minute poll looks at before it dares to create a new relationship. */
    static final List<String> POLL_STATUSES = List.of("QUEUED", "APPROVED", "PENDING", "CANCEL_REQUESTED");
    static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";

    public enum AdoptResult { ADOPTED, REFUSED_PREVIOUS_ID, REFUSED_MASK_MISMATCH, REFUSED_PREDATES_LINK }

    private final UserRepository userRepository;
    private final InvestmentScheduleRepository scheduleRepository;
    private final InvestmentExecutionRepository executionRepository;
    private final AlpacaApiService alpacaApiService;
    private final EncryptionService encryptionService;
    private final PlaidToAlpacaService plaidToAlpacaService;
    private final AnalyticsOutboxService analyticsOutboxService;

    public AchRelationshipService(UserRepository userRepository,
            InvestmentScheduleRepository scheduleRepository,
            InvestmentExecutionRepository executionRepository,
            AlpacaApiService alpacaApiService,
            EncryptionService encryptionService,
            PlaidToAlpacaService plaidToAlpacaService,
            AnalyticsOutboxService analyticsOutboxService) {
        this.userRepository = userRepository;
        this.scheduleRepository = scheduleRepository;
        this.executionRepository = executionRepository;
        this.alpacaApiService = alpacaApiService;
        this.encryptionService = encryptionService;
        this.plaidToAlpacaService = plaidToAlpacaService;
        this.analyticsOutboxService = analyticsOutboxService;
    }

    /**
     * Adopt one relationship as the user's funding source. Refuses (hold_reason=MANUAL_REVIEW plus an
     * alert line) the previous relationship, a mask that does not match the linked Plaid account, and
     * anything created before the current Plaid Item was linked. Clears BANK_CHANGE for MANUAL.
     * Writes is_paused in exactly one case (spec decision 23): the first-ever adoption for this user,
     * recognised by schedule.achRequestId null AND users.previous_ach_relationship_id null, with no
     * hold on the schedule. A bank change (previous id set) never unpauses; a user's pause is never
     * cleared.
     *
     * <p>This is also where the system resume is recorded: see recordScheduleTransition below.
     */
    @Transactional
    public AdoptResult adopt(User user, AchRelationshipSummary rel) {
        Optional<InvestmentSchedule> scheduleOpt = scheduleRepository.findTopByUserOrderByCreatedAtDesc(user);
        String previousId = encryptionService.decrypt(user.getPreviousAchRelationshipId());

        if (previousId != null && previousId.equals(rel.id())) {
            return refuse(user, scheduleOpt, rel, AdoptResult.REFUSED_PREVIOUS_ID);
        }
        if (!maskMatches(rel.bankAccountNumber(), user.getPlaidAccountMask())) {
            return refuse(user, scheduleOpt, rel, AdoptResult.REFUSED_MASK_MISMATCH);
        }
        if (predatesLink(rel.createdAt(), user.getPlaidItemLinkedAt())) {
            return refuse(user, scheduleOpt, rel, AdoptResult.REFUSED_PREDATES_LINK);
        }

        boolean firstEverAdoption = previousId == null
                && scheduleOpt.map(s -> s.getAchRequestId() == null).orElse(false);

        user.setAlpacaAchRelationshipId(encryptionService.encrypt(rel.id()));
        user.setAlpacaAchStatus(rel.status());
        userRepository.save(user);

        scheduleOpt.ifPresent(schedule -> {
            // Read the stopped state BEFORE the writes below: only a schedule this call actually
            // started again may emit a resume.
            boolean stoppedBefore = isStopped(schedule);

            schedule.setAchRequestId(rel.id());
            if (schedule.getMode() == ScheduleMode.MANUAL && schedule.getHoldReason() == HoldReason.BANK_CHANGE) {
                schedule.setHoldReason(null);
            }
            if (firstEverAdoption && schedule.getHoldReason() == null && Boolean.TRUE.equals(schedule.getIsPaused())) {
                schedule.setIsPaused(false);
            }
            scheduleRepository.save(schedule);

            // Analytics: a SYSTEM resume, on a real transition only, at most once per adopt call.
            // Both halves must hold. A schedule that was already running resumed nothing (the ACH
            // id was merely rewritten), and one whose hold cleared while the user's own pause
            // stands is still stopped, so neither emits.
            if (stoppedBefore && !isStopped(schedule)) {
                recordScheduleTransition(user, ServerAnalyticsEvent.SCHEDULE_RESUMED,
                        ServerAnalyticsEvent.INITIATOR_SYSTEM, ServerAnalyticsEvent.REASON_ACH_CONFIRMED);
            }
        });

        logger.info("ACH relationship {} adopted for user {} status={} firstAdoption={}",
                rel.id(), user.getId(), rel.status(), firstEverAdoption);
        return AdoptResult.ADOPTED;
    }

    /** Stopped is either stop: the system's hold or the user's pause. */
    private static boolean isStopped(InvestmentSchedule schedule) {
        return schedule.getHoldReason() != null || Boolean.TRUE.equals(schedule.getIsPaused());
    }

    /**
     * Records one schedule state transition, in whatever transaction the caller already has.
     *
     * <p>Transaction boundary: {@link #adopt} is {@code @Transactional} and its only production
     * caller is createAndAdopt, reached from ensureRelationship, which is {@code @Transactional}
     * too and is always called from outside this bean (AccountStatusService.attemptAchCreation,
     * itself transactional, is the only caller). So a REQUIRED transaction is always active and
     * nothing was added or widened for analytics. {@link AnalyticsOutboxService} refuses to write
     * without one, by design, so the event and the schedule row land together or not at all.
     *
     * <p>Exactly two properties, in this order: who caused it and why. No amount, no frequency, no
     * bank, no id, no dates. This mirrors InvestmentScheduleService's helper of the same name; the
     * resume that used to live there, on the deleted updateWithAchRequestId, lives here now because
     * this class is the single writer of the ACH fields.
     */
    private void recordScheduleTransition(User user, String event, String initiator, String reason) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("initiator", initiator);
        props.put("reason", reason);
        analyticsOutboxService.record(event, user, props);
    }

    private AdoptResult refuse(User user, Optional<InvestmentSchedule> scheduleOpt, AchRelationshipSummary rel, AdoptResult reason) {
        logger.error("alert.ach.adopt_refused user={} reason={} rel={}", user.getId(), reason, rel.id());
        scheduleOpt.ifPresent(schedule -> {
            schedule.setHoldReason(HoldReason.MANUAL_REVIEW);
            scheduleRepository.save(schedule);
        });
        return reason;
    }

    /**
     * Bank change step 3 (spec 7.8): DELETE every QUEUED, APPROVED or PENDING relationship and mark the
     * user CANCEL_REQUESTED. Returns false only when the cancel is deferred because a transfer is in
     * flight; the 15-minute poll retries it from ensureRelationship. Never throws on a broker failure.
     */
    @Transactional
    public boolean cancelAllActive(User user) {
        if (executionRepository.existsByUserAndStatus(user, InvestmentExecution.ExecutionStatus.FUNDING_INITIATED)) {
            logger.warn("ach.cancel.deferred user={} reason=FUNDING_INITIATED", user.getId());
            return false;
        }
        String alpacaAccountId = encryptionService.decrypt(user.getAlpacaAccountId());
        if (alpacaAccountId == null) {
            return true;
        }
        List<AchRelationshipSummary> active;
        try {
            active = alpacaApiService.listAchRelationships(alpacaAccountId, ACTIVE_STATUSES);
        } catch (RestClientException | IllegalStateException e) {
            logger.error("alert.ach.cancel_failed user={} stage=list error={}", user.getId(), e.getMessage());
            return true;
        }
        boolean deletedAny = false;
        for (AchRelationshipSummary rel : active) {
            if (alpacaApiService.deleteAchRelationship(alpacaAccountId, rel.id())) {
                deletedAny = true;
            } else {
                logger.error("alert.ach.cancel_failed user={} stage=delete rel={}", user.getId(), rel.id());
            }
        }
        if (deletedAny) {
            // Only when something was actually asked to cancel; an empty list leaves the status alone.
            user.setAlpacaAchStatus(CANCEL_REQUESTED);
            userRepository.save(user);
        }
        return true;
    }

    /**
     * The 15-minute poll (spec 7.8, decision 19). Creates a new relationship only when Alpaca holds
     * nothing in QUEUED, APPROVED, PENDING or CANCEL_REQUESTED. Waits while the previous relationship
     * is still cancelling; retries the cancel when it is still active (a deferred or failed DELETE);
     * alerts when CANCEL_REQUESTED has lasted more than 24 hours or when an unknown relationship sits
     * on the account. Never throws on a broker failure.
     */
    @Transactional
    public void ensureRelationship(User user) {
        String alpacaAccountId = encryptionService.decrypt(user.getAlpacaAccountId());
        if (alpacaAccountId == null) {
            return;
        }
        List<AchRelationshipSummary> existing;
        try {
            existing = alpacaApiService.listAchRelationships(alpacaAccountId, POLL_STATUSES);
        } catch (RestClientException | IllegalStateException e) {
            logger.error("alert.ach.poll_list_failed user={} error={}", user.getId(), e.getMessage());
            return;
        }
        if (existing.isEmpty()) {
            createAndAdopt(user, alpacaAccountId);
            return;
        }
        String previousId = encryptionService.decrypt(user.getPreviousAchRelationshipId());
        boolean onlyPrevious = previousId != null && existing.stream().allMatch(r -> previousId.equals(r.id()));
        if (!onlyPrevious) {
            logger.error("alert.ach.unknown_relationship user={} ids={}", user.getId(),
                    existing.stream().map(AchRelationshipSummary::id).toList());
            return;
        }
        if (!existing.stream().allMatch(r -> CANCEL_REQUESTED.equals(r.status()))) {
            // The bank-change DELETE was deferred (transfer in flight) or refused: retry it from the poll.
            cancelAllActive(user);
            return;
        }
        // The cancel is requested in the same transaction that stamps plaid_item_linked_at (T09), so
        // that stamp is the cancel-requested clock.
        LocalDateTime cancelRequestedAt = user.getPlaidItemLinkedAt();
        if (cancelRequestedAt == null || cancelRequestedAt.isBefore(EasternTime.now().minusHours(24))) {
            logger.error("alert.ach.cancel_stuck user={} rel={}", user.getId(), previousId);
        } else {
            logger.info("ach.poll.waiting user={} rel={} status=CANCEL_REQUESTED", user.getId(), previousId);
        }
    }

    private void createAndAdopt(User user, String alpacaAccountId) {
        Map<String, Object> result = plaidToAlpacaService.createAchRelationshipFromPlaid(
                alpacaAccountId, user.getPlaidAccountId(), user.getFirstName() + " " + user.getLastName(), user);
        Object id = result == null ? null : result.get("id");
        if (id == null || result.containsKey("error")) {
            // Log only the error text: raw_response carries bank details.
            logger.error("alert.ach.create_failed user={} error={}", user.getId(), result == null ? "null result" : result.get("error"));
            return;
        }
        adopt(user, new AchRelationshipSummary(id.toString(), (String) result.get("status"), (String) result.get("created_at"), null));
    }

    /**
     * Plaid's mask is "the last 2 to 4 characters" of the account number and Alpaca may return the
     * number masked (****1234), so compare alphanumerics by suffix. Missing data on either side cannot
     * be checked and passes.
     */
    static boolean maskMatches(String bankAccountNumber, String plaidMask) {
        if (bankAccountNumber == null || plaidMask == null) {
            return true;
        }
        String number = bankAccountNumber.replaceAll("[^0-9A-Za-z]", "").toUpperCase();
        String mask = plaidMask.replaceAll("[^0-9A-Za-z]", "").toUpperCase();
        if (number.isEmpty() || mask.isEmpty()) {
            return true;
        }
        return number.endsWith(mask);
    }

    /** True when Alpaca's created_at (ISO offset, e.g. 2026-10-19T14:00:00Z) is before the Item link, with two minutes of clock-skew tolerance. */
    static boolean predatesLink(String createdAt, LocalDateTime linkedAtEt) {
        if (createdAt == null || linkedAtEt == null) {
            return false;
        }
        try {
            LocalDateTime createdEt = OffsetDateTime.parse(createdAt).atZoneSameInstant(EasternTime.ZONE).toLocalDateTime();
            return createdEt.isBefore(linkedAtEt.minusMinutes(2));
        } catch (DateTimeParseException e) {
            logger.warn("ach.adopt.unparseable_created_at value={}", createdAt);
            return false;
        }
    }
}
