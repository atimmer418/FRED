package com.investingapp.backend.service;

import com.investingapp.backend.repository.PlaidWebhookEventRepository;
import com.investingapp.backend.support.RequiresCiDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class PlaidWebhookClaimDatabaseTest {

    @Autowired
    private PlaidWebhookEventRepository repository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TransactionTemplate transactions;
    private final List<Long> ours = new ArrayList<>();

    // Four RECEIVED rows dated 2000-01-01 (a string literal: no driver zone conversion), so every claim sees them first.
    @BeforeEach
    void insertFourReceivedRows() {
        transactions = new TransactionTemplate(transactionManager);
        String run = UUID.randomUUID().toString().replace("-", "");
        for (int i = 0; i < 4; i++) {
            String key = "t14claim" + run + i;
            jdbcTemplate.update("INSERT INTO plaid_webhook_events (dedupe_key, webhook_type, webhook_code, plaid_item_id, user_id, "
                            + "environment, payload_json, status, attempts, jwt_iat, received_at) "
                            + "VALUES (?, 'TRANSACTIONS', 'SYNC_UPDATES_AVAILABLE', ?, NULL, 'sandbox', '{}', 'RECEIVED', 0, ?, ?)",
                    key, "item-" + run, 1_700_000_000L + i, "2000-01-01 00:00:0" + i);
            ours.add(jdbcTemplate.queryForObject("SELECT id FROM plaid_webhook_events WHERE dedupe_key = ?", Long.class, key));
        }
    }

    @AfterEach
    void deleteOurRows() {
        ours.forEach(id -> jdbcTemplate.update("DELETE FROM plaid_webhook_events WHERE id = ?", id));
    }

    @Test
    void concurrentClaimersGetDisjointIds() throws Exception {
        CountDownLatch aClaimed = new CountDownLatch(1);
        CountDownLatch aMayCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<Long>> a = pool.submit(() -> transactions.execute(status -> {
                List<Long> ids = repository.claimReceivedIds(2);
                aClaimed.countDown();
                awaitQuietly(aMayCommit);
                return ids;
            }));
            assertThat(aClaimed.await(10, TimeUnit.SECONDS)).isTrue();
            // Without SKIP LOCKED, B blocks on A's locks and this get() times out.
            Future<List<Long>> b = pool.submit(() -> transactions.execute(status -> repository.claimReceivedIds(50)));
            List<Long> bIds = b.get(10, TimeUnit.SECONDS);
            aMayCommit.countDown();
            List<Long> aIds = a.get(10, TimeUnit.SECONDS);

            assertThat(aIds).containsExactlyElementsOf(ours.subList(0, 2));
            assertThat(bIds).doesNotContainAnyElementsOf(aIds).containsAll(ours.subList(2, 4));
        } finally {
            aMayCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void markProcessingStampsTheClaimAndReclaimStaleReturnsOnlyOldClaims() {
        LocalDateTime claimedAt = LocalDateTime.of(2026, 11, 3, 9, 0);

        Integer marked = transactions.execute(status -> repository.markProcessing(ours, claimedAt));

        assertThat(marked).isEqualTo(4);
        for (Long id : ours) {
            assertThat(statusOf(id)).isEqualTo("PROCESSING");
            assertThat(jdbcTemplate.queryForObject("SELECT processed_at IS NOT NULL FROM plaid_webhook_events WHERE id = ?",
                    Boolean.class, id)).isTrue();
        }

        transactions.execute(status -> repository.reclaimStale(claimedAt));
        for (Long id : ours) {
            assertThat(statusOf(id)).as("a claim stamped exactly at the cut-off is not stale").isEqualTo("PROCESSING");
        }

        transactions.execute(status -> repository.reclaimStale(claimedAt.plusMinutes(10)));
        for (Long id : ours) {
            assertThat(statusOf(id)).isEqualTo("RECEIVED");
        }
    }

    private String statusOf(Long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM plaid_webhook_events WHERE id = ?", String.class, id);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("claimer B never finished");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
