### Task 02: paycheck-entities-and-mappings

Maps the three paycheck tables (`paycheck_streams`, `paycheck_events`, `paycheck_cycles`) and the Phase 1 columns of `users` and `investment_schedules` exactly as V6 built them, adds the ten enums those columns store, the three repositories every later task reads and writes through, and `util/ColumnText`, the one helper that cuts a Plaid string to its column width. Nothing behavioural: no service, no job, no controller, no property, no migration. The gate is a boot under `ddl-auto=validate` against a scratch MySQL, backed by two database-free tests that check what validate cannot (lengths, nullability, precision, enum widths, every column mapped, every derived repository name resolvable).

`.../` = `backend/src/main/java/com/investingapp/backend/`; tests go under `backend/src/test/java/com/investingapp/backend/`. Line numbers are HEAD 137998c. T02 is a parallel start (depends on nothing), but T03, T07, T09, T11, T12 and T13 later edit `User.java` or `InvestmentSchedule.java` or consume these classes, so every edit below also quotes the verbatim text it anchors on.

**Facts this task relies on (restated from the handoff and rulings, so this file stands alone):**

- V6 (`backend/src/main/resources/db/migration/V6__paycheck_autopilot.sql`) already created every table and column used here; Phase 0 mapped only a few. **This task writes no migration.** The only Phase 1 migration is `V10__ops_alerts_and_admin_actions.sql`, and it belongs to T01 (R15).
- `ddl-auto=validate` runs in every profile. It checks that each mapped column exists with a compatible type, and nothing else: not lengths, not nullability, not DEFAULTs, not unmapped columns. A Java `Short` maps to SMALLINT and fails against V6 `tinyint`; an `int` fails against `smallint`; a bare `Boolean` against `tinyint(1)` needs `columnDefinition = "BOOLEAN"` (the `plaidTransactionsConsented` precedent, `User.java:183`). V6 DEFAULTs never apply on insert, because Hibernate binds every mapped column, so each NOT NULL DEFAULT is mirrored by a field initialiser.
- `investment_schedules.is_paused` no longer exists (V7). The user's stop is `InvestmentSchedule.Status.PAUSED_BY_USER`; `hold_reason` is the system's stop, orthogonal to the status. Nothing here reads `getIsPaused()`, writes `status`, writes `mode`, writes `holdReason`, or touches `isReadyForInvestment(LocalDate todayET)` (whose body T03 pins by text).
- Ruling R1: detection outcomes are mirrored onto the MANUAL schedule row in every mode once detection has run (`detection_state`, `primary_stream_id`, `next_expected_date`, `last_cadence_check_at`, plus `missed_count`, `dormant_since`, `detection_started_at` for dormancy and the SEARCHING_24H alert). `mode` stays MANUAL under shadow. Nothing reads these on the wire until Phase 2. Decision 20: the anchors (`first_anchor`, `second_anchor`), `authorization_id`, `authorized_amount`, `amount_source`, `snoozed_until` and `paused_by_user_at` stay unmapped.
- Enum names carry a `Paycheck` prefix because `PlaidService.java:18` imports `com.plaid.client.model.*`; a FRED class sharing a Plaid model name would become ambiguous there. Checked against `plaid-java-46.0.0.jar`: no Plaid model class is named `DetectionState` or `Paycheck*`.
- Spring Data REST is on the classpath but its auto-configuration is excluded (`BackendApplication.java:10`, `exclude = RepositoryRestMvcAutoConfiguration.class`), so the new repositories are not exported as HTTP endpoints.
- `InvestmentSchedule` carries `@AllArgsConstructor`; at HEAD nothing calls it (`grep -rn "new InvestmentSchedule(" backend/src | grep -v "new InvestmentSchedule()"` prints nothing), so the arity change from seven new fields breaks no caller. Re-run that grep before Step 7; if it prints a line, stop and report.
- Payer names, descriptors and amounts are payroll data (handoff 8.2). They are stored on these rows and never go into an analytics property, an ops alert, or a log line at INFO or above. This task writes no log line at all.

**Files:**
- Create: `.../util/ColumnText.java`; `.../model/PaycheckStream.java`, `.../model/PaycheckEvent.java`, `.../model/PaycheckCycle.java`; `.../model/PaycheckStreamState.java`, `PaycheckStreamRole.java`, `PaycheckCadenceSource.java`, `PaycheckEventStatus.java`, `PaycheckCycleState.java`, `PaycheckSkipReason.java`, `PaycheckVoidedReason.java`, `PaycheckRejectReason.java`, `PaycheckRetiredReason.java`, `DetectionState.java` (all in `.../model/`); `.../repository/PaycheckStreamRepository.java`, `PaycheckEventRepository.java`, `PaycheckCycleRepository.java`
- Modify: `.../model/User.java:30` (`@ToString` exclusion), `:178` (comment), insert after `:200` (seven fields); `.../model/InvestmentSchedule.java` insert after `:85` (seven fields); `.../model/HoldReason.java:3-6` (javadoc); `.../model/InvestmentFrequency.java:17-18` (javadoc)
- Test: create `util/ColumnTextTest.java`, `model/PaycheckEntityDefaultsTest.java`, `model/PaycheckEntityMappingTest.java`. Gate: the existing `BackendApplicationTests` (`@RequiresCiDatabase`) on a scratch MySQL. T01 creates `model/PhaseOneEntityDefaultsTest.java` in parallel; this task does not touch that file.

**Interfaces:**
- Consumes (Phase 0, all at HEAD): `EasternTime.now()` (`util/EasternTime.java:28`); `InvestmentFrequency` (`model/InvestmentFrequency.java:20-24`, values WEEKLY, BIWEEKLY, SEMI_MONTHLY, MONTHLY); `HoldReason`, `ScheduleMode`, `PlaidItemStatus`, `InvestmentSchedule.Status`; the Phase 0 entity shape of `model/PlaidWebhookEvent.java` (`@Getter @Setter @NoArgsConstructor`, explicit `@Column(name = ...)`, bare `Long userId`). Nothing from another Phase 1 task.
- Produces: listed in full in the "Produces" recap at the end. In short: the three entities with every V6 column; ten enums; three repositories each with `long deleteByUserId(Long)`; seven `User` fields and seven `InvestmentSchedule` fields; `ColumnText.fit(String, int)`.

- [ ] **Step 1: Pre-check (1 minute)**

```
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1
ls backend/src/main/java/com/investingapp/backend/model/ | grep -E '^(Paycheck|DetectionState)'
ls backend/src/main/java/com/investingapp/backend/repository/ | grep '^Paycheck'
ls backend/src/main/java/com/investingapp/backend/util/ColumnText.java 2>&1
grep -rn "new InvestmentSchedule(" backend/src | grep -v "new InvestmentSchedule()"
```

Expected: the first, second and fourth commands print nothing; the third prints `No such file or directory` for `ColumnText.java`. (No glob is used, because zsh aborts an unmatched glob with `no matches found` instead of running `ls`.) Any other output means another session has started this work: stop and report.

- [ ] **Step 2: Failing test for ColumnText**

Create `backend/src/test/java/com/investingapp/backend/util/ColumnTextTest.java`:

```java
package com.investingapp.backend.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MySQL strict mode rejects an over-long value instead of truncating it, and the whole transaction
 * rolls back; for a sync that means the same page is refused on every retry. T07 and T10 pass every
 * Plaid string through ColumnText.fit at its V6 width, so the cut has to be exact and safe.
 */
class ColumnTextTest {

    @Test
    void nullStaysNull() {
        assertThat(ColumnText.fit(null, 10)).isNull();
    }

    @Test
    void valueWithinWidthIsReturnedUnchanged() {
        assertThat(ColumnText.fit("ACME PAYROLL", 12)).isEqualTo("ACME PAYROLL");
        assertThat(ColumnText.fit("ACME", 255)).isEqualTo("ACME");
        assertThat(ColumnText.fit("", 5)).isEmpty();
    }

    @Test
    void longValueIsCutToWidth() {
        assertThat(ColumnText.fit("X".repeat(300), 255)).hasSize(255);
        assertThat(ColumnText.fit("ACME PAYROLL", 4)).isEqualTo("ACME");
    }

    @Test
    void cutNeverSplitsASurrogatePair() {
        // U+1F4B0 is two UTF-16 units at index 2 and 3. A cut at 3 would keep half of it.
        // Written as escapes so the test does not depend on the compiler's source encoding.
        String value = "ab💰cd";
        assertThat(ColumnText.fit(value, 3)).isEqualTo("ab");
        assertThat(ColumnText.fit(value, 4)).isEqualTo("ab💰");
    }

    @Test
    void widthBelowOneIsRejected() {
        assertThatThrownBy(() -> ColumnText.fit("a", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.util.ColumnTextTest' --console=plain`. Expected: `> Task :compileTestJava FAILED` with `error: cannot find symbol` and `symbol:   variable ColumnText`, then `BUILD FAILED`.

