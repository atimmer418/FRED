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
