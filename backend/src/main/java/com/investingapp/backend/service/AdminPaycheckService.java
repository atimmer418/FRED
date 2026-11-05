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
