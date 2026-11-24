### Task 04: ops-alert-service

Full code for the steps this file gives as tables or patterns is in task-04-ops-alert-service-appendix.md; read both.

Builds the ops alert channel that spec 12.3 makes a Phase 1 gate: `OpsAlertService.raise(type, subjectId, message, context)` with a once-per-day dedupe ledger in `ops_alerts`, two providers chosen by `app.ops.alert.provider` (`logging`, the default outside prod, and `webhook`, a JSON POST to a Slack or Discord style incoming webhook), the prod startup refusal in `BrokerEnvironmentGuard`, and every Phase 0 `alert.*` log line routed through the service (R11). Each routed site keeps its `logger.error` line verbatim and adds one `raise` directly under it. Nothing here moves money, writes `InvestmentSchedule.status`, changes `isReadyForInvestment`, or writes a hold.

`.../` = `backend/src/main/java/com/investingapp/backend/`; tests mirror it under `backend/src/test/java/com/investingapp/backend/`. Line numbers are HEAD 137998c, verified; an earlier insert in the same file shifts them, so match the quoted text. Commands run from the worktree root; a leading `cd backend &&` applies to that line only. No earlier Phase 1 task edits a Java file this task modifies; T03 adds lines to the property files, so property anchors are quoted text.

**Facts this task depends on:**

- T01 created `V10__ops_alerts_and_admin_actions.sql`, `model/OpsAlert` (Lombok `@Getter @Setter @NoArgsConstructor`: `Long id`, `alertType` (64), `subjectId` (80), `LocalDate alertDay` (Eastern day), `message` (500), `contextJson` (TEXT), `int occurrences = 1`, `LocalDateTime firstRaisedAt`, `lastRaisedAt`, `deliveryStatus = "PENDING"` (12)) and `OpsAlertRepository.findByAlertTypeAndSubjectIdAndAlertDay(String, String, LocalDate): Optional<OpsAlert>`; unique key `uq_ops_alerts_type_subject_day (alert_type, subject_id, alert_day)`.
- R11: providers `logging` and `webhook`; `email` only if a real `EmailSender` exists. The only implementation at HEAD is `service/LoggingEmailSender.java`, so **`email` is not offered**. Prod refuses `logging`. `util/AnalyticsPrivacyGuard.java:187` is analytics' own line in a static utility and stays log-only. R16: `logging` is the default outside prod; Andy sets `OPS_ALERT_PROVIDER` and `OPS_ALERT_WEBHOOK_URL` on Railway dev when he wants alerts there.
- No HTTP inside a database transaction (decision 37): the ledger row commits in its own REQUIRES_NEW transaction and delivery runs on a one-thread `opsAlertExecutor`, so a slow receiver never holds a caller's thread, Hikari connection or row lock (`adopt`, `record` and `applyToFreshRows` raise from inside transactions).
- Content rule (spec 12.3, handoff 8): `message` and `context` go to a third-party receiver and carry internal ids (user, Plaid Item, removal row, job name), counts, codes and closed words only; never an amount, payer name, descriptor, transaction id, deposit date, token, cursor or free-text error. So the raises drop `total`, `lastError` and `error`; the `logger.error` lines stay as Phase 0 wrote them.
- A Slack or Discord webhook URL embeds its token: nothing prints it. `raise` never throws.

**Files:**
- Create: `.../ops/OpsAlerts.java`, `OpsAlertMessage.java`, `OpsAlertSender.java`, `OpsAlertService.java`, `LoggingOpsAlertSender.java`, `WebhookOpsAlertSender.java`
- Modify: `.../config/SchedulingConfig.java` (`:8`, after `:41`); `.../config/BrokerEnvironmentGuard.java` (after `:68`, `:141`, `:183`); `.../service/JobLockService.java` (`:3`, `:14`, `:36`, `:38`, `:43`, `:56`, `:66`); `.../service/AchRelationshipService.java` (`:7`, `:53`, `:61`, `:68`, sites `:170, 197, 205, 233, 243-244, 256, 268`); `.../service/PlaidItemLifecycleService.java` (`:5`, `:31`, `:60`, `:69`, `:77`, `:161-162`); `.../service/PlaidWebhookInboxService.java` (`:7`, `:23`, `:44`, `:105`); `.../service/InvestmentExecutionService.java` (`:6`, `:94`, `:258`, `:320`, `:399-400`); `.../controller/TradingController.java` (`:9`, `:59`, `:312`, `:323`, `:356`); `application.properties`, `application-ci.properties`, `application-prod.properties` under `backend/src/main/resources/`
- Test: create `ops/OpsAlertServiceTest`, `ops/WebhookOpsAlertSenderTest`, `config/OpsAlertExecutorTest`, `AlertSitesRoutedTest` (package root); modify `config/BrokerEnvironmentGuardTest`, `config/ProdPlaidPropertiesTest`, `service/JobLockServiceTest`, `service/AchRelationshipServiceTest`, `service/PlaidItemLifecycleServiceTest`, `service/PlaidWebhookInboxServiceTest`, `service/InvestmentExecutionServiceEodBatchTest`, `controller/TradingControllerCloseAccountTest`

**Interfaces:**
- Consumes: `model.OpsAlert` (getters, setters), `OpsAlertRepository.findByAlertTypeAndSubjectIdAndAlertDay(String, String, LocalDate): Optional<OpsAlert>`, inherited `save` and `findById` (T01); `EasternTime.now()`, `EasternTime.ZONE` (Phase 0).
- Produces: listed exactly in the Produces recap at the end of this file.

- [ ] **Step 1: Confirm T01 landed and nothing of this task exists yet**

```
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1
ls backend/src/main/resources/db/migration/ | grep V10
grep -n 'findByAlertTypeAndSubjectIdAndAlertDay' backend/src/main/java/com/investingapp/backend/repository/OpsAlertRepository.java
grep -n 'private' backend/src/main/java/com/investingapp/backend/model/OpsAlert.java
ls backend/src/main/java/com/investingapp/backend/ops
```

Expected: `V10__ops_alerts_and_admin_actions.sql`; the finder declared returning `Optional<OpsAlert>`; the ten fields named above; `ls: ... ops: No such file or directory`. If the finder returns a bare `OpsAlert` or a field name differs, stop and report to the controller; do not edit T01's files.

- [ ] **Step 2: Failing test for the service**

Create `backend/src/test/java/com/investingapp/backend/ops/OpsAlertServiceTest.java`:

```java
package com.investingapp.backend.ops;

import com.investingapp.backend.model.OpsAlert;
import com.investingapp.backend.ops.OpsAlertService.OpsAlertOutcome;
import com.investingapp.backend.repository.OpsAlertRepository;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The ledger is a map behind a mocked repository; the mocked transaction manager still runs callbacks. */
class OpsAlertServiceTest {

    private final OpsAlertRepository repository = mock(OpsAlertRepository.class);
    private final OpsAlertSender sender = mock(OpsAlertSender.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final Map<String, OpsAlert> rows = new HashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final Executor synchronous = Runnable::run;

    private static String key(String type, String subject, LocalDate day) {
        return type + "|" + subject + "|" + day;
    }

    @BeforeEach
    void fakeLedger() throws Exception {
        when(repository.findByAlertTypeAndSubjectIdAndAlertDay(anyString(), anyString(), any(LocalDate.class)))
                .thenAnswer(i -> Optional.ofNullable(rows.get(key(i.getArgument(0), i.getArgument(1), i.getArgument(2)))));
        when(repository.save(any(OpsAlert.class))).thenAnswer(i -> {
            OpsAlert row = i.getArgument(0);
            if (row.getId() == null) {
                row.setId(ids.incrementAndGet());
            }
            rows.put(key(row.getAlertType(), row.getSubjectId(), row.getAlertDay()), row);
            return row;
        });
        when(repository.findById(anyLong())).thenAnswer(i -> rows.values().stream()
                .filter(r -> r.getId().equals(i.getArgument(0))).findFirst());
        when(sender.send(any())).thenReturn(OpsAlertService.DELIVERY_SENT);
    }

    private OpsAlertService service(Executor executor) {
        return new OpsAlertService(repository, sender, transactionManager, executor);
    }

    private OpsAlert only() {
        assertThat(rows).hasSize(1);
        return rows.values().iterator().next();
    }

    @Test
    void secondRaiseSameDayIsDeduped() throws Exception {
        OpsAlertService service = service(synchronous);

        assertThat(service.raise(OpsAlerts.ACH_CANCEL_STUCK, "7", "cancel stuck", Map.of("user", "7")))
                .isEqualTo(OpsAlertOutcome.QUEUED);
        assertThat(service.raise(OpsAlerts.ACH_CANCEL_STUCK, "7", "cancel stuck", Map.of("user", "7")))
                .isEqualTo(OpsAlertOutcome.DEDUPED);

        verify(sender, times(1)).send(any());
        OpsAlert row = rows.get(key(OpsAlerts.ACH_CANCEL_STUCK, "7", EasternTime.today()));
        assertThat(row.getOccurrences()).isEqualTo(2);
        assertThat(row.getDeliveryStatus()).isEqualTo(OpsAlertService.DELIVERY_SENT);
        assertThat(row.getContextJson()).isEqualTo("{\"user\":\"7\"}");
        assertThat(row.getLastRaisedAt()).isAfterOrEqualTo(row.getFirstRaisedAt());

        // Another subject is another alert, the same day.
        assertThat(service.raise(OpsAlerts.ACH_CANCEL_STUCK, "8", "cancel stuck")).isEqualTo(OpsAlertOutcome.QUEUED);
        verify(sender, times(2)).send(any());
    }

    @Test
    void ledgerAndDeliveryStatusEachRunInTheirOwnRequiresNewTransaction() {
        service(synchronous).raise(OpsAlerts.JOB_LOCK_UNAVAILABLE, "ach.relationship-poll", "unreachable");

        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues()).extracting(TransactionDefinition::getPropagationBehavior)
                .containsOnly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void senderFailureNeverThrows() throws Exception {
        doThrow(new IOException("receiver answered 500")).when(sender).send(any());

        OpsAlertOutcome outcome = assertDoesNotThrow(
                () -> service(synchronous).raise(OpsAlerts.ACH_CREATE_FAILED, "7", "create failed"));

        assertThat(outcome).isEqualTo(OpsAlertOutcome.QUEUED);
        assertThat(only().getDeliveryStatus()).isEqualTo(OpsAlertService.DELIVERY_FAILED);

        doThrow(new IllegalStateException("boom")).when(sender).send(any());
        assertDoesNotThrow(() -> service(synchronous).raise(OpsAlerts.ACH_CREATE_FAILED, "9", "create failed"));
    }

    @Test
    void ledgerFailureStillSends() throws Exception {
        doThrow(new DataAccessResourceFailureException("datasource down"))
                .when(repository).findByAlertTypeAndSubjectIdAndAlertDay(anyString(), anyString(), any(LocalDate.class));

        OpsAlertOutcome outcome = service(synchronous)
                .raise(OpsAlerts.JOB_LOCK_UNAVAILABLE, "investment.eod-batch", "job_locks unreachable");

        assertThat(outcome).isEqualTo(OpsAlertOutcome.FAILED);
        ArgumentCaptor<OpsAlertMessage> sent = ArgumentCaptor.forClass(OpsAlertMessage.class);
        verify(sender).send(sent.capture());
        assertThat(sent.getValue().type()).isEqualTo(OpsAlerts.JOB_LOCK_UNAVAILABLE);
        assertThat(sent.getValue().subjectId()).isEqualTo("investment.eod-batch");
        // No row id, so there is no delivery status to write.
        verify(repository, never()).findById(anyLong());
    }

    @Test
    void raiseNeverCallsSenderOnCallerThread() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        OpsAlertService service = service(queued::add);

        assertThat(service.raise(OpsAlerts.BATCH_CLAIM_SHORT, "42", "claim short")).isEqualTo(OpsAlertOutcome.QUEUED);

        verifyNoInteractions(sender);
        assertThat(queued).hasSize(1);
        // The ledger row is committed before delivery is even attempted.
        assertThat(only().getDeliveryStatus()).isEqualTo(OpsAlertService.DELIVERY_PENDING);

        AtomicReference<String> senderThread = new AtomicReference<>();
        doAnswer(i -> {
            senderThread.set(Thread.currentThread().getName());
            return OpsAlertService.DELIVERY_SENT;
        }).when(sender).send(any());
        Thread worker = new Thread(queued.get(0), "ops-alert-test");
        worker.start();
        worker.join(5000);

        assertThat(senderThread.get()).isEqualTo("ops-alert-test");
        assertThat(only().getDeliveryStatus()).isEqualTo(OpsAlertService.DELIVERY_SENT);
    }

    @Test
    void overlongMessageIsCutAndStillDedupes() throws Exception {
        String longSubject = "s".repeat(100);
        String longMessage = "m".repeat(600);
        OpsAlertService service = service(synchronous);

        assertThat(service.raise(OpsAlerts.BATCH_ROWS_MISSING, longSubject, longMessage)).isEqualTo(OpsAlertOutcome.QUEUED);
        OpsAlert row = only();
        assertThat(row.getSubjectId()).hasSize(OpsAlertService.SUBJECT_MAX);
        assertThat(row.getMessage()).hasSize(OpsAlertService.MESSAGE_MAX);

        assertThat(service.raise(OpsAlerts.BATCH_ROWS_MISSING, longSubject, longMessage)).isEqualTo(OpsAlertOutcome.DEDUPED);
        assertThat(only().getOccurrences()).isEqualTo(2);

        ArgumentCaptor<OpsAlertMessage> sent = ArgumentCaptor.forClass(OpsAlertMessage.class);
        verify(sender).send(sent.capture());
        assertThat(sent.getValue().message()).hasSize(OpsAlertService.MESSAGE_MAX);
    }

    @Test
    void concurrentFirstInsertCountsAsDedupe() throws Exception {
        OpsAlert winner = new OpsAlert();
        winner.setId(500L);
        winner.setAlertType(OpsAlerts.ACH_UNKNOWN_RELATIONSHIP);
        winner.setSubjectId("7");
        winner.setAlertDay(EasternTime.today());
        doReturn(Optional.empty()).doReturn(Optional.of(winner))
                .when(repository).findByAlertTypeAndSubjectIdAndAlertDay(anyString(), anyString(), any(LocalDate.class));
        doThrow(new DataIntegrityViolationException("uq_ops_alerts_type_subject_day"))
                .doAnswer(i -> i.getArgument(0))
                .when(repository).save(any(OpsAlert.class));

        OpsAlertOutcome outcome = service(synchronous).raise(OpsAlerts.ACH_UNKNOWN_RELATIONSHIP, "7", "unknown relationship");

        assertThat(outcome).isEqualTo(OpsAlertOutcome.DEDUPED);
        assertThat(winner.getOccurrences()).isEqualTo(2);
        verify(sender, never()).send(any());
    }

    @Test
    void nullSubjectMessageAndContextBecomeGlobalEmptyAndEmpty() {
        service(synchronous).raise(OpsAlerts.PLAID_WEBHOOK_AMBIGUOUS_ITEM, null, null, null);

        OpsAlert row = only();
        assertThat(row.getSubjectId()).isEqualTo(OpsAlertService.GLOBAL_SUBJECT);
        assertThat(row.getMessage()).isEmpty();
        assertThat(row.getContextJson()).isEqualTo("{}");
        assertThat(row.getAlertDay()).isEqualTo(EasternTime.today());
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.ops.OpsAlertServiceTest' --console=plain
```

Expected: `compileTestJava FAILED` with `error: cannot find symbol` for `class OpsAlertSender`, `class OpsAlertService`, `class OpsAlertMessage` and `variable OpsAlerts`.

- [ ] **Step 4: The vocabulary, the message and the port**

Create `.../ops/OpsAlerts.java`:

```java
package com.investingapp.backend.ops;

/** ops_alerts.alert_type values (max 64). Phase 0 names are the old log keys without "alert.". */
public final class OpsAlerts {

    public static final String USER_DELETION_DEFERRED = "user_deletion.deferred";
    public static final String ACH_ADOPT_REFUSED = "ach.adopt_refused";
    public static final String ACH_CANCEL_FAILED = "ach.cancel_failed";
    public static final String ACH_POLL_LIST_FAILED = "ach.poll_list_failed";
    public static final String ACH_UNKNOWN_RELATIONSHIP = "ach.unknown_relationship";
    public static final String ACH_CANCEL_STUCK = "ach.cancel_stuck";
    public static final String ACH_CREATE_FAILED = "ach.create_failed";
    public static final String BATCH_ROWS_MISSING = "batch_rows_missing";
    public static final String BATCH_CLAIM_SHORT = "batch_claim_short";
    public static final String BATCH_NO_RELATIONSHIP = "batch_no_relationship";
    public static final String JOB_LOCK_UNAVAILABLE = "job_lock.unavailable";
    public static final String JOB_LOCK_RELEASE_FAILED = "job_lock.release_failed";
    public static final String PLAID_ITEM_REMOVE_EXHAUSTED = "plaid.item_remove.exhausted";
    public static final String PLAID_WEBHOOK_AMBIGUOUS_ITEM = "plaid.webhook.ambiguous_item";

    private OpsAlerts() {
    }
}
```

