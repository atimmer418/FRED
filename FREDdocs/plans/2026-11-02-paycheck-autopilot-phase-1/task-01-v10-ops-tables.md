### Task 01: v10-ops-tables

Adds the only Phase 1 DDL, `V10__ops_alerts_and_admin_actions.sql`, and the two entities and two repositories that map it, so the `ddl-auto=validate` trap is met on day one instead of inside a later behavioural task. `ops_alerts` is the dedupe ledger `OpsAlertService` (T04) writes, keyed `(alert_type, subject_id, alert_day)` (spec 12.3, ruling R11). `admin_actions` is the audit trail every `/api/admin/**` call writes (spec 12.2, ruling R12). Nothing here is behavioural: no service, no property, no event, no log line. The gate is `BackendApplicationTests` booting under the `ci` profile on a scratch MySQL schema with 0 skipped.

`.../` = `backend/src/main/java/com/investingapp/backend/`; tests mirror it under `backend/src/test/java/com/investingapp/backend/`. Every path below was checked against HEAD 137998c on `feature/paycheck-autopilot-phase-1`. This task creates files only and modifies none, so there are no line anchors to drift.

**Facts this task depends on (restated so this file stands alone):**

- **The number is V10, not V9 (R15).** The highest migration on this branch is `V8__analytics_person_deletions.sql`. The concurrent Goals v3 branch (`feature/goals-v3-wt`) already commits the Goals v3 version 9 migration (`goals.sql`). Two files with one version number merge cleanly in git (different names) and then stop Flyway at boot, a failure no unit test or review sees. Step 1 checks the directory before anything is written.
- **V6 says "Phases 1 to 3 add no migration".** That sentence covered spec section 3, the data model. These two tables come from section 12, the ops tooling, which V6 never built. The V10 header says so, so nobody reads V10 as a breach of V6.
- **`ddl-auto=validate` runs in every profile** and checks that each mapped column exists with a compatible type. It never checks `DEFAULT` or `NOT NULL`. Hibernate binds every mapped column on insert, so a database `DEFAULT` never applies to a row the app writes: `occurrences = 1` and `delivery_status = 'PENDING'` must be Java field initialisers. `PhaseOneEntityDefaultsTest` pins the initialisers and the widths and nullability validate cannot see.
- **House migration shape** (copy of V6 and V8): `CREATE TABLE IF NOT EXISTS`, backtick-quoted names, `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`. V10 has no `ALTER`, so it needs none of the V3 `information_schema` plus `PREPARE` guards; the test pins their absence.
- **No foreign keys.** Both tables outlive the users they name: an alert subject is often not a user (an Item id, a job name, `global`), and the record of what an operator did to an account must survive the account. `MetricsViewSeed.cleanCohort` (`backend/src/test/java/com/investingapp/backend/metrics/MetricsViewSeed.java`, the `information_schema.KEY_COLUMN_USAGE` query that finds `REFERENCED_TABLE_NAME = 'users'`) deletes every table that has a foreign key to `users`, so a key here would also change what every metrics view test deletes. No view changes; `BLOCKING_HOLDS` is untouched.
- **Content rule for both tables.** The tables hold ids, counts, error codes and closed-vocabulary words. Never an amount, a payer name or descriptor, a Plaid token or cursor, an email, or a free-text error body (spec 12.3, Decision 37). This task writes no row; T04, T17 and T18 enforce the rule where they write. The javadoc on each entity restates it so the next writer sees it.
- **Phase 0 entity style:** `@Entity @Table @Getter @Setter @NoArgsConstructor` (Lombok, not `@Data`), `import jakarta.persistence.*;`, an explicit `@Column(name = ...)` on every field, bare `Long` ids where a row outlives the user (`PlaidItemRemoval.userId`), timestamps set by the service, never `@CreationTimestamp` or `@PrePersist`.
- **Money and schedule invariants.** This task cannot move money and does not touch `InvestmentSchedule` at all: no `status` write, no `hold_reason` write, no change to `isReadyForInvestment`, no Plaid or Alpaca call.

