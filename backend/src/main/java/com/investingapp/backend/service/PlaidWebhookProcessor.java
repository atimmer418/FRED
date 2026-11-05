package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.model.PlaidWebhookEvent;
import com.investingapp.backend.model.PlaidWebhookEventStatus;
import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.repository.PlaidWebhookEventRepository;
import com.investingapp.backend.service.PlaidWebhookDispatcher.DispatchOutcome;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class PlaidWebhookProcessor {

    private static final Logger logger = LoggerFactory.getLogger(PlaidWebhookProcessor.class);

    static final int BATCH_SIZE = 50;
    static final int MAX_ATTEMPTS = 3;
    static final int MAX_CONCURRENT_ITEMS = 4;
    static final int LAST_ERROR_MAX = 512;
    static final Duration DISPATCH_BUDGET = Duration.ofSeconds(150);
    static final Duration LOCK_LEASE = Duration.ofMinutes(5);
    static final Duration STALE_CLAIM_AGE = Duration.ofMinutes(10);
    static final Duration PERMIT_WAIT = Duration.ofSeconds(90);
    static final Set<String> COALESCED = Set.of("TRANSACTIONS:SYNC_UPDATES_AVAILABLE", "TRANSACTIONS:RECURRING_TRANSACTIONS_UPDATE");

    public enum ReplayResult { REQUEUED, NOT_FOUND, IN_FLIGHT }

    private final PlaidWebhookEventRepository eventRepository;
    private final PlaidWebhookDispatcher dispatcher;
    private final PaycheckAutopilotProperties props;
    private final SchedulerGate schedulerGate;
    private final JobLockService jobLockService;
    private final OpsAlertService opsAlertService;
    private final TransactionTemplate requiresNew;
    private final Executor plaidExecutor;
    private Clock clock = Clock.system(EasternTime.ZONE); // tests replace it with ReflectionTestUtils.setField

    public PlaidWebhookProcessor(PlaidWebhookEventRepository eventRepository, PlaidWebhookDispatcher dispatcher,
                                 PaycheckAutopilotProperties props, SchedulerGate schedulerGate, JobLockService jobLockService,
                                 OpsAlertService opsAlertService, PlatformTransactionManager transactionManager,
                                 @Qualifier("plaidExecutor") Executor plaidExecutor) {
        this.eventRepository = eventRepository;
        this.dispatcher = dispatcher;
        this.props = props;
        this.schedulerGate = schedulerGate;
        this.jobLockService = jobLockService;
        this.opsAlertService = opsAlertService;
        this.plaidExecutor = plaidExecutor;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelay = 15000)
    public void drainInbox() {
        if (!schedulerGate.isEnabled()) {
            return;
        }
        if (!props.isEnabled()) {
            return;
        }
        if (!jobLockService.tryAcquire(JobLocks.PLAID_WEBHOOK_INBOX, LOCK_LEASE)) {
            return;
        }
        try {
            LocalDateTime startET = LocalDateTime.now(clock);
            Integer reclaimed = requiresNew.execute(status -> eventRepository.reclaimStale(startET.minus(STALE_CLAIM_AGE)));
            if (reclaimed != null && reclaimed > 0) {
                logger.warn("plaid.webhook.reclaimed rows={}", reclaimed);
            }
            List<PlaidWebhookEvent> claimed = claim(startET);
            if (!claimed.isEmpty()) {
                int unstarted = dispatchGroups(groupByItem(claimed), startET.plus(DISPATCH_BUDGET));
                logger.info("plaid.webhook.drain claimed={} unstarted={}", claimed.size(), unstarted);
            }
        } finally {
            jobLockService.release(JobLocks.PLAID_WEBHOOK_INBOX);
        }
    }

    /** Ops verb (T18): back to RECEIVED with attempts 0. A PROCESSING row is mid-dispatch and is left alone. */
    public ReplayResult replay(Long eventId) {
        ReplayResult result = requiresNew.execute(status -> eventRepository.findById(eventId).map(row -> {
            if (row.getStatus() == PlaidWebhookEventStatus.PROCESSING) {
                return ReplayResult.IN_FLIGHT;
            }
            row.setStatus(PlaidWebhookEventStatus.RECEIVED);
            row.setAttempts((short) 0);
            row.setLastError(null);
            row.setProcessedAt(null);
            eventRepository.save(row);
            return ReplayResult.REQUEUED;
        }).orElse(ReplayResult.NOT_FOUND));
        logger.info("plaid.webhook.replay id={} result={}", eventId, result);
        return result;
    }

    private List<PlaidWebhookEvent> claim(LocalDateTime claimedAt) {
        List<PlaidWebhookEvent> rows = requiresNew.execute(status -> {
            List<Long> ids = eventRepository.claimReceivedIds(BATCH_SIZE);
            if (ids.isEmpty()) {
                return List.<PlaidWebhookEvent>of();
            }
            eventRepository.markProcessing(ids, claimedAt);
            return eventRepository.findAllById(ids);
        });
        List<PlaidWebhookEvent> sorted = new ArrayList<>(rows == null ? List.of() : rows);
        sorted.sort(Comparator.comparing(PlaidWebhookEvent::getReceivedAt).thenComparing(PlaidWebhookEvent::getId));
        return sorted;
    }

    private static Collection<List<PlaidWebhookEvent>> groupByItem(List<PlaidWebhookEvent> rows) {
        Map<String, List<PlaidWebhookEvent>> groups = new LinkedHashMap<>();
        for (PlaidWebhookEvent row : rows) {
            String key = row.getPlaidItemId() != null ? row.getPlaidItemId() : "row-" + row.getId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        return groups.values();
    }

    /** Returns how many rows went back to RECEIVED unstarted. */
    private int dispatchGroups(Collection<List<PlaidWebhookEvent>> groups, LocalDateTime deadline) {
        Semaphore permits = new Semaphore(MAX_CONCURRENT_ITEMS);
        List<CompletableFuture<Void>> inFlight = new ArrayList<>();
        List<Long> unstarted = new ArrayList<>();
        for (List<PlaidWebhookEvent> group : groups) {
            if (LocalDateTime.now(clock).isAfter(deadline) || !acquire(permits)) {
                group.forEach(row -> unstarted.add(row.getId()));
                continue;
            }
            try {
                inFlight.add(CompletableFuture.runAsync(() -> {
                    try {
                        processGroup(group);
                    } finally {
                        permits.release();
                    }
                }, plaidExecutor));
            } catch (RejectedExecutionException e) {
                permits.release();
                logger.warn("plaid.webhook.executor_full rows={}", group.size());
                group.forEach(row -> unstarted.add(row.getId()));
            }
        }
        try {
            CompletableFuture.allOf(inFlight.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            logger.warn("plaid.webhook.group_crashed cause={}", e.getCause() == null ? "unknown" : e.getCause().getClass().getSimpleName());
        }
        if (!unstarted.isEmpty()) {
            returnToReceived(unstarted);
        }
        return unstarted.size();
    }

    private void processGroup(List<PlaidWebhookEvent> group) {
        Map<String, DispatchOutcome> coalesced = new HashMap<>();
        for (PlaidWebhookEvent row : group) {
            try {
                String key = row.getWebhookType() + ":" + row.getWebhookCode();
                DispatchOutcome outcome = coalesced.get(key); // a coalesced row reuses its Item's first outcome
                String cause = null;
                if (outcome == null) {
                    try {
                        outcome = dispatcher.dispatch(row);
                    } catch (RuntimeException e) {
                        outcome = DispatchOutcome.RETRY;
                        cause = e.getClass().getSimpleName();
                        logger.warn("plaid.webhook.dispatch_threw id={} cause={}", row.getId(), cause);
                    }
                    if (COALESCED.contains(key)) {
                        coalesced.put(key, outcome);
                    }
                }
                finish(row, outcome, cause);
            } catch (RuntimeException e) {
                // The row stays PROCESSING; the next drain reclaims it after STALE_CLAIM_AGE.
                logger.warn("plaid.webhook.finish_failed id={} cause={}", row.getId(), e.getClass().getSimpleName());
            }
        }
    }

    private void finish(PlaidWebhookEvent claimed, DispatchOutcome outcome, String cause) {
        LocalDateTime now = LocalDateTime.now(clock);
        Short failedAttempts = requiresNew.execute(status -> eventRepository.findById(claimed.getId()).map(row -> {
            switch (outcome) {
                case PROCESSED -> close(row, PlaidWebhookEventStatus.PROCESSED, now);
                case IGNORED_STALE -> close(row, PlaidWebhookEventStatus.IGNORED, now);
                case RETRY, FAILED -> {
                    row.setAttempts((short) (row.getAttempts() + 1));
                    String detail = cause != null ? cause : outcome == DispatchOutcome.RETRY ? "retry_requested" : "dispatch_failed";
                    row.setLastError(detail.length() <= LAST_ERROR_MAX ? detail : detail.substring(0, LAST_ERROR_MAX));
                    if (outcome == DispatchOutcome.FAILED || row.getAttempts() >= MAX_ATTEMPTS) {
                        close(row, PlaidWebhookEventStatus.FAILED, now);
                    } else {
                        row.setStatus(PlaidWebhookEventStatus.RECEIVED);
                        row.setProcessedAt(null);
                    }
                }
            }
            eventRepository.save(row);
            return row.getStatus() == PlaidWebhookEventStatus.FAILED ? row.getAttempts() : null;
        }).orElse(null));
        if (failedAttempts != null) {
            logger.error("alert.plaid.webhook.inbox_failed id={} code={} attempts={}", claimed.getId(), claimed.getWebhookCode(), failedAttempts);
            opsAlertService.raise(OpsAlerts.INBOX_FAILED, String.valueOf(claimed.getId()),
                    "Plaid webhook inbox row failed after " + failedAttempts + " attempts",
                    Map.of("code", claimed.getWebhookCode(), "attempts", failedAttempts));
        }
    }

    private static void close(PlaidWebhookEvent row, PlaidWebhookEventStatus status, LocalDateTime now) {
        row.setStatus(status);
        row.setProcessedAt(now);
    }

    private void returnToReceived(List<Long> ids) {
        requiresNew.executeWithoutResult(status -> eventRepository.findAllById(ids).forEach(row -> {
            row.setStatus(PlaidWebhookEventStatus.RECEIVED);
            row.setProcessedAt(null);
            eventRepository.save(row);
        }));
    }

    private static boolean acquire(Semaphore permits) {
        try {
            return permits.tryAcquire(PERMIT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
