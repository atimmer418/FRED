package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.User;
import com.investingapp.backend.paycheck.EvaluationOutcome;
import com.investingapp.backend.paycheck.EvaluationTrigger;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;
import com.investingapp.backend.service.PlaidItemStateService.ItemStateResult;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadOutcome;
import com.investingapp.backend.service.PlaidRecurringService.StreamsReadStatus;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncOutcome;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncStatus;
import com.investingapp.backend.util.EasternTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class PaycheckSyncPipeline {

    private static final Logger logger = LoggerFactory.getLogger(PaycheckSyncPipeline.class);

    /** ITEM_BROKEN codes that end the Item instead of waiting for a login repair (spec 5.7, second row). */
    static final Set<String> REVOKING_CODES = Set.of("ITEM_NOT_FOUND", "USER_PERMISSION_REVOKED");

    /** Any part is null when the pipeline stopped before reaching it; an entry refusal is always in sync. */
    public record PipelineOutcome(SyncOutcome sync, StreamsReadOutcome streams, EvaluationOutcome evaluation,
                                  ItemStateResult itemState) {
    }

    private final PaycheckAutopilotProperties props;
    private final UserRepository userRepository;
    private final PlaidTransactionsSyncService syncService;
    private final PlaidRecurringService recurringService;
    private final PaycheckDetectionService detectionService;
    private final PlaidItemStateService itemStateService;

    public PipelineOutcome run(Long userId, String itemId, SyncReason reason) {
        requireNoCallerTransaction("run");
        Optional<SyncStatus> refused = entryRefusal(userId);
        if (refused.isPresent()) {
            return refusedOutcome(userId, "run", refused.get());
        }
        SyncOutcome sync = syncService.syncItem(userId, itemId, reason);
        logger.info("paycheck.pipeline.sync user_id={} reason={} status={} history_completed={}",
                userId, reason, sync.status(), sync.historyJustCompleted());
        if (sync.status() == SyncStatus.ITEM_ERROR) {
            return new PipelineOutcome(sync, null, null, routeItemError(userId, itemId, sync.error()));
        }
        if (sync.status() != SyncStatus.APPLIED) {
            return new PipelineOutcome(sync, null, null, null);
        }
        LocalDate todayET = EasternTime.today();
        if (sync.historyJustCompleted()) {
            StreamsReadOutcome streams = recurringService.readStreams(userId, itemId);
            logStreams(userId, streams);
            ItemStateResult itemState = streams.status() == StreamsReadStatus.ITEM_ERROR
                    ? routeItemError(userId, itemId, streams.error()) : null;
            if (itemState != null || streams.status() == StreamsReadStatus.REFUSED_STALE_ITEM
                    || streams.status() == StreamsReadStatus.REFUSED_ITEM_STATUS) {
                return new PipelineOutcome(sync, streams, null, itemState);
            }
            // Streams are catalog only (spec 2.6): a transient read failure must not cost the one FULL evaluation.
            return new PipelineOutcome(sync, streams, detectionService.evaluate(userId, EvaluationTrigger.FULL, todayET), null);
        }
        List<Long> newDeposits = sync.applied() == null ? List.of() : sync.applied().newPostedInflowEventIds();
        return new PipelineOutcome(sync, null, detectionService.evaluateAndApply(userId, newDeposits, todayET), null);
    }

    /** RECURRING_TRANSACTIONS_UPDATE and the reconcile's 24-hour re-read. */
    public PipelineOutcome streamsChanged(Long userId, String itemId) {
        requireNoCallerTransaction("streamsChanged");
        Optional<SyncStatus> refused = entryRefusal(userId);
        if (refused.isPresent()) {
            return refusedOutcome(userId, "streamsChanged", refused.get());
        }
        StreamsReadOutcome streams = recurringService.readStreams(userId, itemId);
        logStreams(userId, streams);
        return switch (streams.status()) {
            case APPLIED -> new PipelineOutcome(null, streams,
                    detectionService.evaluate(userId, EvaluationTrigger.STREAMS_CHANGED, EasternTime.today()), null);
            case ITEM_ERROR -> new PipelineOutcome(null, streams, null, routeItemError(userId, itemId, streams.error()));
            default -> new PipelineOutcome(null, streams, null, null);
        };
    }

    /** Spec 2.1 and 5.7 by class; null for TRANSIENT, RATE_LIMIT, MUTATION, PERMANENT. Also used by the ERROR webhook and T15. */
    public ItemStateResult routeItemError(Long userId, String itemId, PlaidApiError error) {
        if (error == null || error.errorClass() == null) {
            return null;
        }
        String code = error.errorCode();
        ItemStateResult result = switch (error.errorClass()) {
            case CONSENT -> itemStateService.onConsentRequired(userId, itemId);
            case ITEM_BROKEN -> REVOKING_CODES.contains(code)
                    ? itemStateService.onRevoked(userId, itemId, code)
                    : itemStateService.onLoginRequired(userId, itemId, code);
            case NOT_SUPPORTED -> itemStateService.onNotSupported(userId, itemId);
            case SYNC_LIMIT -> itemStateService.onSyncLimit(userId, itemId);
            case TRANSIENT, RATE_LIMIT, MUTATION, PERMANENT -> null;
        };
        logger.info("paycheck.pipeline.item_error user_id={} class={} code={} routed={}", userId, error.errorClass(), code, result);
        return result;
    }

    private Optional<SyncStatus> entryRefusal(Long userId) {
        if (!props.isEnabled()) {
            return Optional.of(SyncStatus.REFUSED_DISABLED);
        }
        Optional<User> user = userId == null ? Optional.empty() : userRepository.findById(userId);
        if (user.isEmpty()) {
            logger.info("plaid.stale_item_event user_id={} cause=user_missing", userId);
            return Optional.of(SyncStatus.REFUSED_STALE_ITEM);
        }
        Optional<ExclusionReason> exclusion = DetectionEligibility.exclusion(user.get(), props);
        if (exclusion.isEmpty()) {
            return Optional.empty();
        }
        return switch (exclusion.get()) {
            case ACCOUNT_REJECTED, OPTED_OUT, NO_TOKEN -> Optional.of(SyncStatus.REFUSED_EXCLUDED);
            case DISABLED -> Optional.of(SyncStatus.REFUSED_DISABLED);
            case ITEM_STATUS -> Optional.empty(); // syncItem and readStreams refuse it with no Plaid call
        };
    }

    private static PipelineOutcome refusedOutcome(Long userId, String entry, SyncStatus status) {
        logger.info("paycheck.pipeline.refused user_id={} entry={} status={}", userId, entry, status);
        return new PipelineOutcome(new SyncOutcome(status, false, null, null), null, null, null);
    }

    private static void logStreams(Long userId, StreamsReadOutcome streams) {
        logger.info("paycheck.pipeline.streams user_id={} status={} inflow={} matched={} seeded={}",
                userId, streams.status(), streams.inflowStreams(), streams.matched(), streams.seeded());
    }

    private static void requireNoCallerTransaction(String method) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("PaycheckSyncPipeline." + method + " calls Plaid and must not run inside a transaction");
        }
    }
}