- [ ] **Step 3: Create ColumnText**

Create `backend/src/main/java/com/investingapp/backend/util/ColumnText.java`:

```java
package com.investingapp.backend.util;

/**
 * Fits a string from an outside source (Plaid) to the width of the VARCHAR column it is stored in.
 *
 * <p>MySQL strict mode rejects an over-long value rather than truncating it, and the transaction
 * rolls back. For a sync that sticks the Item on the same page forever, so every Plaid string is cut
 * to its V6 column width before it is set on an entity. A cut never splits a surrogate pair: a
 * character at the boundary is dropped whole instead of being stored as half a character.
 */
public final class ColumnText {

    private ColumnText() {
    }

    /** {@code value} unchanged when it fits (null included), else its first {@code width} chars. */
    public static String fit(String value, int width) {
        if (width < 1) {
            throw new IllegalArgumentException("width must be at least 1, was " + width);
        }
        if (value == null || value.length() <= width) {
            return value;
        }
        int end = Character.isHighSurrogate(value.charAt(width - 1)) ? width - 1 : width;
        return value.substring(0, end);
    }
}
```

Run the Step 2 command, then `grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.util.ColumnTextTest.xml` (from `backend/`). Expected: `BUILD SUCCESSFUL`, and `tests="5" skipped="0" failures="0" errors="0"`.

- [ ] **Step 4: Failing defaults test for the enums, counters and initial states**

Create `backend/src/test/java/com/investingapp/backend/model/PaycheckEntityDefaultsTest.java`:

```java
package com.investingapp.backend.model;

import com.investingapp.backend.util.EasternTime;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 mirrors of the V6 DEFAULTs and enum widths. Hibernate validate checks column types only,
 * never a DEFAULT and never a length, so an initialiser that drifts from V6, or an enum constant
 * longer than its VARCHAR, would only surface as a failed insert at runtime.
 */
class PaycheckEntityDefaultsTest {

    @Test
    void enumNamesFitTheirColumns() {
        assertThat(longest(PaycheckStreamState.values())).isLessThanOrEqualTo(12);
        assertThat(longest(PaycheckStreamRole.values())).isLessThanOrEqualTo(12);
        assertThat(longest(InvestmentFrequency.values())).isLessThanOrEqualTo(12);
        assertThat(longest(PaycheckCadenceSource.values())).isLessThanOrEqualTo(10);
        assertThat(longest(PaycheckEventStatus.values())).isLessThanOrEqualTo(10);
        assertThat(longest(PaycheckCycleState.values())).isLessThanOrEqualTo(10);
        assertThat(longest(PaycheckSkipReason.values())).isLessThanOrEqualTo(32);
        assertThat(longest(PaycheckVoidedReason.values())).isLessThanOrEqualTo(32);
        assertThat(longest(PaycheckRejectReason.values())).isLessThanOrEqualTo(32);
        assertThat(longest(PaycheckRetiredReason.values())).isLessThanOrEqualTo(32);
        assertThat(longest(DetectionState.values())).isLessThanOrEqualTo(16);
    }

    @Test
    void enumValuesMatchTheSpec() {
        assertThat(names(PaycheckStreamState.values()))
                .containsExactly("CANDIDATE", "PROVISIONAL", "CONFIRMED", "DORMANT", "RETIRED", "REJECTED");
        assertThat(names(PaycheckStreamRole.values())).containsExactly("PRIMARY", "SECONDARY", "INTERRUPTION", "NONE");
        assertThat(names(PaycheckCadenceSource.values())).containsExactly("OBSERVED", "PLAID");
        assertThat(names(PaycheckEventStatus.values())).containsExactly("ACTIVE", "REMOVED", "MODIFIED", "SUPERSEDED");
        assertThat(names(PaycheckCycleState.values()))
                .containsExactly("OPEN", "CONFIRMED", "QUEUED", "SKIPPED", "VOIDED", "MISSED");
        assertThat(names(PaycheckSkipReason.values())).containsExactly("SHADOW_CONFIRMED", "AFFORDABILITY",
                "CATCH_UP_CAP", "PAUSED", "HOLD", "NO_AUTHORIZATION", "LATE_SEEN", "ACH_NOT_READY", "KILL_SWITCH",
                "USER_SKIP_THIRD", "STALE");
        assertThat(names(PaycheckVoidedReason.values()))
                .containsExactly("DEPOSIT_REMOVED", "DEPOSIT_PENDING", "AMOUNT_CHANGED", "ADMIN");
        assertThat(names(PaycheckRejectReason.values())).containsExactly("NOT_INFLOW", "NOT_DEPOSITORY",
                "PLAID_TOMBSTONED", "CADENCE_UNSUPPORTED", "PFC_EXCLUDED", "DESCRIPTOR_EXCLUDED", "LOW_MEDIAN", "STALE",
                "BENEFITS_SIGNAL");
        assertThat(names(PaycheckRetiredReason.values())).containsExactly("SUCCEEDED", "DORMANT_120", "ADMIN");
        assertThat(names(DetectionState.values())).containsExactly("SEARCHING", "NEEDS_CONSENT", "UNDETECTABLE",
                "PROVISIONAL", "CONFIRMED", "DORMANT", "ITEM_BROKEN", "REVOKED");
    }

    @Test
    void newCountersDefaultToZero() {
        PaycheckStream stream = new PaycheckStream();
        assertThat(stream.getScore()).isEqualTo((short) 0);
        assertThat(stream.getDepositsFound()).isEqualTo((short) 0);
        assertThat(stream.getMissedCount()).isEqualTo((short) 0);
        assertThat(new InvestmentSchedule().getMissedCount()).isEqualTo((short) 0);
    }

    @Test
    void userAutopilotOptOutDefaultsFalse() {
        for (User user : new User[] {new User("phase1@fredvested.com"), new User()}) {
            assertThat(user.getPaycheckAutopilotOptOut()).isFalse();
            assertThat(user.getPlaidHistoryComplete()).isFalse();
            assertThat(user.getPlaidSyncCursor()).isNull();
            assertThat(user.getPlaidTransactionsUpdateStatus()).isNull();
            assertThat(user.getPlaidDisconnectAt()).isNull();
            assertThat(user.getPlaidLastSyncAt()).isNull();
            assertThat(user.getPlaidStreamsReadAt()).isNull();
        }
    }

    @Test
    void scheduleMirrorStartsEmpty() {
        InvestmentSchedule schedule = new InvestmentSchedule();
        assertThat(schedule.getDetectionState()).isNull();
        assertThat(schedule.getPrimaryStreamId()).isNull();
        assertThat(schedule.getNextExpectedDate()).isNull();
        assertThat(schedule.getDormantSince()).isNull();
        assertThat(schedule.getLastCadenceCheckAt()).isNull();
        assertThat(schedule.getDetectionStartedAt()).isNull();
        assertThat(schedule.getMode()).isEqualTo(ScheduleMode.MANUAL);
        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.PENDING_BANK);
        assertThat(schedule.getHoldReason()).isNull();
    }

    @Test
    void newRowsStartInTheirFirstState() {
        PaycheckStream stream = new PaycheckStream();
        assertThat(stream.getState()).isEqualTo(PaycheckStreamState.CANDIDATE);
        assertThat(stream.getRole()).isEqualTo(PaycheckStreamRole.NONE);
        PaycheckEvent event = new PaycheckEvent();
        assertThat(event.getStatus()).isEqualTo(PaycheckEventStatus.ACTIVE);
        assertThat(event.isPending()).isFalse();
        assertThat(new PaycheckCycle().getState()).isEqualTo(PaycheckCycleState.OPEN);
    }

    @Test
    void timestampsFallBackToEasternNow() {
        LocalDateTime before = EasternTime.now();
        PaycheckStream stream = new PaycheckStream();
        stream.onCreate();
        assertThat(stream.getCreatedAt()).isBetween(before, EasternTime.now());
        assertThat(stream.getUpdatedAt()).isNull();

        LocalDateTime nightly = LocalDateTime.of(2026, 11, 2, 22, 30);
        PaycheckCycle cycle = new PaycheckCycle();
        cycle.setCreatedAt(nightly);
        cycle.onCreate();
        assertThat(cycle.getCreatedAt()).isEqualTo(nightly);
        cycle.onUpdate();
        assertThat(cycle.getUpdatedAt()).isBetween(before, EasternTime.now());
    }

    private static int longest(Enum<?>[] values) {
        return Arrays.stream(values).mapToInt(v -> v.name().length()).max().orElse(0);
    }

    private static String[] names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
}
```

