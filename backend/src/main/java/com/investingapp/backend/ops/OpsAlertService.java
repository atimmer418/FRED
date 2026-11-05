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
