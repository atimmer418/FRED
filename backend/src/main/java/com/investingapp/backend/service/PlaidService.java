// src/main/java/com/investingapp/backend/service/PlaidService.java
package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.EncryptionService;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.util.EasternTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PessimisticLockException;
import org.springframework.dao.PessimisticLockingFailureException;
// Import for frequency enum
import com.plaid.client.model.*;
import com.plaid.client.request.PlaidApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional; // Import Transactional
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.Executor;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime; // For expiry
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
// Removed UUID, ConcurrentHashMap, TimeUnit as they are no longer needed for this part

import java.util.Collections;
import java.util.stream.Collectors;


@Service
public class PlaidService {

    private static final Logger logger = LoggerFactory.getLogger(PlaidService.class);

    @Autowired
    private PlaidApi plaidApi;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private InvestmentScheduleRepository investmentScheduleRepository;

    @Value("${plaid.client.name}")
    private String plaidClientName;

    @Value("${plaid.webhook.url}")
    private String plaidWebhookUrl;

    @Value("${plaid.redirect.uri}")
    private String plaidRedirectUri;

    @Value("${plaid.link.customization}")
    private String plaidLinkCustomization;

    /** Which kind of Link session a token opens. */
    public enum LinkMode {
        LINK, UPDATE;