- [ ] **Step 5: Failing mapping test against the V6 text**

Create `backend/src/test/java/com/investingapp/backend/model/PaycheckEntityMappingTest.java`:

```java
package com.investingapp.backend.model;

import com.investingapp.backend.repository.PaycheckCycleRepository;
import com.investingapp.backend.repository.PaycheckEventRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.parser.PartTree;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.fail;

/**
 * Pins every Phase 1 mapping against the V6 text, without a database. ddl-auto=validate (the
 * BackendApplicationTests gate) proves a mapped column exists with a compatible type; it never
 * checks length, nullability, precision, or that a column is mapped at all. This test checks those,
 * and parses every derived repository method against its entity the way Spring Data does at boot.
 */
class PaycheckEntityMappingTest {

    private static final String FILE = "db/migration/V6__paycheck_autopilot.sql";
    private static final Pattern CREATE = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS `(\\w+)` \\((.*?)\\n\\) ENGINE");
    private static final Pattern CREATE_COLUMN = Pattern.compile("(?m)^\\s+`(\\w+)` (.+?),?$");
    private static final Pattern ALTER_COLUMN = Pattern.compile("(?m)'ALTER TABLE (\\w+) ADD COLUMN (\\w+) (.*)',$");
    private static final Pattern SIZED = Pattern.compile("(\\w+)\\((\\d+)(?:,(\\d+))?\\)");

    private static final Map<Class<?>, Class<?>> REPOSITORIES = Map.of(
            PaycheckStreamRepository.class, PaycheckStream.class,
            PaycheckEventRepository.class, PaycheckEvent.class,
            PaycheckCycleRepository.class, PaycheckCycle.class);

    private static final Map<String, Map<String, String>> created = new HashMap<>();
    private static final Map<String, Map<String, String>> added = new HashMap<>();

    @BeforeAll
    static void load() throws Exception {
        String sql;
        try (InputStream in = PaycheckEntityMappingTest.class.getClassLoader().getResourceAsStream(FILE)) {
            assertThat(in).as(FILE + " must exist on the classpath").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r", "");
        }
        Matcher table = CREATE.matcher(sql);
        while (table.find()) {
            Map<String, String> columns = new LinkedHashMap<>();
            Matcher column = CREATE_COLUMN.matcher(table.group(2));
            while (column.find()) {
                columns.put(column.group(1), column.group(2).toUpperCase(Locale.ROOT));
            }
            created.put(table.group(1), columns);
        }
        Matcher alter = ALTER_COLUMN.matcher(sql);
        while (alter.find()) {
            added.computeIfAbsent(alter.group(1), t -> new LinkedHashMap<>())
                    .put(alter.group(2), alter.group(3).toUpperCase(Locale.ROOT));
        }
    }

    @Test
    void paycheckStreamsMappedColumnForColumn() {
        assertTableMapped("paycheck_streams", PaycheckStream.class);
    }

    @Test
    void paycheckEventsMappedColumnForColumn() {
        assertTableMapped("paycheck_events", PaycheckEvent.class);
    }

    @Test
    void paycheckCyclesMappedColumnForColumn() {
        assertTableMapped("paycheck_cycles", PaycheckCycle.class);
    }

    @Test
    void everyV6UserColumnIsMappedAndMatches() {
        Map<String, Field> mapped = mappedColumns(User.class);
        Map<String, String> v6 = added.get("users");
        assertThat(v6).as("users additions in V6").hasSize(14);
        v6.forEach((column, definition) -> {
            assertThat(mapped).as("users." + column + " is mapped").containsKey(column);
            assertMatches("users." + column, mapped.get(column), definition);
        });
    }

    @Test
    void scheduleMapsTheDetectionMirrorAndLeavesTheRestUnmapped() {
        Map<String, Field> mapped = mappedColumns(InvestmentSchedule.class);
        Map<String, String> v6 = added.get("investment_schedules");
        Set<String> expected = Set.of("mode", "hold_reason", "detection_state", "primary_stream_id",
                "next_expected_date", "missed_count", "dormant_since", "last_cadence_check_at", "detection_started_at");
        assertThat(v6.keySet().stream().filter(mapped::containsKey).toList())
                .as("V6 investment_schedules columns mapped (decision 20: anchors, authorization, snooze stay unmapped)")
                .containsExactlyInAnyOrderElementsOf(expected);
        expected.forEach(column -> assertMatches("investment_schedules." + column, mapped.get(column), v6.get(column)));
    }

    @Test
    void repositoriesCarryThePhaseOneFindersAndEveryDerivedNameResolves() {
        assertThat(methodNames(PaycheckStreamRepository.class)).contains("findByUserIdOrderByIdAsc",
                "findByUserIdAndState", "findByUserIdAndPlaidAccountIdAndPayerToken", "deleteByUserId");
        assertThat(methodNames(PaycheckEventRepository.class)).contains("findByPlaidTransactionId",
                "findByPendingTransactionId", "findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc",
                "findByStreamIdAndStatusOrderByPostedDateDesc", "deleteByUserId", "deleteByUserIdAndPlaidAccountId",
                "deleteByPostedDateBefore");
        assertThat(methodNames(PaycheckCycleRepository.class)).contains("findByStreamIdAndPeriodKey",
                "findByUserIdAndState", "findByStateAndWindowEndBefore", "findByStateAndConfirmedAtBefore",
                "findByUserIdAndSkipReasonAndConfirmedAtAfter", "deleteByUserId");
        REPOSITORIES.forEach((repository, entity) -> derivedMethods(repository).forEach(method ->
                assertThatCode(() -> new PartTree(method.getName(), entity))
                        .as(repository.getSimpleName() + "." + method.getName() + " resolves against " + entity.getSimpleName())
                        .doesNotThrowAnyException()));
    }

    @Test
    void everyDerivedDeleteRunsInAReadWriteTransaction() {
        REPOSITORIES.keySet().forEach(repository -> derivedMethods(repository).stream()
                .filter(m -> m.getName().startsWith("delete"))
                .forEach(m -> {
                    Transactional tx = m.getAnnotation(Transactional.class);
                    assertThat(tx).as(repository.getSimpleName() + "." + m.getName() + " is @Transactional").isNotNull();
                    assertThat(tx.readOnly()).as(repository.getSimpleName() + "." + m.getName() + " is read-write").isFalse();
                }));
    }

    private static void assertTableMapped(String table, Class<?> entity) {
        Map<String, String> columns = created.get(table);
        assertThat(columns).as(table + " in V6").isNotEmpty();
        Map<String, Field> mapped = mappedColumns(entity);
        long fields = Arrays.stream(entity.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()).count();
        assertThat(mapped).as(entity.getSimpleName() + " names a column on every field").hasSize((int) fields);
        assertThat(mapped.keySet()).as(entity.getSimpleName() + " maps every V6 column and nothing else")
                .containsExactlyInAnyOrderElementsOf(columns.keySet());
        columns.forEach((column, definition) -> assertMatches(table + "." + column, mapped.get(column), definition));
    }

    private static Map<String, Field> mappedColumns(Class<?> entity) {
        Map<String, Field> mapped = new LinkedHashMap<>();
        for (Field f : entity.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic() || f.isAnnotationPresent(Transient.class)) {
                continue;
            }
            String name = f.isAnnotationPresent(Id.class) ? "id"
                    : Optional.ofNullable(f.getAnnotation(Column.class)).map(Column::name).orElse("");
            if (!name.isEmpty()) {
                mapped.put(name, f);
            }
        }
        return mapped;
    }

    private static void assertMatches(String where, Field field, String definition) {
        String type = definition.split(" ")[0];
        Matcher sized = SIZED.matcher(type);
        boolean hasSize = sized.matches();
        String base = hasSize ? sized.group(1) : type;
        Class<?> javaType = field.getType();
        Column column = field.getAnnotation(Column.class);
        if (!field.isAnnotationPresent(Id.class)) {
            assertThat(column.nullable()).as(where + " nullable").isEqualTo(!definition.contains("NOT NULL"));
        }
        switch (base) {
            case "VARCHAR" -> {
                assertThat(javaType == String.class || javaType.isEnum()).as(where + " is a String or an enum").isTrue();
                assertThat(column.length()).as(where + " length").isEqualTo(Integer.parseInt(sized.group(2)));
                if (javaType.isEnum()) {
                    Enumerated enumerated = field.getAnnotation(Enumerated.class);
                    assertThat(enumerated).as(where + " @Enumerated").isNotNull();
                    assertThat(enumerated.value()).as(where + " stored by name").isEqualTo(EnumType.STRING);
                    int longest = Arrays.stream(javaType.getEnumConstants())
                            .mapToInt(c -> ((Enum<?>) c).name().length()).max().orElse(0);
                    assertThat(longest).as(where + " longest enum name").isLessThanOrEqualTo(column.length());
                }
            }
            case "BIGINT" -> assertThat(javaType).as(where).isIn(Long.class, long.class);
            case "SMALLINT" -> assertThat(javaType).as(where).isIn(Short.class, short.class);
            case "TINYINT" -> {
                if (hasSize && "1".equals(sized.group(2))) {
                    assertThat(javaType).as(where).isIn(Boolean.class, boolean.class);
                    assertThat(column.columnDefinition()).as(where + " columnDefinition").startsWith("BOOLEAN");
                } else {
                    assertThat(javaType).as(where).isEqualTo(Short.class);
                    assertThat(column.columnDefinition()).as(where + " columnDefinition").isEqualTo("TINYINT");
                }
            }
            case "TEXT" -> {
                assertThat(javaType).as(where).isEqualTo(String.class);
                assertThat(column.columnDefinition()).as(where + " columnDefinition").isEqualTo("TEXT");
            }
            case "DATE" -> assertThat(javaType).as(where).isEqualTo(LocalDate.class);
            case "DATETIME" -> assertThat(javaType).as(where).isEqualTo(LocalDateTime.class);
            case "DECIMAL" -> {
                assertThat(javaType).as(where).isEqualTo(BigDecimal.class);
                assertThat(column.precision()).as(where + " precision").isEqualTo(Integer.parseInt(sized.group(2)));
                assertThat(column.scale()).as(where + " scale").isEqualTo(Integer.parseInt(sized.group(3)));
            }
            default -> fail(where + ": no mapping rule for V6 type " + type);
        }
    }

    private static List<Method> derivedMethods(Class<?> repository) {
        return Arrays.stream(repository.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && !m.isDefault() && !m.isAnnotationPresent(Query.class))
                .toList();
    }

    private static List<String> methodNames(Class<?> repository) {
        return Arrays.stream(repository.getDeclaredMethods()).map(Method::getName).toList();
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.model.PaycheckEntityDefaultsTest' --console=plain`. Expected: `> Task :compileTestJava FAILED`, several `error: cannot find symbol` lines for the new types (for example `symbol:   class PaycheckStream`, `symbol:   variable PaycheckStreamState`, and `symbol:   class PaycheckStreamRepository` with `location: package com.investingapp.backend.repository`), then `BUILD FAILED`.

