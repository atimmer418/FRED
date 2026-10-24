package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.AnalyticsBands;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AccountStatusService {

    private static final Logger logger = LoggerFactory.getLogger(AccountStatusService.class);

    private final UserRepository userRepository;
    private final InvestmentScheduleRepository investmentScheduleRepository;
    private final AlpacaApiService alpacaApiService;
    private final EncryptionService encryptionService;
    private final EmailService emailService;
    private final PlaidToAlpacaService plaidToAlpacaService;
    private final InvestmentScheduleService investmentScheduleService;
    private final AnalyticsOutboxService analyticsOutboxService;
    private final AchRelationshipService achRelationshipService;
    private final ObjectMapper objectMapper;

    public AccountStatusService(
            UserRepository userRepository,
            InvestmentScheduleRepository investmentScheduleRepository,
            AlpacaApiService alpacaApiService,
            EncryptionService encryptionService,
            EmailService emailService,
            PlaidToAlpacaService plaidToAlpacaService,
            InvestmentScheduleService investmentScheduleService,
            AnalyticsOutboxService analyticsOutboxService,
            AchRelationshipService achRelationshipService) {
        this.userRepository = userRepository;
        this.investmentScheduleRepository = investmentScheduleRepository;
        this.alpacaApiService = alpacaApiService;
        this.encryptionService = encryptionService;
        this.emailService = emailService;
        this.plaidToAlpacaService = plaidToAlpacaService;
        this.investmentScheduleService = investmentScheduleService;
        this.analyticsOutboxService = analyticsOutboxService;
        this.achRelationshipService = achRelationshipService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Poll Alpaca for the current status of all users whose account is in a
     * non-terminal state (i.e., not ACTIVE or REJECTED). Called by the scheduler.
     */
    @Transactional
    public void checkAndUpdateAccountStatuses() {
        List<User> pendingUsers = userRepository.findUsersWithPendingAccountStatus();
        logger.info("Account status poll: {} user(s) with pending status", pendingUsers.size());

        for (User user : pendingUsers) {
            try {
                if (user.getAlpacaAccountId() == null) {
                    logger.warn("User {} has accountStatus={} but no Alpaca account ID — skipping",
                            user.getEmail(), user.getAccountStatus());
                    continue;
                }

                String alpacaAccountId = encryptionService.decrypt(user.getAlpacaAccountId());
                String rawResponse = alpacaApiService.getAccountStatus(alpacaAccountId);
                JsonNode responseNode = objectMapper.readTree(rawResponse);

                if (!responseNode.has("status")) {
                    logger.warn("No 'status' field in Alpaca response for account {} (user {})",
                            alpacaAccountId, user.getEmail());
                    continue;
                }

                String alpacaStatus = responseNode.get("status").asText().toUpperCase();

                if (alpacaStatus.equals(user.getAccountStatus())) {
                    // No change — nothing to do
                    continue;
                }

                logger.info("Account status changed for user {}: {} -> {}",
                        user.getEmail(), user.getAccountStatus(), alpacaStatus);

                switch (alpacaStatus) {
                    case "ACTIVE" -> handleActive(user);
                    case "REJECTED" -> handleRejected(user);
                    case "ACTION_REQUIRED" -> handleActionRequired(user);
                    default -> {
                        // Intermediate states (APPROVAL_PENDING, SUBMITTED, etc.) — just update
                        user.setAccountStatus(alpacaStatus);
                        userRepository.save(user);
                        logger.info("Updated accountStatus to {} for user {}", alpacaStatus, user.getEmail());
                    }
                }

            } catch (Exception e) {
                logger.error("Error checking account status for user {}: {}", user.getEmail(), e.getMessage(), e);
            }
        }
    }

    /**
     * Handle an account transitioning to ACTIVE.
     * Updates status in DB, then attempts ACH relationship creation if Plaid data is available.
     */
    @Transactional
    public void handleActive(User user) {
        user.setAccountStatus("ACTIVE");
        userRepository.save(user);
        logger.info("Account ACTIVE for user {} — investment schedule will now be eligible for execution",
                user.getEmail());
        // TODO: push notification to frontend — account is now active

        recordKycDecision(user, "ACTIVE");

        attemptAchCreation(user);
    }

    /**
     * Handle an account that has been REJECTED.
     *
     * Records the decision, cancels the schedule, emails the user, and KEEPS the user row with
     * status REJECTED. It does not delete anyone.
     *
     * This used to hard-delete the applicant. That was wrong twice over. The discovery's decision
     * 8 asked for the row to be kept and marked, for the recordkeeping an RIA is expected to be
     * able to produce; and the standing rule is that no client record is hard-deleted without
     * being asked first. A rejection is also the one deletion that cannot be undone by asking the
     * user, because they never got an account to ask about.
     *
     * What the kept row changes, beyond the obvious: rejected users are now present in `users`, so
     * they appear in the funnel's `signed_up` and the approval rate stops flattering itself. That
     * only holds from here forward. Anyone rejected before this shipped is already gone, which is
     * why FREDdocs/metrics.md still says not to read an approval rate from v_activation_funnel for
     * an early cohort.
     *
     * `findUsersWithPendingAccountStatus` excludes REJECTED, so the poller stops re-deciding this
     * user and the event cannot fire twice. Deleting a rejected applicant is still possible, by
     * the admin verb, which is where a decision to delete belongs.
     *
     * The SSN column is deliberately untouched here. How long a rejected applicant's identity data
     * may be kept is a question for counsel, not for this code, and it is open as of 2026-10-24.
     */
    @Transactional
    public void handleRejected(User user) {
        logger.warn("Account REJECTED for user {}: sending rejection email and marking the record "
                + "REJECTED", user.getEmail());

        // Recorded first, and in this same transaction as the status write, so the event and the
        // state it describes land together or not at all.
        recordKycDecision(user, "REJECTED");

        // Notify the user of the decision.
        emailService.sendEmail(
                user.getEmail(),
                "Your FRED account application was not approved",
                "We're sorry, but we were unable to approve your FRED brokerage account application at this time. "
                + "If you have questions, please contact our support team.");

        // The broker has rejected the application, so the schedule stops. Recorded while the
        // schedule still exists to be asked about.
        recordScheduleCancelled(user, ServerAnalyticsEvent.INITIATOR_SYSTEM,
                ServerAnalyticsEvent.REASON_KYC_REJECTED);
        holdSchedulesForRejection(user);

        user.setAccountStatus("REJECTED");
        userRepository.save(user);
    }

    /**
     * Stops the rejected applicant's schedule, explicitly.
     *
     * This exists because the row now survives. While a rejection deleted the user, it deleted the
     * schedule with them, and `schedule_cancelled` described something that had really gone. Now
     * the row stays, so without this the event would say cancelled while the table said otherwise.
     *
     * It could not actually have invested: `isReadyForInvestment` requires an ACH relationship, and
     * a rejected application never got one, because ACH is only created for an ACTIVE account and
     * an ACTIVE account is never re-polled. That is an invariant two classes away, which is a thin
     * thing to rest money movement on. A blocking hold says it here, where the rejection is known.
     *
     * A HOLD rather than a status change, because status and holds are orthogonal: the status is
     * the user and bank lifecycle, and this is the system stopping a schedule the user never
     * paused. MANUAL_REVIEW is the honest reason. A person decides what happens to a rejected
     * applicant's plan; nothing here should quietly restart it, and a resume must not either.
     */
    private void holdSchedulesForRejection(User user) {
        List<InvestmentSchedule> schedules = investmentScheduleRepository
                .findByUserOrderByCreatedAtDesc(user);
        for (InvestmentSchedule schedule : schedules) {
            schedule.setHoldReason(HoldReason.MANUAL_REVIEW);
            investmentScheduleRepository.save(schedule);
        }
    }

    /**
     * Handle an account that requires additional documentation (ACTION_REQUIRED).
     * Updates status in DB and logs. The frontend polls /api/alpaca/my-account-status to detect this.
     */
    @Transactional
    public void handleActionRequired(User user) {
        user.setAccountStatus("ACTION_REQUIRED");
        userRepository.save(user);
        logger.info("Account ACTION_REQUIRED for user {} — user must upload additional documents",
                user.getEmail());

        recordKycDecision(user, "ACTION_REQUIRED");
        // TODO: push notification to frontend — action required, prompt user to upload documents
    }

    /**
     * Retry ACH setup for ACTIVE users who have Plaid data but no ACH relationship yet.
     * This covers the case where a user's account became ACTIVE before they linked their
     * bank account via Plaid. Called by the ACH retry scheduler.
     */
    @Transactional
    public void setupPendingAchRelationships() {
        List<User> users = userRepository.findActiveUsersNeedingAchSetup();
        logger.info("ACH setup retry: {} ACTIVE user(s) still need ACH relationship", users.size());

        for (User user : users) {
            try {
                attemptAchCreation(user);
            } catch (Exception e) {
                logger.error("Error during ACH setup retry for user {}: {}", user.getEmail(), e.getMessage(), e);
            }
        }
    }

    /**
     * Bank change (spec 7.8), in order: record the relationship being replaced, hold the schedule,
     * cancel the old relationships, and leave creation to the 15-minute poll. The exchange that calls
     * this already copied previous_ach_relationship_id and nulled alpacaAchRelationshipId (T09), so the
     * copy below is a no-op there and only matters for any other caller.
     */
    @Transactional
    public void handleBankAccountChange(User user) {
        if (user.getAlpacaAchRelationshipId() != null) {
            user.setPreviousAchRelationshipId(user.getAlpacaAchRelationshipId());
        } else if (user.getPreviousAchRelationshipId() == null) {
            // Legacy split row: the deleted /update-ach-request-id endpoint could write the
            // schedule's achRequestId while saveAchRelationshipToUser silently swallowed a failure
            // to write alpacaAchRelationshipId on the user. Without this fallback adopt's
            // firstEverAdoption recognizer (previousAchRelationshipId null AND achRequestId null,
            // both true here once resetForBankChange runs) would mistake this bank change for a
            // first adoption and unpause a schedule the user may have paused themselves. Guarded on
            // previousAchRelationshipId already being null so this stays a no-op on the exchange
            // path, where PlaidService's own capture (direct copy or this same schedule fallback)
            // already ran before calling here.
            investmentScheduleService.getCurrentSchedule(user)
                    .map(InvestmentSchedule::getAchRequestId)
                    .filter(id -> id != null && !id.isBlank())
                    .ifPresent(id -> user.setPreviousAchRelationshipId(encryptionService.encrypt(id)));
        }
        investmentScheduleService.resetForBankChange(user);
        if (!achRelationshipService.cancelAllActive(user)) {
            logger.warn("Bank change for user {}: ACH cancellation deferred while a transfer is in flight; the poll retries", user.getEmail());
        }
    }

    /**
     * Precondition guards (Plaid data, Alpaca account, legal name), then
     * AchRelationshipService.ensureRelationship, which is the only path that creates or adopts.
     */
    @Transactional
    public void attemptAchCreation(User user) {
        if (user.getPlaidAccessToken() == null || user.getPlaidAccountId() == null) {
            logger.info("Plaid data not yet available for user {} — ACH will be created by retry scheduler once bank is linked",
                    user.getEmail());
            return;
        }

        if (user.getAlpacaAccountId() == null) {
            logger.warn("Cannot create ACH for user {}: no Alpaca account ID on record", user.getEmail());
            return;
        }

        String firstName = user.getFirstName();
        String lastName = user.getLastName();
        if (firstName == null || firstName.isBlank() || lastName == null || lastName.isBlank()) {
            logger.warn("Cannot create ACH for user {}: first/last name not set — will retry after name is available",
                    user.getEmail());
            return;
        }

        achRelationshipService.ensureRelationship(user);
    }

    // --- Analytics ------------------------------------------------------------------------------

    /**
     * Records kyc_decided for a broker decision, in whatever transaction the caller already has.
     *
     * Every caller is one of the three decision handlers above. Each is annotated
     * {@code @Transactional}, and on the poller path they are self-invoked from
     * checkAndUpdateAccountStatuses, which is itself annotated. Either way a transaction is active,
     * so the event commits with the state change it describes. AnalyticsOutboxService refuses to
     * write without one, by design.
     */
    private void recordKycDecision(User user, String brokerStatus) {
        String outcome = kycOutcome(brokerStatus);
        if (outcome == null) {
            // Only reachable if a future handler passes a status this mapping does not know. A
            // missing event is better than an outcome value nobody can interpret.
            logger.error("analytics: not recording kyc_decided for unmapped broker status '{}'",
                    brokerStatus);
            return;
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("outcome", outcome);
        // Banded, never a raw date or a day count: how long the broker took, measured from our own
        // submission stamp. A null stamp (a user who submitted before the column existed) bands to
        // "unknown", which is the honest answer rather than zero.
        properties.put("days_to_decision_band",
                AnalyticsBands.daysBetween(user.getKycSubmittedAt(), LocalDateTime.now()));

        analyticsOutboxService.record(ServerAnalyticsEvent.KYC_DECIDED, user, properties);
    }

    /**
     * Records schedule_cancelled, in whatever transaction the caller already has.
     *
     * <p>Only a REAL transition is recorded. The schedule's existence is read BEFORE it is changed,
     * and a user with no schedule emits nothing: the deletion below removes no schedule for them,
     * and an event there would invent a cancellation for every rejected user who never set a
     * schedule up. These
     * events exist to count churn, so a phantom would inflate the one number they are for.
     *
     * <p>Called before the deletes for the same reason as the decision above: the outbox copies the
     * user's id and public_id, and in a moment there will be neither to copy. The row has no foreign
     * key to users and denormalises public_id, so it outlives the schedule and the person. When the
     * deletes are deferred (money still in flight) the row is parked as REJECTED instead, and the
     * cancellation still stands: a rejected application never invests again.
     *
     * <p>Two properties and nothing else, in this order: who caused it and why. No amount, no
     * frequency, no bank.
     */
    private void recordScheduleCancelled(User user, String initiator, String reason) {
        if (!investmentScheduleRepository.existsByUser(user)) {
            return;
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("initiator", initiator);
        properties.put("reason", reason);

        analyticsOutboxService.record(ServerAnalyticsEvent.SCHEDULE_CANCELLED, user, properties);
    }

    /**
     * The broker's statuses narrowed to the three outcomes the funnel asks about, or null for a
     * status that is not a decision at all.
     *
     * APPROVED and ACTIVE are one outcome: Alpaca can report either on the way to a usable account,
     * and "was this person approved?" does not distinguish them. Only ACTIVE reaches a handler
     * today; APPROVED is mapped so that stays true if one is ever added for it.
     */
    static String kycOutcome(String brokerStatus) {
        if (brokerStatus == null) {
            return null;
        }
        switch (brokerStatus.trim().toUpperCase()) {
            case "APPROVED":
            case "ACTIVE":
                return "approved";
            case "REJECTED":
                return "rejected";
            case "ACTION_REQUIRED":
                return "needs_more_info";
            default:
                return null;
        }
    }
}
