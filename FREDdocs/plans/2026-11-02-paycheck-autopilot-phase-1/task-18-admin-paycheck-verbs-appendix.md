# Task 18 appendix: full code for admin-paycheck-verbs

This file completes `task-18-admin-paycheck-verbs.md`; read both. The main file had to fit a 75,000 character cap, so it gave some code as tables or named patterns. This appendix writes that code out in full: Step 6 (the `ROW PATTERN` bodies of `CycleRow.of`, `InboxRow.of` and `PlaidItemDump.of`), Step 7 (the `FIELDS` and `ASSIGNMENTS` of `AdminPaycheckService`, `PATTERN A` for `plaidItem`, `PATTERN B` for `evaluate` and `reconcile`), Step 9 (the eight controller handlers described in prose), plus two smaller gaps found on a scan of the whole file: Step 5's conditional import and the Verification section's command rows, including the whole-suite block the main file describes as "the Step 10 block with its gradle line changed". Each Java section below is the complete file: the main file's block with only the compressed lines replaced, so every name, constant, error code and order is the main file's own. Nothing here adds a verb, a test, a behaviour or a dependency. Where a pattern and the main file's code disagree, the code wins and the disagreement is listed at the end.

## Step 5: the inbox finder import

No import edit is needed. T14 (its Step on `PlaidWebhookEventRepository`) adds `java.util.List` together with `PlaidWebhookEventStatus`, `Modifying`, `Query`, `Param` and `LocalDateTime` for `findByStatusOrderByReceivedAtAsc`, which this task consumes. Confirm before inserting the finder:

```
grep -n '^import java.util.List;' backend/src/main/java/com/investingapp/backend/repository/PlaidWebhookEventRepository.java
```

Expected: one line. If it prints nothing, T14 has not landed; stop and report it rather than adding the import here, because this task depends on T14's `findByStatusOrderByReceivedAtAsc` in the same file.

## Step 6: the two admin DTOs, complete