- [ ] **Step 6: The ten enums**

Create each file in `backend/src/main/java/com/investingapp/backend/model/`:

`DetectionState.java`:

```java
package com.investingapp.backend.model;

/**
 * investment_schedules.detection_state (spec 3.2, VARCHAR(16)). Null until detection has run for
 * the user. Ruling R1 amends the spec's "null in MANUAL": shadow mode mirrors detection onto the
 * MANUAL row in every mode, and nothing reads it on the wire until Phase 2.
 */
public enum DetectionState {
    SEARCHING, NEEDS_CONSENT, UNDETECTABLE, PROVISIONAL, CONFIRMED, DORMANT, ITEM_BROKEN, REVOKED
}
```

`PaycheckStreamState.java`:

```java
package com.investingapp.backend.model;

/** paycheck_streams.state (spec 3.3, VARCHAR(12)). A new stream starts as CANDIDATE. */
public enum PaycheckStreamState {
    CANDIDATE, PROVISIONAL, CONFIRMED, DORMANT, RETIRED, REJECTED
}
```

`PaycheckStreamRole.java`:

```java
package com.investingapp.backend.model;

/** paycheck_streams.role (spec 3.3, VARCHAR(12); INTERRUPTION is exactly 12). A new stream is NONE. */
public enum PaycheckStreamRole {
    PRIMARY, SECONDARY, INTERRUPTION, NONE
}
```

`PaycheckCadenceSource.java`:

```java
package com.investingapp.backend.model;

/** paycheck_streams.cadence_source (spec 3.3, VARCHAR(10)): derived from deposits, or Plaid's tiebreak. */
public enum PaycheckCadenceSource {
    OBSERVED, PLAID
}
```

`PaycheckEventStatus.java`:

```java
package com.investingapp.backend.model;

/** paycheck_events.status (spec 3.4, VARCHAR(10); SUPERSEDED is exactly 10). A new event is ACTIVE. */
public enum PaycheckEventStatus {
    ACTIVE, REMOVED, MODIFIED, SUPERSEDED
}
```

`PaycheckCycleState.java`:

```java
package com.investingapp.backend.model;

/**
 * paycheck_cycles.state (spec 3.5, VARCHAR(10)). A new cycle is OPEN. Phase 1 has no CONFIRMED
 * writer: under shadow a matched deposit closes the cycle SKIPPED with SHADOW_CONFIRMED (ruling R7).
 */
public enum PaycheckCycleState {
    OPEN, CONFIRMED, QUEUED, SKIPPED, VOIDED, MISSED
}
```

`PaycheckSkipReason.java`:

```java
package com.investingapp.backend.model;

/** paycheck_cycles.skip_reason (spec 3.5, VARCHAR(32)): the eleven reasons, in the spec's order. */
public enum PaycheckSkipReason {
    SHADOW_CONFIRMED, AFFORDABILITY, CATCH_UP_CAP, PAUSED, HOLD, NO_AUTHORIZATION, LATE_SEEN, ACH_NOT_READY,
    KILL_SWITCH, USER_SKIP_THIRD, STALE
}
```

`PaycheckVoidedReason.java`:

```java
package com.investingapp.backend.model;

/** paycheck_cycles.voided_reason (spec 3.5, VARCHAR(32)). ADMIN is the admin void verb's (decision 16). */
public enum PaycheckVoidedReason {
    DEPOSIT_REMOVED, DEPOSIT_PENDING, AMOUNT_CHANGED, ADMIN
}
```

`PaycheckRejectReason.java`:

```java
package com.investingapp.backend.model;

/** paycheck_streams.reject_reason (VARCHAR(32)): the hard gate a stream failed (spec 4.2 to 4.4). */
public enum PaycheckRejectReason {
    NOT_INFLOW, NOT_DEPOSITORY, PLAID_TOMBSTONED, CADENCE_UNSUPPORTED, PFC_EXCLUDED, DESCRIPTOR_EXCLUDED, LOW_MEDIAN,
    STALE, BENEFITS_SIGNAL
}
```

`PaycheckRetiredReason.java`:

```java
package com.investingapp.backend.model;

/** paycheck_streams.retired_reason (VARCHAR(32)): succession, 120 days dormant, or the admin verb. */
public enum PaycheckRetiredReason {
    SUCCEEDED, DORMANT_120, ADMIN
}
```

- [ ] **Step 7: PaycheckStream**

Create `backend/src/main/java/com/investingapp/backend/model/PaycheckStream.java`:

