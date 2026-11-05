package com.investingapp.backend.ops;

import com.investingapp.backend.ops.OpsAlertService.OpsAlertOutcome;
import com.investingapp.backend.repository.OpsAlertRepository;
import com.investingapp.backend.support.RequiresCiDatabase;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dedupe ledger against the real schema: uq_ops_alerts_type_subject_day is what stops a second
 * row for the same (alert_type, subject_id, alert_day), so this runs on MySQL, not a mock.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class OpsAlertLedgerDbTest {

    @Autowired
    private OpsAlertService service;

    @Autowired
    private OpsAlertRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private final String subject = "t04-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() throws InterruptedException {
        // Delivery runs on opsAlertExecutor; let it finish its status write before the row goes.
        for (int i = 0; i < 50; i++) {
            Integer pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ops_alerts WHERE subject_id = ? AND delivery_status = 'PENDING'",
                    Integer.class, subject);
            if (pending != null && pending == 0) {
                break;
            }
            Thread.sleep(100);
        }
        jdbc.update("DELETE FROM ops_alerts WHERE subject_id = ?", subject);
    }

    private int rows() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ops_alerts WHERE subject_id = ?", Integer.class, subject);
        return count == null ? 0 : count;
    }

    @Test
    void secondRaiseSameDayInsertsNoSecondRowAndCountsOccurrences() {
        assertThat(service.raise(OpsAlerts.ACH_CANCEL_STUCK, subject, "cancel stuck", Map.of("user", subject)))
                .isEqualTo(OpsAlertOutcome.QUEUED);
        assertThat(service.raise(OpsAlerts.ACH_CANCEL_STUCK, subject, "cancel stuck", Map.of("user", subject)))
                .isEqualTo(OpsAlertOutcome.DEDUPED);

        assertThat(rows()).isEqualTo(1);
        var row = repository.findByAlertTypeAndSubjectIdAndAlertDay(
                OpsAlerts.ACH_CANCEL_STUCK, subject, EasternTime.today()).orElseThrow();
        assertThat(row.getOccurrences()).isEqualTo(2);
        assertThat(row.getLastRaisedAt()).isAfterOrEqualTo(row.getFirstRaisedAt());

        // The same subject under another type is another alert.
        assertThat(service.raise(OpsAlerts.ACH_CREATE_FAILED, subject, "create failed")).isEqualTo(OpsAlertOutcome.QUEUED);
        assertThat(rows()).isEqualTo(2);
    }

    @Test
    void concurrentRaisesStillLeaveOneRow() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<OpsAlertOutcome>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                outcomes.add(pool.submit(() -> {
                    go.await();
                    return service.raise(OpsAlerts.BATCH_CLAIM_SHORT, subject, "claim short");
                }));
            }
            go.countDown();
            int queued = 0;
            for (Future<OpsAlertOutcome> outcome : outcomes) {
                if (outcome.get() == OpsAlertOutcome.QUEUED) {
                    queued++;
                }
            }
            assertThat(queued).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(rows()).isEqualTo(1);
        // occurrences is a read-modify-write, so two simultaneous repeats can lose a count; the
        // ledger promise is one row and a count that moved, not an exact tally under a race.
        assertThat(repository.findByAlertTypeAndSubjectIdAndAlertDay(
                OpsAlerts.BATCH_CLAIM_SHORT, subject, EasternTime.today()).orElseThrow().getOccurrences())
                .isBetween(2, threads);
    }
}