Create `.../ops/OpsAlertMessage.java`:

```java
package com.investingapp.backend.ops;

import java.time.LocalDateTime;
import java.util.Map;

/** One alert, cut to the ledger widths; raisedAt is Eastern wall time; context holds ids, counts and codes only. */
public record OpsAlertMessage(String type, String subjectId, String message, Map<String, Object> context,
        LocalDateTime raisedAt) {
}
```

Create `.../ops/OpsAlertSender.java`:

```java
package com.investingapp.backend.ops;

/** Chosen by app.ops.alert.provider (the EmailSender port shape); called only on opsAlertExecutor. */
public interface OpsAlertSender {

    /** Returns OpsAlertService.DELIVERY_SENT or DELIVERY_LOGGED; throws when the receiver was not reached. */
    String send(OpsAlertMessage alert) throws Exception;
}
```

- [ ] **Step 5: The service**

Create `.../ops/OpsAlertService.java`:

```java
package com.investingapp.backend.ops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.OpsAlert;
import com.investingapp.backend.repository.OpsAlertRepository;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * The ops alert channel (spec 12.3). One ops_alerts row per (type, subject, Eastern day): the first
 * raise of the day is delivered, repeats only bump occurrences. The row commits in its own
 * REQUIRES_NEW transaction; delivery runs on opsAlertExecutor, never on the caller's thread or inside
 * its transaction; the outcome is written back in a second REQUIRES_NEW. If the ledger cannot be
 * written (datasource down) the alert is still delivered and raise returns FAILED. Never throws.
 */
@Service
public class OpsAlertService {

    public enum OpsAlertOutcome { QUEUED, DEDUPED, FAILED }

    public static final String GLOBAL_SUBJECT = "global";
    public static final int TYPE_MAX = 64;
    public static final int SUBJECT_MAX = 80;
    public static final int MESSAGE_MAX = 500;

    public static final String DELIVERY_PENDING = "PENDING";
    public static final String DELIVERY_SENT = "SENT";
    public static final String DELIVERY_LOGGED = "LOGGED";
    public static final String DELIVERY_FAILED = "FAILED";

    private static final Logger logger = LoggerFactory.getLogger(OpsAlertService.class);

    private final OpsAlertRepository repository;
    private final OpsAlertSender sender;
    private final TransactionTemplate requiresNew;
    private final Executor opsAlertExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpsAlertService(OpsAlertRepository repository,
            OpsAlertSender sender,
            PlatformTransactionManager transactionManager,
            @Qualifier("opsAlertExecutor") Executor opsAlertExecutor) {
        this.repository = repository;
        this.sender = sender;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.opsAlertExecutor = opsAlertExecutor;
    }

    public OpsAlertOutcome raise(String type, String subjectId, String message) {
        return raise(type, subjectId, message, Map.of());
    }

    public OpsAlertOutcome raise(String type, String subjectId, String message, Map<String, Object> context) {
        OpsAlertMessage alert;
        try {
            alert = new OpsAlertMessage(
                    fit(blankTo(type, "unknown"), TYPE_MAX),
                    fit(blankTo(subjectId, GLOBAL_SUBJECT), SUBJECT_MAX),
                    fit(message == null ? "" : message, MESSAGE_MAX),
                    context == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(context)),
                    EasternTime.now());
        } catch (RuntimeException e) {
            logger.warn("ops.alert.unbuildable cause={}", e.getClass().getSimpleName());
            return OpsAlertOutcome.FAILED;
        }

        Long rowId;
        try {
            Ledger ledger = writeLedger(alert);
            if (ledger.deduped()) {
                logger.info("ops.alert.deduped type={} subject={}", alert.type(), alert.subjectId());
                return OpsAlertOutcome.DEDUPED;
            }
            rowId = ledger.rowId();
        } catch (RuntimeException e) {
            // The class name only: a driver message can carry the JDBC URL.
            logger.warn("ops.alert.ledger_failed type={} subject={} cause={}",
                    alert.type(), alert.subjectId(), e.getClass().getSimpleName());
            rowId = null;
        }

        boolean submitted = submit(alert, rowId);
        return rowId != null && submitted ? OpsAlertOutcome.QUEUED : OpsAlertOutcome.FAILED;
    }

    private record Ledger(Long rowId, boolean deduped) {
    }

    private Ledger writeLedger(OpsAlertMessage alert) {
        LocalDate day = alert.raisedAt().toLocalDate();
        try {
            return requiresNew.execute(status -> insertOrBump(alert, day));
        } catch (DataIntegrityViolationException raced) {
            // A concurrent raise inserted the same key first; read again and count it.
            return requiresNew.execute(status -> insertOrBump(alert, day));
        }
    }

    private Ledger insertOrBump(OpsAlertMessage alert, LocalDate day) {
        Optional<OpsAlert> existing = repository.findByAlertTypeAndSubjectIdAndAlertDay(alert.type(), alert.subjectId(), day);
        if (existing.isPresent()) {
            OpsAlert row = existing.get();
            row.setOccurrences(row.getOccurrences() + 1);
            row.setLastRaisedAt(alert.raisedAt());
            repository.save(row);
            return new Ledger(row.getId(), true);
        }
        OpsAlert row = new OpsAlert();
        row.setAlertType(alert.type());
        row.setSubjectId(alert.subjectId());
        row.setAlertDay(day);
        row.setMessage(alert.message());
        row.setContextJson(json(alert.context()));
        row.setOccurrences(1);
        row.setFirstRaisedAt(alert.raisedAt());
        row.setLastRaisedAt(alert.raisedAt());
        row.setDeliveryStatus(DELIVERY_PENDING);
        OpsAlert saved = repository.save(row);
        return new Ledger(saved.getId(), false);
    }

    private boolean submit(OpsAlertMessage alert, Long rowId) {
        try {
            opsAlertExecutor.execute(() -> deliver(alert, rowId));
            return true;
        } catch (RuntimeException e) {
            logger.warn("ops.alert.not_queued type={} subject={} cause={}",
                    alert.type(), alert.subjectId(), e.getClass().getSimpleName());
            return false;
        }
    }

    /** Runs on opsAlertExecutor. Catches everything: an executor thread has nobody to throw to. */
    private void deliver(OpsAlertMessage alert, Long rowId) {
        String status;
        try {
            status = sender.send(alert);
        } catch (Exception e) {
            status = DELIVERY_FAILED;
            logger.warn("ops.alert.delivery_failed type={} subject={} cause={}",
                    alert.type(), alert.subjectId(), e.getClass().getSimpleName());
        }
        if (rowId == null) {
            return;
        }
        String finalStatus = status == null ? DELIVERY_FAILED : status;
        try {
            requiresNew.executeWithoutResult(tx -> repository.findById(rowId).ifPresent(row -> {
                row.setDeliveryStatus(finalStatus);
                repository.save(row);
            }));
        } catch (RuntimeException e) {
            logger.warn("ops.alert.status_write_failed id={} cause={}", rowId, e.getClass().getSimpleName());
        }
    }

    private String json(Map<String, Object> context) {
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String fit(String value, int width) {
        return value.length() <= width ? value : value.substring(0, width);
    }
}
```