```java
package com.investingapp.backend.model;

import com.investingapp.backend.util.EasternTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * paycheck_streams (spec 3.3, V6): one inflow stream per user, keyed by FRED on
 * (user_id, plaid_account_id, payer_token) because Plaid's stream_id is not stable.
 *
 * <p>Every V6 column is mapped exactly; PaycheckEntityMappingTest pins that against the migration
 * text. Ids are bare Longs, as in PlaidWebhookEvent: the foreign key (ON DELETE CASCADE to users)
 * lives in the database and nothing here navigates it.
 *
 * <p>payer_display_name, raw_descriptor and the amounts are payroll data: never in a log line at
 * INFO or above, an ops alert, or an analytics property.
 */
@Entity
@Table(name = "paycheck_streams")
@Getter
@Setter
@NoArgsConstructor
public class PaycheckStream {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "plaid_item_id", length = 80)
    private String plaidItemId;

    @Column(name = "plaid_account_id", nullable = false, length = 80)
    private String plaidAccountId;

    @Column(name = "plaid_stream_id", length = 80)
    private String plaidStreamId;

    @Column(name = "payer_token", nullable = false, length = 64)
    private String payerToken;

    @Column(name = "payer_display_name", nullable = false, length = 120)
    private String payerDisplayName;

    /** Nulled when the Item breaks (spec 5.7, 7.9); the stream skeleton survives. */
    @Column(name = "raw_descriptor", length = 255)
    private String rawDescriptor;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 12)
    private PaycheckStreamState state = PaycheckStreamState.CANDIDATE;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 12)
    private PaycheckStreamRole role = PaycheckStreamRole.NONE;

    /** The one mapped use of InvestmentFrequency; SEMI_MONTHLY (12 characters) is the longest name. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cadence", length = 12)
    private InvestmentFrequency cadence;

    @Enumerated(EnumType.STRING)
    @Column(name = "cadence_source", length = 10)
    private PaycheckCadenceSource cadenceSource;

    /** ISO day of week, 1 to 7. V6 tinyint: a bare Short validates as smallint and fails at boot. */
    @Column(name = "anchor_dow", columnDefinition = "TINYINT")
    private Short anchorDow;

    @Column(name = "anchor_parity_date")
    private LocalDate anchorParityDate;

    /** A day number or LAST_BD. */
    @Column(name = "anchor_day_1", length = 8)
    private String anchorDay1;

    @Column(name = "anchor_day_2", length = 8)
    private String anchorDay2;

    /** smallint NOT NULL DEFAULT 0. Callers narrow with (short) Math.min(value, Short.MAX_VALUE). */
    @Column(name = "score", nullable = false)
    private short score = 0;

    @Column(name = "score_breakdown_json", columnDefinition = "TEXT")
    private String scoreBreakdownJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "reject_reason", length = 32)
    private PaycheckRejectReason rejectReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "retired_reason", length = 32)
    private PaycheckRetiredReason retiredReason;

    /** Plaid's strings from /transactions/recurring/get, stored as sent (getValue()). */
    @Column(name = "plaid_status", length = 32)
    private String plaidStatus;

    @Column(name = "plaid_frequency", length = 32)
    private String plaidFrequency;

    @Column(name = "plaid_pfc_detailed", length = 32)
    private String plaidPfcDetailed;

    @Column(name = "plaid_pfc_confidence", length = 32)
    private String plaidPfcConfidence;

    /** Tri-state like users.plaid_transactions_consented: null means Plaid has not said. */
    @Column(name = "plaid_is_active", columnDefinition = "BOOLEAN")
    private Boolean plaidIsActive;

    @Column(name = "plaid_predicted_next_date")
    private LocalDate plaidPredictedNextDate;

    @Column(name = "deposits_found", nullable = false)
    private short depositsFound = 0;

    @Column(name = "first_date")
    private LocalDate firstDate;

    @Column(name = "last_date")
    private LocalDate lastDate;

    @Column(name = "last_amount", precision = 12, scale = 2)
    private BigDecimal lastAmount;

    /** Positive. */
    @Column(name = "trailing_median", precision = 12, scale = 2)
    private BigDecimal trailingMedian;

    @Column(name = "trailing_cv", precision = 6, scale = 4)
    private BigDecimal trailingCv;

    @Column(name = "predicted_next_date")
    private LocalDate predictedNextDate;

    @Column(name = "missed_count", nullable = false)
    private short missedCount = 0;

    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    @Column(name = "plaid_snapshot_json", columnDefinition = "TEXT")
    private String plaidSnapshotJson;

    /** Eastern wall time. Services set it from their nowET; onCreate only fills a forgotten one. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = EasternTime.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = EasternTime.now();
    }
}
```

- [ ] **Step 8: PaycheckEvent and PaycheckCycle**

Create `backend/src/main/java/com/investingapp/backend/model/PaycheckEvent.java`:

```java
package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * paycheck_events (spec 3.4, V6): one row per Plaid transaction kept as an inflow candidate,
 * unique on the Plaid id so a replayed sync page is a no-op. Rows older than 24 months are deleted
 * by the reconcile (spec 7.9); every row of a user is purged when the Item breaks (spec 5.7).
 *
 * <p>Descriptor, names and amounts are payroll data: never in a log line at INFO or above, an ops
 * alert, or an analytics property. first_seen_at is Eastern wall time, set by the writer.
 */
@Entity
@Table(name = "paycheck_events")
@Getter
@Setter
@NoArgsConstructor
public class PaycheckEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stream_id")
    private Long streamId;

    @Column(name = "plaid_transaction_id", nullable = false, unique = true, length = 80)
    private String plaidTransactionId;

    /** Nullable in Plaid, never required for matching (spec 3.4). */
    @Column(name = "pending_transaction_id", length = 80)
    private String pendingTransactionId;

    @Column(name = "plaid_account_id", nullable = false, length = 80)
    private String plaidAccountId;

    /** Positive inflow amount. */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** Plaid's amount unmodified: negative means money in. */
    @Column(name = "raw_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal rawAmount;

    @Column(name = "posted_date", nullable = false)
    private LocalDate postedDate;

    @Column(name = "authorized_date")
    private LocalDate authorizedDate;

    @Column(name = "pending", nullable = false, columnDefinition = "BOOLEAN")
    private boolean pending;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private PaycheckEventStatus status = PaycheckEventStatus.ACTIVE;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "removed_at")
    private LocalDateTime removedAt;

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;

    @Column(name = "raw_descriptor", length = 255)
    private String rawDescriptor;

    @Column(name = "merchant_name", length = 120)
    private String merchantName;

    @Column(name = "counterparty_name", length = 120)
    private String counterpartyName;

    @Column(name = "counterparty_type", length = 32)
    private String counterpartyType;

    @Column(name = "pfc_primary", length = 40)
    private String pfcPrimary;

    @Column(name = "pfc_detailed", length = 40)
    private String pfcDetailed;

    @Column(name = "pfc_confidence", length = 40)
    private String pfcConfidence;

    @Column(name = "payment_channel", length = 12)
    private String paymentChannel;

    @Column(name = "ppd_id", length = 64)
    private String ppdId;
}
```

Create `backend/src/main/java/com/investingapp/backend/model/PaycheckCycle.java`:

```java
package com.investingapp.backend.model;

import com.investingapp.backend.util.EasternTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * paycheck_cycles (spec 3.5, V6): one row per pay period per stream. Unique on
 * (stream_id, period_key) and on event_id. Every reference is a bare Long; the six foreign keys
 * live in the database (stream CASCADE, schedule, event and execution SET NULL, authorization
 * RESTRICT). Phase 1 writes no authorization_id, execution_id or queued_night.
 *
 * <p>deposit_amount and amount_to_pull are payroll data: never in a log line at INFO or above, an
 * ops alert, or an analytics property.
 */
@Entity
@Table(name = "paycheck_cycles")
@Getter
@Setter
@NoArgsConstructor
public class PaycheckCycle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "schedule_id")
    private Long scheduleId;

    @Column(name = "stream_id", nullable = false)
    private Long streamId;

    @Column(name = "period_key", nullable = false, length = 16)
    private String periodKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 10)
    private PaycheckCycleState state = PaycheckCycleState.OPEN;

    @Column(name = "expected_date", nullable = false)
    private LocalDate expectedDate;

    @Column(name = "window_start", nullable = false)
    private LocalDate windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDate windowEnd;

    @Column(name = "event_id", unique = true)
    private Long eventId;

    @Column(name = "deposit_amount", precision = 12, scale = 2)
    private BigDecimal depositAmount;

    /** Eastern wall time the deposit matched; under shadow, when the SHADOW_CONFIRMED skip was written. */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** Under shadow, the would-have-pulled amount (ruling R7). */
    @Column(name = "amount_to_pull", precision = 12, scale = 2)
    private BigDecimal amountToPull;

    @Column(name = "authorization_id")
    private Long authorizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "skip_reason", length = 32)
    private PaycheckSkipReason skipReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "voided_reason", length = 32)
    private PaycheckVoidedReason voidedReason;

    @Column(name = "execution_id")
    private Long executionId;

    @Column(name = "queued_night")
    private LocalDate queuedNight;

    /** Eastern wall time. Services set it from their nowET; onCreate only fills a forgotten one. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = EasternTime.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = EasternTime.now();
    }
}
```

- [ ] **Step 9: The seven User fields**

`backend/src/main/java/com/investingapp/backend/model/User.java`. Replace `:30`, which is exactly:

```java
@ToString(exclude = "userProgress") // Exclude userProgress to avoid circular reference
```

with:

```java
@ToString(exclude = {"userProgress", "plaidSyncCursor"}) // userProgress is circular; the cursor never goes in a log line (spec 12.3)
```

Replace `:178`, which is exactly:

```java
    // Paycheck Autopilot, Phase 0 (V6). The rest of spec 3.1 stays unmapped until a later phase.
```

with:

```java
    // Paycheck Autopilot (V6, spec 3.1). Phase 0 mapped the fields below; Phase 1 maps the rest after previousAchRelationshipId.
```

Insert after `:200`, the line that is exactly `    private String previousAchRelationshipId; // Stored as ENC:<base64(iv+ciphertext)>, encrypted at rest` (and before the blank line and `@Column(name = "account_status", length = 30)`):