        /** Request-body value to mode: null or blank is LINK; only link and update are accepted. */
        public static LinkMode fromRequest(String raw) {
            if (raw == null || raw.isBlank()) {
                return LINK;
            }
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "link" -> LINK;
                case "update" -> UPDATE;
                default -> throw new IllegalArgumentException("mode must be \"link\" or \"update\"");
            };
        }
    }

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private AccountStatusService accountStatusService;

    @Autowired
    private AnalyticsOutboxService analyticsOutboxService;

    @Autowired
    private PlaidItemLifecycleService plaidItemLifecycleService;

    /** Bounded Plaid pool (SchedulingConfig.plaidExecutor); the TaskScheduler is also an Executor, hence the qualifier. */
    @Autowired
    @Qualifier("plaidExecutor")
    private Executor plaidExecutor;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Spec 2.2 and 2.3: every token carries redirect_uri, webhook and the DTM customization name.
     * LINK asks for auth + identity and collects the Transactions consent; UPDATE reopens the stored
     * Item (no products) and asks for that consent only while the tri-state flag is not yet true.
     */
    public LinkTokenCreateResponse createLinkToken(User user, LinkMode mode)
            throws IOException, UpdateModeUnavailableException {
        logger.info("Creating Plaid Link token (mode={}) for user ID: {}", mode, user.getId());
        LinkTokenCreateRequest request = new LinkTokenCreateRequest()
                .user(new LinkTokenCreateRequestUser().clientUserId(String.valueOf(user.getId())))
                .clientName(plaidClientName)
                .countryCodes(List.of(CountryCode.US))
                .language("en")
                .webhook(plaidWebhookUrl)
                .redirectUri(plaidRedirectUri)
                .linkCustomizationName(plaidLinkCustomization);

        if (mode == LinkMode.UPDATE) {
            if (!isPresent(user.getPlaidAccessToken())) {
                throw new UpdateModeUnavailableException("No Plaid Item on file to update");
            }
            request.accessToken(encryptionService.decrypt(user.getPlaidAccessToken()));
            if (!Boolean.TRUE.equals(user.getPlaidTransactionsConsented())) {
                request.additionalConsentedProducts(List.of(Products.TRANSACTIONS));
            }
        } else {
            request.products(List.of(Products.AUTH, Products.IDENTITY))
                    .additionalConsentedProducts(List.of(Products.TRANSACTIONS));
        }

        Response<LinkTokenCreateResponse> response = plaidApi.linkTokenCreate(request).execute();
        if (!response.isSuccessful() || response.body() == null) {
            String errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
            logger.error("Plaid Link Token creation failed (mode={}): {} - {}", mode, response.code(), errorBody);
            if (mode == LinkMode.UPDATE) {
                throw new UpdateModeUnavailableException("Plaid refused an update-mode token: " + errorBody);
            }
            throw new IOException("Plaid Link Token creation failed: " + errorBody);
        }
        return response.body();
    }

    // exchangePublicTokenAndLinkUser method - enhanced to store account details
    @Transactional // Add transactional if multiple DB operations
    public ItemPublicTokenExchangeResponse exchangePublicTokenAndLinkUser(String publicToken, User appUser) throws IOException {
        logger.info("Exchanging public token for user ID: {}", appUser.getId());

        // Serialize per user: SELECT ... FOR UPDATE NOWAIT fails fast instead of blocking, because
        // this lock is held across outbound Plaid HTTP calls below; a contended second exchange
        // must surface the designed 409 LINK_IN_PROGRESS, not wait out the DB's lock timeout and
        // then 500. The controller's instance is usually already managed by the request's
        // EntityManager (open-in-view is on), and Hibernate upgrades the lock on a managed instance
        // without re-reading it, so refresh after the lock to see what the other exchange committed.
        try {
            appUser = userRepository.findByIdForUpdateNoWait(appUser.getId()).orElseThrow();
        } catch (PessimisticLockingFailureException | LockTimeoutException | PessimisticLockException e) {
            logger.info("exchangePublicTokenAndLinkUser: user {} row is locked by another exchange: {}",
                    appUser.getId(), e.getMessage());
            throw new LinkInProgressException();
        }
        entityManager.refresh(appUser);
        LocalDateTime linkedAt = EasternTime.now();
        if (appUser.getPlaidItemLinkedAt() != null
                && appUser.getPlaidItemLinkedAt().isAfter(linkedAt.minusSeconds(60))) {
            throw new LinkInProgressException();
        }

        // Read BEFORE anything is overwritten. One endpoint serves the first link, a relink after
        // Plaid revoked the item, and a deliberate bank change; once the new token is stored the
        // three are indistinguishable, so this boolean is the only thing that separates them.
        boolean isFirstLink = !hasExistingPlaidLink(appUser);

        ItemPublicTokenExchangeRequest request = new ItemPublicTokenExchangeRequest().publicToken(publicToken);
        Response<ItemPublicTokenExchangeResponse> response = plaidApi.itemPublicTokenExchange(request).execute();

        if (!response.isSuccessful() || response.body() == null) {
            String errorBody = response.errorBody() != null ? response.errorBody().string() : "Unknown error";
            logger.error("Plaid Public Token exchange failed for user {}: {} - {}", appUser.getId(), response.code(), errorBody);
            throw new IOException("Plaid Public Token exchange failed: " + errorBody);
        }

        ItemPublicTokenExchangeResponse exchangeResponse = response.body();
        String rawAccessToken = exchangeResponse.getAccessToken(); // Raw token from Plaid

        // Spec 2.4 step 1, before the new token overwrites the old one: queue the old Item for
        // /item/remove (Plaid bills per Item until then) and remember which Alpaca relationship
        // belonged to the old bank so AchRelationshipService.adopt refuses to re-adopt it.
        if (isPresent(appUser.getPlaidAccessToken())) {
            plaidItemLifecycleService.enqueueRemoval(appUser);
        }
        if (isPresent(appUser.getAlpacaAchRelationshipId())) {
            appUser.setPreviousAchRelationshipId(appUser.getAlpacaAchRelationshipId());
        } else {
            // Legacy split row: the deleted /update-ach-request-id endpoint could write the
            // schedule's achRequestId while saveAchRelationshipToUser silently swallowed a failure
            // to write alpacaAchRelationshipId on the user. Without this fallback adopt's
            // firstEverAdoption recognizer (previousAchRelationshipId null AND achRequestId null,
            // both true here once resetForBankChange runs) would mistake this bank change for a
            // first adoption and unpause a schedule the user may have paused themselves.
            previousRelationshipIdFromSchedule(appUser).ifPresent(appUser::setPreviousAchRelationshipId);
        }

        String encryptedAccessToken = encryptionService.encrypt(rawAccessToken);

        // Store basic Plaid info
        appUser.setPlaidAccessToken(encryptedAccessToken);
        appUser.setPlaidItemId(exchangeResponse.getItemId());
        appUser.setPlaidItemLinkedAt(linkedAt);
        appUser.setPlaidItemStatus(PlaidItemStatus.OK);
        appUser.setPlaidItemErrorCode(null);
        appUser.setPlaidTransactionsConsented(null); // unknown until the after-commit /item/get answers
        appUser.setPlaidAccountMask(null); // stays null when enrichment fails; adopt then skips the mask check

        // Update progress in UserProgress entity
        if (appUser.getUserProgress() != null) {
            appUser.getUserProgress().setLinkPlaidCompleted(true);
        }
        
        try {
            // Fetch and store account details and institution information
            populateAccountAndInstitutionDetails(rawAccessToken, appUser);
            logger.info("Successfully populated account and institution details for user ID: {}", appUser.getId());
        } catch (Exception e) {
            logger.error("Failed to populate account details for user {}: {}", appUser.getId(), e.getMessage());
            // Don't fail the whole process if this fails - user can still use the app
        }
        
        // Clear stale ACH data — the new bank account requires a fresh ACH relationship
        appUser.setAlpacaAchRelationshipId(null);
        appUser.setAlpacaAchStatus(null);

        userRepository.save(appUser);
        logger.info("Plaid item linked to user ID: {} with account details", appUser.getId());

        // Inside this method's own @Transactional boundary, which is also the boundary the token
        // above is saved in: the event and the link commit together or neither does.
        //
        // Nothing here says WHICH bank. No institution name, no item id, no access token, no
        // account id, name, type or mask. "A bank was linked, and it was/was not their first".
        Map<String, Object> analyticsProperties = new LinkedHashMap<>();
        analyticsProperties.put("is_first_link", isFirstLink);
        analyticsOutboxService.record(ServerAnalyticsEvent.PLAID_LINKED, appUser, analyticsProperties);

        // Reset investment schedule and trigger ACH creation if account is already ACTIVE
        accountStatusService.handleBankAccountChange(appUser);

        // Spec 2.4 step 6: /item/get runs after commit on the Plaid pool, never inside this
        // transaction. With no synchronization active (plain unit tests) it goes to the pool now.
        Long userId = appUser.getId();
        Runnable consentRead = () -> plaidItemLifecycleService.recordConsentAfterLink(userId, rawAccessToken);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    plaidExecutor.execute(consentRead);
                }
            });
        } else {
            plaidExecutor.execute(consentRead);
        }

        return exchangeResponse;
    }
    
    /**
     * Whether this user already had a Plaid link before the current exchange.
     *
     * Blank counts as absent: an empty string is not a link, and treating one as a link would
     * report a genuine first link as a relink.
     */
    private static boolean hasExistingPlaidLink(User appUser) {
        return isPresent(appUser.getPlaidAccessToken()) || isPresent(appUser.getPlaidItemId());
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Fallback for a legacy split row (achRequestId on the schedule, no alpacaAchRelationshipId on
     * the user): the encrypted relationship id to remember as "previous", or empty when the
     * schedule carries nothing to protect.
     */
    private Optional<String> previousRelationshipIdFromSchedule(User appUser) {
        return investmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(appUser)
                .map(InvestmentSchedule::getAchRequestId)
                .filter(id -> id != null && !id.isBlank())
                .map(encryptionService::encrypt);
    }

    /**
     * Fetch and populate account details and institution information
     */
    private void populateAccountAndInstitutionDetails(String accessToken, User appUser) throws IOException {
        // 1. Get accounts information
        AccountsGetRequest accountsRequest = new AccountsGetRequest().accessToken(accessToken);
        Response<AccountsGetResponse> accountsResponse = plaidApi.accountsGet(accountsRequest).execute();
        
        if (!accountsResponse.isSuccessful() || accountsResponse.body() == null) {
            throw new IOException("Failed to fetch account details from Plaid");
        }
        
        List<AccountBase> accounts = accountsResponse.body().getAccounts();
        if (!accounts.isEmpty()) {
            // Use the first checking account, or first account if no checking account
            AccountBase primaryAccount = accounts.stream()
                .filter(acc -> acc.getSubtype() != null && 
                              acc.getSubtype().toString().toLowerCase().contains("checking"))
                .findFirst()
                .orElse(accounts.get(0));
            
            // Store account details
            appUser.setPlaidAccountId(primaryAccount.getAccountId());
            appUser.setPlaidAccountMask(primaryAccount.getMask());
            appUser.setPlaidAccountName(primaryAccount.getName());
            appUser.setPlaidAccountType(primaryAccount.getType() != null ? 
                primaryAccount.getType().toString() : null);
            appUser.setPlaidAccountSubtype(primaryAccount.getSubtype() != null ? 
                primaryAccount.getSubtype().toString() : null);
            
            logger.info("Set primary account for user {}: {} ({})", 
                appUser.getId(), primaryAccount.getName(), primaryAccount.getSubtype());
        }
        
        // 2. Get institution information
        try {
            InstitutionsGetByIdRequest institutionRequest = new InstitutionsGetByIdRequest()
                .institutionId(accountsResponse.body().getItem().getInstitutionId())
                .countryCodes(Arrays.asList(CountryCode.US));
            
            Response<InstitutionsGetByIdResponse> institutionResponse = 
                plaidApi.institutionsGetById(institutionRequest).execute();
            
            if (institutionResponse.isSuccessful() && institutionResponse.body() != null) {
                Institution institution = institutionResponse.body().getInstitution();
                appUser.setPlaidInstitutionName(institution.getName());
                
                logger.info("Set institution for user {}: {}", 
                    appUser.getId(), institution.getName());
            }
        } catch (Exception e) {
            logger.warn("Could not fetch institution details for user {}: {}", 
                appUser.getId(), e.getMessage());
            // Don't fail if institution lookup fails
        }
    }
}