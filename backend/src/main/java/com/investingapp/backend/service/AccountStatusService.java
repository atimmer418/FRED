package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
     * Sends a rejection email, deletes the user's InvestmentSchedule, then hard-deletes the user.
     * All cascade-deleted entities (passkeys, progress, etc.) are handled by JPA cascades.
     */
    @Transactional
    public void handleRejected(User user) {
        logger.warn("Account REJECTED for user {} — sending rejection email and deleting user record",
                user.getEmail());

        // Record the decision FIRST, while the user still has an id and a public_id to copy.
        // Everything below runs in this same transaction, including the hard delete, so the event
        // and the deletion land together or not at all. The outbox row has no foreign key to users
        // and denormalises public_id, so it outlives the row it describes.
        recordKycDecision(user, "REJECTED");

        // Notify the user before deleting their record
        emailService.sendEmail(
                user.getEmail(),
                "Your FRED account application was not approved",
                "We're sorry, but we were unable to approve your FRED brokerage account application at this time. "
                + "If you have questions, please contact our support team.");

        // Delete InvestmentSchedule first (not covered by User cascades)
        investmentScheduleRepository.deleteAllByUser(user);
        logger.info("Deleted InvestmentSchedule(s) for rejected user {}", user.getEmail());

        // Hard delete the user — all @OneToMany cascade=ALL entities deleted by JPA
        userRepository.delete(user);
        logger.info("Hard-deleted user record for rejected user {}", user.getEmail());
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
