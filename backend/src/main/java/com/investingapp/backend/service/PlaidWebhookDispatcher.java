package com.investingapp.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.PlaidWebhookEvent;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidErrorClass;
import com.investingapp.backend.service.PaycheckSyncPipeline.PipelineOutcome;
import com.investingapp.backend.service.PlaidItemStateService.ItemStateResult;
import com.investingapp.backend.service.PlaidItemStateService.RepairPath;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncStatus;
import com.investingapp.backend.util.EasternTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PlaidWebhookDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(PlaidWebhookDispatcher.class);

    public enum DispatchOutcome { PROCESSED, IGNORED_STALE, RETRY, FAILED }

    /** Legacy TRANSACTIONS codes Plaid still sends; the cursor sync makes them redundant (spec 2.8). */
    static final Set<String> LEGACY_TRANSACTIONS_CODES =
            Set.of("INITIAL_UPDATE", "HISTORICAL_UPDATE", "DEFAULT_UPDATE", "TRANSACTIONS_REMOVED");

    /** Plaid sends PENDING_DISCONNECT seven days ahead (spec 2.8); used only when the payload names no time. */
    static final Duration PENDING_DISCONNECT_LEAD = Duration.ofDays(7);

    private final PaycheckSyncPipeline pipeline;
    private final PlaidItemStateService itemStateService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DispatchOutcome dispatch(PlaidWebhookEvent row) {
        Long userId = row.getUserId();
        String itemId = row.getPlaidItemId();
        if (userId == null || itemId == null) {
            logger.info("plaid.stale_item_event id={} cause=unresolved_row", row.getId());
            return DispatchOutcome.IGNORED_STALE;
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(row.getPayloadJson());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            logger.warn("plaid.webhook.unparsable id={}", row.getId());
            return DispatchOutcome.FAILED;
        }
        String type = row.getWebhookType();
        String code = row.getWebhookCode();
        DispatchOutcome outcome = switch (type) {
            case "TRANSACTIONS" -> transactions(row, code, userId, itemId);
            case "ITEM" -> item(row, code, payload, userId, itemId);
            default -> noAction(row, "unhandled_type");
        };
        logger.info("plaid.webhook.dispatched id={} user_id={} type={} code={} outcome={}", row.getId(), userId, type, code, outcome);
        return outcome;
    }

    private DispatchOutcome transactions(PlaidWebhookEvent row, String code, Long userId, String itemId) {
        if ("SYNC_UPDATES_AVAILABLE".equals(code)) {
            return fromPipeline(pipeline.run(userId, itemId, SyncReason.WEBHOOK));
        }
        if ("RECURRING_TRANSACTIONS_UPDATE".equals(code)) {
            return fromPipeline(pipeline.streamsChanged(userId, itemId));
        }
        return noAction(row, LEGACY_TRANSACTIONS_CODES.contains(code) ? "legacy_code" : "unhandled_code");
    }

    private DispatchOutcome item(PlaidWebhookEvent row, String code, JsonNode payload, Long userId, String itemId) {
        return switch (code) {
            case "ERROR" -> itemError(row, payload, userId, itemId);
            case "LOGIN_REPAIRED" -> loginRepaired(userId, itemId);
            case "PENDING_DISCONNECT", "PENDING_EXPIRATION" ->
                    fromItemState(itemStateService.onPendingDisconnect(userId, itemId, disconnectAt(row, payload)));
            case "USER_PERMISSION_REVOKED" -> fromItemState(itemStateService.onRevoked(userId, itemId, code));
            case "USER_ACCOUNT_REVOKED" -> accountRevoked(row, payload, userId, itemId);
            case "WEBHOOK_UPDATE_ACKNOWLEDGED" -> fromItemState(itemStateService.onWebhookAcked(userId, itemId));
            default -> noAction(row, "unhandled_code");
        };
    }

    // ITEM_LOGIN_REQUIRED is an error_code inside ERROR (spec 5.7), classified like an API error.
    private DispatchOutcome itemError(PlaidWebhookEvent row, JsonNode payload, Long userId, String itemId) {
        JsonNode error = payload.path("error");
        String errorCode = text(error, "error_code");
        if (errorCode == null) {
            logger.warn("plaid.webhook.error_without_code id={}", row.getId());
            return DispatchOutcome.FAILED;
        }
        PlaidErrorClass errorClass = PlaidErrorClass.classify(errorCode, 0); // a webhook has no HTTP status; the code decides
        if (!"ITEM_LOGIN_REQUIRED".equals(errorCode)) {
            logger.warn("plaid.webhook.item_error id={} user_id={} code={} class={}", row.getId(), userId, errorCode, errorClass);
        }
        PlaidApiError apiError = new PlaidApiError(text(error, "error_type"), errorCode, text(error, "error_code_reason"), null, 0, errorClass);
        return fromItemState(pipeline.routeItemError(userId, itemId, apiError));
    }

    // Spec 5.7: only from LOGIN_REQUIRED (else NO_OP), then sync and INCREMENTAL.
    private DispatchOutcome loginRepaired(Long userId, String itemId) {
        ItemStateResult repaired = itemStateService.onLoginRepaired(userId, itemId, RepairPath.LOGIN_REPAIRED);
        return repaired == ItemStateResult.APPLIED
                ? fromPipeline(pipeline.run(userId, itemId, SyncReason.WEBHOOK)) : fromItemState(repaired);
    }

    private DispatchOutcome accountRevoked(PlaidWebhookEvent row, JsonNode payload, Long userId, String itemId) {
        String accountId = text(payload, "account_id");
        if (accountId == null) {
            logger.warn("plaid.webhook.account_revoked_without_account id={}", row.getId());
            return DispatchOutcome.FAILED;
        }
        return fromItemState(itemStateService.onAccountRevoked(userId, itemId, accountId));
    }

    private OffsetDateTime disconnectAt(PlaidWebhookEvent row, JsonNode payload) {
        for (String field : List.of("disconnect_time", "consent_expiration_time")) {
            String value = text(payload, field);
            if (value != null) {
                try {
                    return OffsetDateTime.parse(value);
                } catch (DateTimeParseException e) {
                    logger.warn("plaid.webhook.unparsable_time id={} field={}", row.getId(), field);
                }
            }
        }
        return row.getReceivedAt().plus(PENDING_DISCONNECT_LEAD).atZone(EasternTime.ZONE).toOffsetDateTime();
    }

    /** RETRY only for what a later attempt can fix: RETRY_LATER, FAILED, and TRANSIENT or RATE_LIMIT errors. */
    static DispatchOutcome fromPipeline(PipelineOutcome outcome) {
        if (outcome.itemState() == ItemStateResult.STALE_ITEM) {
            return DispatchOutcome.IGNORED_STALE;
        }
        SyncOutcome sync = outcome.sync();
        if (sync != null && sync.status() != SyncStatus.APPLIED) {
            return switch (sync.status()) {
                case REFUSED_STALE_ITEM -> DispatchOutcome.IGNORED_STALE;
                case RETRY_LATER, FAILED -> DispatchOutcome.RETRY;
                case ITEM_ERROR -> retryable(sync.error()) ? DispatchOutcome.RETRY : DispatchOutcome.PROCESSED;
                default -> DispatchOutcome.PROCESSED;
            };
        }
        StreamsReadOutcome streams = outcome.streams();
        if (sync == null && streams != null) {
            return switch (streams.status()) {
                case REFUSED_STALE_ITEM -> DispatchOutcome.IGNORED_STALE;
                case FAILED -> DispatchOutcome.RETRY;
                case ITEM_ERROR -> retryable(streams.error()) ? DispatchOutcome.RETRY : DispatchOutcome.PROCESSED;
                default -> DispatchOutcome.PROCESSED;
            };
        }
        return DispatchOutcome.PROCESSED;
    }

    private static boolean retryable(PlaidApiError error) {
        return error != null && (error.errorClass() == PlaidErrorClass.TRANSIENT || error.errorClass() == PlaidErrorClass.RATE_LIMIT);
    }

    private static DispatchOutcome fromItemState(ItemStateResult result) {
        return result == ItemStateResult.STALE_ITEM ? DispatchOutcome.IGNORED_STALE : DispatchOutcome.PROCESSED;
    }

    private static DispatchOutcome noAction(PlaidWebhookEvent row, String why) {
        logger.info("plaid.webhook.no_action id={} code={} why={}", row.getId(), row.getWebhookCode(), why);
        return DispatchOutcome.PROCESSED;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
