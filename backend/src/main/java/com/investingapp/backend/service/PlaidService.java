// src/main/java/com/investingapp/backend/service/PlaidService.java
package com.investingapp.backend.service;

import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.EncryptionService;
import com.investingapp.backend.util.ServerAnalyticsEvent;
// Import for frequency enum
import com.plaid.client.model.*;
import com.plaid.client.request.PlaidApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional; // Import Transactional
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

        String encryptedAccessToken = encryptionService.encrypt(rawAccessToken);

        // Store basic Plaid info
        appUser.setPlaidAccessToken(encryptedAccessToken);
        appUser.setPlaidItemId(exchangeResponse.getItemId());
        
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