```java

    /** Plaid /transactions/sync cursor. Excluded from toString; never logged, sent or returned. */
    @Column(name = "plaid_sync_cursor", columnDefinition = "TEXT")
    private String plaidSyncCursor;

    /** Flipped once by HISTORICAL_UPDATE_COMPLETE (spec 2.5); V6 TINYINT(1) NOT NULL DEFAULT 0. */
    @Column(name = "plaid_history_complete", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean plaidHistoryComplete = false;

    @Column(name = "plaid_transactions_update_status", length = 40)
    private String plaidTransactionsUpdateStatus;

    /** Eastern wall time Plaid will disconnect the Item (consent expiry); null when none is known. */
    @Column(name = "plaid_disconnect_at")
    private LocalDateTime plaidDisconnectAt;

    @Column(name = "plaid_last_sync_at")
    private LocalDateTime plaidLastSyncAt;

    @Column(name = "plaid_streams_read_at")
    private LocalDateTime plaidStreamsReadAt;

    /** Written only when the user rejects a found paycheck (spec 5.9); detection skips them (R8). */
    @Column(name = "paycheck_autopilot_opt_out", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean paycheckAutopilotOptOut = false;
```

`User` keeps `@Getter @Setter`, so the accessors are `getPlaidSyncCursor()`, `setPlaidSyncCursor(String)`, `getPlaidHistoryComplete()`, `setPlaidHistoryComplete(Boolean)`, `getPlaidTransactionsUpdateStatus()`, `getPlaidDisconnectAt()`, `getPlaidLastSyncAt()`, `getPlaidStreamsReadAt()`, `getPaycheckAutopilotOptOut()` and their setters. `LocalDateTime` is already imported (`:17`).

- [ ] **Step 10: The seven InvestmentSchedule fields**

`backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java`. Insert after `:85`, the line that is exactly `    private HoldReason holdReason;` (the field under the javadoc quoted as `/** The system's stop, orthogonal to {@link Status}; null means none.`), before the blank line and `@Column(name = "monthly_streak", nullable = false)`:

```java

    // Paycheck Autopilot, Phase 1: the detection mirror (spec 3.2, ruling R1). Written in every mode
    // once detection has run; mode stays MANUAL under shadow, and nothing reads these on the wire
    // until Phase 2. Neither status nor holdReason is ever derived from them. The anchors,
    // authorization, amount_source, snoozed_until and paused_by_user_at stay unmapped (decision 20).
    @Enumerated(EnumType.STRING)
    @Column(name = "detection_state", length = 16)
    private DetectionState detectionState;

    /** paycheck_streams.id; the V6 foreign key is ON DELETE SET NULL, so a stream purge nulls it. */
    @Column(name = "primary_stream_id")
    private Long primaryStreamId;

    /** Display and the D-1 notice only, never a trigger. */
    @Column(name = "next_expected_date")
    private LocalDate nextExpectedDate;

    @Column(name = "missed_count", nullable = false)
    private short missedCount = 0;

    @Column(name = "dormant_since")
    private LocalDate dormantSince;

    @Column(name = "last_cadence_check_at")
    private LocalDateTime lastCadenceCheckAt;

    @Column(name = "detection_started_at")
    private LocalDateTime detectionStartedAt;
```

`@Data` generates `getDetectionState()`, `getPrimaryStreamId()`, `getNextExpectedDate()`, `short getMissedCount()`, `getDormantSince()`, `getLastCadenceCheckAt()`, `getDetectionStartedAt()` and the setters. `LocalDate` and `LocalDateTime` are already imported (`:8-9`). `isReadyForInvestment(LocalDate todayET)` is not edited: confirm with `git diff HEAD -- backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java` that the only hunk is this insertion.

- [ ] **Step 11: The three repositories**

Create `backend/src/main/java/com/investingapp/backend/repository/PaycheckStreamRepository.java`:

```java
package com.investingapp.backend.repository;

import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * paycheck_streams. Derived deletes load the rows and remove them one by one, so the persistence
 * context stays consistent with the database; a derived delete needs a read-write transaction,
 * hence @Transactional (it joins the caller's when there is one).
 *
 * <p>A caller purging several paycheck tables deletes cycles, then events, then streams: MySQL
 * cascades a stream delete to its cycles, and Hibernate would then find no row for a cycle it was
 * also told to delete.
 */
@Repository
public interface PaycheckStreamRepository extends JpaRepository<PaycheckStream, Long> {

    List<PaycheckStream> findByUserIdOrderByIdAsc(Long userId);

    List<PaycheckStream> findByUserIdAndState(Long userId, PaycheckStreamState state);

    /** The natural key, unique in V6 (uq_paycheck_streams_payer). */
    Optional<PaycheckStream> findByUserIdAndPlaidAccountIdAndPayerToken(Long userId, String plaidAccountId,
            String payerToken);

    @Transactional
    long deleteByUserId(Long userId);
}
```

Create `backend/src/main/java/com/investingapp/backend/repository/PaycheckEventRepository.java`:

```java
package com.investingapp.backend.repository;

import com.investingapp.backend.model.PaycheckEvent;
import com.investingapp.backend.model.PaycheckEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** paycheck_events. Delete rules as on PaycheckStreamRepository. */
@Repository
public interface PaycheckEventRepository extends JpaRepository<PaycheckEvent, Long> {

    /** Unique in V6 (uq_paycheck_events_plaid_transaction): the replay guard. */
    Optional<PaycheckEvent> findByPlaidTransactionId(String plaidTransactionId);

    /** Posted rows that name this pending transaction. A list: V6 indexes the column but does not make it unique. */
    List<PaycheckEvent> findByPendingTransactionId(String pendingTransactionId);

    /** Posted (not pending) rows in one status, oldest first: what grouping reads. */
    List<PaycheckEvent> findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc(Long userId, PaycheckEventStatus status);

    List<PaycheckEvent> findByStreamIdAndStatusOrderByPostedDateDesc(Long streamId, PaycheckEventStatus status);

    @Transactional
    long deleteByUserId(Long userId);

    /** One account's events, for an account-level revoke (spec 5.7). */
    @Transactional
    long deleteByUserIdAndPlaidAccountId(Long userId, String plaidAccountId);

    /** 24-month retention (spec 7.9); the reconcile passes the cutoff date. */
    @Transactional
    long deleteByPostedDateBefore(LocalDate cutoff);
}
```

Create `backend/src/main/java/com/investingapp/backend/repository/PaycheckCycleRepository.java`:

```java
package com.investingapp.backend.repository;

import com.investingapp.backend.model.PaycheckCycle;
import com.investingapp.backend.model.PaycheckCycleState;
import com.investingapp.backend.model.PaycheckSkipReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** paycheck_cycles. Delete rules as on PaycheckStreamRepository. */
@Repository
public interface PaycheckCycleRepository extends JpaRepository<PaycheckCycle, Long> {

    /** Unique in V6 (uq_paycheck_cycles_stream_period). */
    Optional<PaycheckCycle> findByStreamIdAndPeriodKey(Long streamId, String periodKey);

    List<PaycheckCycle> findByUserIdAndState(Long userId, PaycheckCycleState state);

    /** Overdue cycles for the reconcile's miss pass; windowEnd is an Eastern date. */
    List<PaycheckCycle> findByStateAndWindowEndBefore(PaycheckCycleState state, LocalDate windowEnd);

    /** Stale confirmations for the reconcile; confirmedAt is Eastern wall time. */
    List<PaycheckCycle> findByStateAndConfirmedAtBefore(PaycheckCycleState state, LocalDateTime confirmedAt);

    /** The shadow report's cycles: SHADOW_CONFIRMED skips since a point in Eastern time. */
    List<PaycheckCycle> findByUserIdAndSkipReasonAndConfirmedAtAfter(Long userId, PaycheckSkipReason skipReason,
            LocalDateTime confirmedAt);

    @Transactional
    long deleteByUserId(Long userId);
}
```

- [ ] **Step 12: Run the two model tests**

From `backend/`:

```
./gradlew test --tests 'com.investingapp.backend.model.PaycheckEntityDefaultsTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.model.PaycheckEntityDefaultsTest.xml
./gradlew test --tests 'com.investingapp.backend.model.PaycheckEntityMappingTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.model.PaycheckEntityMappingTest.xml
```

Expected: `BUILD SUCCESSFUL` twice; `tests="7" skipped="0" failures="0" errors="0"` for each. A mapping failure names the column, for example `[paycheck_streams.anchor_dow columnDefinition] expected: "TINYINT"`; fix the field, never the test or V6.

- [ ] **Step 13: The two javadoc corrections**

`backend/src/main/java/com/investingapp/backend/model/HoldReason.java`. Replace `:3-6`, which is exactly:

```java
/**
 * investment_schedules.hold_reason (spec 3.2): the system's stop. is_paused stays the user's stop;
 * null means no system hold.
 */
```