- [ ] **Step 6: Run the service test**

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.ops.OpsAlertServiceTest' --console=plain
```

Expected: `BUILD SUCCESSFUL`, 8 tests, 0 failures (`grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.ops.OpsAlertServiceTest.xml` prints `tests="8" skipped="0" failures="0" errors="0"`). Plain unit test: no Spring context starts.

- [ ] **Step 7: Failing tests for the two senders**

Create `backend/src/test/java/com/investingapp/backend/ops/WebhookOpsAlertSenderTest.java` (a JDK `HttpServer` on loopback stands in for the receiver; the last test also pins the logging sender):

```java
package com.investingapp.backend.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookOpsAlertSenderTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private volatile int answer = 204;

    @BeforeEach
    void startReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hook/secret-token", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.sendResponseHeaders(answer, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopReceiver() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook/secret-token";
    }

    private static OpsAlertMessage alert() {
        return new OpsAlertMessage(OpsAlerts.ACH_CANCEL_STUCK, "7", "cancel stuck over 24 hours",
                Map.of("user", "7"), LocalDateTime.of(2026, 11, 4, 9, 7));
    }

    @Test
    void postsOneJsonDocumentWithEveryField() throws Exception {
        WebhookOpsAlertSender sender = new WebhookOpsAlertSender(url(), "dev", HttpClient.newHttpClient());

        assertThat(sender.send(alert())).isEqualTo(OpsAlertService.DELIVERY_SENT);

        assertThat(contentType.get()).startsWith("application/json");
        JsonNode json = new ObjectMapper().readTree(body.get());
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactly("type", "subject", "message", "context", "env", "at", "text", "content");
        assertThat(json.get("type").asText()).isEqualTo("ach.cancel_stuck");
        assertThat(json.get("subject").asText()).isEqualTo("7");
        assertThat(json.get("message").asText()).isEqualTo("cancel stuck over 24 hours");
        assertThat(json.get("context").get("user").asText()).isEqualTo("7");
        assertThat(json.get("env").asText()).isEqualTo("dev");
        assertThat(json.get("at").asText()).isEqualTo("2026-11-04T09:07-05:00");
        assertThat(json.get("text").asText()).isEqualTo("[dev] ach.cancel_stuck 7: cancel stuck over 24 hours");
        assertThat(json.get("content").asText()).isEqualTo(json.get("text").asText());
    }

    @Test
    void non2xxIsAFailureAndNeverEchoesTheUrl() {
        answer = 500;
        WebhookOpsAlertSender sender = new WebhookOpsAlertSender(url(), "dev", HttpClient.newHttpClient());

        assertThatThrownBy(() -> sender.send(alert()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("500")
                .hasMessageNotContaining("secret-token");
    }

    @Test
    void blankUrlFailsAndTheLoggingSenderReportsLogged() {
        WebhookOpsAlertSender sender = new WebhookOpsAlertSender(" ", "dev", HttpClient.newHttpClient());

        assertThatThrownBy(() -> sender.send(alert()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ops.alert.webhook-url is not set");
        assertThat(WebhookOpsAlertSender.TIMEOUT).isEqualTo(Duration.ofSeconds(5));
        assertThat(new LoggingOpsAlertSender().send(alert())).isEqualTo(OpsAlertService.DELIVERY_LOGGED);
    }
}
```

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.ops.WebhookOpsAlertSenderTest' --console=plain
```

Expected: `compileTestJava FAILED`, `cannot find symbol: class LoggingOpsAlertSender` and `class WebhookOpsAlertSender`.

- [ ] **Step 8: The two senders**

Create `.../ops/LoggingOpsAlertSender.java`:

```java
package com.investingapp.backend.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default outside prod: the alert goes to the log at WARN only. BrokerEnvironmentGuard refuses it in prod. */
@Component
@ConditionalOnProperty(name = "app.ops.alert.provider", havingValue = "logging", matchIfMissing = true)
public class LoggingOpsAlertSender implements OpsAlertSender {

    private static final Logger logger = LoggerFactory.getLogger(LoggingOpsAlertSender.class);

    @Override
    public String send(OpsAlertMessage alert) {
        logger.warn("ops.alert type={} subject={} message={} context={} at={}",
                alert.type(), alert.subjectId(), alert.message(), alert.context(), alert.raisedAt());
        return OpsAlertService.DELIVERY_LOGGED;
    }
}
```

Create `.../ops/WebhookOpsAlertSender.java`:

```java
package com.investingapp.backend.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.util.EasternTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POSTs one JSON document per alert to app.ops.alert.webhook-url. "text" (Slack) and "content"
 * (Discord) repeat one summary line, since each receiver rejects a body without its own field. The
 * URL embeds the receiver's token: never logged, never in an exception message.
 */
@Component
@ConditionalOnProperty(name = "app.ops.alert.provider", havingValue = "webhook")
public class WebhookOpsAlertSender implements OpsAlertSender {

    static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final String webhookUrl;
    private final String env;
    private final HttpClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public WebhookOpsAlertSender(@Value("${app.ops.alert.webhook-url:}") String webhookUrl, Environment environment) {
        this(webhookUrl, String.join(",", environment.getActiveProfiles()),
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    WebhookOpsAlertSender(String webhookUrl, String env, HttpClient client) {
        this.webhookUrl = webhookUrl;
        this.env = env == null || env.isBlank() ? "default" : env;
        this.client = client;
    }

    @Override
    public String send(OpsAlertMessage alert) throws Exception {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalStateException("app.ops.alert.webhook-url is not set");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl.trim()))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload(alert)), StandardCharsets.UTF_8))
                .build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("ops alert receiver answered HTTP " + response.statusCode());
        }
        return OpsAlertService.DELIVERY_SENT;
    }

    Map<String, Object> payload(OpsAlertMessage alert) {
        String summary = "[" + env + "] " + alert.type() + " " + alert.subjectId() + ": " + alert.message();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", alert.type());
        payload.put("subject", alert.subjectId());
        payload.put("message", alert.message());
        payload.put("context", alert.context());
        payload.put("env", env);
        payload.put("at", alert.raisedAt().atZone(EasternTime.ZONE).toOffsetDateTime().toString());
        payload.put("text", summary);
        payload.put("content", summary);
        return payload;
    }
}
```

Run, one class at a time:

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.ops.WebhookOpsAlertSenderTest' --console=plain
```

Expected: `BUILD SUCCESSFUL`, 3 tests, 0 failures.

- [ ] **Step 9: Failing test for the executor bean**

Not `SchedulingConfigTest`: T14 creates that. Create `backend/src/test/java/com/investingapp/backend/config/OpsAlertExecutorTest.java`:

```java
package com.investingapp.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class OpsAlertExecutorTest {

    @Test
    void opsAlertExecutorIsOneThreadQueue200DroppingTheOldest() {
        ThreadPoolTaskExecutor executor = new SchedulingConfig().opsAlertExecutor();
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(200);
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.DiscardOldestPolicy.class);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("ops-alert-");
        } finally {
            executor.shutdown();
        }
    }
}
```

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.config.OpsAlertExecutorTest' --console=plain
```

Expected: `compileTestJava FAILED`, `cannot find symbol: method opsAlertExecutor()`.

- [ ] **Step 10: The executor bean**

`SchedulingConfig.java`: after `:8` (`import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;`) add a blank line and `import java.util.concurrent.ThreadPoolExecutor;`. After `:41` (the `}` closing `plaidExecutor()`) insert:

```java

    /**
     * Ops alert delivery (spec 12.3): the HTTP call never holds a caller's thread, connection or lock.
     * Past 200 waiting, the oldest is dropped (its ledger row stays PENDING). Injected by name.
     */
    @Bean(name = "opsAlertExecutor")
    public ThreadPoolTaskExecutor opsAlertExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("ops-alert-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
```

Run the Step 9 command. Expected: `BUILD SUCCESSFUL`, 1 test.

- [ ] **Step 11: Failing guard tests**

`BrokerEnvironmentGuardTest.java`: after `:28` (`private static final String PROD_JWT_SECRET = "prod-signing-key-not-the-dev-one";`) add:

```java
    private static final String OPS_WEBHOOK = "https://alerts.example.invalid/hook";
```

Replace `:73-74`, exactly:

```java
            env.withProperty("spring.datasource.url", PROD_DATASOURCE)
                    .withProperty("jwt.secret", PROD_JWT_SECRET);
```

with (every prod fixture, `prodLiveStarts` included, needs a valid alert provider or it goes red):

```java
            env.withProperty("spring.datasource.url", PROD_DATASOURCE)
                    .withProperty("jwt.secret", PROD_JWT_SECRET)
                    .withProperty("app.ops.alert.provider", "webhook")
                    .withProperty("app.ops.alert.webhook-url", OPS_WEBHOOK);
```

Before the final `}` of the class (after `localKnownSandboxSecretStarts`, `:338`) add:

```java

    @Test
    @DisplayName("prod refuses the logging or a blank alert provider: an alert nobody reads is no alert")
    void prodRefusesLoggingAlertProvider() {
        for (String provider : new String[] {"logging", ""}) {
            assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production")
                    .withProperty("app.ops.alert.provider", provider)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.ops.alert.provider=" + provider + " cannot deliver alerts");
        }
    }

    @Test
    void prodRefusesWebhookProviderWithoutUrl() {
        assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production")
                .withProperty("app.ops.alert.webhook-url", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ops.alert.webhook-url is not set");
    }

    @Test
    void prodRefusesPlainHttpAlertWebhookAndNeverEchoesIt() {
        assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production")
                .withProperty("app.ops.alert.webhook-url", "http://alerts.example.invalid/hook/secret-token-xyz")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.ops.alert.webhook-url must start with https://")
                .hasMessageNotContaining("secret-token-xyz");
    }

    @Test
    void nonProdLoggingAlertProviderStarts() {
        assertThatCode(() -> run(env("dev", SANDBOX_BROKER, PAPER_TRADING, "sandbox")
                .withProperty("app.ops.alert.provider", "logging")))
                .doesNotThrowAnyException();
    }
```

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.config.BrokerEnvironmentGuardTest' --console=plain
```

Expected: `BUILD FAILED`, 32 tests, 3 failures (`prodRefusesLoggingAlertProvider`, `prodRefusesWebhookProviderWithoutUrl`, `prodRefusesPlainHttpAlertWebhookAndNeverEchoesIt`), each `Expecting code to raise a throwable.`

- [ ] **Step 12: The guard rule**

`BrokerEnvironmentGuard.java`: after `:68` (`static final String PLAID_ENV_PRODUCTION = "production";`) add:

```java

    /** app.ops.alert.provider values (spec 12.3): logging writes to the log only, webhook reaches a person. */
    static final String OPS_ALERT_PROVIDER_LOGGING = "logging";
    static final String OPS_ALERT_PROVIDER_WEBHOOK = "webhook";
```

After `:141` (`            checkProdJwtSecret(violations);`) add:

```java
            checkProdOpsAlertProvider(violations);
```

After `:183` (the `}` closing `checkProdWebhookUrl`) insert:

```java

    /**
     * Spec 12.1, 12.3: a prod alert must reach a person, so logging is refused, and so is webhook
     * without an https receiver. The URL embeds its token: violations name the property, never the value.
     */
    private void checkProdOpsAlertProvider(List<String> violations) {
        String provider = environment.getProperty("app.ops.alert.provider", OPS_ALERT_PROVIDER_LOGGING);
        if (!OPS_ALERT_PROVIDER_WEBHOOK.equals(provider)) {
            violations.add("app.ops.alert.provider=" + provider
                    + " cannot deliver alerts to a person in prod; set OPS_ALERT_PROVIDER=webhook");
            return;
        }
        String url = environment.getProperty("app.ops.alert.webhook-url", "");
        if (url.isBlank()) {
            violations.add("app.ops.alert.webhook-url is not set, so prod alerts would go nowhere");
        } else if (!url.trim().toLowerCase().startsWith("https://")) {
            violations.add("app.ops.alert.webhook-url must start with https:// in prod");
        }
    }
