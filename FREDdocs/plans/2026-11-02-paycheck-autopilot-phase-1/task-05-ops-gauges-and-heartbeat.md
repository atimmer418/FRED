### Task 05: ops-gauges-and-heartbeat

Every gauge the morning digest needs, read as one SQL snapshot; the 09:07 ET heartbeat job that sends that snapshot through `OpsAlertService` every day; and the daily alerts the schema can answer (spec 12.3; ruling R11). This task is half of the Phase 1 gate ("the ops alert channel and heartbeat digest", spec 14): T04 built the channel, this task makes it speak every morning, so a morning with no digest is itself the alarm. The task reads the database and raises ops alerts. It moves no money, writes no `InvestmentSchedule.status`, no `mode`, no hold, no Item state, no user row, and emits no analytics event.

Paths starting `.../` are under `backend/src/main/java/com/investingapp/backend/`; tests go under `backend/src/test/java/com/investingapp/backend/ops/`. Line numbers are HEAD 137998c. `service/JobLocks.java` is edited later by T14 and T15 (they append below the line this task appends), so its anchor is quoted text. `ops/OpsAlerts.java` does not exist at HEAD: T04 creates it and T06 may append to it before this task runs, so its anchor is quoted text too. Every command block below starts at the repository root; a block that enters `backend/` ends with `cd ..`, and the commands between run inside `backend/` (so `build/test-results/...` paths are relative to it).

Facts this task depends on, restated so this file stands alone:

- `investment_schedules.status` (PENDING_BANK, ACTIVE, PAUSED_BY_USER) is the user and bank lifecycle; `hold_reason` is the system's stop and is orthogonal. This task only counts holds; it never writes either column, and never reads the deprecated `getIsPaused()`.
- A KYC rejection keeps the user row with `account_status = 'REJECTED'` and puts a `MANUAL_REVIEW` hold on every schedule that user has (handoff 2.1). That hold stays for good, which is why the "MANUAL_REVIEW present" alert below leaves REJECTED applicants out: otherwise it would fire every morning forever after the first rejection.
- `plaid_webhook_events.received_at` is written with `EasternTime.now()` (`PlaidWebhookInboxService.java:88`), and every Phase 1 writer of `paycheck_cycles.confirmed_at`, `investment_schedules.detection_started_at` and `ops_alerts.last_raised_at` stamps Eastern wall time through Hibernate. The datasource session is UTC (`serverTimezone=UTC` in the ci URL). Therefore no gauge query may use SQL `NOW()`, `CURDATE()` or `CURRENT_TIMESTAMP`: the caller binds `nowET` and its cut-offs as parameters (design Decision 49). Every temporal parameter is bound as `java.sql.Timestamp` / `java.sql.Date`, the JDBC types Hibernate 6 binds for `LocalDateTime` / `LocalDate`, so whatever conversion the driver applies to Hibernate's writes it also applies to the gauge's comparisons. `agesUseTheBoundEasternClock` writes its row through Hibernate to prove this.
- No Micrometer and no actuator exist (design Decision 8): every gauge is SQL, read through `JdbcTemplate` (auto-configured by `spring-boot-starter-data-jpa`). This task adds no repository method, so it does not touch `PlaidWebhookEventRepository`, which T14 edits.
- Job shape (house rule): `schedulerGate.isEnabled()` first, then `jobLockService.tryAcquire(JobLocks.X, lease)` with `release` in `finally`, `zone = "America/New_York"`, one clock read per run. `investment.scheduler.enabled` is `false` under the ci profile, so the job never fires inside a `@SpringBootTest`.
- Ops alert content rule (spec 12.3, Decision 37): an alert's `message` and `context` carry counts, closed-vocabulary bucket names, error codes and fixed words only: never a payer name, descriptor, amount, id, date, token, cursor or free-text error. INFO log lines follow the same rule. The heartbeat's subjects are the fixed words `digest`, `global` and `nightly`.
- `paycheck.autopilot.enabled=false` (spec 12.1) stops the drainer, the pipeline and the reconcile, but the heartbeat still runs. The four alerts about the pipeline's own progress (inbox age, no webhooks, SEARCHING over 24 h, reconcile late) are therefore raised only when the flag is true.
- This task adds no property, so there is no `application-local.properties` line to add by hand. Locally the digest goes to the `logging` provider (T04's default); `paycheck.autopilot.enabled` in your gitignored `application-local.properties` (hand-added in T03) decides whether the four pipeline alerts can fire.

**Files:**
- Create: `.../ops/OpsGaugeSnapshot.java`, `.../ops/OpsGaugeService.java`, `.../ops/OpsHeartbeatJob.java`
- Modify: `.../service/JobLocks.java:9` (append after the quoted line `    public static final String PLAID_ITEM_REMOVALS = "plaid.item-removals";`); `.../ops/OpsAlerts.java` (T04's file: insert immediately above the quoted line `    private OpsAlerts() {`)
- Test: create `OpsGaugeServiceTest.java` (no database), `OpsGaugeServiceDatabaseTest.java` (`@RequiresCiDatabase`), `OpsHeartbeatJobTest.java`

**Interfaces:**
- Consumes:
  - T01: table `ops_alerts` (V10: `alert_type VARCHAR(64)`, `subject_id VARCHAR(80)`, `alert_day DATE`, `message VARCHAR(500)`, `occurrences INT`, `first_raised_at DATETIME(6)`, `last_raised_at DATETIME(6)`, `delivery_status VARCHAR(12)`).
  - T02: `model/DetectionState` (`SEARCHING`), `model/PaycheckCycleState` (`SKIPPED`), `model/PaycheckSkipReason` (`SHADOW_CONFIRMED`).
  - T03: `config/PaycheckAutopilotProperties.isEnabled()` (see Deviations).
  - T04: `ops/OpsAlertService.raise(String type, String subjectId, String message, Map<String, Object> context)` (never throws; writes the dedupe row keyed `(type, subjectId, EasternTime.today())` and hands delivery to `opsAlertExecutor`), `OpsAlertService.GLOBAL_SUBJECT` (`"global"`), class `ops/OpsAlerts`.
  - Phase 0: `config/SchedulerGate.isEnabled()`; `service/JobLockService.tryAcquire(String, Duration)` and `release(String)`; `service/JobLocks`; `util/EasternTime.now()`; enums `model/PlaidWebhookEventStatus`, `model/PlaidItemStatus`, `model/HoldReason`; `repository/PlaidWebhookEventRepository` (inherited `save`, test only); `metrics/MetricsViewSeed` (test only: `user`, `scheduleWithStatus`, `accountStatus`, `cleanCohort`, `mondayOf`).
- Produces:
  - `public record OpsGaugeSnapshot(Map<String, Long> paycheckUsersByState, Map<String, Long> plaidItemsByStatus, long plaidItemsBillable, Map<String, Long> holdsByReason, long inboxBacklog, long inboxFailed, long inboxOldestAgeSec, long webhooksReceived24h, long removalsPending, long cyclesShadowConfirmed7d, long opsAlerts24h, long searchingOver24h, long manualReviewOpen, boolean reconcileRanYesterday)` with `public static final List<String> GAUGE_KEYS` and `public Map<String, Object> toContext()`.
  - `@Service OpsGaugeService(JdbcTemplate)`, `@Transactional(readOnly = true) public OpsGaugeSnapshot snapshot(LocalDateTime nowET)`.
  - `@Component OpsHeartbeatJob(SchedulerGate, JobLockService, OpsGaugeService, OpsAlertService, PaycheckAutopilotProperties)`, `@Scheduled(cron = "0 7 9 * * *", zone = "America/New_York") public void run()`, `LOCK_LEASE = Duration.ofMinutes(5)`, `INBOX_OLDEST_AGE_LIMIT_SEC = 1800`, `SUBJECT_DIGEST = "digest"`, `SUBJECT_NIGHTLY = "nightly"`.
  - `JobLocks.OPS_HEARTBEAT = "ops.heartbeat"`.
  - `OpsAlerts.HEARTBEAT`, `INBOX_FAILED`, `INBOX_OLDEST_AGE`, `WEBHOOKS_NONE_24H`, `SEARCHING_24H`, `HOLDS_MANUAL_REVIEW_PRESENT`, `RECONCILE_LATE`, and `RECONCILE_RAN = "paycheck.reconcile.ran"` (raised by T15 once per reconcile run with subject `nightly`; this task only reads it).

- [ ] **Step 1: Confirm the upstream pieces are in the tree (2 minutes)**

Run from the repository root:

```
ls backend/src/main/resources/db/migration/ | grep '^V10__'
grep -rln 'enum DetectionState\|enum PaycheckCycleState\|enum PaycheckSkipReason' backend/src/main/java/com/investingapp/backend/model/
grep -n 'public boolean isEnabled()' backend/src/main/java/com/investingapp/backend/config/PaycheckAutopilotProperties.java
grep -n 'raise(String type' backend/src/main/java/com/investingapp/backend/ops/OpsAlertService.java
grep -n 'GLOBAL_SUBJECT' backend/src/main/java/com/investingapp/backend/ops/OpsAlertService.java
grep -n 'setLastRaisedAt' backend/src/main/java/com/investingapp/backend/ops/OpsAlertService.java
grep -n '    private OpsAlerts() {' backend/src/main/java/com/investingapp/backend/ops/OpsAlerts.java
grep -n 'HEARTBEAT\|INBOX_FAILED\|INBOX_OLDEST_AGE\|WEBHOOKS_NONE_24H\|SEARCHING_24H\|HOLDS_MANUAL_REVIEW_PRESENT\|RECONCILE_LATE\|RECONCILE_RAN' backend/src/main/java/com/investingapp/backend/ops/OpsAlerts.java
grep -n 'PLAID_ITEM_REMOVALS = "plaid.item-removals";' backend/src/main/java/com/investingapp/backend/service/JobLocks.java
```

Expected, in order: `V10__ops_alerts_and_admin_actions.sql`; three file paths (`DetectionState.java`, `PaycheckCycleState.java`, `PaycheckSkipReason.java`); one line for `isEnabled()`; at least one `raise(String type, String subjectId, String message, Map<String, Object> context)` line; one or more `GLOBAL_SUBJECT` lines; one or more `setLastRaisedAt(` lines whose argument is `alert.raisedAt()`, where `alert` is the `OpsAlertMessage` that the four-argument `raise` builds with `EasternTime.now()` as its last constructor argument (open the file to check); one line for the private constructor; nothing at all for the eight constant names; `9:    public static final String PLAID_ITEM_REMOVALS = "plaid.item-removals";`.

Stop and report to the controller if any line differs: a missing V10 or enum means T01 or T02 has not landed; a `last_raised_at` stamped from any clock other than `EasternTime.now()` would make the `ops_alerts.24h` gauge read the wrong window; an existing constant of one of the eight names means another task took it. If `OpsAlerts` has no private constructor, insert the Step 4 block immediately above its final closing `}` instead, and say so in the commit body.

- [ ] **Step 2: Write the failing gauge tests (5 minutes)**

Create `backend/src/test/java/com/investingapp/backend/ops/OpsGaugeServiceTest.java`:

```java
package com.investingapp.backend.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The parts of the gauge snapshot that need no database: the SQL text and the digest's shape. */
class OpsGaugeServiceTest {

    private static List<String> gaugeQueries() throws IllegalAccessException {
        List<String> sql = new ArrayList<>();
        for (Field field : OpsGaugeService.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class
                    && field.getName().startsWith("SQL_")) {
                field.setAccessible(true);
                sql.add((String) field.get(null));
            }
        }
        return sql;
    }

    @Test
    @DisplayName("no gauge query reads the SQL clock: the session is UTC and the data is Eastern")
    void noGaugeQueryReadsTheSqlClock() throws Exception {
        List<String> queries = gaugeQueries();

        // Fourteen queries; a fifteenth must be added here too, so it cannot dodge this check.
        assertThat(queries).hasSize(14);
        for (String query : queries) {
            assertThat(query.toUpperCase(Locale.ROOT)).as(query).doesNotContain(
                    "NOW(", "CURDATE(", "CURRENT_TIMESTAMP", "CURRENT_DATE", "UTC_TIMESTAMP",
                    "SYSDATE(", "LOCALTIME", "UNIX_TIMESTAMP(");
        }
    }

    @Test
    @DisplayName("the digest context carries every gauge, in the published order, buckets sorted")
    void contextCarriesEveryGaugeKeyInOrder() {
        OpsGaugeSnapshot snapshot = new OpsGaugeSnapshot(
                Map.of("SEARCHING", 2L), Map.of("OK", 3L, "LOGIN_REQUIRED", 1L), 3,
                Map.of("MANUAL_REVIEW", 1L), 4, 0, 120, 9, 1, 2, 5, 0, 1, true);

        Map<String, Object> context = snapshot.toContext();

        assertThat(new ArrayList<>(context.keySet())).containsExactlyElementsOf(OpsGaugeSnapshot.GAUGE_KEYS);
        assertThat(context)
                .containsEntry("plaid.items.billable", 3L)
                .containsEntry("inbox.backlog", 4L)
                .containsEntry("inbox.oldest_age_sec", 120L)
                .containsEntry("ops_alerts.24h", 5L)
                .containsEntry("reconcile.ran_yesterday", true);
        Map<?, ?> items = (Map<?, ?>) context.get("plaid.items");
        assertThat(new ArrayList<>(items.keySet())).containsExactly("LOGIN_REQUIRED", "OK");
    }

    @Test
    @DisplayName("no gauge is named for data an ops alert may never carry")
    void contextKeysNameNoForbiddenData() {
        for (String key : OpsGaugeSnapshot.GAUGE_KEYS) {
            assertThat(key).doesNotContain("amount", "date", "descriptor", "name", "token", "cursor", "email");
        }
    }
}
```

Create `backend/src/test/java/com/investingapp/backend/ops/OpsGaugeServiceDatabaseTest.java`:

```java
package com.investingapp.backend.ops;

import com.investingapp.backend.metrics.MetricsViewSeed;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.PaycheckCycleState;
import com.investingapp.backend.model.PaycheckSkipReason;
import com.investingapp.backend.model.PlaidWebhookEvent;
import com.investingapp.backend.model.PlaidWebhookEventStatus;
import com.investingapp.backend.repository.PlaidWebhookEventRepository;
import com.investingapp.backend.support.RequiresCiDatabase;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gauge SQL against the migrated schema (V1 to V10 under ddl-auto=validate).
 *
 * Two isolation tricks, because every gauge aggregates a whole table. Window tests bind a clock
 * in 2020, years before any real row, and bound every window on both sides, so their counts are
 * exact. Totals that cannot be windowed (billable Items, holds, removals) are measured as a delta
 * between two snapshots. Users belong to the cohort week of Monday 2019-11-04, which no other test
 * uses, and are removed before and after every test.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class OpsGaugeServiceDatabaseTest {

    /** The bound Eastern clock: a query that read the SQL clock instead would be off by years. */
    private static final LocalDateTime NOW_ET = LocalDateTime.of(2020, 1, 1, 12, 0);
    /** Wednesday 2019-11-06, in the cohort week of Monday 2019-11-04. */
    private static final LocalDateTime SIGNUP = LocalDateTime.of(2019, 11, 6, 9, 30);
    private static final String TEST_ALERT_TYPE = "test.ops_gauge";

    @Autowired
    private OpsGaugeService gauges;
    @Autowired
    private PlaidWebhookEventRepository webhookEvents;
    @Autowired
    private JdbcTemplate jdbc;

    private MetricsViewSeed seed;

    @BeforeEach
    void setUp() {
        seed = new MetricsViewSeed(jdbc);
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        seed.cleanCohort(MetricsViewSeed.mondayOf(SIGNUP.toLocalDate()));
        jdbc.update("DELETE FROM plaid_webhook_events WHERE dedupe_key LIKE 'opsgauge%'");
        jdbc.update("DELETE FROM plaid_item_removals WHERE item_id LIKE 'item-opsgauge-%'");
        jdbc.update("DELETE FROM ops_alerts WHERE alert_day < ? AND (alert_type LIKE 'test.ops_gauge%' "
                + "OR alert_type IN (?, ?))", Date.valueOf("2020-01-10"), OpsAlerts.HEARTBEAT, OpsAlerts.RECONCILE_RAN);
    }

    private static Timestamp ts(LocalDateTime value) {
        return Timestamp.valueOf(value);
    }

    /** Written through Hibernate on purpose: the gauge must read what the inbox service writes. */
    private void webhook(LocalDateTime receivedAt, PlaidWebhookEventStatus status) {
        PlaidWebhookEvent event = new PlaidWebhookEvent();
        event.setDedupeKey("opsgauge" + UUID.randomUUID().toString().replace("-", ""));
        event.setWebhookType("TRANSACTIONS");
        event.setWebhookCode("SYNC_UPDATES_AVAILABLE");
        event.setEnvironment("sandbox");
        event.setPayloadJson("{}");
        event.setStatus(status);
        event.setJwtIat(1577880000L);
        event.setReceivedAt(receivedAt);
        webhookEvents.save(event);
    }

    private long userWithSchedule(String holdReason) {
        long userId = seed.user(SIGNUP);
        seed.scheduleWithStatus(userId, SIGNUP, "ACTIVE");
        if (holdReason != null) {
            jdbc.update("UPDATE investment_schedules SET hold_reason = ? WHERE user_id = ?", holdReason, userId);
        }
        return userId;
    }

    private void searchingSince(long userId, LocalDateTime startedAt) {
        jdbc.update("UPDATE investment_schedules SET detection_state = ?, detection_started_at = ? WHERE user_id = ?",
                DetectionState.SEARCHING.name(), ts(startedAt), userId);
    }

    private long stream(long userId) {
        jdbc.update("INSERT INTO paycheck_streams (user_id, plaid_account_id, payer_token, payer_display_name, "
                + "state, role, created_at) VALUES (?, 'acct-opsgauge', 'opsgauge', 'Ops Gauge Payer', "
                + "'PROVISIONAL', 'PRIMARY', ?)", userId, ts(SIGNUP));
        return jdbc.queryForObject("SELECT id FROM paycheck_streams WHERE user_id = ? AND payer_token = 'opsgauge'",
                Long.class, userId);
    }

    private void shadowCycle(long userId, long streamId, String periodKey, LocalDateTime confirmedAt) {
        LocalDate day = confirmedAt.toLocalDate();
        jdbc.update("INSERT INTO paycheck_cycles (user_id, stream_id, period_key, state, expected_date, "
                        + "window_start, window_end, skip_reason, confirmed_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                userId, streamId, periodKey, PaycheckCycleState.SKIPPED.name(), Date.valueOf(day),
                Date.valueOf(day.minusDays(2)), Date.valueOf(day.plusDays(2)),
                PaycheckSkipReason.SHADOW_CONFIRMED.name(), ts(confirmedAt), ts(confirmedAt));
    }

    private void alert(String type, LocalDateTime raisedAt) {
        jdbc.update("INSERT INTO ops_alerts (alert_type, subject_id, alert_day, message, occurrences, "
                        + "first_raised_at, last_raised_at, delivery_status) VALUES (?, ?, ?, 'test', 1, ?, ?, 'LOGGED')",
                type, "opsgauge-" + UUID.randomUUID().toString().substring(0, 8),
                Date.valueOf(raisedAt.toLocalDate()), ts(raisedAt), ts(raisedAt));
    }

    private void item(long userId, String status, boolean synced) {
        jdbc.update("UPDATE users SET plaid_access_token = 'ENC:opsgauge', plaid_item_status = ? WHERE id = ?",
                status, userId);
        if (synced) {
            jdbc.update("UPDATE users SET plaid_last_sync_at = ? WHERE id = ?", ts(SIGNUP), userId);
        }
    }

    private static long delta(Map<String, Long> after, Map<String, Long> before, String key) {
        return after.getOrDefault(key, 0L) - before.getOrDefault(key, 0L);
    }

    @Test
    @DisplayName("every gauge query runs against the migrated schema on the real clock")
    void everyGaugeQueryRunsAgainstTheMigratedSchema() {
        OpsGaugeSnapshot snapshot = gauges.snapshot(EasternTime.now());

        assertThat(snapshot.toContext().keySet()).containsExactlyElementsOf(OpsGaugeSnapshot.GAUGE_KEYS);
        assertThat(snapshot.inboxOldestAgeSec()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("a row received 10 minutes before the bound Eastern clock is 600 seconds old")
    void agesUseTheBoundEasternClock() {
        webhook(NOW_ET.minusMinutes(10), PlaidWebhookEventStatus.RECEIVED);
        webhook(NOW_ET.minusMinutes(5), PlaidWebhookEventStatus.PROCESSING);

        assertThat(gauges.snapshot(NOW_ET).inboxOldestAgeSec()).isEqualTo(600);
    }

    @Test
    @DisplayName("the 24-hour and 7-day windows hang off the bound clock, not the SQL one")
    void windowsAreBoundToTheEasternClock() {
        webhook(NOW_ET.minusMinutes(10), PlaidWebhookEventStatus.PROCESSED);
        webhook(NOW_ET.minusHours(25), PlaidWebhookEventStatus.PROCESSED);
        long stale = userWithSchedule(null);
        searchingSince(stale, NOW_ET.minusHours(25));
        long fresh = userWithSchedule(null);
        searchingSince(fresh, NOW_ET.minusHours(23));
        long notYetEvaluated = userWithSchedule(null); // stamped by INIT, mirror still null (R1)
        jdbc.update("UPDATE investment_schedules SET detection_started_at = ? WHERE user_id = ?",
                ts(NOW_ET.minusHours(25)), notYetEvaluated);
        long rejected = userWithSchedule(null); // R8: never evaluated again, so never alerted on
        searchingSince(rejected, NOW_ET.minusHours(25));
        seed.accountStatus(rejected, "REJECTED");
        long streamId = stream(stale);
        shadowCycle(stale, streamId, "P-IN", NOW_ET.minusDays(3));
        shadowCycle(stale, streamId, "P-OUT", NOW_ET.minusDays(8));
        alert(TEST_ALERT_TYPE, NOW_ET.minusHours(4));
        alert(TEST_ALERT_TYPE + ".old", NOW_ET.minusHours(30));
        alert(OpsAlerts.HEARTBEAT, NOW_ET.minusHours(3)); // the digest itself is not an alert

        OpsGaugeSnapshot snapshot = gauges.snapshot(NOW_ET);

        assertThat(snapshot.webhooksReceived24h()).isEqualTo(1);
        assertThat(snapshot.searchingOver24h()).isEqualTo(2);
        assertThat(snapshot.cyclesShadowConfirmed7d()).isEqualTo(1);
        assertThat(snapshot.opsAlerts24h()).isEqualTo(1);
    }

    @Test
    @DisplayName("reconcile-ran reads yesterday's Eastern day and nothing else")
    void reconcileRanReadsYesterdaysRow() {
        alert(OpsAlerts.RECONCILE_RAN, LocalDateTime.of(2019, 12, 31, 22, 30));

        assertThat(gauges.snapshot(LocalDateTime.of(2020, 1, 1, 9, 7)).reconcileRanYesterday()).isTrue();
        assertThat(gauges.snapshot(LocalDateTime.of(2020, 1, 3, 9, 7)).reconcileRanYesterday()).isFalse();
    }

    @Test
    @DisplayName("billable means a token, a completed sync, and an Item that is not REMOVED")
    void billableCountsSyncedLiveItemsOnly() {
        OpsGaugeSnapshot before = gauges.snapshot(EasternTime.now());
        item(seed.user(SIGNUP), "OK", true);
        item(seed.user(SIGNUP), "REMOVED", true);
        item(seed.user(SIGNUP), "OK", false);
        seed.user(SIGNUP); // no Item at all

        OpsGaugeSnapshot after = gauges.snapshot(EasternTime.now());

        assertThat(after.plaidItemsBillable() - before.plaidItemsBillable()).isEqualTo(1);
        assertThat(delta(after.plaidItemsByStatus(), before.plaidItemsByStatus(), "OK")).isEqualTo(2);
        assertThat(delta(after.plaidItemsByStatus(), before.plaidItemsByStatus(), "REMOVED")).isEqualTo(1);
    }

    @Test
    @DisplayName("holds count every reason; the review alert ignores rejected applicants")
    void manualReviewOpenIgnoresRejectedApplicants() {
        OpsGaugeSnapshot before = gauges.snapshot(EasternTime.now());
        userWithSchedule("MANUAL_REVIEW");
        long rejected = userWithSchedule("MANUAL_REVIEW");
        seed.accountStatus(rejected, "REJECTED");
        userWithSchedule("BANK_CHANGE");

        OpsGaugeSnapshot after = gauges.snapshot(EasternTime.now());

        assertThat(delta(after.holdsByReason(), before.holdsByReason(), "MANUAL_REVIEW")).isEqualTo(2);
        assertThat(delta(after.holdsByReason(), before.holdsByReason(), "BANK_CHANGE")).isEqualTo(1);
        assertThat(after.manualReviewOpen() - before.manualReviewOpen()).isEqualTo(1);
    }

    @Test
    @DisplayName("a pending removal is one whose removed_at is still empty")
    void removalsPendingCountsUnremovedRowsOnly() {
        OpsGaugeSnapshot before = gauges.snapshot(EasternTime.now());
        jdbc.update("INSERT INTO plaid_item_removals (item_id, access_token_enc, requested_at, attempts) "
                + "VALUES ('item-opsgauge-pending', 'ENC:opsgauge', ?, 0)", ts(SIGNUP));
        jdbc.update("INSERT INTO plaid_item_removals (item_id, access_token_enc, requested_at, removed_at, attempts) "
                + "VALUES ('item-opsgauge-done', 'ENC:opsgauge', ?, ?, 1)", ts(SIGNUP), ts(SIGNUP.plusMinutes(15)));

        OpsGaugeSnapshot after = gauges.snapshot(EasternTime.now());

        assertThat(after.removalsPending() - before.removalsPending()).isEqualTo(1);
    }
}
```

- [ ] **Step 3: Run the unit test and see it fail (2 minutes)**

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.ops.OpsGaugeServiceTest' --console=plain
cd ..
```

Expected: `> Task :compileTestJava FAILED` with `error: cannot find symbol` naming `class OpsGaugeSnapshot`, `class OpsGaugeService` and `variable HEARTBEAT` (location `class OpsAlerts`), then `BUILD FAILED`.

- [ ] **Step 4: Add the job lock name and the alert names (2 minutes)**

`.../service/JobLocks.java`: after the line `    public static final String PLAID_ITEM_REMOVALS = "plaid.item-removals";` (`:9` at HEAD) add:

```java
    public static final String OPS_HEARTBEAT = "ops.heartbeat";
```

`.../ops/OpsAlerts.java`: immediately above the line `    private OpsAlerts() {` insert (keep every constant T04 or T06 already wrote, unchanged and in place):

```java
    /** The 09:07 ET digest (T05): raised every morning with every gauge, subject "digest". */
    public static final String HEARTBEAT = "ops.heartbeat";
    /** Webhook inbox rows in FAILED (T05 daily, subject "global"; T14 also raises it per row). */
    public static final String INBOX_FAILED = "plaid.inbox.failed";
    /** The oldest RECEIVED or PROCESSING inbox row is older than 30 minutes (T05). */
    public static final String INBOX_OLDEST_AGE = "plaid.inbox.oldest_age";
    /** No webhook row received in 24 hours while billable Items exist (T05). */
    public static final String WEBHOOKS_NONE_24H = "plaid.webhooks.none_24h";
    /** detection_started_at is over 24 hours old and the mirror is SEARCHING or still null; REJECTED users excluded (T05). */
    public static final String SEARCHING_24H = "paycheck.searching_24h";
    /** A MANUAL_REVIEW hold exists on a user who is not a rejected KYC applicant (T05). */
    public static final String HOLDS_MANUAL_REVIEW_PRESENT = "holds.manual_review_present";
    /** No RECONCILE_RAN row for yesterday's Eastern day (T05, subject "nightly"). */
    public static final String RECONCILE_LATE = "paycheck.reconcile.late";
    /** Raised by the 22:30 reconcile once per run, subject "nightly" (T15); T05 reads it. */
    public static final String RECONCILE_RAN = "paycheck.reconcile.ran";

```

Each value is under the 64 characters of `ops_alerts.alert_type`, and `ops.heartbeat` is also under the 64 of `job_locks.name`. The two tables never meet, so sharing the spelling between `JobLocks.OPS_HEARTBEAT` and `OpsAlerts.HEARTBEAT` is safe.

- [ ] **Step 5: Create the snapshot record (3 minutes)**

Create `backend/src/main/java/com/investingapp/backend/ops/OpsGaugeSnapshot.java`:

```java
package com.investingapp.backend.ops;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every gauge of spec 12.3 the schema can answer, read in one pass by {@link OpsGaugeService} and
 * sent whole in the heartbeat digest. Values are counts, closed-vocabulary bucket names (detection
 * states, Item statuses, hold reasons) and one boolean. Nothing here is an amount, a descriptor, a
 * payer name, an id or a date, because the context leaves the building through the alert provider.
 *
 * <p>paycheck.users counts schedules carrying a detection mirror (ruling R1), so a shadow user
 * with no schedule row appears under plaid.items only. The spec's paycheck.confirm_lag_days is
 * not here: no institution id is stored, and the shadow report (T19) carries confirm lag per user.
 */
public record OpsGaugeSnapshot(
        Map<String, Long> paycheckUsersByState,
        Map<String, Long> plaidItemsByStatus,
        long plaidItemsBillable,
        Map<String, Long> holdsByReason,
        long inboxBacklog,
        long inboxFailed,
        long inboxOldestAgeSec,
        long webhooksReceived24h,
        long removalsPending,
        long cyclesShadowConfirmed7d,
        long opsAlerts24h,
        long searchingOver24h,
        long manualReviewOpen,
        boolean reconcileRanYesterday) {

    /** The digest's keys, in the order a reader sees them. Spec 12.3 names where it has a name. */
    public static final List<String> GAUGE_KEYS = List.of(
            "paycheck.users",
            "plaid.items",
            "plaid.items.billable",
            "holds.by_reason",
            "inbox.backlog",
            "inbox.failed",
            "inbox.oldest_age_sec",
            "webhooks.received_24h",
            "removals.pending",
            "cycles.shadow_confirmed_7d",
            "ops_alerts.24h",
            "searching.over_24h",
            "manual_review.open",
            "reconcile.ran_yesterday");

    public OpsGaugeSnapshot {
        paycheckUsersByState = sorted(paycheckUsersByState);
        plaidItemsByStatus = sorted(plaidItemsByStatus);
        holdsByReason = sorted(holdsByReason);
    }

    private static Map<String, Long> sorted(Map<String, Long> buckets) {
        return buckets == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(buckets));
    }

    /** The heartbeat's alert context: every gauge under its {@link #GAUGE_KEYS} name, in that order. */
    public Map<String, Object> toContext() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("paycheck.users", paycheckUsersByState);
        context.put("plaid.items", plaidItemsByStatus);
        context.put("plaid.items.billable", plaidItemsBillable);
        context.put("holds.by_reason", holdsByReason);
        context.put("inbox.backlog", inboxBacklog);
        context.put("inbox.failed", inboxFailed);
        context.put("inbox.oldest_age_sec", inboxOldestAgeSec);
        context.put("webhooks.received_24h", webhooksReceived24h);
        context.put("removals.pending", removalsPending);
        context.put("cycles.shadow_confirmed_7d", cyclesShadowConfirmed7d);
        context.put("ops_alerts.24h", opsAlerts24h);
        context.put("searching.over_24h", searchingOver24h);
        context.put("manual_review.open", manualReviewOpen);
        context.put("reconcile.ran_yesterday", reconcileRanYesterday);
        return context;
    }
}
```

- [ ] **Step 6: Create the gauge service (5 minutes)**

Create `backend/src/main/java/com/investingapp/backend/ops/OpsGaugeService.java`:

```java
package com.investingapp.backend.ops;

import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.PaycheckCycleState;
import com.investingapp.backend.model.PaycheckSkipReason;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.PlaidWebhookEventStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads every heartbeat gauge with plain SQL (no Micrometer exists, design Decision 8) and no
 * repository methods.
 *
 * <p>Clock rule: no query reads the SQL clock. received_at, confirmed_at, detection_started_at and
 * last_raised_at hold Eastern wall time while the datasource session is UTC, so the caller binds
 * nowET and every cut-off is derived from it here. Temporal parameters are bound as
 * java.sql.Timestamp and java.sql.Date, the JDBC types Hibernate 6 binds for LocalDateTime and
 * LocalDate, so the driver treats the gauge's parameters exactly as it treated the stored values.
 * Windows are bounded on both sides so a bound clock in the past counts only rows inside it.
 *
 * <p>One read-only transaction gives every gauge the same MySQL consistent-read view.
 */
@Service
public class OpsGaugeService {

    static final String ACCOUNT_REJECTED = "REJECTED";

    static final String SQL_USERS_BY_STATE =
            "SELECT detection_state, COUNT(*) FROM investment_schedules "
                    + "WHERE detection_state IS NOT NULL GROUP BY detection_state";
    static final String SQL_ITEMS_BY_STATUS =
            "SELECT plaid_item_status, COUNT(*) FROM users "
                    + "WHERE plaid_access_token IS NOT NULL GROUP BY plaid_item_status";
    static final String SQL_ITEMS_BILLABLE =
            "SELECT COUNT(*) FROM users WHERE plaid_access_token IS NOT NULL "
                    + "AND plaid_last_sync_at IS NOT NULL AND plaid_item_status <> ?";
    static final String SQL_HOLDS_BY_REASON =
            "SELECT hold_reason, COUNT(*) FROM investment_schedules "
                    + "WHERE hold_reason IS NOT NULL GROUP BY hold_reason";
    static final String SQL_MANUAL_REVIEW_OPEN =
            "SELECT COUNT(*) FROM investment_schedules s JOIN users u ON u.id = s.user_id "
                    + "WHERE s.hold_reason = ? AND (u.account_status IS NULL OR u.account_status <> ?)";
    static final String SQL_INBOX_IN_STATUS =
            "SELECT COUNT(*) FROM plaid_webhook_events WHERE status IN (?, ?)";
    static final String SQL_INBOX_FAILED =
            "SELECT COUNT(*) FROM plaid_webhook_events WHERE status = ?";
    static final String SQL_INBOX_OLDEST =
            "SELECT MIN(received_at) FROM plaid_webhook_events WHERE status IN (?, ?)";
    static final String SQL_WEBHOOKS_WINDOW =
            "SELECT COUNT(*) FROM plaid_webhook_events WHERE received_at > ? AND received_at <= ?";
    static final String SQL_REMOVALS_PENDING =
            "SELECT COUNT(*) FROM plaid_item_removals WHERE removed_at IS NULL";
    static final String SQL_CYCLES_SHADOW_WINDOW =
            "SELECT COUNT(*) FROM paycheck_cycles WHERE state = ? AND skip_reason = ? "
                    + "AND confirmed_at > ? AND confirmed_at <= ?";
    static final String SQL_ALERTS_WINDOW =
            "SELECT COUNT(*) FROM ops_alerts WHERE last_raised_at > ? AND last_raised_at <= ? "
                    + "AND alert_type NOT IN (?, ?)";
    static final String SQL_SEARCHING_SINCE_BEFORE =
            "SELECT COUNT(*) FROM investment_schedules s JOIN users u ON u.id = s.user_id "
                    + "WHERE (s.detection_state = ? OR s.detection_state IS NULL) AND s.detection_started_at < ? "
                    + "AND (u.account_status IS NULL OR u.account_status <> ?)";
    static final String SQL_ALERT_ON_DAY =
            "SELECT COUNT(*) FROM ops_alerts WHERE alert_type = ? AND alert_day = ?";

    private final JdbcTemplate jdbc;

    public OpsGaugeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public OpsGaugeSnapshot snapshot(LocalDateTime nowET) {
        Timestamp now = Timestamp.valueOf(nowET);
        Timestamp dayAgo = Timestamp.valueOf(nowET.minusHours(24));
        Timestamp weekAgo = Timestamp.valueOf(nowET.minusDays(7));
        Date yesterday = Date.valueOf(nowET.toLocalDate().minusDays(1));
        String received = PlaidWebhookEventStatus.RECEIVED.name();
        String processing = PlaidWebhookEventStatus.PROCESSING.name();

        Timestamp oldest = jdbc.queryForObject(SQL_INBOX_OLDEST, Timestamp.class, received, processing);
        long oldestAgeSec = oldest == null
                ? 0
                : Math.max(0, Duration.between(oldest.toLocalDateTime(), nowET).getSeconds());

        return new OpsGaugeSnapshot(
                buckets(SQL_USERS_BY_STATE),
                buckets(SQL_ITEMS_BY_STATUS),
                count(SQL_ITEMS_BILLABLE, PlaidItemStatus.REMOVED.name()),
                buckets(SQL_HOLDS_BY_REASON),
                count(SQL_INBOX_IN_STATUS, received, processing),
                count(SQL_INBOX_FAILED, PlaidWebhookEventStatus.FAILED.name()),
                oldestAgeSec,
                count(SQL_WEBHOOKS_WINDOW, dayAgo, now),
                count(SQL_REMOVALS_PENDING),
                count(SQL_CYCLES_SHADOW_WINDOW, PaycheckCycleState.SKIPPED.name(),
                        PaycheckSkipReason.SHADOW_CONFIRMED.name(), weekAgo, now),
                count(SQL_ALERTS_WINDOW, dayAgo, now, OpsAlerts.HEARTBEAT, OpsAlerts.RECONCILE_RAN),
                count(SQL_SEARCHING_SINCE_BEFORE, DetectionState.SEARCHING.name(), dayAgo, ACCOUNT_REJECTED),
                count(SQL_MANUAL_REVIEW_OPEN, HoldReason.MANUAL_REVIEW.name(), ACCOUNT_REJECTED),
                count(SQL_ALERT_ON_DAY, OpsAlerts.RECONCILE_RAN, yesterday) > 0);
    }

    private Map<String, Long> buckets(String sql) {
        Map<String, Long> buckets = new TreeMap<>();
        jdbc.query(sql, (RowCallbackHandler) rs -> buckets.put(rs.getString(1), rs.getLong(2)));
        return buckets;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
```

`opsAlerts24h` leaves out `HEARTBEAT` (the digest itself) and `RECONCILE_RAN` (a nightly marker, not a problem), so the number means "real alerts in the last day". `searchingOver24h` and `manualReviewOpen` exist to drive the two alerts of the same name from this one snapshot.

- [ ] **Step 7: Run the unit test and see it pass (2 minutes)**

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.ops.OpsGaugeServiceTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.ops.OpsGaugeServiceTest.xml
cd ..
```

Expected: `BUILD SUCCESSFUL`; the grep prints `tests="3" skipped="0" failures="0" errors="0"`. Read the counts from the XML, never from the console, which does not total them.

- [ ] **Step 8: Run the database test on a scratch MySQL (5 minutes)**

`OpsGaugeServiceDatabaseTest` is `@RequiresCiDatabase`: without a reachable MySQL it is skipped and Gradle still prints `BUILD SUCCESSFUL`. A run with `skipped` above 0 is not a pass. Use a scratch schema and drop it afterwards. The password is read into a variable and never typed on a command line or echoed:

```
read -rs FRED_SCRATCH_PW && export FRED_SCRATCH_PW
MYSQL_PWD="$FRED_SCRATCH_PW" mysql -u root -h 127.0.0.1 -e "CREATE DATABASE fred_t05"
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t05?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD="$FRED_SCRATCH_PW"
export PLAID_WEBHOOK_URL='https://ci.invalid/api/plaid/webhook'
export PLAID_REDIRECT_URI='https://local.fredvested.com/link-bank'
export PLAID_LINK_CUSTOMIZATION=default
cd backend && ./gradlew cleanTest test --tests 'com.investingapp.backend.ops.OpsGaugeServiceDatabaseTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.ops.OpsGaugeServiceDatabaseTest.xml
cd ..
```

Expected: `BUILD SUCCESSFUL`; the grep prints `tests="7" skipped="0" failures="0" errors="0"`. Keep this shell open (and the schema) for Step 13, which drops it.

If `agesUseTheBoundEasternClock` alone fails with `expected: 600L but was: 18600L` (the bound clock is in January, so the shift is the 5-hour EST offset), Hibernate on this stack binds `LocalDateTime` directly rather than as a `Timestamp`, so the driver shifted the gauge's parameters and not the stored row. Then change `OpsGaugeService` to bind `nowET`, `nowET.minusHours(24)`, `nowET.minusDays(7)` and `nowET.toLocalDate().minusDays(1)` as the `LocalDateTime` / `LocalDate` values themselves (drop the `Timestamp.valueOf` and `Date.valueOf` wrappers), read the oldest row with `LocalDateTime oldest = jdbc.queryForObject(SQL_INBOX_OLDEST, LocalDateTime.class, received, processing)` and use `oldest` in place of `oldest.toLocalDateTime()`; in the test make the helper `private static LocalDateTime ts(LocalDateTime value) { return value; }` and pass the plain `LocalDate` wherever it calls `Date.valueOf(...)`; rerun this step and record the change in the commit body. Any other failure is a bug in the SQL: fix the query, not the test.

- [ ] **Step 9: Write the failing heartbeat test (5 minutes)**

Create `backend/src/test/java/com/investingapp/backend/ops/OpsHeartbeatJobTest.java`:

```java
package com.investingapp.backend.ops;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.service.JobLockService;
import com.investingapp.backend.service.JobLocks;
import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class OpsHeartbeatJobTest {

    private final SchedulerGate gate = mock(SchedulerGate.class);
    private final JobLockService locks = mock(JobLockService.class);
    private final OpsGaugeService gauges = mock(OpsGaugeService.class);
    private final OpsAlertService alerts = mock(OpsAlertService.class);
    private final PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
    private OpsHeartbeatJob job;

    @BeforeEach
    void setUp() {
        job = new OpsHeartbeatJob(gate, locks, gauges, alerts, props);
        when(gate.isEnabled()).thenReturn(true);
        when(locks.tryAcquire(eq(JobLocks.OPS_HEARTBEAT), any())).thenReturn(true);
        when(props.isEnabled()).thenReturn(true);
    }

    private static OpsGaugeSnapshot snap(long inboxFailed, long oldestAgeSec, long billable, long webhooks24h,
                                         long searchingOver24h, long manualReviewOpen, boolean ranYesterday) {
        return new OpsGaugeSnapshot(Map.of("SEARCHING", 2L), Map.of("OK", 3L), billable,
                Map.of("MANUAL_REVIEW", manualReviewOpen), 0, inboxFailed, oldestAgeSec, webhooks24h, 1, 4, 2,
                searchingOver24h, manualReviewOpen, ranYesterday);
    }

    private static OpsGaugeSnapshot quiet() {
        return snap(0, 0, 3, 5, 0, 0, true);
    }

    private static OpsGaugeSnapshot busy() {
        return snap(2, 1801, 3, 0, 1, 1, false);
    }

    @Test
    @DisplayName("the heartbeat fires at 09:07 New York time")
    void cronPinned0907NewYork() throws Exception {
        Scheduled scheduled = OpsHeartbeatJob.class.getMethod("run").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 7 9 * * *");
        assertThat(scheduled.zone()).isEqualTo("America/New_York");
    }

    @Test
    @DisplayName("investment.scheduler.enabled=false stops the job before the lock")
    void honorsSchedulerEnabledFalse() {
        when(gate.isEnabled()).thenReturn(false);

        job.run();

        verifyNoInteractions(locks, gauges, alerts);
    }

    @Test
    @DisplayName("a lease held elsewhere means no snapshot and no alert")
    void lockNotTakenRaisesNothing() {
        when(locks.tryAcquire(eq(JobLocks.OPS_HEARTBEAT), any())).thenReturn(false);

        job.run();

        verifyNoInteractions(gauges, alerts);
        verify(locks, never()).release(any());
    }

    @Test
    @DisplayName("the snapshot is taken once, on the Eastern clock, and the lock is released")
    void snapshotIsTakenOnTheEasternClock() {
        when(gauges.snapshot(any())).thenReturn(quiet());
        LocalDateTime before = EasternTime.now();

        job.run();

        ArgumentCaptor<LocalDateTime> clock = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(gauges, times(1)).snapshot(clock.capture());
        assertThat(clock.getValue()).isBetween(before, EasternTime.now());
        verify(locks).tryAcquire(JobLocks.OPS_HEARTBEAT, OpsHeartbeatJob.LOCK_LEASE);
        verify(locks).release(JobLocks.OPS_HEARTBEAT);
    }

    @Test
    @DisplayName("the digest is raised first and carries every gauge")
    @SuppressWarnings("unchecked")
    void heartbeatCarriesEveryGauge() {
        when(gauges.snapshot(any())).thenReturn(busy());

        job.run();

        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        InOrder order = inOrder(alerts);
        order.verify(alerts).raise(eq(OpsAlerts.HEARTBEAT), eq(OpsHeartbeatJob.SUBJECT_DIGEST), anyString(),
                context.capture());
        assertThat(new ArrayList<>(context.getValue().keySet())).containsExactlyElementsOf(OpsGaugeSnapshot.GAUGE_KEYS);
        assertThat(context.getValue())
                .containsEntry("plaid.items.billable", 3L)
                .containsEntry("inbox.failed", 2L)
                .containsEntry("inbox.oldest_age_sec", 1801L)
                .containsEntry("reconcile.ran_yesterday", false);
    }

    @Test
    @DisplayName("a quiet morning raises the digest and nothing else")
    void quietDayRaisesOnlyTheHeartbeat() {
        when(gauges.snapshot(any())).thenReturn(quiet());

        job.run();

        verify(alerts).raise(eq(OpsAlerts.HEARTBEAT), eq(OpsHeartbeatJob.SUBJECT_DIGEST), anyString(), anyMap());
        verifyNoMoreInteractions(alerts);
    }

    @Test
    @DisplayName("every crossed threshold raises its own alert once, after the digest")
    void everyThresholdRaisesItsAlertOnce() {
        when(gauges.snapshot(any())).thenReturn(busy());

        job.run();

        InOrder order = inOrder(alerts);
        order.verify(alerts).raise(eq(OpsAlerts.HEARTBEAT), eq(OpsHeartbeatJob.SUBJECT_DIGEST), anyString(), anyMap());
        order.verify(alerts).raise(eq(OpsAlerts.INBOX_FAILED), eq(OpsAlertService.GLOBAL_SUBJECT), anyString(), anyMap());
        order.verify(alerts).raise(eq(OpsAlerts.INBOX_OLDEST_AGE), eq(OpsAlertService.GLOBAL_SUBJECT), anyString(), anyMap());
        order.verify(alerts).raise(eq(OpsAlerts.WEBHOOKS_NONE_24H), eq(OpsAlertService.GLOBAL_SUBJECT), anyString(), anyMap());
        order.verify(alerts).raise(eq(OpsAlerts.SEARCHING_24H), eq(OpsAlertService.GLOBAL_SUBJECT), anyString(), anyMap());
        order.verify(alerts).raise(eq(OpsAlerts.HOLDS_MANUAL_REVIEW_PRESENT), eq(OpsAlertService.GLOBAL_SUBJECT),
                anyString(), anyMap());
        order.verify(alerts).raise(eq(OpsAlerts.RECONCILE_LATE), eq(OpsHeartbeatJob.SUBJECT_NIGHTLY), anyString(), anyMap());
        verifyNoMoreInteractions(alerts);
    }

    @Test
    @DisplayName("an inbox exactly 1800 seconds old is not late; 1801 is")
    void inboxAgeAt1800IsNotAlerted() {
        when(gauges.snapshot(any())).thenReturn(snap(0, 1800, 3, 5, 0, 0, true));
        job.run();
        verify(alerts, never()).raise(eq(OpsAlerts.INBOX_OLDEST_AGE), anyString(), anyString(), anyMap());

        when(gauges.snapshot(any())).thenReturn(snap(0, 1801, 3, 5, 0, 0, true));
        job.run();
        verify(alerts, times(1)).raise(eq(OpsAlerts.INBOX_OLDEST_AGE), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("with the pipeline off, only the digest, inbox failures and review holds are raised")
    void disabledAutopilotSkipsPipelineAlerts() {
        when(props.isEnabled()).thenReturn(false);
        when(gauges.snapshot(any())).thenReturn(busy());

        job.run();

        InOrder order = inOrder(alerts);
        order.verify(alerts).raise(eq(OpsAlerts.HEARTBEAT), anyString(), anyString(), anyMap());
        order.verify(alerts).raise(eq(OpsAlerts.INBOX_FAILED), anyString(), anyString(), anyMap());
        order.verify(alerts).raise(eq(OpsAlerts.HOLDS_MANUAL_REVIEW_PRESENT), anyString(), anyString(), anyMap());
        verifyNoMoreInteractions(alerts);
    }

    @Test
    @DisplayName("no reconcile marker for yesterday is a late reconcile")
    void reconcileLateWhenNoRanRow() {
        when(gauges.snapshot(any())).thenReturn(snap(0, 0, 3, 5, 0, 0, false));

        job.run();

        verify(alerts).raise(eq(OpsAlerts.RECONCILE_LATE), eq(OpsHeartbeatJob.SUBJECT_NIGHTLY), anyString(), anyMap());
    }

    @Test
    @DisplayName("a failed snapshot still raises the digest, says so, and releases the lock")
    @SuppressWarnings("unchecked")
    void snapshotFailureStillRaisesHeartbeatAndReleases() {
        when(gauges.snapshot(any())).thenThrow(new DataAccessResourceFailureException("db down"));

        job.run();

        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(alerts).raise(eq(OpsAlerts.HEARTBEAT), eq(OpsHeartbeatJob.SUBJECT_DIGEST), eq("heartbeat snapshot=failed"),
                context.capture());
        assertThat(context.getValue()).containsExactly(Map.entry("snapshot", "failed"));
        verifyNoMoreInteractions(alerts);
        verify(locks).release(JobLocks.OPS_HEARTBEAT);
    }

    @Test
    @DisplayName("alert messages and context keys carry counts and fixed words only")
    @SuppressWarnings("unchecked")
    void messagesCarryCountsOnly() {
        when(gauges.snapshot(any())).thenReturn(busy());

        job.run();

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(alerts, times(7)).raise(anyString(), anyString(), message.capture(), context.capture());
        for (String text : message.getAllValues()) {
            assertThat(text).matches("[a-z0-9_.= ]+");
        }
        for (Map<String, Object> map : context.getAllValues()) {
            for (String key : map.keySet()) {
                assertThat(key).doesNotContain("amount", "date", "descriptor", "name", "token", "cursor", "email");
            }
        }
    }
}
```

- [ ] **Step 10: Run it and see it fail (2 minutes)**

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.ops.OpsHeartbeatJobTest' --console=plain
cd ..
```

Expected: `> Task :compileTestJava FAILED` with `error: cannot find symbol` naming `class OpsHeartbeatJob`, then `BUILD FAILED`.

- [ ] **Step 11: Create the heartbeat job (5 minutes)**

Create `backend/src/main/java/com/investingapp/backend/ops/OpsHeartbeatJob.java`:

```java
package com.investingapp.backend.ops;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.config.SchedulerGate;
import com.investingapp.backend.service.JobLockService;
import com.investingapp.backend.service.JobLocks;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * The 09:07 ET heartbeat (spec 12.3): one digest carrying every gauge, then the daily alerts the
 * schema can answer. The digest is raised every morning on purpose, so a morning without one is
 * itself the signal that the scheduler, the database or the alert channel is down. Minute :07
 * follows the TlhScheduler offset convention, away from the quarter-hour pollers.
 *
 * <p>Runs whatever paycheck.autopilot.enabled says (spec 12.1 keeps the heartbeat on when the
 * pipeline is off). The four alerts about the pipeline's own progress (inbox age, no webhooks,
 * SEARCHING over 24 hours, reconcile late) are raised only when the pipeline is on: with it off
 * the drainer and the reconcile never run, and those four would fire every morning.
 *
 * <p>Reads and raises only. It moves no money and writes no schedule, hold, Item or user row.
 * OpsAlertService.raise writes its dedupe row in its own transaction and delivers on its own
 * executor, so nothing here holds a connection while an alert is sent.
 */
@Component
public class OpsHeartbeatJob {

    private static final Logger logger = LoggerFactory.getLogger(OpsHeartbeatJob.class);

    static final Duration LOCK_LEASE = Duration.ofMinutes(5);
    static final long INBOX_OLDEST_AGE_LIMIT_SEC = 1800;
    static final String SUBJECT_DIGEST = "digest";
    static final String SUBJECT_NIGHTLY = "nightly";

    private final SchedulerGate schedulerGate;
    private final JobLockService jobLockService;
    private final OpsGaugeService gaugeService;
    private final OpsAlertService opsAlertService;
    private final PaycheckAutopilotProperties props;

    public OpsHeartbeatJob(SchedulerGate schedulerGate,
                           JobLockService jobLockService,
                           OpsGaugeService gaugeService,
                           OpsAlertService opsAlertService,
                           PaycheckAutopilotProperties props) {
        this.schedulerGate = schedulerGate;
        this.jobLockService = jobLockService;
        this.gaugeService = gaugeService;
        this.opsAlertService = opsAlertService;
        this.props = props;
    }

    @Scheduled(cron = "0 7 9 * * *", zone = "America/New_York")
    public void run() {
        if (!schedulerGate.isEnabled()) {
            return;
        }
        if (!jobLockService.tryAcquire(JobLocks.OPS_HEARTBEAT, LOCK_LEASE)) {
            logger.info("ops.heartbeat.skipped reason=lock_not_taken");
            return;
        }
        try {
            beat(EasternTime.now());
        } finally {
            jobLockService.release(JobLocks.OPS_HEARTBEAT);
        }
    }

    private void beat(LocalDateTime nowET) {
        OpsGaugeSnapshot s;
        try {
            s = gaugeService.snapshot(nowET);
        } catch (RuntimeException e) {
            logger.error("alert.ops.heartbeat_snapshot_failed cause={}", e.getClass().getSimpleName(), e);
            opsAlertService.raise(OpsAlerts.HEARTBEAT, SUBJECT_DIGEST, "heartbeat snapshot=failed", Map.of("snapshot", "failed"));
            return;
        }

        opsAlertService.raise(OpsAlerts.HEARTBEAT, SUBJECT_DIGEST, digestLine(s), s.toContext());

        boolean pipelineOn = props.isEnabled();
        String global = OpsAlertService.GLOBAL_SUBJECT;
        if (s.inboxFailed() > 0) {
            opsAlertService.raise(OpsAlerts.INBOX_FAILED, global,
                    "inbox.failed count=" + s.inboxFailed(), Map.of("inbox.failed", s.inboxFailed()));
        }
        if (pipelineOn && s.inboxOldestAgeSec() > INBOX_OLDEST_AGE_LIMIT_SEC) {
            opsAlertService.raise(OpsAlerts.INBOX_OLDEST_AGE, global,
                    "inbox.oldest_age_sec value=" + s.inboxOldestAgeSec(),
                    Map.of("inbox.oldest_age_sec", s.inboxOldestAgeSec()));
        }
        if (pipelineOn && s.plaidItemsBillable() > 0 && s.webhooksReceived24h() == 0) {
            opsAlertService.raise(OpsAlerts.WEBHOOKS_NONE_24H, global,
                    "webhooks.received_24h count=0 billable=" + s.plaidItemsBillable(),
                    Map.of("webhooks.received_24h", 0L, "plaid.items.billable", s.plaidItemsBillable()));
        }
        if (pipelineOn && s.searchingOver24h() > 0) {
            opsAlertService.raise(OpsAlerts.SEARCHING_24H, global,
                    "paycheck.searching_24h count=" + s.searchingOver24h(),
                    Map.of("searching.over_24h", s.searchingOver24h()));
        }
        if (s.manualReviewOpen() > 0) {
            opsAlertService.raise(OpsAlerts.HOLDS_MANUAL_REVIEW_PRESENT, global,
                    "holds.manual_review_open count=" + s.manualReviewOpen(),
                    Map.of("manual_review.open", s.manualReviewOpen()));
        }
        if (pipelineOn && !s.reconcileRanYesterday()) {
            opsAlertService.raise(OpsAlerts.RECONCILE_LATE, SUBJECT_NIGHTLY,
                    "paycheck.reconcile.late ran_yesterday=false", Map.of("reconcile.ran_yesterday", false));
        }
        logger.info("ops.heartbeat.sent billable={} backlog={} failed={} alerts_24h={}",
                s.plaidItemsBillable(), s.inboxBacklog(), s.inboxFailed(), s.opsAlerts24h());
    }

    private static String digestLine(OpsGaugeSnapshot s) {
        return "heartbeat billable=" + s.plaidItemsBillable()
                + " backlog=" + s.inboxBacklog()
                + " failed=" + s.inboxFailed()
                + " alerts_24h=" + s.opsAlerts24h();
    }
}
```

The `logger.error("alert.ops.heartbeat_snapshot_failed ...")` line is followed on the next line by `opsAlertService.raise(`, which is the shape T04's `AlertSitesRoutedTest` requires of every `alert.` line, and no `raise(` argument in this file names `total`, `amount`, `getLastError`, `getMessage` or `error`, which is its second rule. The exception goes only to the log, never into the alert.

- [ ] **Step 12: Run the heartbeat test and the neighbours (4 minutes)**

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.ops.OpsHeartbeatJobTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.ops.OpsHeartbeatJobTest.xml
./gradlew test --tests 'com.investingapp.backend.AlertSitesRoutedTest' --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --tests 'com.investingapp.backend.service.JobLockServiceTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.AlertSitesRoutedTest.xml build/test-results/test/TEST-com.investingapp.backend.PhaseOneMoneyInvarianceTest.xml build/test-results/test/TEST-com.investingapp.backend.service.JobLockServiceTest.xml
cd ..
```

Expected: `BUILD SUCCESSFUL` both times; the first grep prints `tests="12" skipped="0" failures="0" errors="0"`; the second grep prints three lines, each with `skipped="0" failures="0" errors="0"` and the same `tests` count each class had before this task (this task adds no case to them). `AlertSitesRoutedTest` now also walks `OpsHeartbeatJob`; `PhaseOneMoneyInvarianceTest` walks `ops/**` and finds no `setStatus(Status.`, `setMode(`, `getIsPaused(` or `isPausedForAnyReason(`.

- [ ] **Step 13: Boot the context on the scratch MySQL, then drop the schema (4 minutes)**

In the same shell as Step 8 (the `SPRING_DATASOURCE_*` and `PLAID_*` exports still set):

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.BackendApplicationTests' --tests 'com.investingapp.backend.ops.OpsGaugeServiceDatabaseTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml build/test-results/test/TEST-com.investingapp.backend.ops.OpsGaugeServiceDatabaseTest.xml
MYSQL_PWD="$FRED_SCRATCH_PW" mysql -u root -h 127.0.0.1 -e "DROP DATABASE fred_t05"
unset FRED_SCRATCH_PW SPRING_DATASOURCE_PASSWORD
cd ..
```

Expected: `BUILD SUCCESSFUL`; `tests="1" skipped="0" failures="0" errors="0"` for `BackendApplicationTests` (the context wires `OpsHeartbeatJob` with T03's and T04's beans) and `tests="7" skipped="0" failures="0" errors="0"` for the database test; the `DROP DATABASE` prints nothing.

- [ ] **Step 14: Commit (3 minutes)**

From the repository root, check that no em dash entered the tree, then hunk-verify each modified file with `git diff HEAD -- <file>` (stage nothing you did not write; never stage `application-local.properties` or `ITPM/agent-memory/*`):

```
grep -n "$(printf '\342\200\224')" backend/src/main/java/com/investingapp/backend/ops/OpsGaugeSnapshot.java backend/src/main/java/com/investingapp/backend/ops/OpsGaugeService.java backend/src/main/java/com/investingapp/backend/ops/OpsHeartbeatJob.java backend/src/main/java/com/investingapp/backend/ops/OpsAlerts.java backend/src/main/java/com/investingapp/backend/service/JobLocks.java backend/src/test/java/com/investingapp/backend/ops/OpsGaugeServiceTest.java backend/src/test/java/com/investingapp/backend/ops/OpsGaugeServiceDatabaseTest.java backend/src/test/java/com/investingapp/backend/ops/OpsHeartbeatJobTest.java
git diff HEAD -- backend/src/main/java/com/investingapp/backend/service/JobLocks.java backend/src/main/java/com/investingapp/backend/ops/OpsAlerts.java
git add backend/src/main/java/com/investingapp/backend/ops/OpsGaugeSnapshot.java backend/src/main/java/com/investingapp/backend/ops/OpsGaugeService.java backend/src/main/java/com/investingapp/backend/ops/OpsHeartbeatJob.java backend/src/main/java/com/investingapp/backend/ops/OpsAlerts.java backend/src/main/java/com/investingapp/backend/service/JobLocks.java backend/src/test/java/com/investingapp/backend/ops/OpsGaugeServiceTest.java backend/src/test/java/com/investingapp/backend/ops/OpsGaugeServiceDatabaseTest.java backend/src/test/java/com/investingapp/backend/ops/OpsHeartbeatJobTest.java
git status --short
git commit -m "feat(ops): SQL gauge snapshot and the 09:07 ET heartbeat digest" -m "Every spec 12.3 gauge the schema can answer, read with JdbcTemplate on a bound Eastern clock, sent whole every morning through OpsAlertService, plus the inbox, webhook, SEARCHING, MANUAL_REVIEW and reconcile-late alerts. Reads only; moves no money and writes no schedule, hold or Item."
```

Expected: the first command prints nothing; the second shows exactly the one added `JobLocks` line and the eight-constant block in `OpsAlerts`; `git status --short` shows the eight paths staged (`A ` for the six new files, `M ` for the two modified) and nothing else staged. If your session's instructions give attribution trailer lines for commits, add them as a further `-m` paragraph.

## Verification

Run from the repository root.

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.ops.OpsGaugeServiceTest' --tests 'com.investingapp.backend.ops.OpsHeartbeatJobTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.ops.*.xml
cd ..
```

Expected: `BUILD SUCCESSFUL`; `tests="3" skipped="0" failures="0" errors="0"` (OpsGaugeServiceTest) and `tests="12" skipped="0" failures="0" errors="0"` (OpsHeartbeatJobTest). The database class is not in this run.

Then the whole backend suite against a fresh scratch schema. In a new shell at the repository root, repeat Step 8's first eight lines exactly (the `read`, the `CREATE DATABASE fred_t05` and the six exports), then:

```
cd backend && ./gradlew cleanTest test --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.ops.OpsGaugeServiceDatabaseTest.xml
grep -ho 'tests="[0-9]*"' build/test-results/test/*.xml | grep -o '[0-9][0-9]*' | paste -sd+ - | bc
grep -ho 'skipped="[0-9]*"' build/test-results/test/*.xml | grep -o '[0-9][0-9]*' | paste -sd+ - | bc
grep -ho 'failures="[0-9]*"\|errors="[0-9]*"' build/test-results/test/*.xml | grep -o '[0-9][0-9]*' | paste -sd+ - | bc
MYSQL_PWD="$FRED_SCRATCH_PW" mysql -u root -h 127.0.0.1 -e "DROP DATABASE fred_t05"
unset FRED_SCRATCH_PW SPRING_DATASOURCE_PASSWORD
cd ..
```

Expected: `BUILD SUCCESSFUL`; `tests="7" skipped="0" failures="0" errors="0"` for the database class; the total is the total of the last full-suite run before this task (whichever tasks landed in between) plus 22 (3 + 7 + 12); the skipped sum is `0`; the failures-plus-errors sum is `0`. A run with any skip is not a pass.

Source checks:

```
grep -n 'NOW()\|CURDATE\|CURRENT_TIMESTAMP' backend/src/main/java/com/investingapp/backend/ops/OpsGaugeService.java
grep -n 'setStatus\|setHoldReason\|setMode\|getIsPaused\|AnalyticsOutboxService' backend/src/main/java/com/investingapp/backend/ops/OpsGauge*.java backend/src/main/java/com/investingapp/backend/ops/OpsHeartbeatJob.java
```

Expected: both print nothing.

## Produces

- `com.investingapp.backend.ops.OpsGaugeSnapshot`: record components `paycheckUsersByState`, `plaidItemsByStatus`, `plaidItemsBillable`, `holdsByReason`, `inboxBacklog`, `inboxFailed`, `inboxOldestAgeSec`, `webhooksReceived24h`, `removalsPending`, `cyclesShadowConfirmed7d`, `opsAlerts24h`, `searchingOver24h`, `manualReviewOpen`, `reconcileRanYesterday`; `GAUGE_KEYS`; `toContext()`.
- `com.investingapp.backend.ops.OpsGaugeService`: `OpsGaugeService(JdbcTemplate)`, `OpsGaugeSnapshot snapshot(LocalDateTime nowET)` (read-only transaction, no SQL clock, Eastern cut-offs `nowET.minusHours(24)` and `nowET.minusDays(7)` bound as parameters; `searchingOver24h` per Deviation 8).
- `com.investingapp.backend.ops.OpsHeartbeatJob`: `run()` at `@Scheduled(cron = "0 7 9 * * *", zone = "America/New_York")`, scheduler gate, `JobLocks.OPS_HEARTBEAT` lease of 5 minutes; raises `OpsAlerts.HEARTBEAT` (subject `digest`, the whole snapshot as context), then `INBOX_FAILED`, `INBOX_OLDEST_AGE` (over 1800 s), `WEBHOOKS_NONE_24H`, `SEARCHING_24H`, `HOLDS_MANUAL_REVIEW_PRESENT` (subject `global`) and `RECONCILE_LATE` (subject `nightly`, no `RECONCILE_RAN` row for yesterday's Eastern day).
- `com.investingapp.backend.service.JobLocks.OPS_HEARTBEAT = "ops.heartbeat"`.
- `com.investingapp.backend.ops.OpsAlerts`: `HEARTBEAT`, `INBOX_FAILED`, `INBOX_OLDEST_AGE`, `WEBHOOKS_NONE_24H`, `SEARCHING_24H`, `HOLDS_MANUAL_REVIEW_PRESENT`, `RECONCILE_LATE`, `RECONCILE_RAN = "paycheck.reconcile.ran"` (T15 raises it, subject `nightly`, once per run; T14 may also raise `INBOX_FAILED` per failed row).

## Deviations from the contract entry, with reasons

1. Consumes T03's `PaycheckAutopilotProperties.isEnabled()`, so T05 also depends on T03 (which already precedes it in the execution order; no reordering). With `paycheck.autopilot.enabled=false` (prod, test, the local default) the drainer and the reconcile never run, so the inbox-age, no-webhooks, SEARCHING and reconcile-late alerts would fire every morning; the heartbeat itself still runs, as spec 12.1 says.
2. `OpsGaugeSnapshot` carries three components beyond the contract's list: `searchingOver24h`, `manualReviewOpen` and `reconcileRanYesterday`, so every alert is decided from the one snapshot. Additive; every contract name is present.
3. `HOLDS_MANUAL_REVIEW_PRESENT` counts MANUAL_REVIEW holds on users whose `account_status` is not `REJECTED` (`holdsByReason` still counts all). Handoff 2.1 puts a permanent MANUAL_REVIEW hold on every rejected applicant, so the plain count would never clear and the alert would carry no information.
4. `WEBHOOKS_NONE_24H` is raised only while `plaidItemsBillable > 0`: with no synced Item, silence is expected.
5. `opsAlerts24h` excludes `HEARTBEAT` and `RECONCILE_RAN` rows, so it counts real alerts only.
6. A `@RequiresCiDatabase` `@SpringBootTest` class (`OpsGaugeServiceDatabaseTest`) is added, and the contract's `agesUseTheBoundEasternClock` lives there. The inputs allow new database tests where a transaction boundary is under test; here the SQL itself is under test, and a mocked `JdbcTemplate` cannot prove a query against the V6 and V10 columns. The existing `metrics/*ViewTest` classes are the precedent and `MetricsViewSeed` is reused.
7. When the snapshot throws, the heartbeat still raises `HEARTBEAT` with `{snapshot: failed}`, so a broken database shows up in the digest channel instead of as silence.
8. `searchingOver24h` (and so `SEARCHING_24H`) counts a schedule whose `detection_started_at` is over 24 hours old while its mirror is `SEARCHING` or still null, and leaves out users whose `account_status` is `REJECTED`. Null counts because under shadow the MANUAL row's mirror stays null until detection first runs (R1), T15 and T16 stamp `detection_started_at` at INIT, and the status endpoint reports a null mirror as SEARCHING: a first sync that never completes is exactly the stall this alert exists to catch. REJECTED is left out because R8 stops evaluating those users, so their mirror never moves and the alert would fire every morning forever (the same reason as item 3). T13's `resetForNewItem` nulls both columns together, so a relink does not trip it.