with:

```java
/**
 * investment_schedules.hold_reason (spec 3.2): the system's stop; null means no system hold. The
 * user's stop is InvestmentSchedule.Status.PAUSED_BY_USER (V7 replaced the old pause flag), and neither one
 * ever writes the other. Which holds block money is decided only in isReadyForInvestment.
 */
```

`backend/src/main/java/com/investingapp/backend/model/InvestmentFrequency.java`. Replace `:17-18`, which is exactly:

```java
 * <p>InvestmentSchedule.frequency stays a VARCHAR(20) String column holding one of these names.
 * This enum is for parsing and money math only, never a mapped column type.
```

with:

```java
 * <p>InvestmentSchedule.frequency stays a VARCHAR(20) String column holding one of these names.
 * The one mapped use is PaycheckStream.cadence (paycheck_streams.cadence, VARCHAR(12), stored by
 * name): SEMI_MONTHLY is 12 characters, so a longer constant needs a wider column first.
```

Comment-only edits; re-run `./gradlew test --tests 'com.investingapp.backend.model.FrequencyFactorParityTest' --console=plain` and expect `BUILD SUCCESSFUL` with `tests="8" skipped="0" failures="0" errors="0"` in its XML (it reads the factors, not the javadoc).

- [ ] **Step 14: Boot under validate on a scratch MySQL (the gate)**

`BackendApplicationTests` is `@RequiresCiDatabase`: without a reachable database it is **skipped and the build still says BUILD SUCCESSFUL**, so the console proves nothing. Read the XML. Set `SCRATCH_DB_PASSWORD` in your shell first (your local MySQL root password; never echo it, never paste it into a file), then from the repository root run the block below as one command (an agent shell does not keep exported variables from one call to the next):

```
MYSQL_PWD="$SCRATCH_DB_PASSWORD" mysql -h 127.0.0.1 -u root -e "CREATE DATABASE fred_t02_scratch"
cd backend
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t02_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD="$SCRATCH_DB_PASSWORD"
export PLAID_WEBHOOK_URL='https://ci.invalid/api/plaid/webhook'
export PLAID_REDIRECT_URI='https://ci.invalid/plaid/oauth'
export PLAID_LINK_CUSTOMIZATION=default
./gradlew cleanTest test --tests 'com.investingapp.backend.BackendApplicationTests' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml
cd ..
MYSQL_PWD="$SCRATCH_DB_PASSWORD" mysql -h 127.0.0.1 -u root -e "DROP DATABASE fred_t02_scratch"
```

Expected: `tests="1" skipped="0" failures="0" errors="0"`. Flyway applies V1 to V8 (V9 and V10 too if T01 or the Goals v3 branch already landed) and the seven views, then validate passes for every entity. A `skipped="1"` means the database was not reached: fix the connection and re-run. A failure such as `Schema-validation: wrong column type encountered in column [anchor_dow] in table [paycheck_streams]; found [tinyint (Types#TINYINT)], but expecting [smallint (Types#SMALLINT)]` means a field lost its `columnDefinition`: fix the field. A `PropertyReferenceException: No property ... found for type` names a repository method whose derived name does not match a field. If Flyway reports `Migration checksum mismatch for migration version 6`, the scratch schema was not fresh: drop it and start again, and never run `flyway repair`.

No property is added by this task, so `application-local.properties` needs no edit.

- [ ] **Step 15: Commit**

Hunk-verify each modified file with `git diff HEAD -- <file>` (shared tree: another session may have edits there; stage nothing you did not write), confirm no em dash in anything new or changed:

```
git diff HEAD -- backend/src | LC_ALL=C grep -n $'^+.*\xe2\x80\x94'
LC_ALL=C grep -rln $'\xe2\x80\x94' backend/src/main/java/com/investingapp/backend/model/Paycheck*.java backend/src/main/java/com/investingapp/backend/model/DetectionState.java backend/src/main/java/com/investingapp/backend/repository/Paycheck*Repository.java backend/src/main/java/com/investingapp/backend/util/ColumnText.java backend/src/test/java/com/investingapp/backend/util/ColumnTextTest.java backend/src/test/java/com/investingapp/backend/model/PaycheckEntity*Test.java
```

Both print nothing. Then:

```
git add backend/src/main/java/com/investingapp/backend/util/ColumnText.java backend/src/main/java/com/investingapp/backend/model/PaycheckStream.java backend/src/main/java/com/investingapp/backend/model/PaycheckEvent.java backend/src/main/java/com/investingapp/backend/model/PaycheckCycle.java backend/src/main/java/com/investingapp/backend/model/PaycheckStreamState.java backend/src/main/java/com/investingapp/backend/model/PaycheckStreamRole.java backend/src/main/java/com/investingapp/backend/model/PaycheckCadenceSource.java backend/src/main/java/com/investingapp/backend/model/PaycheckEventStatus.java backend/src/main/java/com/investingapp/backend/model/PaycheckCycleState.java backend/src/main/java/com/investingapp/backend/model/PaycheckSkipReason.java backend/src/main/java/com/investingapp/backend/model/PaycheckVoidedReason.java backend/src/main/java/com/investingapp/backend/model/PaycheckRejectReason.java backend/src/main/java/com/investingapp/backend/model/PaycheckRetiredReason.java backend/src/main/java/com/investingapp/backend/model/DetectionState.java backend/src/main/java/com/investingapp/backend/model/User.java backend/src/main/java/com/investingapp/backend/model/InvestmentSchedule.java backend/src/main/java/com/investingapp/backend/model/HoldReason.java backend/src/main/java/com/investingapp/backend/model/InvestmentFrequency.java backend/src/main/java/com/investingapp/backend/repository/PaycheckStreamRepository.java backend/src/main/java/com/investingapp/backend/repository/PaycheckEventRepository.java backend/src/main/java/com/investingapp/backend/repository/PaycheckCycleRepository.java backend/src/test/java/com/investingapp/backend/util/ColumnTextTest.java backend/src/test/java/com/investingapp/backend/model/PaycheckEntityDefaultsTest.java backend/src/test/java/com/investingapp/backend/model/PaycheckEntityMappingTest.java
git status --short
git commit -m "feat(paycheck): map the three paycheck tables and the Phase 1 user and schedule columns"
```

`git status --short` before the commit shows exactly these 24 paths staged (`A ` or `M `) and nothing under `ITPM/agent-memory/` or `application-local.properties`. Never `git checkout`, `git restore`, `git stash` or `git reset`. Do not push.

### Verification

From `backend/`, each class on its own, reading counts from `build/test-results/test/TEST-<class>.xml` with `grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"'`:

| command | expected XML counts |
| --- | --- |
| `./gradlew test --tests 'com.investingapp.backend.util.ColumnTextTest' --console=plain` | `tests="5" skipped="0" failures="0" errors="0"` |
| `./gradlew test --tests 'com.investingapp.backend.model.PaycheckEntityDefaultsTest' --console=plain` | `tests="7" skipped="0" failures="0" errors="0"` |
| `./gradlew test --tests 'com.investingapp.backend.model.PaycheckEntityMappingTest' --console=plain` | `tests="7" skipped="0" failures="0" errors="0"` |
| `./gradlew test --tests 'com.investingapp.backend.model.PhaseZeroEntityDefaultsTest' --console=plain` | `tests="5" skipped="0" failures="0" errors="0"` (unchanged) |
| `./gradlew test --tests 'com.investingapp.backend.migration.V6PaycheckAutopilotMigrationTest' --console=plain` | `tests="2" skipped="0" failures="0" errors="0"` (unchanged; V6 is not edited) |
| `./gradlew test --tests 'com.investingapp.backend.model.FrequencyFactorParityTest' --console=plain` | `tests="8" skipped="0" failures="0" errors="0"` (unchanged) |
| Step 14 scratch form with `--tests 'com.investingapp.backend.BackendApplicationTests'` | `tests="1" skipped="0" failures="0" errors="0"` |

Then the whole backend suite on a fresh scratch schema, Step 14's form with the `--tests` filter removed (`./gradlew cleanTest test --console=plain`), totalled with `grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/*.xml | awk -F'"' '{t+=$2; s+=$4; f+=$6; e+=$8} END {print t, s, f, e}'`. Expected: `775 0 0 0` when this task lands directly on 137998c (756 at HEAD plus this task's 19); if other Phase 1 tasks landed first, their total plus 19, still with 0 skipped, 0 failures and 0 errors. The `ScheduleChurnViewTest` and `ScheduleTransitionOutboxRollbackTest` classes in that run are the proof that the new `InvestmentSchedule` columns (`missed_count` written as 0, the rest null) do not disturb existing schedule writes. Drop the scratch schema afterwards.

