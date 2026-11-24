package com.investingapp.backend.service;

import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.plaid.PlaidCallResult;
import com.investingapp.backend.plaid.PlaidGateway;
import com.investingapp.backend.service.PlaidItemStateService.ItemStateResult;
import com.investingapp.backend.service.PlaidItemStateService.RepairPath;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.plaid.client.model.ItemGetRequest;
import com.plaid.client.model.ItemGetResponse;
import com.plaid.client.model.ItemWebhookUpdateRequest;
import com.plaid.client.model.ItemWebhookUpdateResponse;
import com.plaid.client.model.ItemWithConsentFields;
import com.plaid.client.model.Products;
import com.plaid.client.request.PlaidApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executor;

/** Server half of an update-mode Link (spec 2.3, 8.8). No transaction; writes only through PlaidItemStateService. */
@Service
public class PlaidUpdateModeService {

    public enum UpdateModeResult {
        CONSENT_GRANTED, CONSENT_STILL_MISSING, REPAIRED, NO_CHANGE, STALE_ITEM, PLAID_ERROR
    }

    private static final Logger logger = LoggerFactory.getLogger(PlaidUpdateModeService.class);

    private final PlaidApi plaidApi;
    private final PlaidGateway gateway;
    private final EncryptionService encryptionService;
    private final PlaidItemStateService itemStateService;
    private final PaycheckSyncPipeline pipeline;
    private final Executor plaidExecutor;
    private final String plaidWebhookUrl;

    public PlaidUpdateModeService(PlaidApi plaidApi, PlaidGateway gateway, EncryptionService encryptionService,
            PlaidItemStateService itemStateService, PaycheckSyncPipeline pipeline,
            @Qualifier("plaidExecutor") Executor plaidExecutor,
            @Value("${plaid.webhook.url}") String plaidWebhookUrl) {
        this.plaidApi = plaidApi;
        this.gateway = gateway;
        this.encryptionService = encryptionService;
        this.itemStateService = itemStateService;
        this.pipeline = pipeline;
        this.plaidExecutor = plaidExecutor;
        this.plaidWebhookUrl = plaidWebhookUrl;
    }

    public UpdateModeResult complete(User user) {
        String token = user.getPlaidAccessToken();
        String storedItemId = user.getPlaidItemId();
        if (token == null || token.isBlank() || storedItemId == null || storedItemId.isBlank()) {
            throw new UpdateModeUnavailableException("No Plaid Item on file to complete update mode for");
        }
        Long userId = user.getId();
        String rawToken = encryptionService.decrypt(token);

        PlaidCallResult<ItemGetResponse> got =
                gateway.call("item.get", plaidApi.itemGet(new ItemGetRequest().accessToken(rawToken)));
        ItemWithConsentFields item = got.ok() && got.body() != null ? got.body().getItem() : null;
        if (item == null || item.getItemId() == null) {
            logger.warn("plaid.update_mode.item_get_failed user_id={} code={}", userId,
                    got.error() == null ? "NO_ITEM" : got.error().errorCode());
            return UpdateModeResult.PLAID_ERROR;
        }
        String itemId = item.getItemId();
        if (!itemId.equals(storedItemId)) {
            return finish(UpdateModeResult.STALE_ITEM); // a relink won; nothing here applies
        }
        if (item.getError() != null) {
            logger.warn("plaid.update_mode.item_still_errored user_id={} code={}", userId, item.getError().getErrorCode());
            return UpdateModeResult.PLAID_ERROR;
        }

        PlaidCallResult<ItemWebhookUpdateResponse> hook = gateway.call("item.webhook_update",
                plaidApi.itemWebhookUpdate(new ItemWebhookUpdateRequest().accessToken(rawToken).webhook(plaidWebhookUrl)));
        if (!hook.ok()) {
            // Not fatal: the consent and repair writes still apply; the reconcile's /item/get covers it.
            logger.warn("plaid.update_mode.webhook_update_failed user_id={} code={}", userId,
                    hook.error() == null ? "UNKNOWN" : hook.error().errorCode());
        }

        List<Products> consented = item.getConsentedProducts();
        boolean consentedNow = consented != null && consented.contains(Products.TRANSACTIONS);
        Boolean consentBefore = user.getPlaidTransactionsConsented();
        boolean wasLoginRequired = user.getPlaidItemStatus() == PlaidItemStatus.LOGIN_REQUIRED;
        boolean hasCursor = user.getPlaidSyncCursor() != null;

        UpdateModeResult result = UpdateModeResult.NO_CHANGE;
        ItemStateResult step = ItemStateResult.APPLIED;
        if (consentedNow && !Boolean.TRUE.equals(consentBefore)) {
            step = itemStateService.onConsentGranted(userId, itemId);
            result = UpdateModeResult.CONSENT_GRANTED;
        } else if (!consentedNow && consentBefore != null) {
            // FALSE stays refused; TRUE means the scope was dropped inside update mode; NULL stays unknown.
            step = itemStateService.onConsentRequired(userId, itemId);
            result = UpdateModeResult.CONSENT_STILL_MISSING;
        }
        // Every success renews the expiry after granting (which nulls it); PENDING_DISCONNECT to OK (2.3).
        if (step != ItemStateResult.STALE_ITEM) {
            step = itemStateService.onConsentRenewed(userId, itemId, item.getConsentExpirationTime());
        }
        if (step != ItemStateResult.STALE_ITEM && wasLoginRequired) {
            step = itemStateService.onLoginRepaired(userId, itemId, RepairPath.UPDATE_MODE);
            if (step == ItemStateResult.APPLIED) {
                result = UpdateModeResult.REPAIRED;
                if (hasCursor) {
                    submitResync(userId, itemId);
                }
            }
        }
        return finish(step == ItemStateResult.STALE_ITEM ? UpdateModeResult.STALE_ITEM : result);
    }

    private UpdateModeResult finish(UpdateModeResult result) {
        logger.info("plaid.update_mode.complete result={}", result);
        return result;
    }

    private void submitResync(Long userId, String itemId) {
        try {
            plaidExecutor.execute(() -> {
                try {
                    pipeline.run(userId, itemId, SyncReason.MANUAL);
                } catch (RuntimeException e) {
                    logger.warn("plaid.update_mode.resync_failed user_id={} error_class={}", userId, e.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException e) {
            logger.warn("plaid.update_mode.resync_rejected user_id={} error_class={}", userId, e.getClass().getSimpleName());
        }
    }
}