```

Run the Step 11 command. Expected: `BUILD SUCCESSFUL`, 32 tests, 0 failures.

- [ ] **Step 13: Properties, with a failing prod-file test first**

`ProdPlaidPropertiesTest.java`: before the final `}` of the class add:

```java

    @Test
    @DisplayName("prod ops alerts default to the webhook provider and read the receiver URL from the environment")
    void prodOpsAlertsDefaultToWebhook() throws IOException {
        Properties props = prodProperties();
        assertThat(props.getProperty("app.ops.alert.provider")).isEqualTo("${OPS_ALERT_PROVIDER:webhook}");
        assertThat(props.getProperty("app.ops.alert.webhook-url")).isEqualTo("${OPS_ALERT_WEBHOOK_URL:}");
    }
```

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.config.ProdPlaidPropertiesTest' --console=plain
```

Expected: `BUILD FAILED`, 4 tests, 1 failure: `expected: "${OPS_ALERT_PROVIDER:webhook}" but was: null`.

`application.properties`: after the line `app.email.provider=${EMAIL_PROVIDER:logging}` insert:

```properties

# Ops alerts (spec 12.3): logging = log only; webhook = JSON POST to a Slack or Discord webhook.
app.ops.alert.provider=${OPS_ALERT_PROVIDER:logging}
app.ops.alert.webhook-url=${OPS_ALERT_WEBHOOK_URL:}
```

`application-ci.properties`: after the line `app.email.enabled=false` insert:

```properties

# --- Ops alerts: written to the log in CI ------------------------------------
app.ops.alert.provider=logging
```

`application-prod.properties`: after `:6` (`#   PLAID_WEBHOOK_URL, PLAID_LINK_CUSTOMIZATION, PLAID_REDIRECT_URI.`) insert the header line:

```properties
#   Ops alerts: OPS_ALERT_WEBHOOK_URL (https, the alert receiver); OPS_ALERT_PROVIDER defaults to webhook.
```

and after the line `plaid.redirect.uri=${PLAID_REDIRECT_URI}` insert:

```properties

# Ops alerts must reach a person in prod: BrokerEnvironmentGuard refuses logging and a blank or http URL.
app.ops.alert.provider=${OPS_ALERT_PROVIDER:webhook}
app.ops.alert.webhook-url=${OPS_ALERT_WEBHOOK_URL:}
```

Run the command above. Expected: `BUILD SUCCESSFUL`, 4 tests.

**Local-properties note.** `backend/src/main/resources/application-local.properties` is gitignored, so this commit cannot reach it. Add these two lines to it by hand (key names only; do not paste a real receiver URL into any tracked file):

```properties
app.ops.alert.provider=${OPS_ALERT_PROVIDER:logging}
app.ops.alert.webhook-url=${OPS_ALERT_WEBHOOK_URL:}
```

Local then runs on `logging`; to try `webhook`, export `OPS_ALERT_PROVIDER` and `OPS_ALERT_WEBHOOK_URL` in the `bootRun` shell.

- [ ] **Step 14: Failing source walk for the routed sites**

Create `backend/src/test/java/com/investingapp/backend/AlertSitesRoutedTest.java`:

```java
package com.investingapp.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** R11: every alert.* line is also raised, and no raise carries money or free-text error text. */
class AlertSitesRoutedTest {

    private static final Path MAIN = Paths.get("src", "main", "java");
    private static final Set<String> LOG_ONLY = Set.of("OpsAlertService.java", "AnalyticsPrivacyGuard.java");
    private static final Pattern ALERT_LINE = Pattern.compile("logger\\.error\\(\"alert\\.");
    private static final String RAISE = "opsAlertService.raise(";
    private static final Pattern FORBIDDEN_ARGUMENT = Pattern.compile("\\b(total|amount|getLastError|getMessage|error)\\b");
    private static final int PHASE_ZERO_SITES = 15;

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    void everyAlertLineIsRoutedThroughOpsAlertService() throws IOException {
        List<String> unrouted = new ArrayList<>();
        int sites = 0;
        for (Path file : sources()) {
            if (LOG_ONLY.contains(file.getFileName().toString())) {
                continue;
            }
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (!ALERT_LINE.matcher(lines.get(i)).find()) {
                    continue;
                }
                sites++;
                boolean routed = false;
                for (int j = i + 1; j <= Math.min(i + 3, lines.size() - 1); j++) {
                    if (lines.get(j).contains(RAISE)) {
                        routed = true;
                        break;
                    }
                }
                if (!routed) {
                    unrouted.add(MAIN.relativize(file) + ":" + (i + 1));
                }
            }
        }
        assertThat(unrouted).as("alert.* lines with no " + RAISE + " within the next 3 lines").isEmpty();
        assertThat(sites).as("alert.* sites found (a moved file must not pass by vanishing)")
                .isGreaterThanOrEqualTo(PHASE_ZERO_SITES);
    }

    @Test
    void routedRaisesCarryNoAmountOrErrorText() throws IOException {
        List<String> offenders = new ArrayList<>();
        int raises = 0;
        for (Path file : sources()) {
            if (file.getFileName().toString().equals("OpsAlertService.java")) {
                continue;
            }
            String text = Files.readString(file);
            int from = 0;
            while ((from = text.indexOf(RAISE, from)) >= 0) {
                int open = from + RAISE.length() - 1;
                int close = matchingParen(text, open);
                raises++;
                Matcher m = FORBIDDEN_ARGUMENT.matcher(text.substring(open + 1, close));
                if (m.find()) {
                    int line = (int) text.substring(0, from).chars().filter(c -> c == '\n').count() + 1;
                    offenders.add(MAIN.relativize(file) + ":" + line + " names " + m.group(1));
                }
                from = close;
            }
        }
        assertThat(raises).as(RAISE + " calls found").isGreaterThanOrEqualTo(PHASE_ZERO_SITES);
        assertThat(offenders).as("raise arguments naming an amount or free-text error").isEmpty();
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && text.charAt(i - 1) != '\\') {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        throw new IllegalStateException("unbalanced " + RAISE + " call");
    }
}
```

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.AlertSitesRoutedTest' --console=plain
```

Expected: `BUILD FAILED`, 2 tests, 2 failures. The first lists exactly 15 entries `com/investingapp/backend/<file>:<line>`: `controller/TradingController.java:356`; `service/AchRelationshipService.java` 170, 197, 205, 233, 243, 256, 268; `service/InvestmentExecutionService.java` 258, 320, 399; `service/JobLockService.java` 56, 66; `service/PlaidItemLifecycleService.java:161`; `service/PlaidWebhookInboxService.java:105`. The second reports `[opsAlertService.raise( calls found] Expecting actual: 0 to be greater than or equal to: 15`.

- [ ] **Step 15: Route JobLockService (2 sites)**

`JobLockService.java`: directly before `:3` (`import com.investingapp.backend.repository.JobLockRepository;`) add `import com.investingapp.backend.ops.OpsAlertService;` and `import com.investingapp.backend.ops.OpsAlerts;`; after `:14` (`import java.time.ZoneOffset;`) add `import java.util.Map;`. After `:36` (`    private final String owner;`) add `    private final OpsAlertService opsAlertService;`; replace `:38` (`    public JobLockService(JobLockRepository jobLockRepository, PlatformTransactionManager transactionManager) {`) with:

```java
    public JobLockService(JobLockRepository jobLockRepository, PlatformTransactionManager transactionManager,
            OpsAlertService opsAlertService) {
```

and after `:43` (`        this.owner = runtimeName.length() > 64 ? runtimeName.substring(0, 64) : runtimeName;`) add `        this.opsAlertService = opsAlertService;`.

Directly under `:56` (`            logger.error("alert.job_lock.unavailable job={}", name, e);`) add:

```java
            opsAlertService.raise(OpsAlerts.JOB_LOCK_UNAVAILABLE, name, "job_locks unreachable; this run is skipped",
                    Map.of("job", name, "cause", e.getClass().getSimpleName()));
```

Directly under `:66` (`            logger.error("alert.job_lock.release_failed job={}", name, e);`) add:

```java
            opsAlertService.raise(OpsAlerts.JOB_LOCK_RELEASE_FAILED, name, "job_locks release failed; the lease expires on its own",
                    Map.of("job", name, "cause", e.getClass().getSimpleName()));
```

`JobLockServiceTest.java`: add imports `com.investingapp.backend.ops.OpsAlertService`, `com.investingapp.backend.ops.OpsAlerts`, `java.util.Map`; add the field `private OpsAlertService opsAlertService;`; in `setUp` replace `service = new JobLockService(repository, transactionManager);` with:

```java
        opsAlertService = mock(OpsAlertService.class);
        service = new JobLockService(repository, transactionManager, opsAlertService);
```

At the end of `databaseFailureReadsAsNotAcquiredAndNeverThrows` (after `verify(transactionManager).rollback(any());`) add:

```java
        verify(opsAlertService).raise(eq(OpsAlerts.JOB_LOCK_UNAVAILABLE), eq(JobLocks.EOD_BATCH), anyString(),
                eq(Map.of("job", JobLocks.EOD_BATCH, "cause", "DataAccessResourceFailureException")));
```

At the end of `releaseSwallowsDatabaseFailuresBecauseTheLeaseExpiresAnyway` add:

```java
        verify(opsAlertService).raise(eq(OpsAlerts.JOB_LOCK_RELEASE_FAILED), eq(JobLocks.EOD_BATCH), anyString(),
                eq(Map.of("job", JobLocks.EOD_BATCH, "cause", "DataAccessResourceFailureException")));
```

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.service.JobLockServiceTest' --console=plain
```

Expected: `BUILD SUCCESSFUL`, 7 tests. Each of Steps 16 to 19 changes one production class and its test together, so the test tree compiles after every step; Step 20 runs every touched class.

- [ ] **Step 16: Route AchRelationshipService (7 sites)**

`AchRelationshipService.java`: after `:7` (`import com.investingapp.backend.model.User;`) add `import com.investingapp.backend.ops.OpsAlertService;` and `import com.investingapp.backend.ops.OpsAlerts;`. In the field block after `:53` (`    private final AnalyticsOutboxService analyticsOutboxService;`) add `    private final OpsAlertService opsAlertService;`. Replace `:61` (`            AnalyticsOutboxService analyticsOutboxService) {`) with:

```java
            AnalyticsOutboxService analyticsOutboxService,
            OpsAlertService opsAlertService) {
```

and after `:68` (`        this.analyticsOutboxService = analyticsOutboxService;`) add `        this.opsAlertService = opsAlertService;`.

Add one `raise` directly under each alert line, at that line's indentation. Every id goes through `String.valueOf`, so a null id never makes `Map.of` throw. The first site, in full (under `:170`, `logger.error("alert.ach.adopt_refused ...`):

```java
        opsAlertService.raise(OpsAlerts.ACH_ADOPT_REFUSED, String.valueOf(user.getId()),
                "ACH relationship adoption refused; schedule held for MANUAL_REVIEW",
                Map.of("user", String.valueOf(user.getId()), "reason", reason.name()));
```

The other six follow that exact shape, `opsAlertService.raise(<type>, String.valueOf(user.getId()), "<message>", Map.of("user", String.valueOf(user.getId())<, extra>));`, with these values:

| under HEAD line | type | message | extra context entries |
|---|---|---|---|
| `:197` (`stage=list error={}`) | `OpsAlerts.ACH_CANCEL_FAILED` | `listing ACH relationships for the bank-change cancel failed` | `"stage", "list"` |
| `:205` (`stage=delete rel={}`) | `OpsAlerts.ACH_CANCEL_FAILED` | `deleting an ACH relationship for the bank-change cancel failed` | `"stage", "delete"` |
| `:233` (`poll_list_failed`) | `OpsAlerts.ACH_POLL_LIST_FAILED` | `listing ACH relationships for the 15-minute poll failed` | `"stage", "poll_list"` |
| `:244` (`existing.stream().map(AchRelationshipSummary::id).toList());`, the second line of the `unknown_relationship` call) | `OpsAlerts.ACH_UNKNOWN_RELATIONSHIP` | `an ACH relationship FRED did not create sits on the account; the poll is waiting` | `"count", existing.size()` |
| `:256` (`cancel_stuck`) | `OpsAlerts.ACH_CANCEL_STUCK` | `previous ACH relationship still CANCEL_REQUESTED after 24 hours` | none |
| `:268` (`create_failed`) | `OpsAlerts.ACH_CREATE_FAILED` | `creating the ACH relationship failed; the poll retries in 15 minutes` | `"stage", "create"` |

`AchRelationshipServiceTest.java`: add imports `com.investingapp.backend.ops.OpsAlertService` and `com.investingapp.backend.ops.OpsAlerts`; after `:49` (`private final AnalyticsOutboxService analyticsOutboxService = mock(AnalyticsOutboxService.class);`) add `    private final OpsAlertService opsAlertService = mock(OpsAlertService.class);`; replace `:57` (`                plaidToAlpaca, analyticsOutboxService);`) with `                plaidToAlpaca, analyticsOutboxService, opsAlertService);`. At the end of `adoptRefusesMaskMismatch` (after `assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.PENDING_BANK);`) add:

```java
        verify(opsAlertService).raise(eq(OpsAlerts.ACH_ADOPT_REFUSED), eq("7"), anyString(),
                eq(Map.of("user", "7", "reason", "REFUSED_MASK_MISMATCH")));
```

- [ ] **Step 17: Route PlaidItemLifecycleService and PlaidWebhookInboxService (1 site each)**

`PlaidItemLifecycleService.java`: after `:5` (`import com.investingapp.backend.model.User;`) add the two `ops` imports; after `:31` (`import java.util.List;`) add `import java.util.Map;`. After `:60` (`    private final String plaidWebhookUrl;`) add `    private final OpsAlertService opsAlertService;`. Replace `:69` (`            @Value("${plaid.webhook.url}") String plaidWebhookUrl) {`) with:

```java
            @Value("${plaid.webhook.url}") String plaidWebhookUrl,
            OpsAlertService opsAlertService) {
```

and after `:77` (`        this.plaidWebhookUrl = plaidWebhookUrl;`) add `        this.opsAlertService = opsAlertService;`. Directly under `:162` (`                        row.getId(), row.getItemId(), row.getAttempts(), row.getLastError());`) add (no `lastError`: it holds Plaid's error text):

```java
                opsAlertService.raise(OpsAlerts.PLAID_ITEM_REMOVE_EXHAUSTED, String.valueOf(row.getId()),
                        "Plaid /item/remove retries exhausted after 7 days; the Item may still bill",
                        Map.of("id", String.valueOf(row.getId()), "item", String.valueOf(row.getItemId()),
                                "attempts", row.getAttempts()));
```

`PlaidItemLifecycleServiceTest.java`: add the two `ops` imports, `java.util.Map` and `import static org.mockito.ArgumentMatchers.anyString;`; add the field `private OpsAlertService opsAlertService;`; in `setUp` before `service = new PlaidItemLifecycleService(` add `opsAlertService = mock(OpsAlertService.class);` and change the constructor's last argument line `schedulerGate, jobLockService, mock(PlatformTransactionManager.class), WEBHOOK);` to `schedulerGate, jobLockService, mock(PlatformTransactionManager.class), WEBHOOK, opsAlertService);`. In `drainAlertsOnRowsOlderThanSevenDaysInsteadOfCallingPlaid`, after `stale.setAttempts(600);` add `stale.setId(31L);` (else the subject is the string `"null"`), and at its end add:

```java
        verify(opsAlertService).raise(eq(OpsAlerts.PLAID_ITEM_REMOVE_EXHAUSTED), eq("31"), anyString(),
                eq(Map.of("id", "31", "item", "item-old", "attempts", 600)));
```

`PlaidWebhookInboxService.java`: after `:7` (`import com.investingapp.backend.model.User;`) add the two `ops` imports; after `:23` (`import java.util.HexFormat;`) add `import java.util.Map;`. After `:44` (`    private UserRepository userRepository;`) add:

```java

    @Autowired
    private OpsAlertService opsAlertService;
```

Directly under `:105` (`            logger.error("alert.plaid.webhook.ambiguous_item item_id={}", itemId);`) add:

```java
            opsAlertService.raise(OpsAlerts.PLAID_WEBHOOK_AMBIGUOUS_ITEM, itemId,
                    "webhook item_id matches more than one user; row stored IGNORED",
                    Map.of("item", itemId));
```

`PlaidWebhookInboxServiceTest.java` (field-injected wiring, called pattern W below): add the two `ops` imports, `java.util.Map` and `import static org.mockito.ArgumentMatchers.eq;`; add the field `private OpsAlertService opsAlertService;`; in `setUp` after `ReflectionTestUtils.setField(service, "plaidEnv", "sandbox");` add:

```java
        opsAlertService = mock(OpsAlertService.class);
        ReflectionTestUtils.setField(service, "opsAlertService", opsAlertService);
```

At the end of `ambiguousItemIsIgnoredNotDropped` add:

```java
        verify(opsAlertService).raise(eq(OpsAlerts.PLAID_WEBHOOK_AMBIGUOUS_ITEM), eq(ITEM_ID), anyString(),
                eq(Map.of("item", ITEM_ID)));
```

- [ ] **Step 18: Route InvestmentExecutionService (3 sites)**

`InvestmentExecutionService.java`: after `:6` (`import com.investingapp.backend.service.ScheduledInvestmentProcessor.ProcessOutcome;`) add the two `ops` imports (`java.util.*` already covers `Map`). After `:94` (`    private PlatformTransactionManager transactionManager;`) add:

```java

    @Autowired
    private OpsAlertService opsAlertService;
```

Directly under `:258` (`logger.error("alert.batch_rows_missing ...`), inside the REQUIRES_NEW callback:

```java
                opsAlertService.raise(OpsAlerts.BATCH_ROWS_MISSING, String.valueOf(userId),
                        "batch reload found fewer execution rows than ids",
                        Map.of("user", String.valueOf(userId), "expected", ids.size(), "found", found.size()));
```

Directly under `:320` (`logger.error("alert.batch_claim_short ...`):

```java
                        opsAlertService.raise(OpsAlerts.BATCH_CLAIM_SHORT, String.valueOf(batchUser.userId()),
                                "batch claim short; this user is skipped tonight",
                                Map.of("user", String.valueOf(batchUser.userId()), "expected", ids.size(), "claimed", claimed));
```

Directly under `:400` (`                batchUser.userId(), batchUser.executionIds().size(), batchUser.totalAmount());`; no `total`, which is money):

```java
        opsAlertService.raise(OpsAlerts.BATCH_NO_RELATIONSHIP, String.valueOf(batchUser.userId()),
                "no ACH relationship on file; rows marked FUNDING_FAILED",
                Map.of("user", String.valueOf(batchUser.userId()), "rows", batchUser.executionIds().size()));
```

`InvestmentExecutionServiceEodBatchTest.java`: pattern W (the two `ops` imports; `Map`, `eq` and `anyString` are already imported), with the two `setUp` lines placed after `ReflectionTestUtils.setField(service, "transactionManager", transactionManager);`. At the end of `nullRelationshipFailsFastWithoutClaimOrPost` add:

```java
        verify(opsAlertService).raise(eq(OpsAlerts.BATCH_NO_RELATIONSHIP), eq("42"), anyString(),
                eq(Map.of("user", "42", "rows", 2)));
```

The other five `InvestmentExecutionService*Test` classes (`AchAnalytics`, `Coordinator`, `EodGate`, `TlhGuard`, `TodayEt`) never reach an alert line (the EodGate test's repository returns empty lists), so they need no change.

- [ ] **Step 19: Route TradingController (1 site) and give the alert a subject**

The Phase 0 line carries no user, so an operator could not tell whose account to finish. `TradingController.java`: after `:9` (`import com.investingapp.backend.service.UserDeletionService;`) add `import com.investingapp.backend.ops.OpsAlertService;` and `import com.investingapp.backend.ops.OpsAlerts;`. After `:59` (`    private AnalyticsOutboxService analyticsOutboxService;`) add:

```java

    @Autowired
    private OpsAlertService opsAlertService;
```

In `closeAccount`, replace `:312` (`        try {`, the first line of the method body, directly under `public ResponseEntity<?> closeAccount(Authentication authentication) {`) with:

```java
        Long closingUserId = null;
        try {
```

After the `user == null` refusal block (the `}` at `:323` that closes `if (user == null || user.getAlpacaAccountId() == null) {`) add:

```java
            closingUserId = user.getId();
```

Directly under `:356` (`            logger.error("alert.user_deletion.deferred reason=ACCOUNT_CLOSED deferral={}", deferred.reason);`) add:

```java
            opsAlertService.raise(OpsAlerts.USER_DELETION_DEFERRED,
                    closingUserId == null ? OpsAlertService.GLOBAL_SUBJECT : String.valueOf(closingUserId),
                    "brokerage account closed, FRED row deletion deferred",
                    Map.of("reason", "ACCOUNT_CLOSED", "deferral", String.valueOf(deferred.reason)));
```

`user` stays declared inside the `try`, so the `() -> recordScheduleCancelled(user)` lambda still captures an effectively final variable.

`TradingControllerCloseAccountTest.java`: pattern W with `controller` in place of `service` (the two `ops` imports; `Map`, `eq` and `anyString` are already imported), the two `setUp` lines placed after `ReflectionTestUtils.setField(controller, "analyticsOutboxService", analyticsOutboxService);`. At the end of `lateDeferralIs409` add:

```java
        verify(opsAlertService).raise(eq(OpsAlerts.USER_DELETION_DEFERRED), eq("42"), anyString(),
                eq(Map.of("reason", "ACCOUNT_CLOSED", "deferral", "SETTLEMENT_WINDOW")));
```

`TradingControllerScheduleCancelledTest` never reaches the deferral catch and needs no change.

- [ ] **Step 20: Run every class this task touched, one at a time**

```
(cd backend && ./gradlew compileTestJava --console=plain)
(cd backend && for c in AlertSitesRoutedTest service.JobLockServiceTest service.AchRelationshipServiceTest service.PlaidItemLifecycleServiceTest service.PlaidWebhookInboxServiceTest service.InvestmentExecutionServiceEodBatchTest controller.TradingControllerCloseAccountTest controller.TradingControllerScheduleCancelledTest; do ./gradlew test --tests "com.investingapp.backend.$c" --console=plain || break; done)
```

Expected: `BUILD SUCCESSFUL` for each; counts 2, 7, 20, 8, 7, 11, 3, 9, all 0 failures. HEAD has no other `new` of the three changed constructors; if `compileTestJava` names one added since 137998c, pass `mock(OpsAlertService.class)` last and add that file to the commit.

- [ ] **Step 21: The whole suite on a scratch MySQL, 0 skipped**

`@RequiresCiDatabase` classes skip without a database; only a real boot proves the `opsAlertExecutor` qualifier and the `LoggingOpsAlertSender` selection, and the rule is the whole suite. Run this as ONE shell invocation from the repository root; it reads the local MySQL user and password from the gitignored `application-local.properties` without printing them:

```bash
MYSQL_USER=$(sed -n 's/^spring\.datasource\.username=//p' backend/src/main/resources/application-local.properties)
MYSQL_PW=$(sed -n 's/^spring\.datasource\.password=//p' backend/src/main/resources/application-local.properties)
db() { MYSQL_PWD="$MYSQL_PW" mysql -h 127.0.0.1 -u "$MYSQL_USER" "$@"; }
db -e "DROP DATABASE IF EXISTS fred_t04_scratch; CREATE DATABASE fred_t04_scratch"
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t04_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME="$MYSQL_USER" SPRING_DATASOURCE_PASSWORD="$MYSQL_PW"
export PLAID_WEBHOOK_URL='https://ci.invalid/api/plaid/webhook' PLAID_REDIRECT_URI='https://ci.invalid/plaid/oauth' PLAID_LINK_CUSTOMIZATION=default
(cd backend && ./gradlew cleanTest test --console=plain)
R=backend/build/test-results/test
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $R/TEST-com.investingapp.backend.BackendApplicationTests.xml $R/TEST-com.investingapp.backend.controller.CloseAccountRollbackTest.xml
grep -l 'skipped="[1-9]\|failures="[1-9]\|errors="[1-9]' $R/*.xml
db -e "DROP DATABASE fred_t04_scratch"
unset SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD PLAID_WEBHOOK_URL PLAID_REDIRECT_URI PLAID_LINK_CUSTOMIZATION MYSQL_PW
```

Expected: `BUILD SUCCESSFUL`; `tests="1" skipped="0" failures="0" errors="0"`, then `tests="2" skipped="0" failures="0" errors="0"`; the last grep prints nothing. Trust the XML, not the console; a skip means no database.

- [ ] **Step 22: Content and style checks**

```
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1
git diff HEAD -- backend/src | LC_ALL=C grep -n $'^+.*\xe2\x80\x94'
LC_ALL=C grep -rn $'\xe2\x80\x94' backend/src/main/java/com/investingapp/backend/ops backend/src/test/java/com/investingapp/backend/ops backend/src/test/java/com/investingapp/backend/AlertSitesRoutedTest.java backend/src/test/java/com/investingapp/backend/config/OpsAlertExecutorTest.java
grep -rn 'setStatus(\|setHoldReason(\|setMode(\|getIsPaused(\|getMessage()' backend/src/main/java/com/investingapp/backend/ops
```

Expected: all three print nothing (the first two search for the em dash character by its UTF-8 bytes).

- [ ] **Step 23: Commit**

Hunk-verify each modified file with `git diff HEAD -- <file>` (stage nothing you did not write; never `application-local.properties` or `ITPM/agent-memory/*`; the two new `ops` directories hold only this task's files), then:

```
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1/backend/src
git add main/java/com/investingapp/backend/ops test/java/com/investingapp/backend/ops \
  main/java/com/investingapp/backend/config/SchedulingConfig.java main/java/com/investingapp/backend/config/BrokerEnvironmentGuard.java \
  main/java/com/investingapp/backend/service/JobLockService.java main/java/com/investingapp/backend/service/AchRelationshipService.java \
  main/java/com/investingapp/backend/service/PlaidItemLifecycleService.java main/java/com/investingapp/backend/service/PlaidWebhookInboxService.java \
  main/java/com/investingapp/backend/service/InvestmentExecutionService.java main/java/com/investingapp/backend/controller/TradingController.java \
  main/resources/application.properties main/resources/application-ci.properties main/resources/application-prod.properties \
  test/java/com/investingapp/backend/AlertSitesRoutedTest.java test/java/com/investingapp/backend/config/OpsAlertExecutorTest.java \
  test/java/com/investingapp/backend/config/BrokerEnvironmentGuardTest.java test/java/com/investingapp/backend/config/ProdPlaidPropertiesTest.java \
  test/java/com/investingapp/backend/service/JobLockServiceTest.java test/java/com/investingapp/backend/service/AchRelationshipServiceTest.java \
  test/java/com/investingapp/backend/service/PlaidItemLifecycleServiceTest.java test/java/com/investingapp/backend/service/PlaidWebhookInboxServiceTest.java \
  test/java/com/investingapp/backend/service/InvestmentExecutionServiceEodBatchTest.java test/java/com/investingapp/backend/controller/TradingControllerCloseAccountTest.java
git status --short
git commit -m "feat(ops): add OpsAlertService with daily dedupe and route every Phase 0 alert line through it"
```

`git status --short` must show 29 staged paths (first column `A` or `M`): the 8 new files under the two `ops` directories plus the 21 listed files. If anything else shows as staged, do not commit: stop and ask the controller.

### Verification

```
cd backend && for c in ops.OpsAlertServiceTest ops.WebhookOpsAlertSenderTest config.OpsAlertExecutorTest config.BrokerEnvironmentGuardTest config.ProdPlaidPropertiesTest AlertSitesRoutedTest; do ./gradlew test --tests "com.investingapp.backend.$c" --console=plain || break; done
```

plus the Step 20 loop and the Step 21 scratch-MySQL runs. Expected, all 0 failures and 0 skipped: `OpsAlertServiceTest` 8, `WebhookOpsAlertSenderTest` 3, `OpsAlertExecutorTest` 1, `BrokerEnvironmentGuardTest` 32, `ProdPlaidPropertiesTest` 4, `AlertSitesRoutedTest` 2, `JobLockServiceTest` 7, `AchRelationshipServiceTest` 20, `PlaidItemLifecycleServiceTest` 8, `PlaidWebhookInboxServiceTest` 7, `InvestmentExecutionServiceEodBatchTest` 11, `TradingControllerCloseAccountTest` 3, `TradingControllerScheduleCancelledTest` 9, `BackendApplicationTests` 1, `CloseAccountRollbackTest` 2. Step 22 prints nothing.

### Produces

- `ops.OpsAlerts`: `USER_DELETION_DEFERRED`, `ACH_ADOPT_REFUSED`, `ACH_CANCEL_FAILED`, `ACH_POLL_LIST_FAILED`, `ACH_UNKNOWN_RELATIONSHIP`, `ACH_CANCEL_STUCK`, `ACH_CREATE_FAILED`, `BATCH_ROWS_MISSING`, `BATCH_CLAIM_SHORT`, `BATCH_NO_RELATIONSHIP`, `JOB_LOCK_UNAVAILABLE`, `JOB_LOCK_RELEASE_FAILED`, `PLAID_ITEM_REMOVE_EXHAUSTED`, `PLAID_WEBHOOK_AMBIGUOUS_ITEM` (later tasks add their own names here).
- `record OpsAlertMessage(String type, String subjectId, String message, Map<String, Object> context, LocalDateTime raisedAt)`.
- `interface OpsAlertSender { String send(OpsAlertMessage alert) throws Exception; }`; `LoggingOpsAlertSender` (`havingValue = "logging", matchIfMissing = true`); `WebhookOpsAlertSender` (`havingValue = "webhook"`, `TIMEOUT = Duration.ofSeconds(5)`, body `{type, subject, message, context, env, at, text, content}`).
- `OpsAlertService(OpsAlertRepository, OpsAlertSender, PlatformTransactionManager, @Qualifier("opsAlertExecutor") Executor opsAlertExecutor)`; `enum OpsAlertService.OpsAlertOutcome {QUEUED, DEDUPED, FAILED}`; `OpsAlertOutcome raise(String type, String subjectId, String message, Map<String, Object> context)` and `raise(String type, String subjectId, String message)`; `GLOBAL_SUBJECT = "global"`, `TYPE_MAX = 64`, `SUBJECT_MAX = 80`, `MESSAGE_MAX = 500`, `DELIVERY_PENDING`, `DELIVERY_SENT`, `DELIVERY_LOGGED`, `DELIVERY_FAILED`. Never throws; REQUIRES_NEW ledger keyed `(type, subjectId, EasternTime.today())`; repeat = DEDUPED, no send; ledger failure = FAILED, still sent.
- Bean `opsAlertExecutor` (core 1, max 1, queue 200, `DiscardOldestPolicy`). Guard: prod needs `app.ops.alert.provider=webhook` and an https `app.ops.alert.webhook-url`. Properties `app.ops.alert.provider`, `app.ops.alert.webhook-url`.
- Constructors gain `OpsAlertService` last: `AchRelationshipService`, `JobLockService`, `PlaidItemLifecycleService`; field `opsAlertService` on `TradingController`, `InvestmentExecutionService`, `PlaidWebhookInboxService`.
- `AlertSitesRoutedTest`: every later `logger.error("alert.` line needs `opsAlertService.raise(` within three lines, no argument naming `total`, `amount`, `getLastError`, `getMessage` or `error`.