**Database caution (read before booting against anything but a scratch schema).** On a fresh scratch schema the V8 to V10 gap is fine: Flyway applies V1 to V8, then V10. On a persistent database (Andy's local `investing_dev`, or `api-dev` on Railway), if V10 is recorded first and the Goals v3 version 9 migration (`goals.sql`) arrives afterwards, Flyway refuses to boot with `Detected resolved migration not applied to database: 9`, because `application.properties:119-122` sets no `spring.flyway.out-of-order`. This task does not change Flyway configuration. Run this task's database steps against the scratch schema only, and leave the merge order of Goals v3 and this branch to Andy (the plan header lists it as a known seam). Nothing is live, so this is a convenience, not a gate: the easy path is to merge develop into this branch after Goals v3 lands; if a dev database got V10 first, delete its `flyway_schema_history` row for version 10 and boot again (V10 is two `CREATE TABLE IF NOT EXISTS`, so the re-run is a no-op).

**Seam with T02 (parallel start).** T01 creates `PhaseOneEntityDefaultsTest`. If T02 has already landed and created a file of the same name, add this task's three test methods, its four private helpers (`column`, `string`, `plain`, `text`) and the `jakarta.persistence.Column`, `java.time.LocalDate` and `java.time.LocalDateTime` imports to the existing class instead of replacing it, and keep every method T02 put there. In that case Step 13 shows the file as ` M` (and in `git diff --stat HEAD`) instead of `??`.

**Local-properties note:** this task adds no property. The gitignored `backend/src/main/resources/application-local.properties` needs no edit for T01. Its `spring.datasource.username` and `spring.datasource.password` keys are read (never printed) by the scratch-schema blocks in Steps 11 and 12.

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__ops_alerts_and_admin_actions.sql`
- Create: `.../model/OpsAlert.java`, `.../model/AdminAction.java`
- Create: `.../repository/OpsAlertRepository.java`, `.../repository/AdminActionRepository.java`
- Test: create `backend/src/test/java/com/investingapp/backend/migration/V10OpsAlertsAndAdminActionsMigrationTest.java` (5 tests) and `backend/src/test/java/com/investingapp/backend/model/PhaseOneEntityDefaultsTest.java` (3 tests, including `opsAlertDefaultsMatchV10`). Gate: `backend/src/test/java/com/investingapp/backend/BackendApplicationTests.java` (unchanged, `@RequiresCiDatabase`) on a scratch MySQL schema, 0 skipped.
- Modify: none.

**Interfaces:**
- Consumes: nothing from another Phase 1 task. Spring Data JPA derived queries, Lombok, and the existing `ci` profile (`backend/src/main/resources/application-ci.properties`).
- Produces (consumed by T04, T05, T17 and T18):
  - `public class OpsAlert` (`ops_alerts`): `Long id`; `String alertType` (64, NN); `String subjectId` (80, NN); `LocalDate alertDay` (NN, the Eastern day); `String message` (500, NN); `String contextJson` (TEXT); `int occurrences = 1` (NN); `LocalDateTime firstRaisedAt` (NN, set by the service); `LocalDateTime lastRaisedAt` (NN, set by the service); `@Column(name = "delivery_status", length = 12, nullable = false) String deliveryStatus = "PENDING"` (T04 writes `SENT`, `FAILED` or `LOGGED`). Lombok getters and setters for every field.
  - `public class AdminAction` (`admin_actions`): `Long id`; `String actor` (80, NN); `Long userId` (nullable, no foreign key); `String verb` (64, NN); `String targetId` (80); `String beforeJson` (TEXT); `String afterJson` (TEXT); `String reason` (255); `LocalDateTime createdAt` (NN, Eastern wall time, set by the service). Lombok getters and setters for every field.
  - `public interface OpsAlertRepository extends JpaRepository<OpsAlert, Long>`: `Optional<OpsAlert> findByAlertTypeAndSubjectIdAndAlertDay(String alertType, String subjectId, LocalDate alertDay)`; `long countByFirstRaisedAtAfter(LocalDateTime cutoff)`.
  - `public interface AdminActionRepository extends JpaRepository<AdminAction, Long>`: `List<AdminAction> findTop50ByUserIdOrderByCreatedAtDesc(Long userId)`.
  - Database: unique key `uq_ops_alerts_type_subject_day (alert_type, subject_id, alert_day)`, keys `ix_ops_alerts_day`, `ix_admin_actions_user_created`, `ix_admin_actions_created`.

- [ ] **Step 1: R15 directory check (stop if V10 is taken)**

From the repository root:

```bash
ls backend/src/main/resources/db/migration/
ls backend/src/main/resources/db/migration/ | grep -E '^V(9|[1-9][0-9])__'
```

Expected: the first command lists exactly these 15 files, `R__v_activation_funnel.sql`, `R__v_consent.sql`, `R__v_contribution_retention.sql`, `R__v_kyc_outcomes.sql`, `R__v_outbox_reconciliation.sql`, `R__v_schedule_churn.sql`, `R__v_time_to_funded.sql`, `V1__baseline.sql`, `V2__user_public_id.sql`, `V3__catch_up_pre_flyway_schemas.sql`, `V4__user_analytics_opt_out.sql`, `V5__analytics_outbox.sql`, `V6__paycheck_autopilot.sql`, `V7__investment_schedule_status.sql`, `V8__analytics_person_deletions.sql`. The second prints nothing.

Decision rule: if the second command prints only the Goals v3 version 9 migration (`goals.sql`), Goals v3 merged into this branch first; that is expected and you continue. If it prints any `V10__` file, or any version 9 file other than that one, or any higher number, **stop**: write nothing, and report the listing to the controller. Do not rename someone else's migration.

- [ ] **Step 2: Write the failing migration shape test**

Create `backend/src/test/java/com/investingapp/backend/migration/V10OpsAlertsAndAdminActionsMigrationTest.java`:

```java
package com.investingapp.backend.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the shape of V10 without a database: exactly two CREATE TABLE IF NOT EXISTS, nothing that
 * alters, drops or references an existing table, every column and key exactly as the Phase 1
 * design states it, and one file per Flyway version in db/migration (two branches choosing the
 * same number merge cleanly and then stop Flyway at boot, ruling R15). The validate gate
 * (BackendApplicationTests under ci) proves the SQL runs and the entities match it.
 */
class V10OpsAlertsAndAdminActionsMigrationTest {

    private static final String FILE = "db/migration/V10__ops_alerts_and_admin_actions.sql";
    private static final String ENGINE = ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;";
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.+\\.sql$");
    private static final char EM_DASH = (char) 0x2014;

    private static final List<String> OPS_ALERTS = List.of(
            "`id` bigint NOT NULL AUTO_INCREMENT,",
            "`alert_type` varchar(64) NOT NULL,",
            "`subject_id` varchar(80) NOT NULL,",
            "`alert_day` date NOT NULL,",
            "`message` varchar(500) NOT NULL,",
            "`context_json` text,",
            "`occurrences` int NOT NULL DEFAULT 1,",
            "`first_raised_at` datetime(6) NOT NULL,",
            "`last_raised_at` datetime(6) NOT NULL,",
            "`delivery_status` varchar(12) NOT NULL DEFAULT 'PENDING',",
            "PRIMARY KEY (`id`),",
            "UNIQUE KEY `uq_ops_alerts_type_subject_day` (`alert_type`, `subject_id`, `alert_day`),",
            "KEY `ix_ops_alerts_day` (`alert_day`)");

    private static final List<String> ADMIN_ACTIONS = List.of(
            "`id` bigint NOT NULL AUTO_INCREMENT,",
            "`actor` varchar(80) NOT NULL,",
            "`user_id` bigint DEFAULT NULL,",
            "`verb` varchar(64) NOT NULL,",
            "`target_id` varchar(80) DEFAULT NULL,",
            "`before_json` text,",
            "`after_json` text,",
            "`reason` varchar(255) DEFAULT NULL,",
            "`created_at` datetime(6) NOT NULL,",
            "PRIMARY KEY (`id`),",
            "KEY `ix_admin_actions_user_created` (`user_id`, `created_at`),",
            "KEY `ix_admin_actions_created` (`created_at`)");

    @Test
    void createsExactlyTwoTablesIfNotExists() throws IOException {
        String body = statements();
        assertThat(Pattern.compile("(?m)^CREATE TABLE(?! IF NOT EXISTS)").matcher(body).find()).isFalse();
        assertThat(count(body, "CREATE TABLE")).isEqualTo(2);
        assertThat(body).contains("CREATE TABLE IF NOT EXISTS `ops_alerts` (")
                .contains("CREATE TABLE IF NOT EXISTS `admin_actions` (");
        assertThat(count(body, ENGINE)).isEqualTo(2);
    }

    @Test
    void altersDropsAndReferencesNothing() throws IOException {
        String upper = statements().toUpperCase(Locale.ROOT);
        for (String forbidden : List.of("ALTER ", "DROP ", "PREPARE ", "FOREIGN KEY", "REFERENCES ",
                "VIEW ", "INSERT ", "UPDATE ", "DELETE ")) {
            assertThat(upper).as(forbidden.strip()).doesNotContain(forbidden);
        }
    }

    @Test
    void everyColumnAndKeyMatchesTheDesign() throws IOException {
        String body = statements();
        assertThat(lines(table(body, "ops_alerts"))).containsExactlyElementsOf(OPS_ALERTS);
        assertThat(lines(table(body, "admin_actions"))).containsExactlyElementsOf(ADMIN_ACTIONS);
    }

    @Test
    void hasNoEmDash() throws IOException {
        assertThat(sql()).doesNotContain(String.valueOf(EM_DASH));
    }

    @Test
    void versionedMigrationNumbersAreUnique() throws Exception {
        URL dir = getClass().getClassLoader().getResource("db/migration");
        assertThat(dir).as("db/migration on the classpath").isNotNull();
        assertThat(dir.getProtocol()).as("tests read migrations from build/resources/main").isEqualTo("file");
        Map<Integer, List<String>> byVersion = new TreeMap<>();
        try (Stream<Path> files = Files.list(Paths.get(dir.toURI()))) {
            files.map(path -> path.getFileName().toString()).sorted().forEach(name -> {
                Matcher m = VERSIONED.matcher(name);
                if (m.matches()) {
                    byVersion.computeIfAbsent(Integer.parseInt(m.group(1)), v -> new ArrayList<>()).add(name);
                }
            });
        }
        assertThat(byVersion).containsKey(10);
        byVersion.forEach((version, names) -> assertThat(names).as("Flyway version " + version).hasSize(1));
    }

    private static String sql() throws IOException {
        try (InputStream in = V10OpsAlertsAndAdminActionsMigrationTest.class.getClassLoader().getResourceAsStream(FILE)) {
            assertThat(in).as(FILE + " must exist on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The file without its comment lines, so the header prose can neither satisfy nor trip a check. */
    private static String statements() throws IOException {
        return sql().lines().filter(line -> !line.strip().startsWith("--")).collect(Collectors.joining("\n"));
    }

    private static String table(String body, String name) {
        String head = "CREATE TABLE IF NOT EXISTS `" + name + "` (";
        int start = body.indexOf(head);
        assertThat(start).as(head).isNotNegative();
        int end = body.indexOf(ENGINE, start);
        assertThat(end).as(name + " closes with the house ENGINE line").isGreaterThan(start);
        return body.substring(start + head.length(), end);
    }

    private static List<String> lines(String block) {
        return block.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private static int count(String haystack, String needle) {
        return haystack.split(Pattern.quote(needle), -1).length - 1;
    }
}
```

`EM_DASH` is the code point `0x2014` written as a number, so the test source itself never contains the character it forbids (a Java `\u` escape would also work, but it is processed before lexing and is easy to mangle in an editor).

- [ ] **Step 3: Run it and watch all five fail**

```bash
(cd backend && ./gradlew test --tests 'com.investingapp.backend.migration.V10OpsAlertsAndAdminActionsMigrationTest' --console=plain)
```

Every gradle command in this task runs in a `( ... )` subshell from the repository root, so the shell stays at the root and the root-relative `backend/build/...` paths in later steps resolve.

Expected: compilation succeeds, then `BUILD FAILED` with `5 tests completed, 5 failed`; each of the five methods prints `FAILED` followed by `java.lang.AssertionError`. In `backend/build/test-results/test/TEST-com.investingapp.backend.migration.V10OpsAlertsAndAdminActionsMigrationTest.xml` the first four failures carry the message `[db/migration/V10__ops_alerts_and_admin_actions.sql must exist on the classpath]` and `versionedMigrationNumbersAreUnique` carries `to contain key:` followed by `10`.

- [ ] **Step 4: Write V10**

Create `backend/src/main/resources/db/migration/V10__ops_alerts_and_admin_actions.sql`:

```sql
-- Paycheck Autopilot Phase 1: the ops alert ledger (spec 12.3) and the admin audit trail (spec 12.2).
--
-- V6 says Phases 1 to 3 add no migration. That held for spec section 3, the data model. These two
-- tables come from section 12, the ops tooling, which V6 never built: OpsAlertService needs a
-- ledger to dedupe on, and the prod-registered /api/admin/** surface needs one row per call.
--
-- Numbered V10, not V9: the Goals v3 branch owns version 9 (goals.sql). Two files with one version number
-- merge cleanly in git and then stop Flyway at boot.
--
-- Shape, per V6 and V8: CREATE TABLE IF NOT EXISTS, so a re-run is a no-op. There is no ALTER, so
-- no information_schema guard is needed. No view changes. No foreign keys: both tables outlive the
-- users they name, an alert subject is often not a user at all, and MetricsViewSeed.cleanCohort
-- deletes every table with a foreign key to users, so a key here would change what it deletes.
--
-- ops_alerts: one row per (alert_type, subject_id, alert_day). alert_day is the Eastern calendar
-- day. A repeat on the same day bumps occurrences and last_raised_at and sends nothing. Both
-- timestamps are Eastern wall time, set by OpsAlertService. delivery_status is PENDING until the
-- delivery attempt records SENT, FAILED or LOGGED. The application sets every column on insert,
-- so the DEFAULTs below are for hand-written rows only; the entity mirrors them in Java.
--
-- admin_actions: one row per /api/admin/** call. user_id is kept after the user is deleted, on
-- purpose: the record of who did what to an account has to survive the account. created_at is
-- Eastern wall time, set by AdminAuditService.
--
-- Content rule for both tables: ids, counts, error codes and closed-vocabulary words only. Never an
-- amount, a payer name or descriptor, a Plaid token or cursor, an email, or a free-text error body.
--
-- Idempotent: safe to run against a database that already has both tables.

CREATE TABLE IF NOT EXISTS `ops_alerts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `alert_type` varchar(64) NOT NULL,
  `subject_id` varchar(80) NOT NULL,
  `alert_day` date NOT NULL,
  `message` varchar(500) NOT NULL,
  `context_json` text,
  `occurrences` int NOT NULL DEFAULT 1,
  `first_raised_at` datetime(6) NOT NULL,
  `last_raised_at` datetime(6) NOT NULL,
  `delivery_status` varchar(12) NOT NULL DEFAULT 'PENDING',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ops_alerts_type_subject_day` (`alert_type`, `subject_id`, `alert_day`),
  KEY `ix_ops_alerts_day` (`alert_day`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `admin_actions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `actor` varchar(80) NOT NULL,
  `user_id` bigint DEFAULT NULL,
  `verb` varchar(64) NOT NULL,
  `target_id` varchar(80) DEFAULT NULL,
  `before_json` text,
  `after_json` text,
  `reason` varchar(255) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_admin_actions_user_created` (`user_id`, `created_at`),
  KEY `ix_admin_actions_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

- [ ] **Step 5: Run the shape test green**

Run the Step 3 command. Expected: `BUILD SUCCESSFUL`. Read the counts from the XML, not the console:

```bash
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.migration.V10OpsAlertsAndAdminActionsMigrationTest.xml
```

Expected: `tests="5" skipped="0" failures="0" errors="0"`. If `everyColumnAndKeyMatchesTheDesign` fails, the assertion output lists the expected lines it did not find and the lines it did not expect; fix the SQL, not the test (the test is the design table).

- [ ] **Step 6: Write the failing entity defaults test**

Create `backend/src/test/java/com/investingapp/backend/model/PhaseOneEntityDefaultsTest.java` (see the T02 seam note above if the file already exists):

```java
package com.investingapp.backend.model;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 entities must mirror the migration DEFAULTs in Java (ops_alerts.occurrences 1,
 * ops_alerts.delivery_status 'PENDING'): Hibernate binds every mapped column on insert, so a
 * database DEFAULT never applies to a row the application writes, and an unset NOT NULL column
 * would fail every insert. ddl-auto=validate checks column existence and type only, never DEFAULT,
 * width or NOT NULL, so the column test pins what validate cannot see.
 */
class PhaseOneEntityDefaultsTest {

    @Test
    void opsAlertDefaultsMatchV10() {
        OpsAlert alert = new OpsAlert();
        assertThat(alert.getOccurrences()).isEqualTo(1);
        assertThat(alert.getDeliveryStatus()).isEqualTo("PENDING");
        assertThat(alert.getId()).isNull();
        assertThat(alert.getAlertType()).isNull();
        assertThat(alert.getSubjectId()).isNull();
        assertThat(alert.getAlertDay()).isNull();
        assertThat(alert.getMessage()).isNull();
        assertThat(alert.getContextJson()).isNull();
        assertThat(alert.getFirstRaisedAt()).isNull();
        assertThat(alert.getLastRaisedAt()).isNull();
    }

    @Test
    void adminActionStartsEmpty() {
        AdminAction action = new AdminAction();
        assertThat(action.getId()).isNull();
        assertThat(action.getActor()).isNull();
        assertThat(action.getUserId()).isNull();
        assertThat(action.getVerb()).isNull();
        assertThat(action.getTargetId()).isNull();
        assertThat(action.getBeforeJson()).isNull();
        assertThat(action.getAfterJson()).isNull();
        assertThat(action.getReason()).isNull();
        assertThat(action.getCreatedAt()).isNull();
    }

    @Test
    void columnsMirrorV10WidthsAndNullability() throws NoSuchFieldException {
        string(OpsAlert.class, "alertType", "alert_type", 64, false);
        string(OpsAlert.class, "subjectId", "subject_id", 80, false);
        plain(OpsAlert.class, "alertDay", "alert_day", LocalDate.class, false);
        string(OpsAlert.class, "message", "message", 500, false);
        text(OpsAlert.class, "contextJson", "context_json");
        plain(OpsAlert.class, "occurrences", "occurrences", int.class, false);
        plain(OpsAlert.class, "firstRaisedAt", "first_raised_at", LocalDateTime.class, false);
        plain(OpsAlert.class, "lastRaisedAt", "last_raised_at", LocalDateTime.class, false);
        string(OpsAlert.class, "deliveryStatus", "delivery_status", 12, false);

        string(AdminAction.class, "actor", "actor", 80, false);
        plain(AdminAction.class, "userId", "user_id", Long.class, true);
        string(AdminAction.class, "verb", "verb", 64, false);
        string(AdminAction.class, "targetId", "target_id", 80, true);
        text(AdminAction.class, "beforeJson", "before_json");
        text(AdminAction.class, "afterJson", "after_json");
        string(AdminAction.class, "reason", "reason", 255, true);
        plain(AdminAction.class, "createdAt", "created_at", LocalDateTime.class, false);
    }

    private static Column column(Class<?> type, String field) throws NoSuchFieldException {
        Column column = type.getDeclaredField(field).getAnnotation(Column.class);
        assertThat(column).as(type.getSimpleName() + "." + field + " carries @Column").isNotNull();
        return column;
    }

    private static void string(Class<?> type, String field, String name, int length, boolean nullable)
            throws NoSuchFieldException {
        String label = type.getSimpleName() + "." + field;
        Column column = column(type, field);
        assertThat(type.getDeclaredField(field).getType()).as(label).isEqualTo(String.class);
        assertThat(column.name()).as(label).isEqualTo(name);
        assertThat(column.length()).as(label + " length").isEqualTo(length);
        assertThat(column.nullable()).as(label + " nullable").isEqualTo(nullable);
    }

    private static void plain(Class<?> type, String field, String name, Class<?> javaType, boolean nullable)
            throws NoSuchFieldException {
        String label = type.getSimpleName() + "." + field;
        Column column = column(type, field);
        assertThat(type.getDeclaredField(field).getType()).as(label).isEqualTo(javaType);
        assertThat(column.name()).as(label).isEqualTo(name);
        assertThat(column.nullable()).as(label + " nullable").isEqualTo(nullable);
    }

    private static void text(Class<?> type, String field, String name) throws NoSuchFieldException {
        String label = type.getSimpleName() + "." + field;
        Column column = column(type, field);
        assertThat(type.getDeclaredField(field).getType()).as(label).isEqualTo(String.class);
        assertThat(column.name()).as(label).isEqualTo(name);
        assertThat(column.columnDefinition()).as(label + " columnDefinition").isEqualTo("TEXT");
        assertThat(column.nullable()).as(label + " nullable").isTrue();
    }
}
```

- [ ] **Step 7: Run it and watch it fail to compile**

```bash
(cd backend && ./gradlew test --tests 'com.investingapp.backend.model.PhaseOneEntityDefaultsTest' --console=plain)
```

Expected: `> Task :compileTestJava FAILED` with `PhaseOneEntityDefaultsTest.java` reporting `error: cannot find symbol` for `symbol:   class OpsAlert` and `symbol:   class AdminAction`, then `BUILD FAILED`.

- [ ] **Step 8: Write the two entities**

Create `backend/src/main/java/com/investingapp/backend/model/OpsAlert.java`:

```java
package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row of the ops alert ledger (spec 12.3), written only by OpsAlertService.raise. The row is
 * the dedupe key: one per (alertType, subjectId, alertDay), where alertDay is the Eastern calendar
 * day. A repeat on the same day bumps occurrences and lastRaisedAt and sends nothing.
 *
 * <p>message and contextJson carry ids, counts, error codes and closed-vocabulary words only:
 * never an amount, a payer name or descriptor, a Plaid token or cursor, an email, or a free-text
 * error body. The ledger is read by people and forwarded to a third-party receiver.
 *
 * <p>occurrences and deliveryStatus are initialised here because Hibernate binds every mapped
 * column on insert, so the V10 DEFAULTs never apply to a row the application writes.
 * firstRaisedAt and lastRaisedAt are Eastern wall time, set by the service.
 */
@Entity
@Table(name = "ops_alerts")
@Getter
@Setter
@NoArgsConstructor
public class OpsAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** An OpsAlerts constant, for example the Plaid outage alert. */
    @Column(name = "alert_type", nullable = false, length = 64)
    private String alertType;

    /** A user id, an Item id, a job name or "global". Not a foreign key: alerts outlive users. */
    @Column(name = "subject_id", nullable = false, length = 80)
    private String subjectId;

    /** The Eastern calendar day of the first raise; the third part of the dedupe key. */
    @Column(name = "alert_day", nullable = false)
    private LocalDate alertDay;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    @Column(name = "occurrences", nullable = false)
    private int occurrences = 1;

    @Column(name = "first_raised_at", nullable = false)
    private LocalDateTime firstRaisedAt;

    @Column(name = "last_raised_at", nullable = false)
    private LocalDateTime lastRaisedAt;

    /** PENDING until the delivery attempt records SENT, FAILED or LOGGED. */
    @Column(name = "delivery_status", length = 12, nullable = false)
    private String deliveryStatus = "PENDING";
}
```

Create `backend/src/main/java/com/investingapp/backend/model/AdminAction.java`:

```java
package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One call to the prod-registered admin surface (spec 12.2): who (actor, from X-Admin-Actor), on
 * which user, which verb, the state before and after, and why. Written by AdminAuditService for
 * every /api/admin/** call, refusals included.
 *
 * <p>beforeJson and afterJson carry state words and ids only (status, holdReason, detectionState,
 * primaryStreamId and the like): never a Plaid token or cursor, a descriptor, a payer name, an
 * amount or an email. createdAt is Eastern wall time, set by the service.
 */
@Entity
@Table(name = "admin_actions")
@Getter
@Setter
@NoArgsConstructor
public class AdminAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor", nullable = false, length = 80)
    private String actor;

    /**
     * No foreign key: the audit row outlives the user it names. Null for a verb that names no
     * user (the Item listing, the webhook verbs).
     */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "verb", nullable = false, length = 64)
    private String verb;

    /** The verb's own target when it has one: a stream id, a cycle id, a webhook row id. */
    @Column(name = "target_id", length = 80)
    private String targetId;

    @Column(name = "before_json", columnDefinition = "TEXT")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "TEXT")
    private String afterJson;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
```

- [ ] **Step 9: Run the entity test green**

Run the Step 7 command. Expected: `BUILD SUCCESSFUL`, and

```bash
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.model.PhaseOneEntityDefaultsTest.xml
```

prints `tests="3" skipped="0" failures="0" errors="0"` (more tests if T02 landed first and added its methods to this class; skipped and failures stay 0).

- [ ] **Step 10: Write the two repositories and compile**

Create `backend/src/main/java/com/investingapp/backend/repository/OpsAlertRepository.java`:

```java
package com.investingapp.backend.repository;

import com.investingapp.backend.model.OpsAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/** ops_alerts: the dedupe ledger behind OpsAlertService.raise (spec 12.3). */
@Repository
public interface OpsAlertRepository extends JpaRepository<OpsAlert, Long> {

    /** The dedupe lookup: today's row for this alert type and subject, if one was raised. */
    Optional<OpsAlert> findByAlertTypeAndSubjectIdAndAlertDay(String alertType, String subjectId, LocalDate alertDay);

    /** Alerts first raised after the cut-off. The cut-off is Eastern wall time, like first_raised_at. */
    long countByFirstRaisedAtAfter(LocalDateTime cutoff);
}
```

Create `backend/src/main/java/com/investingapp/backend/repository/AdminActionRepository.java`:

```java
package com.investingapp.backend.repository;

import com.investingapp.backend.model.AdminAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** admin_actions: the audit trail of the /api/admin/** surface (spec 12.2). Rows are never deleted. */
@Repository
public interface AdminActionRepository extends JpaRepository<AdminAction, Long> {

    /** The most recent actions on one user, newest first, for the admin status dump. */
    List<AdminAction> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);
}
```

Neither repository gets a `deleteByUserId`: `UserDeletionService` deliberately leaves both tables alone (ids and state words only, and the audit must outlive the account). Spring Data parses the three derived query names when the context starts, so a misspelt property fails `BackendApplicationTests` in Step 11 with `No property '<name>' found for type 'OpsAlert'` (or `'AdminAction'`).

```bash
(cd backend && ./gradlew compileJava compileTestJava --console=plain)
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11: Validate gate on a scratch MySQL schema (BackendApplicationTests, 0 skipped)**

`BackendApplicationTests` is `@RequiresCiDatabase`: with no reachable database it is **skipped, not failed**, and Gradle still says `BUILD SUCCESSFUL`. Read the XML. Run the whole block below as ONE shell invocation from the repository root (variables and the `db` function do not survive between separate invocations). It reads the local MySQL user and password from the gitignored `application-local.properties` into shell variables without printing them, creates a throwaway schema (never `investing_dev`), exports the ci-equivalent Plaid values so a shell that already exports real ones cannot leak them into the ci boot, runs the gate, inspects the schema, proves the file re-runs cleanly and that the unique key dedupes, and drops the schema whatever happened.

```bash
MYSQL_USER=$(sed -n 's/^spring\.datasource\.username=//p' backend/src/main/resources/application-local.properties)
MYSQL_PW=$(sed -n 's/^spring\.datasource\.password=//p' backend/src/main/resources/application-local.properties)
db() { MYSQL_PWD="$MYSQL_PW" mysql -h 127.0.0.1 -u "$MYSQL_USER" -N -B "$@"; }
db -e "DROP DATABASE IF EXISTS fred_t01_scratch; CREATE DATABASE fred_t01_scratch"
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t01_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME="$MYSQL_USER"
export SPRING_DATASOURCE_PASSWORD="$MYSQL_PW"
export PLAID_WEBHOOK_URL='https://ci.invalid/api/plaid/webhook'
export PLAID_REDIRECT_URI='https://ci.invalid/plaid/oauth'
export PLAID_LINK_CUSTOMIZATION=default
(cd backend && ./gradlew cleanTest test --tests 'com.investingapp.backend.BackendApplicationTests' --console=plain)
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml
db fred_t01_scratch -e "SELECT version, success FROM flyway_schema_history WHERE version = '10'"
db fred_t01_scratch -e "SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = 'fred_t01_scratch' AND TABLE_NAME IN ('ops_alerts', 'admin_actions') GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE"
db fred_t01_scratch < backend/src/main/resources/db/migration/V10__ops_alerts_and_admin_actions.sql && echo "V10 re-run: ok"
db fred_t01_scratch -e "INSERT INTO ops_alerts (alert_type, subject_id, alert_day, message, first_raised_at, last_raised_at) VALUES ('t01.check', 'global', '2026-11-04', 't01', NOW(6), NOW(6)); SELECT occurrences, delivery_status FROM ops_alerts WHERE alert_type = 't01.check'"
db fred_t01_scratch -e "INSERT INTO ops_alerts (alert_type, subject_id, alert_day, message, first_raised_at, last_raised_at) VALUES ('t01.check', 'global', '2026-11-04', 't01', NOW(6), NOW(6))"
db -e "DROP DATABASE fred_t01_scratch"
unset SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD PLAID_WEBHOOK_URL PLAID_REDIRECT_URI PLAID_LINK_CUSTOMIZATION MYSQL_PW
```

Expected, in order:

1. Gradle ends `BUILD SUCCESSFUL`.
2. The XML line is exactly `tests="1" skipped="0" failures="0" errors="0"`. `skipped="1"` means the database was not reached (the XML's `<skipped message=...>` gives the cause); that is not a pass, fix the connection and re-run. A `Schema-validation: wrong column type encountered in column [x] in table [y]` or `missing table [ops_alerts]` in the XML names the annotation or file to fix. A failure inside an `R__v_*.sql` view is outside this task: report it and do not touch those files.
3. `10	1`.
4. Six rows, in any order: `admin_actions	PRIMARY	0	id`, `admin_actions	ix_admin_actions_created	1	created_at`, `admin_actions	ix_admin_actions_user_created	1	user_id,created_at`, `ops_alerts	PRIMARY	0	id`, `ops_alerts	ix_ops_alerts_day	1	alert_day`, `ops_alerts	uq_ops_alerts_type_subject_day	0	alert_type,subject_id,alert_day`.
5. `V10 re-run: ok` (each `CREATE TABLE IF NOT EXISTS` is a no-op note, not an error).
6. `1	PENDING` (the database defaults, for hand-written rows).
7. `ERROR 1062 (23000) at line 1: Duplicate entry 't01.check-global-2026-11-04' for key 'ops_alerts.uq_ops_alerts_type_subject_day'`. This is the property T04's dedupe relies on.
8. The drop prints nothing.

- [ ] **Step 12: Whole backend suite on a fresh scratch schema (never trust a run with skips)**

Same shape as Step 11, as ONE shell invocation from the repository root. The run takes several minutes; it is one command, not a step to split.

```bash
MYSQL_USER=$(sed -n 's/^spring\.datasource\.username=//p' backend/src/main/resources/application-local.properties)
MYSQL_PW=$(sed -n 's/^spring\.datasource\.password=//p' backend/src/main/resources/application-local.properties)
db() { MYSQL_PWD="$MYSQL_PW" mysql -h 127.0.0.1 -u "$MYSQL_USER" -N -B "$@"; }
db -e "DROP DATABASE IF EXISTS fred_t01_suite; CREATE DATABASE fred_t01_suite"
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t01_suite?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME="$MYSQL_USER"
export SPRING_DATASOURCE_PASSWORD="$MYSQL_PW"
export PLAID_WEBHOOK_URL='https://ci.invalid/api/plaid/webhook'
export PLAID_REDIRECT_URI='https://ci.invalid/plaid/oauth'
export PLAID_LINK_CUSTOMIZATION=default
(cd backend && ./gradlew cleanTest test --console=plain)
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/*.xml \
  | awk -F'"' '{t+=$2; s+=$4; f+=$6; e+=$8} END {print "tests=" t, "skipped=" s, "failures=" f, "errors=" e}'
db -e "DROP DATABASE fred_t01_suite"
unset SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD PLAID_WEBHOOK_URL PLAID_REDIRECT_URI PLAID_LINK_CUSTOMIZATION MYSQL_PW
```

Expected: `BUILD SUCCESSFUL` and `tests=764 skipped=0 failures=0 errors=0` when T01 is the first backend task to land (the handoff records 756 at 597bb4f, `git diff --stat 597bb4f HEAD -- backend` is empty, and this task adds 8). If T02 landed first, the total is 764 plus T02's added tests; if Goals v3 merged first (Step 1 found the Goals v3 version 9 migration (`goals.sql`)), it also includes that branch's backend tests. Whatever the total, `skipped=0 failures=0 errors=0` is the bar. A non-zero `skipped` means the scratch schema was not reached.

- [ ] **Step 13: Scope and house-style checks**

From the repository root:

```bash
grep -n $'\xe2\x80\x94' backend/src/main/resources/db/migration/V10__ops_alerts_and_admin_actions.sql backend/src/main/java/com/investingapp/backend/model/OpsAlert.java backend/src/main/java/com/investingapp/backend/model/AdminAction.java backend/src/main/java/com/investingapp/backend/repository/OpsAlertRepository.java backend/src/main/java/com/investingapp/backend/repository/AdminActionRepository.java backend/src/test/java/com/investingapp/backend/migration/V10OpsAlertsAndAdminActionsMigrationTest.java backend/src/test/java/com/investingapp/backend/model/PhaseOneEntityDefaultsTest.java
git diff --stat HEAD
git status --short
```

Expected: the em dash grep prints nothing. `git diff --stat HEAD` lists no file of this task (it modifies no tracked file; the T02 seam case above is the one exception, and another session's tracked WIP may appear, which you leave alone). `git status --short` shows exactly the seven new files as `??` (plus anything another session left, which you do not stage). In particular `backend/src/main/resources/application-local.properties` must not appear, and nothing under `.../model/InvestmentSchedule.java`, `.../service/` or `backend/src/main/resources/*.properties` may appear from this task.

- [ ] **Step 14: Commit exactly these files**

The repository may be a shared dirty tree: stage by full path only, never `git add -A`, and never `git checkout`, `git restore`, `git stash` or `git reset`.

```bash
git add backend/src/main/resources/db/migration/V10__ops_alerts_and_admin_actions.sql \
  backend/src/main/java/com/investingapp/backend/model/OpsAlert.java \
  backend/src/main/java/com/investingapp/backend/model/AdminAction.java \
  backend/src/main/java/com/investingapp/backend/repository/OpsAlertRepository.java \
  backend/src/main/java/com/investingapp/backend/repository/AdminActionRepository.java \
  backend/src/test/java/com/investingapp/backend/migration/V10OpsAlertsAndAdminActionsMigrationTest.java \
  backend/src/test/java/com/investingapp/backend/model/PhaseOneEntityDefaultsTest.java
git status --short
git commit -m "feat(ops): add V10 ops_alerts and admin_actions tables with their entities"
```

Expected: before the commit, `git status --short` shows the seven files as `A ` and nothing else staged. If T02 created `PhaseOneEntityDefaultsTest.java` first, it shows as `M ` instead; hunk-verify it with `git diff --cached -- backend/src/test/java/com/investingapp/backend/model/PhaseOneEntityDefaultsTest.java` (only this task's three methods and imports added, none of T02's removed) before committing.

### Verification

| what | command | expected |
| --- | --- | --- |
| R15 directory check | `ls backend/src/main/resources/db/migration/ \| grep -E '^V(9\|[1-9][0-9])__'` (before Step 2) | nothing, or only the Goals v3 version 9 migration (`goals.sql`); anything else stops the task |
| migration shape | `(cd backend && ./gradlew test --tests 'com.investingapp.backend.migration.V10OpsAlertsAndAdminActionsMigrationTest' --console=plain)`, then the XML grep of Step 5 | `tests="5" skipped="0" failures="0" errors="0"` |
| entity defaults and columns | `(cd backend && ./gradlew test --tests 'com.investingapp.backend.model.PhaseOneEntityDefaultsTest' --console=plain)`, then the XML grep of Step 9 | `tests="3" skipped="0" failures="0" errors="0"` (more if T02 added methods) |
| validate gate | the Step 11 block (scratch schema `fred_t01_scratch`, created and dropped) | `tests="1" skipped="0" failures="0" errors="0"`; flyway row `10 1`; six index rows; re-run ok; `1 PENDING`; `ERROR 1062` on the duplicate |
| whole backend suite | the Step 12 block (scratch schema `fred_t01_suite`, created and dropped) | `tests=764 skipped=0 failures=0 errors=0` if T01 lands first; `skipped=0 failures=0 errors=0` always |
| house style and scope | the Step 13 commands | no em dash; no tracked file modified; seven untracked files |

No frontend file changes, so no Karma run belongs to this task.

### Produces

- `backend/src/main/resources/db/migration/V10__ops_alerts_and_admin_actions.sql`: tables `ops_alerts` and `admin_actions`; unique key `uq_ops_alerts_type_subject_day (alert_type, subject_id, alert_day)`; keys `ix_ops_alerts_day`, `ix_admin_actions_user_created`, `ix_admin_actions_created`; no foreign keys, no ALTER, no view.
- `com.investingapp.backend.model.OpsAlert`: `Long id`, `String alertType`, `String subjectId`, `LocalDate alertDay`, `String message`, `String contextJson`, `int occurrences = 1`, `LocalDateTime firstRaisedAt`, `LocalDateTime lastRaisedAt`, `@Column(name = "delivery_status", length = 12, nullable = false) String deliveryStatus = "PENDING"`, with Lombok getters and setters.
- `com.investingapp.backend.model.AdminAction`: `Long id`, `String actor`, `Long userId` (no foreign key), `String verb`, `String targetId`, `String beforeJson`, `String afterJson`, `String reason`, `LocalDateTime createdAt`, with Lombok getters and setters.
- `com.investingapp.backend.repository.OpsAlertRepository`: `Optional<OpsAlert> findByAlertTypeAndSubjectIdAndAlertDay(String, String, LocalDate)`, `long countByFirstRaisedAtAfter(LocalDateTime)`.
- `com.investingapp.backend.repository.AdminActionRepository`: `List<AdminAction> findTop50ByUserIdOrderByCreatedAtDesc(Long)`.
- Tests: `V10OpsAlertsAndAdminActionsMigrationTest` (`createsExactlyTwoTablesIfNotExists`, `altersDropsAndReferencesNothing`, `everyColumnAndKeyMatchesTheDesign`, `hasNoEmDash`, `versionedMigrationNumbersAreUnique`); `PhaseOneEntityDefaultsTest` (`opsAlertDefaultsMatchV10`, `adminActionStartsEmpty`, `columnsMirrorV10WidthsAndNullability`).