Static checks: `git diff HEAD~1 -- backend/src/main/resources` prints nothing (no migration, no property); `git diff HEAD~1 -- backend/src/main/java | grep -nE '^\+.*(setStatus\(|setMode\(|setHoldReason\(|getIsPaused\(|isPausedForAnyReason\()'` prints nothing (no money-path writer touched).

### Produces

Entities (package `com.investingapp.backend.model`, `@Entity @Getter @Setter @NoArgsConstructor`, bare `Long` ids, every V6 column mapped):

- `PaycheckStream` (`paycheck_streams`): `Long id, userId`; `String plaidItemId, plaidAccountId, plaidStreamId, payerToken, payerDisplayName, rawDescriptor`; `PaycheckStreamState state = CANDIDATE`; `PaycheckStreamRole role = NONE`; `@Enumerated(STRING) @Column(length = 12) InvestmentFrequency cadence`; `PaycheckCadenceSource cadenceSource`; `@Column(name = "anchor_dow", columnDefinition = "TINYINT") Short anchorDow`; `LocalDate anchorParityDate`; `String anchorDay1, anchorDay2`; `short score = 0`, `short depositsFound = 0`, `short missedCount = 0` (each `nullable = false`; callers narrow with `(short) Math.min(value, Short.MAX_VALUE)`); `String scoreBreakdownJson` (TEXT); `PaycheckRejectReason rejectReason`; `PaycheckRetiredReason retiredReason`; `String plaidStatus, plaidFrequency, plaidPfcDetailed, plaidPfcConfidence`; `@Column(name = "plaid_is_active", columnDefinition = "BOOLEAN") Boolean plaidIsActive`; `LocalDate plaidPredictedNextDate, firstDate, lastDate, predictedNextDate`; `BigDecimal lastAmount, trailingMedian` (`precision = 12, scale = 2`); `@Column(name = "trailing_cv", precision = 6, scale = 4) BigDecimal trailingCv`; `LocalDateTime retiredAt, createdAt, updatedAt`; `String plaidSnapshotJson` (TEXT); `@PrePersist onCreate()` fills a null `createdAt` with `EasternTime.now()`, `@PreUpdate onUpdate()` stamps `updatedAt`.
- `PaycheckEvent` (`paycheck_events`): `Long id, userId, streamId`; `String plaidTransactionId, pendingTransactionId, plaidAccountId`; `BigDecimal amount, rawAmount` (12,2, NOT NULL); `LocalDate postedDate, authorizedDate`; `@Column(name = "pending", nullable = false, columnDefinition = "BOOLEAN") boolean pending` (accessor `isPending()`); `PaycheckEventStatus status = ACTIVE`; `LocalDateTime firstSeenAt, removedAt, modifiedAt`; `String rawDescriptor` (255), `merchantName` (120), `counterpartyName` (120), `counterpartyType` (32), `pfcPrimary`, `pfcDetailed`, `pfcConfidence` (40), `paymentChannel` (12), `ppdId` (64). No lifecycle callbacks.
- `PaycheckCycle` (`paycheck_cycles`): `Long id, userId, scheduleId, streamId, eventId, authorizationId, executionId`; `String periodKey` (16); `PaycheckCycleState state = OPEN`; `LocalDate expectedDate, windowStart, windowEnd, queuedNight`; `BigDecimal depositAmount, amountToPull` (12,2); `LocalDateTime confirmedAt, createdAt, updatedAt`; `PaycheckSkipReason skipReason`; `PaycheckVoidedReason voidedReason`; `onCreate()` and `onUpdate()` as on `PaycheckStream`.

Enums (package `com.investingapp.backend.model`, one file each): `PaycheckStreamState {CANDIDATE, PROVISIONAL, CONFIRMED, DORMANT, RETIRED, REJECTED}`; `PaycheckStreamRole {PRIMARY, SECONDARY, INTERRUPTION, NONE}`; `PaycheckCadenceSource {OBSERVED, PLAID}`; `PaycheckEventStatus {ACTIVE, REMOVED, MODIFIED, SUPERSEDED}`; `PaycheckCycleState {OPEN, CONFIRMED, QUEUED, SKIPPED, VOIDED, MISSED}`; `PaycheckSkipReason {SHADOW_CONFIRMED, AFFORDABILITY, CATCH_UP_CAP, PAUSED, HOLD, NO_AUTHORIZATION, LATE_SEEN, ACH_NOT_READY, KILL_SWITCH, USER_SKIP_THIRD, STALE}`; `PaycheckVoidedReason {DEPOSIT_REMOVED, DEPOSIT_PENDING, AMOUNT_CHANGED, ADMIN}`; `PaycheckRejectReason {NOT_INFLOW, NOT_DEPOSITORY, PLAID_TOMBSTONED, CADENCE_UNSUPPORTED, PFC_EXCLUDED, DESCRIPTOR_EXCLUDED, LOW_MEDIAN, STALE, BENEFITS_SIGNAL}`; `PaycheckRetiredReason {SUCCEEDED, DORMANT_120, ADMIN}`; `DetectionState {SEARCHING, NEEDS_CONSENT, UNDETECTABLE, PROVISIONAL, CONFIRMED, DORMANT, ITEM_BROKEN, REVOKED}`.

`User` fields (with Lombok getters and setters): `String plaidSyncCursor` (TEXT, excluded from `toString`), `Boolean plaidHistoryComplete = false` (`BOOLEAN DEFAULT FALSE`), `String plaidTransactionsUpdateStatus` (40), `LocalDateTime plaidDisconnectAt`, `plaidLastSyncAt`, `plaidStreamsReadAt`, `Boolean paycheckAutopilotOptOut = false` (`BOOLEAN DEFAULT FALSE`).

`InvestmentSchedule` fields (with `@Data` accessors): `DetectionState detectionState` (16), `Long primaryStreamId`, `LocalDate nextExpectedDate`, `short missedCount = 0` (`nullable = false`), `LocalDate dormantSince`, `LocalDateTime lastCadenceCheckAt`, `LocalDateTime detectionStartedAt`. `status`, `mode`, `holdReason` and `isReadyForInvestment` unchanged.

Repositories (package `com.investingapp.backend.repository`; every `delete*` is `@Transactional`, joins the caller's transaction, and returns the row count; purge order across tables is cycles, then events, then streams):

- `PaycheckStreamRepository extends JpaRepository<PaycheckStream, Long>`: `List<PaycheckStream> findByUserIdOrderByIdAsc(Long)`, `List<PaycheckStream> findByUserIdAndState(Long, PaycheckStreamState)`, `Optional<PaycheckStream> findByUserIdAndPlaidAccountIdAndPayerToken(Long, String, String)`, `long deleteByUserId(Long)`.
- `PaycheckEventRepository extends JpaRepository<PaycheckEvent, Long>`: `Optional<PaycheckEvent> findByPlaidTransactionId(String)`, `List<PaycheckEvent> findByPendingTransactionId(String)`, `List<PaycheckEvent> findByUserIdAndStatusAndPendingFalseOrderByPostedDateAsc(Long, PaycheckEventStatus)`, `List<PaycheckEvent> findByStreamIdAndStatusOrderByPostedDateDesc(Long, PaycheckEventStatus)`, `long deleteByUserId(Long)`, `long deleteByUserIdAndPlaidAccountId(Long, String)`, `long deleteByPostedDateBefore(LocalDate)`.
- `PaycheckCycleRepository extends JpaRepository<PaycheckCycle, Long>`: `Optional<PaycheckCycle> findByStreamIdAndPeriodKey(Long, String)`, `List<PaycheckCycle> findByUserIdAndState(Long, PaycheckCycleState)`, `List<PaycheckCycle> findByStateAndWindowEndBefore(PaycheckCycleState, LocalDate)`, `List<PaycheckCycle> findByStateAndConfirmedAtBefore(PaycheckCycleState, LocalDateTime)`, `List<PaycheckCycle> findByUserIdAndSkipReasonAndConfirmedAtAfter(Long, PaycheckSkipReason, LocalDateTime)`, `long deleteByUserId(Long)`.

Utility: `com.investingapp.backend.util.ColumnText.fit(String value, int width): String` (null-safe; returns `value` when `value.length() <= width`, else its first `width` chars, or `width - 1` when char `width - 1` is a high surrogate; throws `IllegalArgumentException` for `width < 1`).

Tests a later task may extend: `PaycheckEntityMappingTest` parses every derived method (not `@Query` ones) of the three repositories with Spring Data's `PartTree` and requires `@Transactional` on every derived delete, so a task adding a finder or delete to these repositories is checked automatically and needs no edit to that test.