`ROW PATTERN` written out: one argument per record component in order, each the getter of the same name, enums through `name(...)` (`CycleRow`: `state`, `skipReason`, `voidedReason`; `InboxRow`: `status`). `PlaidWebhookEvent.attempts` is a `short` (HEAD entity) and the `InboxRow.attempts` component is an `int`, as `StreamRow` already does for `score`, `depositsFound` and `missedCount`: Java widens it, no cast. `PlaidItemDump.of` uses the three expressions the main file gives for `plaidItemStatus`, `hasAccessToken` and `hasSyncCursor`; every other component is the same-named Lombok getter on `User` (Phase 0 columns at HEAD, plus T02's seven new getters).

Create `backend/src/main/java/com/investingapp/backend/dto/admin/AdminUserStatusView.java`:

```java
package com.investingapp.backend.dto.admin;

import com.investingapp.backend.dto.PaycheckDetectionStatusResponse;
import com.investingapp.backend.model.PaycheckCycle;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PlaidWebhookEvent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** GET /{userId}/status: never the token, cursor or a payload; never logged or audited. */
public record AdminUserStatusView(PaycheckDetectionStatusResponse detection, List<StreamRow> streams,
                                  List<CycleRow> cycles, List<InboxRow> inbox) {

    public record StreamRow(Long id, String state, String role, String payerToken, String payerDisplayName,
                            String rawDescriptor, String plaidAccountId, String cadence, int score,
                            String scoreBreakdownJson, int depositsFound, int missedCount, LocalDate lastDate,
                            BigDecimal trailingMedian, LocalDate predictedNextDate, String rejectReason,
                            String retiredReason, LocalDateTime retiredAt) {
        public static StreamRow of(PaycheckStream s) {
            return new StreamRow(s.getId(), name(s.getState()), name(s.getRole()), s.getPayerToken(),
                    s.getPayerDisplayName(), s.getRawDescriptor(), s.getPlaidAccountId(), name(s.getCadence()),
                    s.getScore(), s.getScoreBreakdownJson(), s.getDepositsFound(), s.getMissedCount(),
                    s.getLastDate(), s.getTrailingMedian(), s.getPredictedNextDate(), name(s.getRejectReason()),
                    name(s.getRetiredReason()), s.getRetiredAt());
        }
    }

    public record CycleRow(Long id, Long streamId, String periodKey, String state, LocalDate expectedDate,
                           LocalDate windowEnd, Long eventId, BigDecimal amountToPull, LocalDateTime confirmedAt,
                           String skipReason, String voidedReason, Long executionId) {
        public static CycleRow of(PaycheckCycle c) {
            return new CycleRow(c.getId(), c.getStreamId(), c.getPeriodKey(), name(c.getState()), c.getExpectedDate(),
                    c.getWindowEnd(), c.getEventId(), c.getAmountToPull(), c.getConfirmedAt(),
                    name(c.getSkipReason()), name(c.getVoidedReason()), c.getExecutionId());
        }
    }

    public record InboxRow(Long id, Long userId, String plaidItemId, String webhookType, String webhookCode,
                           String status, int attempts, String lastError, LocalDateTime receivedAt,
                           LocalDateTime processedAt) {
        public static InboxRow of(PlaidWebhookEvent e) {
            return new InboxRow(e.getId(), e.getUserId(), e.getPlaidItemId(), e.getWebhookType(), e.getWebhookCode(),
                    name(e.getStatus()), e.getAttempts(), e.getLastError(), e.getReceivedAt(), e.getProcessedAt());
        }
    }

    public static AdminUserStatusView of(PaycheckDetectionStatusResponse detection, List<PaycheckStream> streams,
                                         List<PaycheckCycle> cycles, List<PlaidWebhookEvent> inbox) {
        return new AdminUserStatusView(detection, streams.stream().map(StreamRow::of).toList(),
                cycles.stream().map(CycleRow::of).toList(), inbox.stream().map(InboxRow::of).toList());
    }

    static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
```

Create `backend/src/main/java/com/investingapp/backend/dto/admin/PlaidItemDump.java`:

```java
package com.investingapp.backend.dto.admin;

import com.investingapp.backend.model.User;

import java.time.LocalDateTime;

public record PlaidItemDump(String publicId, String accountStatus, String plaidItemId, String plaidAccountId,
                            String plaidInstitutionName, String plaidAccountName, String plaidAccountType,
                            String plaidAccountSubtype, String plaidAccountMask, String plaidItemStatus,
                            String plaidItemErrorCode, Boolean plaidTransactionsConsented, boolean hasAccessToken,
                            boolean hasSyncCursor, Boolean plaidHistoryComplete, String plaidTransactionsUpdateStatus,
                            LocalDateTime plaidItemLinkedAt, LocalDateTime plaidWebhookAckedAt,
                            LocalDateTime plaidLastSyncAt, LocalDateTime plaidStreamsReadAt,
                            LocalDateTime plaidDisconnectAt, Boolean paycheckAutopilotOptOut) {

    public static PlaidItemDump of(User u) {
        return new PlaidItemDump(u.getPublicId(), u.getAccountStatus(), u.getPlaidItemId(), u.getPlaidAccountId(),
                u.getPlaidInstitutionName(), u.getPlaidAccountName(), u.getPlaidAccountType(),
                u.getPlaidAccountSubtype(), u.getPlaidAccountMask(),
                u.getPlaidItemStatus() == null ? null : u.getPlaidItemStatus().name(),
                u.getPlaidItemErrorCode(), u.getPlaidTransactionsConsented(), u.getPlaidAccessToken() != null,
                u.getPlaidSyncCursor() != null, u.getPlaidHistoryComplete(), u.getPlaidTransactionsUpdateStatus(),
                u.getPlaidItemLinkedAt(), u.getPlaidWebhookAckedAt(), u.getPlaidLastSyncAt(),
                u.getPlaidStreamsReadAt(), u.getPlaidDisconnectAt(), u.getPaycheckAutopilotOptOut());
    }
}
```

## Step 7: AdminPaycheckService, complete

This is the main file's Step 7 block with four lines replaced:

- `// FIELDS (see the note after this block).` became the twelve `private final` fields named and typed as the first twelve constructor parameters, in constructor order, then `private final TransactionTemplate writeTx;`. `transactionManager` is not kept as a field; only `writeTx` is built from it.
- `// ASSIGNMENTS (see the note).` became the twelve `this.x = x;` lines, before the existing `this.writeTx = new TransactionTemplate(transactionManager);`.
- `// plaidItem(String actor, Long userId): PATTERN A (note after this block).` became `plaidItem`: a GET verb, so its single audit row is the `REQUIRES_NEW` `record`, before and after both the same snapshot, and the body is `PlaidItemDump.of(user)` (token and cursor as presence only).
- `// evaluate(String actor, Long userId) and reconcile(String actor, Long userId): PATTERN B (note).` became `evaluate` and `reconcile`: `sync` without the `NO_ITEM` check. Nothing is open around the detection or reconcile call, then the audit row goes through `auditInOwnTransaction` (`recordInTransaction` in a short template of its own), then `done(...)` logs and returns.

The main file's note under its block ("Mechanical repetitions in this class") is fully satisfied by this text, so an implementer can take this file as written and skip that note. `PaycheckDetectionService.evaluate` is `@Transactional` itself (T11), so it opens and commits its own transaction; what this verb guarantees is that it holds none around the call, which `pipelineVerbsRunOutsideAnyTransaction` pins with `RecordingTransactionManager.open`.

Create `backend/src/main/java/com/investingapp/backend/service/AdminPaycheckService.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.dto.admin.AdminUserStatusView;
import com.investingapp.backend.dto.admin.PlaidItemDump;
import com.investingapp.backend.model.*;
import com.investingapp.backend.model.InvestmentSchedule.Status;
import com.investingapp.backend.paycheck.EvaluationTrigger;
import com.investingapp.backend.repository.*;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidWebhookProcessor.ReplayResult;
import com.investingapp.backend.util.ColumnText;
import com.investingapp.backend.util.EasternTime;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

/** Spec 12.2 verbs, Phase 1 (R12). No class-level transaction (Decision 56); none for pipelines. */
@Service
public class AdminPaycheckService {

    private static final Logger logger = LoggerFactory.getLogger(AdminPaycheckService.class);

    public static final String VERB_STATUS = "paycheck.status";
    public static final String VERB_PLAID_ITEM = "paycheck.plaid-item";
    public static final String VERB_SYNC = "paycheck.sync";
    public static final String VERB_EVALUATE = "paycheck.evaluate";
    public static final String VERB_RECONCILE = "paycheck.reconcile";
    public static final String VERB_HOLD = "paycheck.hold";
    public static final String VERB_CLEAR_REVIEW = "paycheck.clear-review";
    public static final String VERB_PROMOTE_STREAM = "paycheck.promote-stream";
    public static final String VERB_RETIRE_STREAM = "paycheck.retire-stream";
    public static final String VERB_VOID_CYCLE = "paycheck.void-cycle";
    public static final String VERB_WEBHOOKS = "paycheck.webhooks";
    public static final String VERB_REPLAY = "paycheck.webhook-replay";

    /** Phase 2 adds snooze, set-manual and webhooks/replay {from}. */
    public static final List<String> VERBS = List.of(VERB_STATUS, VERB_PLAID_ITEM, VERB_SYNC, VERB_EVALUATE,
            VERB_RECONCILE, VERB_HOLD, VERB_CLEAR_REVIEW, VERB_PROMOTE_STREAM, VERB_RETIRE_STREAM,
            VERB_VOID_CYCLE, VERB_WEBHOOKS, VERB_REPLAY);

    /** Everything the hold verb may write besides null (R2, Decision 31). */
    public static final Set<HoldReason> HOLD_VERB_REASONS = EnumSet.of(HoldReason.ITEM_LOGIN_REQUIRED,
            HoldReason.PERMISSION_REVOKED, HoldReason.CONSENT_REQUIRED, HoldReason.BANK_CHANGE);

    static final int NOTE_MAX = 255;
    static final int TARGET_MAX = 80;
    static final int CYCLE_LIST_MAX = 50;
    static final int WEBHOOK_LIST_MAX = 200;
    static final String USER_NOT_FOUND = "USER_NOT_FOUND";

    public record ScheduleSnapshot(String status, String holdReason, String detectionState, Long primaryStreamId) {
        static final ScheduleSnapshot NONE = new ScheduleSnapshot(null, null, null, null);

        static ScheduleSnapshot of(InvestmentSchedule s) {
            return s == null ? NONE : new ScheduleSnapshot(name(s.getStatus()), name(s.getHoldReason()),
                    name(s.getDetectionState()), s.getPrimaryStreamId());
        }
    }

    public record VerbResult(int httpStatus, Object body) {
        public static VerbResult ok(Object body) {
            return new VerbResult(200, body);
        }

        public static VerbResult refusal(int httpStatus, String code, String message) {
            return new VerbResult(httpStatus, new ErrorCodeResponse(code, message));
        }

        public boolean isRefusal() {
            return httpStatus != 200;
        }

        String code() {
            return body instanceof ErrorCodeResponse e ? e.code() : null;
        }
    }

    @FunctionalInterface
    private interface Mutation {
        /** Runs under the user row lock. Decide first, then write: a refusal writes nothing. */
        VerbResult apply(User user, InvestmentSchedule scheduleOrNull);
    }

    private final UserRepository userRepository;
    private final InvestmentScheduleRepository scheduleRepository;
    private final PaycheckStreamRepository streamRepository;
    private final PaycheckCycleRepository cycleRepository;
    private final PlaidWebhookEventRepository webhookEventRepository;
    private final PaycheckDetectionStatusService statusService;
    private final PaycheckSyncPipeline pipeline;
    private final PaycheckDetectionService detectionService;
    private final PaycheckReconcileService reconcileService;
    private final PlaidWebhookProcessor webhookProcessor;
    private final AdminAuditService adminAuditService;
    private final AnalyticsOutboxService analyticsOutboxService;
    private final TransactionTemplate writeTx;

    public AdminPaycheckService(UserRepository userRepository, InvestmentScheduleRepository scheduleRepository,
                                PaycheckStreamRepository streamRepository, PaycheckCycleRepository cycleRepository,
                                PlaidWebhookEventRepository webhookEventRepository,
                                PaycheckDetectionStatusService statusService, PaycheckSyncPipeline pipeline,
                                PaycheckDetectionService detectionService, PaycheckReconcileService reconcileService,
                                PlaidWebhookProcessor webhookProcessor, AdminAuditService adminAuditService,
                                AnalyticsOutboxService analyticsOutboxService,
                                PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.scheduleRepository = scheduleRepository;
        this.streamRepository = streamRepository;
        this.cycleRepository = cycleRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.statusService = statusService;
        this.pipeline = pipeline;
        this.detectionService = detectionService;
        this.reconcileService = reconcileService;
        this.webhookProcessor = webhookProcessor;
        this.adminAuditService = adminAuditService;
        this.analyticsOutboxService = analyticsOutboxService;
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    // GET verbs: REQUIRES_NEW audit.

    public VerbResult status(String actor, Long userId) {
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return refuseUnknownUser(actor, userId, VERB_STATUS);
        }
        User user = found.get();
        ScheduleSnapshot snapshot = ScheduleSnapshot.of(scheduleOf(user));
        List<PaycheckCycle> userCycles = new ArrayList<>();
        for (PaycheckCycleState state : PaycheckCycleState.values()) {
            userCycles.addAll(cycleRepository.findByUserIdAndState(userId, state));
        }
        userCycles.sort(Comparator.comparing(PaycheckCycle::getId).reversed());
        AdminUserStatusView view = AdminUserStatusView.of(statusService.status(user),
                streamRepository.findByUserIdOrderByIdAsc(userId),
                userCycles.subList(0, Math.min(userCycles.size(), CYCLE_LIST_MAX)),
                webhookEventRepository.findTop20ByUserIdOrderByReceivedAtDesc(userId));
        adminAuditService.record(actor, userId, VERB_STATUS, null, snapshot, snapshot, null);
        return done(VERB_STATUS, VerbResult.ok(view));
    }

    public VerbResult plaidItem(String actor, Long userId) {
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return refuseUnknownUser(actor, userId, VERB_PLAID_ITEM);
        }
        ScheduleSnapshot snapshot = ScheduleSnapshot.of(scheduleOf(found.get()));
        adminAuditService.record(actor, userId, VERB_PLAID_ITEM, null, snapshot, snapshot, null);
        return done(VERB_PLAID_ITEM, VerbResult.ok(PlaidItemDump.of(found.get())));
    }

    public VerbResult webhooks(String actor, String status) {
        String wanted = status == null || status.isBlank() ? "FAILED" : status.trim().toUpperCase(Locale.ROOT);
        PlaidWebhookEventStatus parsed = Arrays.stream(PlaidWebhookEventStatus.values())
                .filter(s -> s.name().equals(wanted)).findFirst().orElse(null);
        if (parsed == null) {
            adminAuditService.record(actor, null, VERB_WEBHOOKS, ColumnText.fit(wanted, TARGET_MAX), null, null, "BAD_STATUS");
            return done(VERB_WEBHOOKS, VerbResult.refusal(400, "BAD_STATUS", "unknown inbox status"));
        }
        List<AdminUserStatusView.InboxRow> rows = webhookEventRepository.findByStatusOrderByReceivedAtAsc(parsed)
                .stream().limit(WEBHOOK_LIST_MAX).map(AdminUserStatusView.InboxRow::of).toList();
        adminAuditService.record(actor, null, VERB_WEBHOOKS, parsed.name(), null, null, null);
        return done(VERB_WEBHOOKS, VerbResult.ok(rows));
    }

    // Pipeline verbs: nothing open around the call.

    public VerbResult sync(String actor, Long userId) {
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return refuseUnknownUser(actor, userId, VERB_SYNC);
        }
        ScheduleSnapshot before = ScheduleSnapshot.of(scheduleOf(found.get()));
        String itemId = found.get().getPlaidItemId();
        if (itemId == null) {
            adminAuditService.record(actor, userId, VERB_SYNC, null, before, before, "NO_ITEM");
            return done(VERB_SYNC, VerbResult.refusal(409, "NO_ITEM", "the user has no linked Plaid Item"));
        }
        var outcome = pipeline.run(userId, itemId, SyncReason.MANUAL);
        Map<String, Object> body = new LinkedHashMap<>();
        if (outcome != null) {
            body.put("sync", outcome.sync() == null ? null : name(outcome.sync().status()));
            body.put("errorCode", outcome.sync() == null || outcome.sync().error() == null
                    ? null : outcome.sync().error().errorCode());
            body.put("streams", outcome.streams() == null ? null : name(outcome.streams().status()));
            body.put("evaluation", outcome.evaluation() == null ? null : name(outcome.evaluation().state()));
            body.put("itemState", name(outcome.itemState()));
        }
        auditInOwnTransaction(actor, userId, VERB_SYNC, null, before, snapshotFor(userId), null);
        return done(VERB_SYNC, VerbResult.ok(body));
    }

    public VerbResult evaluate(String actor, Long userId) {
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return refuseUnknownUser(actor, userId, VERB_EVALUATE);
        }
        ScheduleSnapshot before = ScheduleSnapshot.of(scheduleOf(found.get()));
        var outcome = detectionService.evaluate(userId, EvaluationTrigger.RECONCILE, EasternTime.today());
        Map<String, Object> body = new LinkedHashMap<>();
        if (outcome != null) {
            body.put("state", name(outcome.state()));
            body.put("primaryStreamId", outcome.primaryStreamId());
            body.put("primaryChanged", outcome.primaryChanged());
            body.put("switched", outcome.switched());
            body.put("excluded", outcome.excluded() == null ? null : outcome.excluded().map(Enum::name).orElse(null));
        }
        auditInOwnTransaction(actor, userId, VERB_EVALUATE, null, before, snapshotFor(userId), null);
        return done(VERB_EVALUATE, VerbResult.ok(body));
    }

    public VerbResult reconcile(String actor, Long userId) {
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty()) {
            return refuseUnknownUser(actor, userId, VERB_RECONCILE);
        }
        ScheduleSnapshot before = ScheduleSnapshot.of(scheduleOf(found.get()));
        PaycheckReconcileService.ReconcileReport report = reconcileService.runForUser(userId, EasternTime.today());
        auditInOwnTransaction(actor, userId, VERB_RECONCILE, null, before, snapshotFor(userId), null);
        return done(VERB_RECONCILE, VerbResult.ok(report));
    }

    public VerbResult replay(String actor, Long eventId) {
        Optional<PlaidWebhookEvent> row = webhookEventRepository.findById(eventId);
        if (row.isEmpty()) {
            adminAuditService.record(actor, null, VERB_REPLAY, String.valueOf(eventId), null, null, "WEBHOOK_NOT_FOUND");
            return done(VERB_REPLAY, VerbResult.refusal(404, "WEBHOOK_NOT_FOUND", "no inbox row with that id"));
        }
        Long userId = row.get().getUserId();
        String previousStatus = name(row.get().getStatus());
        ScheduleSnapshot before = snapshotFor(userId);
        ReplayResult replayed = webhookProcessor.replay(eventId);
        if (replayed != ReplayResult.REQUEUED) {
            boolean inFlight = replayed == ReplayResult.IN_FLIGHT;
            String code = inFlight ? "WEBHOOK_IN_FLIGHT" : "WEBHOOK_NOT_FOUND";
            adminAuditService.record(actor, userId, VERB_REPLAY, String.valueOf(eventId), before, before, code);
            return done(VERB_REPLAY, VerbResult.refusal(inFlight ? 409 : 404, code, "the row was not requeued"));
        }
        auditInOwnTransaction(actor, userId, VERB_REPLAY, String.valueOf(eventId), before, snapshotFor(userId),
                "requeued from " + previousStatus);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", eventId);
        body.put("previousStatus", previousStatus);
        body.put("requeued", true);
        return done(VERB_REPLAY, VerbResult.ok(body));
    }

    // Mutating verbs: one template under the user row lock.

    public VerbResult hold(String actor, Long userId, String requestedReason, String note) {
        HoldReason wanted = null;
        if (requestedReason != null && !requestedReason.isBlank()) {
            wanted = Arrays.stream(HoldReason.values()).filter(h -> h.name().equals(requestedReason.trim()))
                    .findFirst().orElse(null);
            if (wanted == null || !HOLD_VERB_REASONS.contains(wanted)) {
                adminAuditService.record(actor, userId, VERB_HOLD, ColumnText.fit(requestedReason.trim(), TARGET_MAX),
                        null, null, "HOLD_RESERVED");
                return done(VERB_HOLD, VerbResult.refusal(422, "HOLD_RESERVED",
                        "allowed: ITEM_LOGIN_REQUIRED, PERMISSION_REVOKED, CONSENT_REQUIRED, BANK_CHANGE, null"));
            }
        }
        HoldReason target = wanted;
        return mutate(actor, userId, VERB_HOLD, target == null ? "null" : target.name(), note, (user, schedule) -> {
            if (schedule == null) {
                return VerbResult.refusal(409, "NO_SCHEDULE", "the user has no schedule row");
            }
            if (InvestmentSchedule.isBlockingHold(schedule.getHoldReason())) {
                return VerbResult.refusal(409, "HOLD_BLOCKING", "a blocking hold is lifted only by clear-review");
            }
            boolean changed = schedule.getHoldReason() != target;
            if (changed) {
                schedule.setHoldReason(target);
                scheduleRepository.save(schedule);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("changed", changed);
            body.put("holdReason", name(target));
            return VerbResult.ok(body);
        });
    }

    public VerbResult clearReview(String actor, Long userId, String note) {
        return mutate(actor, userId, VERB_CLEAR_REVIEW, null, note, (user, schedule) -> {
            if ("REJECTED".equals(user.getAccountStatus())) {
                return VerbResult.refusal(409, "ACCOUNT_REJECTED", "a rejected applicant's MANUAL_REVIEW hold stays");
            }
            if (schedule == null) {
                return VerbResult.refusal(409, "NO_SCHEDULE", "the user has no schedule row");
            }
            if (schedule.getHoldReason() != HoldReason.MANUAL_REVIEW) {
                return VerbResult.refusal(409, "NOT_IN_REVIEW", "the hold is not MANUAL_REVIEW");
            }
            boolean wasActive = schedule.getStatus() == Status.ACTIVE;
            schedule.setHoldReason(null);
            scheduleRepository.save(schedule);
            boolean resumed = wasActive && !InvestmentSchedule.isBlockingHold(schedule.getHoldReason());
            if (resumed) {
                recordScheduleTransition(user, ServerAnalyticsEvent.SCHEDULE_RESUMED,
                        ServerAnalyticsEvent.INITIATOR_SYSTEM, ServerAnalyticsEvent.REASON_REVIEW_CLEARED);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cleared", true);
            body.put("resumed", resumed);
            return VerbResult.ok(body);
        });
    }

    public VerbResult promoteStream(String actor, Long userId, Long streamId, String note) {
        return mutate(actor, userId, VERB_PROMOTE_STREAM, String.valueOf(streamId), note, (user, schedule) -> {
            Optional<PaycheckStream> found = streamRepository.findById(streamId).filter(s -> userId.equals(s.getUserId()));
            if (found.isEmpty()) {
                return VerbResult.refusal(404, "STREAM_NOT_FOUND", "no such stream for this user");
            }
            PaycheckStream stream = found.get();
            if (stream.getState() == PaycheckStreamState.RETIRED || stream.getState() == PaycheckStreamState.REJECTED) {
                return VerbResult.refusal(409, "STREAM_NOT_PROMOTABLE", "a retired or rejected stream cannot be primary");
            }
            LocalDateTime now = EasternTime.now();
            int demoted = 0;
            for (PaycheckStream other : streamRepository.findByUserIdOrderByIdAsc(userId)) {
                if (!other.getId().equals(streamId) && other.getRole() == PaycheckStreamRole.PRIMARY) {
                    other.setRole(PaycheckStreamRole.SECONDARY);
                    other.setUpdatedAt(now);
                    streamRepository.save(other);
                    demoted++;
                }
            }
            stream.setRole(PaycheckStreamRole.PRIMARY);
            stream.setState(PaycheckStreamState.PROVISIONAL);
            stream.setUpdatedAt(now);
            streamRepository.save(stream);
            if (schedule != null) {
                schedule.setPrimaryStreamId(streamId);
                scheduleRepository.save(schedule);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("streamId", streamId);
            body.put("demoted", demoted);
            return VerbResult.ok(body);
        });
    }

    public VerbResult retireStream(String actor, Long userId, Long streamId, String note) {
        return mutate(actor, userId, VERB_RETIRE_STREAM, String.valueOf(streamId), note, (user, schedule) -> {
            Optional<PaycheckStream> found = streamRepository.findById(streamId).filter(s -> userId.equals(s.getUserId()));
            if (found.isEmpty()) {
                return VerbResult.refusal(404, "STREAM_NOT_FOUND", "no such stream for this user");
            }
            PaycheckStream stream = found.get();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("changed", stream.getState() != PaycheckStreamState.RETIRED);
            if (stream.getState() == PaycheckStreamState.RETIRED) {
                return VerbResult.ok(body);
            }
            LocalDateTime now = EasternTime.now();
            stream.setState(PaycheckStreamState.RETIRED);
            stream.setRetiredReason(PaycheckRetiredReason.ADMIN);
            stream.setRetiredAt(now);
            stream.setRole(PaycheckStreamRole.NONE);
            stream.setUpdatedAt(now);
            streamRepository.save(stream);
            int voided = 0;
            for (PaycheckCycle open : cycleRepository.findByUserIdAndState(userId, PaycheckCycleState.OPEN)) {
                if (streamId.equals(open.getStreamId())) {
                    voidAsAdmin(open, now);
                    voided++;
                }
            }
            if (schedule != null && streamId.equals(schedule.getPrimaryStreamId())) {
                schedule.setPrimaryStreamId(null);
                schedule.setNextExpectedDate(null);
                scheduleRepository.save(schedule);
            }
            body.put("openCyclesVoided", voided);
            return VerbResult.ok(body);
        });
    }

    public VerbResult voidCycle(String actor, Long userId, Long cycleId, String note) {
        return mutate(actor, userId, VERB_VOID_CYCLE, String.valueOf(cycleId), note, (user, schedule) -> {
            Optional<PaycheckCycle> found = cycleRepository.findById(cycleId).filter(c -> userId.equals(c.getUserId()));
            if (found.isEmpty()) {
                return VerbResult.refusal(404, "CYCLE_NOT_FOUND", "no such cycle for this user");
            }
            PaycheckCycle cycle = found.get();
            if (cycle.getExecutionId() != null) {
                return VerbResult.refusal(409, "CYCLE_HAS_EXECUTION", "a cycle with an execution is never voided here");
            }
            if (cycle.getState() != PaycheckCycleState.OPEN && cycle.getState() != PaycheckCycleState.CONFIRMED) {
                return VerbResult.refusal(409, "CYCLE_NOT_VOIDABLE", "only OPEN and CONFIRMED cycles are voided");
            }
            voidAsAdmin(cycle, EasternTime.now());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cycleId", cycleId);
            body.put("state", name(cycle.getState()));
            return VerbResult.ok(body);
        });
    }

    // Shared machinery.

    private VerbResult mutate(String actor, Long userId, String verb, String targetId, String note, Mutation mutation) {
        String reason = ColumnText.fit(note, NOTE_MAX);
        ScheduleSnapshot[] before = {ScheduleSnapshot.NONE};
        VerbResult result = writeTx.execute(tx -> {
            Optional<User> locked = userRepository.findByIdForUpdate(userId);
            if (locked.isEmpty()) {
                return VerbResult.refusal(404, USER_NOT_FOUND, "no user with that id");
            }
            InvestmentSchedule schedule = scheduleOf(locked.get());
            before[0] = ScheduleSnapshot.of(schedule);
            VerbResult outcome = mutation.apply(locked.get(), schedule);
            if (!outcome.isRefusal()) {
                adminAuditService.recordInTransaction(actor, userId, verb, targetId, before[0],
                        ScheduleSnapshot.of(schedule), reason);
            }
            return outcome;
        });
        if (result.isRefusal()) {
            // The template wrote nothing; the refusal is still recorded, in its own transaction.
            adminAuditService.record(actor, userId, verb, targetId, before[0], before[0], result.code());
        }
        return done(verb, result);
    }

    private void voidAsAdmin(PaycheckCycle cycle, LocalDateTime now) {
        cycle.setState(PaycheckCycleState.VOIDED);
        cycle.setVoidedReason(PaycheckVoidedReason.ADMIN);
        cycle.setUpdatedAt(now);
        cycleRepository.save(cycle);
    }

    private void auditInOwnTransaction(String actor, Long userId, String verb, String targetId,
                                       ScheduleSnapshot before, ScheduleSnapshot after, String reason) {
        writeTx.executeWithoutResult(tx ->
                adminAuditService.recordInTransaction(actor, userId, verb, targetId, before, after, reason));
    }

    private VerbResult refuseUnknownUser(String actor, Long userId, String verb) {
        adminAuditService.record(actor, userId, verb, null, null, null, USER_NOT_FOUND);
        return done(verb, VerbResult.refusal(404, USER_NOT_FOUND, "no user with that id"));
    }

    private InvestmentSchedule scheduleOf(User user) {
        return scheduleRepository.findTopByUserOrderByCreatedAtDesc(user).orElse(null);
    }

    private ScheduleSnapshot snapshotFor(Long userId) {
        return userId == null ? ScheduleSnapshot.NONE
                : userRepository.findById(userId).map(u -> ScheduleSnapshot.of(scheduleOf(u))).orElse(ScheduleSnapshot.NONE);
    }

    /** As InvestmentScheduleService.recordScheduleTransition: exactly initiator then reason. */
    private void recordScheduleTransition(User user, String event, String initiator, String reason) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("initiator", initiator);
        props.put("reason", reason);
        analyticsOutboxService.record(event, user, props);
    }

    /** INFO carries the verb, the HTTP status and the refusal code; ids live in admin_actions. */
    private static VerbResult done(String verb, VerbResult result) {
        logger.info("admin.action verb={} http={} code={}", verb, result.httpStatus(), result.code());
        return result;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
```

Then run the Step 4 command as the main file says. Expected: `BUILD SUCCESSFUL`, 21 tests (12 methods; the three parameterized ones give 2, 3 and 7), 0 failures, 0 skipped in `build/test-results/test/TEST-com.investingapp.backend.service.AdminPaycheckServiceTest.xml`. Read the counts with:

```
cd backend
./gradlew test --tests 'com.investingapp.backend.service.AdminPaycheckServiceTest' --console=plain
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.service.AdminPaycheckServiceTest.xml
cd ..
```

Expected: `tests="21" skipped="0" failures="0"`.

## Step 9: AdminPaycheckController, complete

The main file shows four handlers (`status`, `hold`, `clearReview`, `webhooks`) and describes eight more. All twelve are written out here, in the Step 11 table's order. Each copies its model exactly: every `@PathVariable` is named, and every handler returns `respond(service.<same name>(actor(request), ...))`.

- As `status`: `plaidItem` on `@GetMapping("/{userId}/plaid-item")`; `sync`, `evaluate` and `reconcile` on `@PostMapping("/{userId}/sync")`, `@PostMapping("/{userId}/evaluate")` and `@PostMapping("/{userId}/reconcile")`, each passing `userId`.
- As `status` with `@PathVariable("eventId") Long eventId` in place of `userId`: `replay` on `@PostMapping("/webhooks/{eventId}/replay")`.
- As `clearReview` (optional `NoteRequest` body) with one more path variable passed before `note(body)`: `promoteStream` on `@PostMapping("/{userId}/promote-stream/{streamId}")` and `retireStream` on `@PostMapping("/{userId}/retire-stream/{streamId}")` (`Long streamId`), and `voidCycle` on `@PostMapping("/{userId}/cycles/{cycleId}/void")` (`Long cycleId`).

No two mappings collide: `/webhooks/{eventId}/replay` has a literal last segment that no `/{userId}/...` POST path of three segments shares, and `GET /webhooks` has one segment where every per-user GET has two. The imports are unchanged from the main file (`org.springframework.web.bind.annotation.*` covers `GetMapping`, `PostMapping`, `PathVariable`, `RequestBody`, `RequestParam`). T19 later adds its shadow-report verb and a last constructor parameter to this class; nothing here pre-empts that.

Create `backend/src/main/java/com/investingapp/backend/controller/admin/AdminPaycheckController.java`:

```java
package com.investingapp.backend.controller.admin;

import com.investingapp.backend.service.AdminAuditService;
import com.investingapp.backend.service.AdminPaycheckService;
import com.investingapp.backend.service.AdminPaycheckService.VerbResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/paycheck")
public class AdminPaycheckController {

    public record HoldRequest(String reason, String note) {
    }

    public record NoteRequest(String note) {
    }

    private final AdminPaycheckService service;
    private final AdminAuditService adminAuditService;

    public AdminPaycheckController(AdminPaycheckService service, AdminAuditService adminAuditService) {
        this.service = service;
        this.adminAuditService = adminAuditService;
    }

    @GetMapping("/{userId}/status")
    public ResponseEntity<Object> status(@PathVariable("userId") Long userId, HttpServletRequest request) {
        return respond(service.status(actor(request), userId));
    }

    @GetMapping("/{userId}/plaid-item")
    public ResponseEntity<Object> plaidItem(@PathVariable("userId") Long userId, HttpServletRequest request) {
        return respond(service.plaidItem(actor(request), userId));
    }

    @PostMapping("/{userId}/sync")
    public ResponseEntity<Object> sync(@PathVariable("userId") Long userId, HttpServletRequest request) {
        return respond(service.sync(actor(request), userId));
    }

    @PostMapping("/{userId}/evaluate")
    public ResponseEntity<Object> evaluate(@PathVariable("userId") Long userId, HttpServletRequest request) {
        return respond(service.evaluate(actor(request), userId));
    }

    @PostMapping("/{userId}/reconcile")
    public ResponseEntity<Object> reconcile(@PathVariable("userId") Long userId, HttpServletRequest request) {
        return respond(service.reconcile(actor(request), userId));
    }

    @PostMapping("/{userId}/hold")
    public ResponseEntity<Object> hold(@PathVariable("userId") Long userId, @RequestBody HoldRequest body,
                                       HttpServletRequest request) {
        return respond(service.hold(actor(request), userId, body.reason(), body.note()));
    }

    @PostMapping("/{userId}/clear-review")
    public ResponseEntity<Object> clearReview(@PathVariable("userId") Long userId,
                                              @RequestBody(required = false) NoteRequest body,
                                              HttpServletRequest request) {
        return respond(service.clearReview(actor(request), userId, note(body)));
    }

    @PostMapping("/{userId}/promote-stream/{streamId}")
    public ResponseEntity<Object> promoteStream(@PathVariable("userId") Long userId,
                                                @PathVariable("streamId") Long streamId,
                                                @RequestBody(required = false) NoteRequest body,
                                                HttpServletRequest request) {
        return respond(service.promoteStream(actor(request), userId, streamId, note(body)));
    }

    @PostMapping("/{userId}/retire-stream/{streamId}")
    public ResponseEntity<Object> retireStream(@PathVariable("userId") Long userId,
                                               @PathVariable("streamId") Long streamId,
                                               @RequestBody(required = false) NoteRequest body,
                                               HttpServletRequest request) {
        return respond(service.retireStream(actor(request), userId, streamId, note(body)));
    }

    @PostMapping("/{userId}/cycles/{cycleId}/void")
    public ResponseEntity<Object> voidCycle(@PathVariable("userId") Long userId,
                                            @PathVariable("cycleId") Long cycleId,
                                            @RequestBody(required = false) NoteRequest body,
                                            HttpServletRequest request) {
        return respond(service.voidCycle(actor(request), userId, cycleId, note(body)));
    }

    @GetMapping("/webhooks")
    public ResponseEntity<Object> webhooks(@RequestParam(name = "status", defaultValue = "FAILED") String status,
                                           HttpServletRequest request) {
        return respond(service.webhooks(actor(request), status));
    }

    @PostMapping("/webhooks/{eventId}/replay")
    public ResponseEntity<Object> replay(@PathVariable("eventId") Long eventId, HttpServletRequest request) {
        return respond(service.replay(actor(request), eventId));
    }

    private String actor(HttpServletRequest request) {
        return adminAuditService.actor(request);
    }

    private static String note(NoteRequest body) {
        return body == null ? null : body.note();
    }

    private static ResponseEntity<Object> respond(VerbResult result) {
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
```

Step 8's test class is unchanged: it still has exactly two tests, so Step 9's expected count stays `BUILD SUCCESSFUL`, 2 tests, 0 failures. The eight added handlers add no test (the main file describes none, and adding one would change the stated count):

```
cd backend
./gradlew test --tests 'com.investingapp.backend.controller.admin.AdminPaycheckControllerTest' --console=plain
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.controller.admin.AdminPaycheckControllerTest.xml
cd ..
```

Expected: `tests="2" skipped="0" failures="0"`.

## Steps 3 and 12: the Verification rows as commands

Step 3 says "Run the Verification rows for `ScheduleTransitionAnalyticsTest`, `AnalyticsOutboxForbiddenPayloadTest` and `ServerAnalyticsEventParityTest`", and Step 12 says "Run every row of the Verification table". The main file gives each backend row as `(cd backend && ./gradlew test --tests '<class>' --console=plain)` with counts from the XML. Written out, from the repository root:

```
for c in service.ScheduleTransitionAnalyticsTest service.AnalyticsOutboxForbiddenPayloadTest util.ServerAnalyticsEventParityTest; do
  (cd backend && ./gradlew test --tests "com.investingapp.backend.$c" --console=plain)
  printf '%s: ' "$c"
  grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' "backend/build/test-results/test/TEST-com.investingapp.backend.$c.xml"
done
```

Expected at Step 3: `ScheduleTransitionAnalyticsTest` the count before this task (T23 added one) plus 1; `AnalyticsOutboxForbiddenPayloadTest` `tests="20"`; `ServerAnalyticsEventParityTest` its count unchanged by this task; every line `skipped="0" failures="0"`.

Step 12, the remaining unit rows (no database needed):

```
for c in service.AdminPaycheckServiceTest controller.admin.AdminPaycheckControllerTest service.ScheduleTransitionAnalyticsTest service.AnalyticsOutboxForbiddenPayloadTest util.ServerAnalyticsEventParityTest PhaseOneMoneyInvarianceTest; do
  (cd backend && ./gradlew test --tests "com.investingapp.backend.$c" --console=plain)
  printf '%s: ' "$c"
  grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' "backend/build/test-results/test/TEST-com.investingapp.backend.$c.xml"
done
(cd frontend && npm run test:events-documented); echo "events-documented exit $?"
```

Expected: `AdminPaycheckServiceTest` `tests="21"`; `AdminPaycheckControllerTest` `tests="2"`; `ScheduleTransitionAnalyticsTest` the count before this task plus 1; `AnalyticsOutboxForbiddenPayloadTest` `tests="20"`; `ServerAnalyticsEventParityTest` and `PhaseOneMoneyInvarianceTest` green with counts unchanged by this task; every line `skipped="0" failures="0"`; `events-documented exit 0`.

The `AdminPaycheckServiceRollbackTest` row is the Step 10 block exactly as the main file gives it (expected `tests="2" skipped="0" failures="0"`).

The whole-suite row, "the Step 10 block with its gradle line changed to `./gradlew cleanTest test --console=plain`", written out in full. Run it from the repository root against a scratch MySQL 8 on 127.0.0.1:3306; the password comes from a prompt, never a command line, and is never echoed:

```
read -rs SCRATCH_PW; echo
MYSQL_PWD="$SCRATCH_PW" mysql -h 127.0.0.1 -u root -e "CREATE DATABASE fred_t18"
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t18?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD="$SCRATCH_PW"
export PLAID_WEBHOOK_URL='https://ci.invalid/api/plaid/webhook' PLAID_REDIRECT_URI='https://ci.invalid/plaid/oauth' PLAID_LINK_CUSTOMIZATION='default'
cd backend && ./gradlew cleanTest test --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/*.xml
cd .. && MYSQL_PWD="$SCRATCH_PW" mysql -h 127.0.0.1 -u root -e "DROP DATABASE fred_t18"
unset SCRATCH_PW SPRING_DATASOURCE_PASSWORD
```

Expected: Flyway migrates the empty schema through V10, then every printed line reads `skipped="0" failures="0"`. Any `skipped` above 0 means a `@RequiresCiDatabase` class did not reach the database: fix the connection and re-run. Read the XML only, never the console.

The four `grep` checks after the Verification table are already given in full in the main file and are unchanged.

## Disagreements and clarifications found

1. PATTERN B says `reconcile` "returns `VerbResult.ok(reconcileService.runForUser(userId, EasternTime.today()))`", but the same note says both verbs audit with `auditInOwnTransaction` after the call, and every verb in the main file's code returns through `done(...)` (the INFO line the Verification greps expect). The code here follows the main file's code: call, audit, then `return done(VERB_RECONCILE, VerbResult.ok(report))`. The body is the same `ReconcileReport`.
2. ROW PATTERN says each argument is "the getter of the same name". Three components have no same-named getter or a different type: `InboxRow.attempts` is an `int` fed by `PlaidWebhookEvent.getAttempts()` (`short`, widened, as `StreamRow` already does for its three `short` columns), and `PlaidItemDump.hasAccessToken` / `hasSyncCursor` use the presence expressions the main file gives. No disagreement in behaviour.
3. Step 5 says "plus `import java.util.List;` if T14 did not add it". T14 does add it (with `findByStatusOrderByReceivedAtAsc`), so no import edit belongs to this task; the appendix's Step 5 check stops the task if it is missing.
4. Step 9's prose names `promoteStream`, `retireStream` and `voidCycle` paths without the HTTP method; "as `clearReview`" and the Step 11 table both make them `POST`, which is what is written here.
5. No disagreement was found between the Step 11 table and the code on paths, status codes or error codes: `USER_NOT_FOUND` 404 (every verb), `NO_ITEM` 409 (sync only), `HOLD_RESERVED` 422, `HOLD_BLOCKING` / `NO_SCHEDULE` 409, `ACCOUNT_REJECTED` / `NOT_IN_REVIEW` 409, `STREAM_NOT_FOUND` 404 and `STREAM_NOT_PROMOTABLE` 409, `CYCLE_NOT_FOUND` 404 and `CYCLE_HAS_EXECUTION` / `CYCLE_NOT_VOIDABLE` 409, `BAD_STATUS` 400, `WEBHOOK_NOT_FOUND` 404 and `WEBHOOK_IN_FLIGHT` 409; 50 cycles (`CYCLE_LIST_MAX`), 20 inbox rows (`findTop20ByUserIdOrderByReceivedAtDesc`), at most 200 webhook rows (`WEBHOOK_LIST_MAX`). The table abbreviates some 404s as a bare "404" (the codes are `STREAM_NOT_FOUND` and `CYCLE_NOT_FOUND` in the code).
