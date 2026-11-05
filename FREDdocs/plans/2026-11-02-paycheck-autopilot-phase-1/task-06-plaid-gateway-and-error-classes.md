### Task 06: plaid-gateway-and-error-classes

One way for Phase 1 code to call Plaid. `PlaidGateway.call(op, Call<T>)` executes a retrofit call on the caller's thread, turns the answer into a typed `PlaidCallResult<T>` (a body or a `PlaidApiError`, never both), classifies every error into a spec 2.1 `PlaidErrorClass` on the exact Plaid `error_code` (never the HTTP status, never `error_type`), logs one `plaid.call` line with the Plaid `request_id`, and feeds `PlaidOutageBreaker`, whose fifth consecutive TRANSIENT or RATE_LIMIT result raises the `plaid.outage` ops alert. The task also ships the test-only `RecordingTransactionManager` that T08, T10, T14 and T16 use to prove a Plaid call runs outside a database transaction. Nothing calls the gateway yet: T08 (sync), T10 (recurring), T15 (reconcile `/item/get`), T16 (update mode) and T19 (sandbox) are its callers.

Facts from the handoff, the rulings and the contract that this task depends on, restated so this file stands alone:

- No Plaid HTTP inside a database transaction (plan-wide house rule; spec 2.1 "never inside a DB transaction"). The gateway enforces it: a call made while `TransactionSynchronizationManager.isActualTransactionActive()` is true throws `IllegalStateException` before `execute()` runs. A caller that must call Plaid "after commit" hands the work to `plaidExecutor` (as `PlaidService` does with `registerSynchronization`), because an `afterCommit` callback on the same thread still counts as inside the transaction and still holds the Hikari connection.
- The contract runs the call on the caller's thread. Spec 2.1 says the gateway wraps calls on `plaidExecutor`; the Phase 1 callers (the T14 drainer groups, the T16 enqueuer, the T15 reconcile, admin verbs) already run on `plaidExecutor` or a scheduler thread, so a second hop would only add a queue.
- Logging (spec 12.3, plan house rule): INFO and above carry ids, codes, states and counts only. The gateway logs `op`, `request_id`, the HTTP status, the Plaid `error_code` and the class; it never logs a response body, `error_message`, `display_message`, a token or a cursor. `PlaidApiError` does not even keep `error_message` or `display_message`.
- Ops alerts (T04): `OpsAlertService.raise(String type, String subjectId, String message, Map<String, Object> context)` never throws, writes its ledger in its own transaction and delivers on `opsAlertExecutor`; `OpsAlertService.GLOBAL_SUBJECT = "global"`. Alert type names are string constants on `ops/OpsAlerts`, and each later task adds the names it raises. T04's `AlertSitesRoutedTest` requires every `logger.error("alert.` line to be followed within 3 lines by `opsAlertService.raise(`, and no `raise(` argument may name `total`, `amount`, `getLastError`, `getMessage` or `error`; the outage site below is written to pass both.
- This task moves no money, writes no `InvestmentSchedule` field (no `status`, no `mode`, no hold), does not touch `isReadyForInvestment`, emits no analytics event and adds no property. No `application*.properties` key is added, so there is no local-properties step.
- House rules: JUnit 5 + Mockito, `new Service(...)`, `retrofit2.Call` mocked with `Response.success` / `Response.error`; no `@SpringBootTest`; no em dashes anywhere; never `git checkout`, `git restore`, `git stash` or `git reset`; stage only the files named in the commit step; one conventional commit.

Classification table (spec 2.1, plus `MUTATION` from Decision 24; the RATE_LIMIT list is every code on plaid.com/docs/errors/rate-limit-exceeded as read on 2026-11-04, because `classify` sees no `error_type`):

| class | exact `error_code` values |
|---|---|
| TRANSIENT | `INSTITUTION_DOWN`, `INSTITUTION_NOT_RESPONDING`, `INSTITUTION_NOT_AVAILABLE`, `INTERNAL_SERVER_ERROR`, `PLANNED_MAINTENANCE`, `PRODUCT_NOT_READY`; also any answer with no parsable `error_code` and HTTP >= 500 (spec 11) |
| RATE_LIMIT | `ACCOUNTS_LIMIT`, `ACCOUNTS_BALANCE_GET_LIMIT`, `AUTH_LIMIT`, `BALANCE_LIMIT`, `CREDITS_EXHAUSTED`, `IDENTITY_LIMIT`, `INSTITUTIONS_GET_LIMIT`, `INSTITUTIONS_GET_BY_ID_LIMIT`, `INSTITUTION_RATE_LIMIT`, `INVESTMENTS_HOLDINGS_GET_LIMIT`, `INVESTMENTS_LIMIT`, `ITEM_GET_LIMIT`, `RATE_LIMIT`, `TRANSACTIONS_LIMIT`, `TRANSACTIONS_REFRESH_LIMIT`, `TRANSACTIONS_SYNC_LIMIT`, `TRIAL_CONNECTION_LIMIT` |
| CONSENT | `ADDITIONAL_CONSENT_REQUIRED`, `ACCESS_NOT_GRANTED` (the 2.1 classifier wins over 5.7, Decision 24) |
| ITEM_BROKEN | `ITEM_LOGIN_REQUIRED`, `ITEM_NOT_FOUND`, `USER_PERMISSION_REVOKED`, `NO_ACCOUNTS` |
| NOT_SUPPORTED | `PRODUCTS_NOT_SUPPORTED`, `ITEM_NOT_SUPPORTED`, `INVALID_PRODUCT` |
| SYNC_LIMIT | `TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED` (Phase 1 writes no hold for it in any mode, R2; that is T13's handler, not this enum) |
| MUTATION | `TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION` |
| PERMANENT | everything else, including other `INSTITUTION_*` codes, a parsed code Plaid adds later, and no parsable code below HTTP 500 |

`.../` = `backend/src/main/java/com/investingapp/backend/`; tests mirror it under `backend/src/test/java/com/investingapp/backend/`. Anchors verified at HEAD 137998c: the `plaid` package does not exist (`ls .../plaid` fails); `backend/src/test/java/com/investingapp/backend/support/` holds only `RequiresCiDatabase.java`; `.../ops/` does not exist at HEAD and is created by T04, so the one edit there anchors on quoted text. Versions on the test classpath (from `./gradlew -q dependencies --configuration testCompileClasspath`): plaid-java 46.0.0, retrofit 2.11.0, okhttp 4.12.0, okio 3.6.0, Mockito 5.17.0, spring-tx 6.2.7. `ItemGetResponse` has `requestId(String)` and `getRequestId()` (javap-verified). okhttp 4.12 `ResponseBody.string()` is `source().use { ... }` (one `source()` call per read, sources jar line 186), which is what `errorBodyReadOnce` counts.

**Files:**
- Create: `.../plaid/PlaidErrorClass.java`, `.../plaid/PlaidApiError.java`, `.../plaid/PlaidCallResult.java`, `.../plaid/PlaidOutageBreaker.java`, `.../plaid/PlaidGateway.java`
- Modify: `.../ops/OpsAlerts.java` (T04's file; one constant inserted before the quoted line `    private OpsAlerts() {`)
- Test (create): `backend/src/test/java/com/investingapp/backend/plaid/PlaidErrorClassTest.java`, `PlaidApiErrorTest.java`, `PlaidCallResultTest.java`, `PlaidOutageBreakerTest.java`, `PlaidGatewayTest.java`; `backend/src/test/java/com/investingapp/backend/support/RecordingTransactionManager.java`, `RecordingTransactionManagerTest.java`
- T03's `PhaseOneMoneyInvarianceTest` already walks `plaid/**` (T03 contract), so no invariance edit is needed here.

**Interfaces:**
- Consumes: `OpsAlertService.raise(String type, String subjectId, String message, Map<String, Object> context)` and `OpsAlertService.GLOBAL_SUBJECT` (T04); `ops/OpsAlerts` (T04, constants class with a private constructor); the `PlaidApi` bean (`config/PlaidConfig`, `config/CiStubConfig` under ci).
- Produces:
  - `enum PlaidErrorClass {TRANSIENT, RATE_LIMIT, CONSENT, ITEM_BROKEN, NOT_SUPPORTED, SYNC_LIMIT, MUTATION, PERMANENT}`, `static PlaidErrorClass classify(String errorCode, int httpStatus)`, `Set<String> codes()`.
  - `record PlaidApiError(String errorType, String errorCode, String errorCodeReason, String requestId, int httpStatus, PlaidErrorClass errorClass)`, `static PlaidApiError fromBody(int httpStatus, String body)`, `static PlaidApiError transport()`, `TRANSPORT_ERROR_TYPE = "TRANSPORT"`.
  - `record PlaidCallResult<T>(T body, PlaidApiError error)` with `boolean ok()`, `static <T> PlaidCallResult<T> success(T)`, `static <T> PlaidCallResult<T> failure(PlaidApiError)`; exactly one of the two components is non-null.
  - `@Component PlaidGateway(PlaidApi, PlaidOutageBreaker, OpsAlertService)`: `<T> PlaidCallResult<T> call(String op, Call<T> call)`, `PlaidApi api()` (callers build the `Call` from it: `gateway.call("item_get", gateway.api().itemGet(request))`).
  - `@Component PlaidOutageBreaker`: `THRESHOLD = 5`, `boolean record(PlaidErrorClass errorClassOrNullOnSuccess)` (true only on the result that brings the count to 5), `boolean tripped()`, `void reset()`.
  - `OpsAlerts.PLAID_OUTAGE = "plaid.outage"`.
  - Test support: `RecordingTransactionManager extends AbstractPlatformTransactionManager` with public `volatile boolean open` and `AtomicInteger begins`, `commits`, `rollbacks`.

- [ ] **Step 1: Pre-checks (T04 landed, nothing to collide with)**

```
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1
ls backend/src/main/java/com/investingapp/backend/ops/
grep -n 'GLOBAL_SUBJECT\|public .* raise(String' backend/src/main/java/com/investingapp/backend/ops/OpsAlertService.java
grep -n 'private OpsAlerts() {' backend/src/main/java/com/investingapp/backend/ops/OpsAlerts.java
grep -n 'PLAID_OUTAGE' backend/src/main/java/com/investingapp/backend/ops/OpsAlerts.java
ls backend/src/main/java/com/investingapp/backend/plaid
```

Expected: the `ops` listing includes `OpsAlertService.java` and `OpsAlerts.java`; the first grep shows `GLOBAL_SUBJECT = "global"` and a 4-argument `raise(String type, String subjectId, String message, Map<String, Object> context)`; the second grep shows the private constructor line; the third prints nothing; the last `ls` fails with `No such file or directory`. If T04 has not landed (no `ops` directory), stop: this task depends on it. If `PLAID_OUTAGE` already exists, skip the `OpsAlerts` edit in Step 11 and keep the existing constant.

- [ ] **Step 2: Failing test for the error classes**

Create `backend/src/test/java/com/investingapp/backend/plaid/PlaidErrorClassTest.java`:

```java
package com.investingapp.backend.plaid;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 2.1 classes keyed on the exact error_code; the HTTP status matters only when no code parses. */
class PlaidErrorClassTest {

    @Test
    void classifiesOnExactCodeNeverHttpStatus() {
        for (PlaidErrorClass expected : PlaidErrorClass.values()) {
            for (String code : expected.codes()) {
                for (int status : new int[] {200, 400, 429, 500, 503}) {
                    assertThat(PlaidErrorClass.classify(code, status)).as(code + " at HTTP " + status).isEqualTo(expected);
                }
            }
        }
        // The spec 2.1 rows spelled out once, so a typo in the enum's own sets cannot hide behind the loop above.
        assertThat(PlaidErrorClass.classify("INSTITUTION_DOWN", 400)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("INSTITUTION_NOT_RESPONDING", 400)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("INSTITUTION_NOT_AVAILABLE", 400)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("INTERNAL_SERVER_ERROR", 500)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("PLANNED_MAINTENANCE", 503)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("PRODUCT_NOT_READY", 400)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("RATE_LIMIT", 429)).isEqualTo(PlaidErrorClass.RATE_LIMIT);
        assertThat(PlaidErrorClass.classify("TRANSACTIONS_SYNC_LIMIT", 429)).isEqualTo(PlaidErrorClass.RATE_LIMIT);
        assertThat(PlaidErrorClass.classify("ITEM_GET_LIMIT", 429)).isEqualTo(PlaidErrorClass.RATE_LIMIT);
        assertThat(PlaidErrorClass.classify("ADDITIONAL_CONSENT_REQUIRED", 400)).isEqualTo(PlaidErrorClass.CONSENT);
        assertThat(PlaidErrorClass.classify("ACCESS_NOT_GRANTED", 400)).isEqualTo(PlaidErrorClass.CONSENT);
        assertThat(PlaidErrorClass.classify("ITEM_LOGIN_REQUIRED", 400)).isEqualTo(PlaidErrorClass.ITEM_BROKEN);
        assertThat(PlaidErrorClass.classify("ITEM_NOT_FOUND", 400)).isEqualTo(PlaidErrorClass.ITEM_BROKEN);
        assertThat(PlaidErrorClass.classify("USER_PERMISSION_REVOKED", 400)).isEqualTo(PlaidErrorClass.ITEM_BROKEN);
        assertThat(PlaidErrorClass.classify("NO_ACCOUNTS", 400)).isEqualTo(PlaidErrorClass.ITEM_BROKEN);
        assertThat(PlaidErrorClass.classify("PRODUCTS_NOT_SUPPORTED", 400)).isEqualTo(PlaidErrorClass.NOT_SUPPORTED);
        assertThat(PlaidErrorClass.classify("ITEM_NOT_SUPPORTED", 400)).isEqualTo(PlaidErrorClass.NOT_SUPPORTED);
        assertThat(PlaidErrorClass.classify("INVALID_PRODUCT", 400)).isEqualTo(PlaidErrorClass.NOT_SUPPORTED);
        assertThat(PlaidErrorClass.classify("TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED", 400)).isEqualTo(PlaidErrorClass.SYNC_LIMIT);
        assertThat(PlaidErrorClass.classify("TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION", 400)).isEqualTo(PlaidErrorClass.MUTATION);
        // A parsed code decides even at 5xx: a known one keeps its class, an unknown one is PERMANENT.
        assertThat(PlaidErrorClass.classify("ITEM_LOGIN_REQUIRED", 500)).isEqualTo(PlaidErrorClass.ITEM_BROKEN);
        assertThat(PlaidErrorClass.classify("A_CODE_PLAID_ADDS_LATER", 500)).isEqualTo(PlaidErrorClass.PERMANENT);
        // Exact means exact: Plaid sends upper snake case, so nothing else matches.
        assertThat(PlaidErrorClass.classify("item_login_required", 400)).isEqualTo(PlaidErrorClass.PERMANENT);
    }

    @Test
    void unparsable5xxIsTransient() {
        assertThat(PlaidErrorClass.classify(null, 500)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify(null, 502)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify(null, 503)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("", 503)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("   ", 504)).isEqualTo(PlaidErrorClass.TRANSIENT);
        // Below 500 a missing code is PERMANENT, 429 included: the contract keys the fallback on 5xx only.
        assertThat(PlaidErrorClass.classify(null, 400)).isEqualTo(PlaidErrorClass.PERMANENT);
        assertThat(PlaidErrorClass.classify(null, 429)).isEqualTo(PlaidErrorClass.PERMANENT);
        assertThat(PlaidErrorClass.classify(null, 200)).isEqualTo(PlaidErrorClass.PERMANENT);
    }

    @Test
    void unknownInstitutionCodeIsPermanent() {
        assertThat(PlaidErrorClass.classify("INSTITUTION_NO_LONGER_SUPPORTED", 400)).isEqualTo(PlaidErrorClass.PERMANENT);
        assertThat(PlaidErrorClass.classify("INSTITUTION_NOT_FOUND", 400)).isEqualTo(PlaidErrorClass.PERMANENT);
        assertThat(PlaidErrorClass.classify("INSTITUTION_REGISTRATION_REQUIRED", 400)).isEqualTo(PlaidErrorClass.PERMANENT);
        // The INSTITUTION_ prefix decides nothing: these two are listed.
        assertThat(PlaidErrorClass.classify("INSTITUTION_DOWN", 400)).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidErrorClass.classify("INSTITUTION_RATE_LIMIT", 429)).isEqualTo(PlaidErrorClass.RATE_LIMIT);
    }

    @Test
    void everyCodeBelongsToExactlyOneClass() {
        Set<String> seen = new HashSet<>();
        for (PlaidErrorClass errorClass : PlaidErrorClass.values()) {
            for (String code : errorClass.codes()) {
                assertThat(seen.add(code)).as(code + " listed twice").isTrue();
            }
        }
        assertThat(PlaidErrorClass.PERMANENT.codes()).isEmpty();
        assertThat(seen).hasSize(6 + 17 + 2 + 4 + 3 + 1 + 1);
    }
}
```

Run:

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.plaid.PlaidErrorClassTest' --console=plain
```

Expected: `> Task :compileTestJava FAILED` with `error: cannot find symbol` and `symbol:   class PlaidErrorClass`; `BUILD FAILED`.

- [ ] **Step 3: PlaidErrorClass**

Create `backend/src/main/java/com/investingapp/backend/plaid/PlaidErrorClass.java`:

```java
package com.investingapp.backend.plaid;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Spec 2.1 error classes. Classification is on Plaid's exact error_code, never the HTTP status and
 * never error_type, with one fallback from spec 11: an answer with no parsable error_code and an
 * HTTP status of 500 or more is TRANSIENT. A parsed code that is not listed is PERMANENT (stop, log;
 * the nightly reconcile tries the Item again), which is also where a code Plaid adds later lands.
 *
 * <p>Handling per class belongs to the callers (T08, T13, T14, T15), not to this enum.
 */
public enum PlaidErrorClass {

    /** Re-queue once after 5 minutes, then the nightly reconcile. Counts toward the outage breaker. */
    TRANSIENT(Set.of("INSTITUTION_DOWN", "INSTITUTION_NOT_RESPONDING", "INSTITUTION_NOT_AVAILABLE",
            "INTERNAL_SERVER_ERROR", "PLANNED_MAINTENANCE", "PRODUCT_NOT_READY")),

    /**
     * Retry next minute. Counts toward the outage breaker. Every code Plaid documents under
     * error_type RATE_LIMIT_EXCEEDED (plaid.com/docs/errors/rate-limit-exceeded, read 2026-11-04).
     */
    RATE_LIMIT(Set.of("ACCOUNTS_LIMIT", "ACCOUNTS_BALANCE_GET_LIMIT", "AUTH_LIMIT", "BALANCE_LIMIT",
            "CREDITS_EXHAUSTED", "IDENTITY_LIMIT", "INSTITUTIONS_GET_LIMIT", "INSTITUTIONS_GET_BY_ID_LIMIT",
            "INSTITUTION_RATE_LIMIT", "INVESTMENTS_HOLDINGS_GET_LIMIT", "INVESTMENTS_LIMIT", "ITEM_GET_LIMIT",
            "RATE_LIMIT", "TRANSACTIONS_LIMIT", "TRANSACTIONS_REFRESH_LIMIT", "TRANSACTIONS_SYNC_LIMIT",
            "TRIAL_CONNECTION_LIMIT")),

    /** Consent flag false, NEEDS_CONSENT, no retry until update mode (T13 onConsentRequired). */
    CONSENT(Set.of("ADDITIONAL_CONSENT_REQUIRED", "ACCESS_NOT_GRANTED")),

    /** Item-state handler, spec 5.7 (T13). */
    ITEM_BROKEN(Set.of("ITEM_LOGIN_REQUIRED", "ITEM_NOT_FOUND", "USER_PERMISSION_REVOKED", "NO_ACCOUNTS")),

    /** plaid_item_status NOT_SUPPORTED, UNDETECTABLE, no hold, no reconnect card (T13 onNotSupported). */
    NOT_SUPPORTED(Set.of("PRODUCTS_NOT_SUPPORTED", "ITEM_NOT_SUPPORTED", "INVALID_PRODUCT")),

    /** Never retried. Phase 1 writes no hold for it in any mode (R2); T13 onSyncLimit owns the rest. */
    SYNC_LIMIT(Set.of("TRANSACTIONS_SYNC_ITEM_LIMIT_EXCEEDED")),

    /** /transactions/sync restarts from the first-page cursor, at most 3 times (T08). */
    MUTATION(Set.of("TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION")),

    /** Everything else. Stop and log. */
    PERMANENT(Set.of());

    private static final Map<String, PlaidErrorClass> BY_CODE = new HashMap<>();

    static {
        for (PlaidErrorClass errorClass : values()) {
            for (String code : errorClass.codes) {
                BY_CODE.put(code, errorClass);
            }
        }
    }

    private final Set<String> codes;

    PlaidErrorClass(Set<String> codes) {
        this.codes = codes;
    }

    /** The exact error_code values this class owns (empty for PERMANENT). */
    public Set<String> codes() {
        return codes;
    }

    /**
     * @param errorCode  Plaid's error_code as parsed from the body, or null when none parsed
     * @param httpStatus the HTTP status, used only when errorCode is null or blank
     */
    public static PlaidErrorClass classify(String errorCode, int httpStatus) {
        if (errorCode == null || errorCode.isBlank()) {
            return httpStatus >= 500 ? TRANSIENT : PERMANENT;
        }
        return BY_CODE.getOrDefault(errorCode, PERMANENT);
    }
}
```

Run the Step 2 command, then read the counts from the XML (the console does not total them):

```
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1/backend && grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.plaid.PlaidErrorClassTest.xml
```

Expected: `BUILD SUCCESSFUL`; `tests="4" skipped="0" failures="0" errors="0"`.

- [ ] **Step 4: Failing test for the parsed error**

Create `backend/src/test/java/com/investingapp/backend/plaid/PlaidApiErrorTest.java`:

```java
package com.investingapp.backend.plaid;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A Plaid error body reduced to codes and ids; free text is never kept. */
class PlaidApiErrorTest {

    private static final String LOGIN_BODY = "{\"error_type\":\"ITEM_ERROR\",\"error_code\":\"ITEM_LOGIN_REQUIRED\","
            + "\"error_code_reason\":\"OAUTH_INVALID_TOKEN\","
            + "\"error_message\":\"the login details of this item have changed at Bank of Sentinel\","
            + "\"display_message\":null,\"request_id\":\"req-123\"}";

    @Test
    void parsesEveryFieldFromPlaidBody() {
        PlaidApiError error = PlaidApiError.fromBody(400, LOGIN_BODY);

        assertThat(error).isEqualTo(new PlaidApiError("ITEM_ERROR", "ITEM_LOGIN_REQUIRED", "OAUTH_INVALID_TOKEN",
                "req-123", 400, PlaidErrorClass.ITEM_BROKEN));
    }

    @Test
    void keepsNoPlaidFreeText() {
        assertThat(PlaidApiError.fromBody(400, LOGIN_BODY).toString())
                .doesNotContain("login details")
                .doesNotContain("Sentinel");
    }

    @Test
    void unparsableBodyKeepsTheStatusForClassification() {
        assertThat(PlaidApiError.fromBody(503, "<html>Service Unavailable</html>"))
                .isEqualTo(new PlaidApiError(null, null, null, null, 503, PlaidErrorClass.TRANSIENT));
        assertThat(PlaidApiError.fromBody(400, "not json"))
                .isEqualTo(new PlaidApiError(null, null, null, null, 400, PlaidErrorClass.PERMANENT));
        assertThat(PlaidApiError.fromBody(502, null).errorClass()).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidApiError.fromBody(500, "").errorClass()).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidApiError.fromBody(500, "{\"error_code\":null}").errorClass()).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidApiError.fromBody(500, "[]").errorClass()).isEqualTo(PlaidErrorClass.TRANSIENT);
        assertThat(PlaidApiError.fromBody(500, "{\"error_code\":42}").errorCode()).isNull();
    }

    @Test
    void transportErrorIsTransientWithNoCode() {
        assertThat(PlaidApiError.transport()).isEqualTo(new PlaidApiError(
                PlaidApiError.TRANSPORT_ERROR_TYPE, null, null, null, 0, PlaidErrorClass.TRANSIENT));
        assertThat(PlaidApiError.TRANSPORT_ERROR_TYPE).isEqualTo("TRANSPORT");
    }
}
```

Run:

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.plaid.PlaidApiErrorTest' --console=plain
```

Expected: `> Task :compileTestJava FAILED` with `error: cannot find symbol` and `symbol:   class PlaidApiError`; `BUILD FAILED`.

- [ ] **Step 5: PlaidApiError**

Create `backend/src/main/java/com/investingapp/backend/plaid/PlaidApiError.java`:

```java
package com.investingapp.backend.plaid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * One non-2xx Plaid answer, or a transport failure, reduced to what FRED branches and logs on.
 *
 * <p>error_message and display_message are deliberately not kept: they are free text (spec 12.3)
 * and can name an institution. Every component here is a Plaid code, a Plaid request id or a
 * number. The body is parsed with Jackson JsonNode (the PlaidWebhookInboxService pattern), not
 * with Jackson into the SDK's PlaidError, whose Gson names and enum adapters Jackson cannot map.
 *
 * @param errorType       Plaid error_type, or {@link #TRANSPORT_ERROR_TYPE} for an IOException, or null
 * @param errorCode       Plaid error_code, or null when none parsed
 * @param errorCodeReason Plaid error_code_reason, or null
 * @param requestId       Plaid request_id from the error body, or null
 * @param httpStatus      the HTTP status, 0 for a transport failure
 * @param errorClass      {@link PlaidErrorClass#classify(String, int)} of the two above
 */
public record PlaidApiError(String errorType, String errorCode, String errorCodeReason, String requestId,
        int httpStatus, PlaidErrorClass errorClass) {

    /** errorType of the synthetic error that stands for an IOException (no HTTP answer at all). */
    public static final String TRANSPORT_ERROR_TYPE = "TRANSPORT";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Parses a Plaid error body that the caller has already read once; a missing or broken body keeps the status. */
    public static PlaidApiError fromBody(int httpStatus, String body) {
        JsonNode root = parse(body);
        String errorCode = text(root, "error_code");
        return new PlaidApiError(text(root, "error_type"), errorCode, text(root, "error_code_reason"),
                text(root, "request_id"), httpStatus, PlaidErrorClass.classify(errorCode, httpStatus));
    }

    /** The IOException case: no answer, so no code, and TRANSIENT (spec 2.1, contract T06). */
    public static PlaidApiError transport() {
        return new PlaidApiError(TRANSPORT_ERROR_TYPE, null, null, null, 0, PlaidErrorClass.TRANSIENT);
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            return root == null ? MissingNode.getInstance() : root;
        } catch (JsonProcessingException e) {
            return MissingNode.getInstance();
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
```

Run the Step 4 command, then:

```
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1/backend && grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.plaid.PlaidApiErrorTest.xml
```

Expected: `BUILD SUCCESSFUL`; `tests="4" skipped="0" failures="0" errors="0"`.

- [ ] **Step 6: Failing tests for the call result and the breaker**

Create `backend/src/test/java/com/investingapp/backend/plaid/PlaidCallResultTest.java`:

```java
package com.investingapp.backend.plaid;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaidCallResultTest {

    @Test
    void okOnlyWhenErrorIsNull() {
        PlaidCallResult<String> success = PlaidCallResult.success("body");
        assertThat(success.ok()).isTrue();
        assertThat(success.body()).isEqualTo("body");
        assertThat(success.error()).isNull();

        PlaidApiError error = PlaidApiError.transport();
        PlaidCallResult<String> failure = PlaidCallResult.failure(error);
        assertThat(failure.ok()).isFalse();
        assertThat(failure.body()).isNull();
        assertThat(failure.error()).isSameAs(error);
    }

    @Test
    void exactlyOneOfBodyAndError() {
        assertThatThrownBy(() -> new PlaidCallResult<>(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlaidCallResult<>("body", PlaidApiError.transport()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

Create `backend/src/test/java/com/investingapp/backend/plaid/PlaidOutageBreakerTest.java`:

```java
package com.investingapp.backend.plaid;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Five consecutive TRANSIENT or RATE_LIMIT results trip it; any other result resets it (spec 2.1, 11). */
class PlaidOutageBreakerTest {

    @Test
    void tripsAfterFiveConsecutiveTransientOrRateLimit() {
        PlaidOutageBreaker breaker = new PlaidOutageBreaker();

        assertThat(breaker.record(PlaidErrorClass.TRANSIENT)).isFalse();
        assertThat(breaker.record(PlaidErrorClass.RATE_LIMIT)).isFalse();
        assertThat(breaker.record(PlaidErrorClass.TRANSIENT)).isFalse();
        assertThat(breaker.record(PlaidErrorClass.RATE_LIMIT)).isFalse();
        assertThat(breaker.tripped()).isFalse();

        assertThat(breaker.record(PlaidErrorClass.TRANSIENT)).as("the fifth trips").isTrue();
        assertThat(breaker.tripped()).isTrue();

        assertThat(breaker.record(PlaidErrorClass.TRANSIENT)).as("only the fifth reports the trip").isFalse();
        assertThat(breaker.tripped()).isTrue();
        assertThat(PlaidOutageBreaker.THRESHOLD).isEqualTo(5);
    }

    @Test
    void anyOtherResultResetsTheCount() {
        List<PlaidErrorClass> others = Arrays.asList(PlaidErrorClass.CONSENT, PlaidErrorClass.ITEM_BROKEN,
                PlaidErrorClass.NOT_SUPPORTED, PlaidErrorClass.SYNC_LIMIT, PlaidErrorClass.MUTATION,
                PlaidErrorClass.PERMANENT, null);
        for (PlaidErrorClass other : others) {
            PlaidOutageBreaker breaker = new PlaidOutageBreaker();
            for (int i = 0; i < 4; i++) {
                breaker.record(PlaidErrorClass.TRANSIENT);
            }
            assertThat(breaker.record(other)).as(String.valueOf(other)).isFalse();
            for (int i = 0; i < 4; i++) {
                assertThat(breaker.record(PlaidErrorClass.RATE_LIMIT)).as(String.valueOf(other)).isFalse();
            }
            assertThat(breaker.tripped()).as(other + " resets, so 4 + 4 never trips").isFalse();
        }
    }

    @Test
    void resetClearsATrippedBreaker() {
        PlaidOutageBreaker breaker = new PlaidOutageBreaker();
        for (int i = 0; i < 5; i++) {
            breaker.record(PlaidErrorClass.TRANSIENT);
        }
        assertThat(breaker.tripped()).isTrue();

        breaker.reset();

        assertThat(breaker.tripped()).isFalse();
        for (int i = 0; i < 4; i++) {
            assertThat(breaker.record(PlaidErrorClass.TRANSIENT)).isFalse();
        }
        assertThat(breaker.record(PlaidErrorClass.TRANSIENT)).as("a new run can trip again").isTrue();
    }

    @Test
    void concurrentRecordsTripExactlyOnce() throws Exception {
        PlaidOutageBreaker breaker = new PlaidOutageBreaker();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger trips = new AtomicInteger();
        List<Future<Object>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 10; i++) {
                    if (breaker.record(PlaidErrorClass.TRANSIENT)) {
                        trips.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<Object> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(trips.get()).isEqualTo(1);
        assertThat(breaker.tripped()).isTrue();
    }
}
```

Run each class:

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.plaid.PlaidCallResultTest' --console=plain
cd backend && ./gradlew test --tests 'com.investingapp.backend.plaid.PlaidOutageBreakerTest' --console=plain
```

Expected, both: `> Task :compileTestJava FAILED` with `error: cannot find symbol`, naming `class PlaidCallResult` and `class PlaidOutageBreaker`; `BUILD FAILED`.

- [ ] **Step 7: PlaidCallResult and PlaidOutageBreaker**

Create `backend/src/main/java/com/investingapp/backend/plaid/PlaidCallResult.java`:

```java
package com.investingapp.backend.plaid;

/**
 * What {@link PlaidGateway#call} returns: the response body on a 2xx with a body, otherwise the
 * classified error. Exactly one of the two is non-null.
 */
public record PlaidCallResult<T>(T body, PlaidApiError error) {

    public PlaidCallResult {
        if ((body == null) == (error == null)) {
            throw new IllegalArgumentException("a Plaid call result carries exactly one of body and error");
        }
    }

    public static <T> PlaidCallResult<T> success(T body) {
        return new PlaidCallResult<>(body, null);
    }

    public static <T> PlaidCallResult<T> failure(PlaidApiError error) {
        return new PlaidCallResult<>(null, error);
    }

    public boolean ok() {
        return error == null;
    }
}
```

Create `backend/src/main/java/com/investingapp/backend/plaid/PlaidOutageBreaker.java`:

```java
package com.investingapp.backend.plaid;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts consecutive TRANSIENT or RATE_LIMIT results across every Plaid call in this JVM (spec 2.1:
 * five in a row abort a run and raise plaid.outage). Any other result, a success included, resets
 * the count to 0. {@link PlaidGateway} feeds it and raises the alert on the tripping result; the
 * reconcile (T15) calls {@link #reset()} at the start of each run and stops dispatching while
 * {@link #tripped()} is true.
 */
@Component
public class PlaidOutageBreaker {

    public static final int THRESHOLD = 5;

    private final AtomicInteger consecutive = new AtomicInteger();

    /**
     * @param errorClassOrNullOnSuccess the call's class, or null for a success
     * @return true only for the result that brings the count to {@link #THRESHOLD}, so exactly one
     *         caller raises the alert however many threads record at once
     */
    public boolean record(PlaidErrorClass errorClassOrNullOnSuccess) {
        if (errorClassOrNullOnSuccess == PlaidErrorClass.TRANSIENT || errorClassOrNullOnSuccess == PlaidErrorClass.RATE_LIMIT) {
            return consecutive.updateAndGet(n -> n < Integer.MAX_VALUE ? n + 1 : n) == THRESHOLD;
        }
        consecutive.set(0);
        return false;
    }

    public boolean tripped() {
        return consecutive.get() >= THRESHOLD;
    }

    /** Clears the count and raises nothing. */
    public void reset() {
        consecutive.set(0);
    }
}
```

Run the two classes in one Gradle invocation, then read both XML files:

```
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1/backend && ./gradlew test --tests 'com.investingapp.backend.plaid.PlaidCallResultTest' --tests 'com.investingapp.backend.plaid.PlaidOutageBreakerTest' --console=plain
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1/backend && grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.plaid.PlaidCallResultTest.xml build/test-results/test/TEST-com.investingapp.backend.plaid.PlaidOutageBreakerTest.xml
```

(Gradle deletes every old `TEST-*.xml` at the start of each test run, so two separate `--tests` runs would leave only the second class's file.) Expected: `BUILD SUCCESSFUL`; `tests="2" skipped="0" failures="0" errors="0"` for `PlaidCallResultTest` and `tests="4" skipped="0" failures="0" errors="0"` for `PlaidOutageBreakerTest`.

- [ ] **Step 8: Failing test for RecordingTransactionManager**

A Mockito `PlatformTransactionManager` binds no synchronization and a `new Service()` has no proxy, so an "outside a transaction" test built on them passes whatever the code does. The recorder is a real `AbstractPlatformTransactionManager` with no database, so `TransactionTemplate` really begins, binds synchronization and sets `isActualTransactionActive()`.

Create `backend/src/test/java/com/investingapp/backend/support/RecordingTransactionManagerTest.java`:

```java
package com.investingapp.backend.support;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordingTransactionManagerTest {

    private final RecordingTransactionManager tx = new RecordingTransactionManager();

    @Test
    void openOnlyWhileTemplateRuns() {
        assertThat(tx.open).isFalse();

        new TransactionTemplate(tx).executeWithoutResult(status -> {
            assertThat(tx.open).isTrue();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
        });

        assertThat(tx.open).isFalse();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(tx.begins.get()).isEqualTo(1);
        assertThat(tx.commits.get()).isEqualTo(1);
        assertThat(tx.rollbacks.get()).isZero();
    }

    @Test
    void rollbackClosesIt() {
        assertThatThrownBy(() -> new TransactionTemplate(tx).executeWithoutResult(status -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(tx.open).isFalse();
        assertThat(tx.rollbacks.get()).isEqualTo(1);
        assertThat(tx.commits.get()).isZero();
    }

    @Test
    void requiresNewNestsAndOuterStaysOpen() {
        TransactionTemplate inner = new TransactionTemplate(tx);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        new TransactionTemplate(tx).executeWithoutResult(outer -> {
            inner.executeWithoutResult(status -> assertThat(tx.open).isTrue());
            assertThat(tx.open).as("the outer transaction is still open after the inner commits").isTrue();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        });

        assertThat(tx.open).isFalse();
        assertThat(tx.begins.get()).isEqualTo(2);
        assertThat(tx.commits.get()).isEqualTo(2);
    }

    @Test
    void nestedRequiredParticipates() {
        TransactionTemplate template = new TransactionTemplate(tx);

        template.executeWithoutResult(outer ->
                template.executeWithoutResult(inner -> assertThat(tx.open).isTrue()));

        assertThat(tx.begins.get()).isEqualTo(1);
        assertThat(tx.commits.get()).isEqualTo(1);
        assertThat(tx.open).isFalse();
    }
}
```

Run:

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.support.RecordingTransactionManagerTest' --console=plain
```

Expected: `> Task :compileTestJava FAILED` with `error: cannot find symbol` and `symbol:   class RecordingTransactionManager`; `BUILD FAILED`.

- [ ] **Step 9: RecordingTransactionManager**

Create `backend/src/test/java/com/investingapp/backend/support/RecordingTransactionManager.java`:

```java
package com.investingapp.backend.support;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real transaction manager with no database, for proving that code runs inside or outside a
 * transaction. TransactionTemplate over it begins a transaction, binds synchronization and makes
 * TransactionSynchronizationManager.isActualTransactionActive() true, which a Mockito
 * PlatformTransactionManager never does.
 *
 * <p>Usage (T08, T10, T14, T16): build the service with {@code new TransactionTemplate(tx)} or pass
 * {@code tx} where it takes a PlatformTransactionManager, and make the mocked
 * {@code Call.execute()} answer record {@code tx.open}; assert it was false and that
 * {@code tx.begins} is above 0, so the test proves both that a transaction ran and that the Plaid
 * call was not inside it.
 *
 * <p>REQUIRED participates in an open transaction, REQUIRES_NEW suspends and nests (open stays
 * true until the outer one ends), NOT_SUPPORTED suspends (open stays true while the outer one is
 * suspended, though isActualTransactionActive() is false there). Rollback-only marks set by a
 * participating scope are not tracked.
 */
public class RecordingTransactionManager extends AbstractPlatformTransactionManager {

    /** True while at least one transaction begun here has not yet committed or rolled back. */
    public volatile boolean open;

    public final AtomicInteger begins = new AtomicInteger();
    public final AtomicInteger commits = new AtomicInteger();
    public final AtomicInteger rollbacks = new AtomicInteger();

    private final AtomicInteger openCount = new AtomicInteger();
    private final ThreadLocal<Object> current = new ThreadLocal<>();

    private static final class TxObject {
        private Object marker;
    }

    @Override
    protected Object doGetTransaction() {
        TxObject transaction = new TxObject();
        transaction.marker = current.get();
        return transaction;
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        return ((TxObject) transaction).marker != null;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        Object marker = new Object();
        ((TxObject) transaction).marker = marker;
        current.set(marker);
        begins.incrementAndGet();
        openCount.incrementAndGet();
        open = true;
    }

    @Override
    protected Object doSuspend(Object transaction) {
        Object suspended = current.get();
        ((TxObject) transaction).marker = null;
        current.remove();
        return suspended;
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) {
        current.set(suspendedResources);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        commits.incrementAndGet();
        close();
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        rollbacks.incrementAndGet();
        close();
    }

    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) {
        // A participating scope failed; the outer scope's own commit or rollback is what tests read.
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        current.remove();
    }

    private void close() {
        open = openCount.decrementAndGet() > 0;
    }
}
```

Run the Step 8 command, then:

```
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1/backend && grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.support.RecordingTransactionManagerTest.xml
```

Expected: `BUILD SUCCESSFUL`; `tests="4" skipped="0" failures="0" errors="0"`.

- [ ] **Step 10: Failing test for PlaidGateway**

Create `backend/src/test/java/com/investingapp/backend/plaid/PlaidGatewayTest.java`:

```java
package com.investingapp.backend.plaid;

import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.investingapp.backend.support.RecordingTransactionManager;
import com.plaid.client.model.ItemGetResponse;
import com.plaid.client.model.TransactionsSyncResponse;
import com.plaid.client.request.PlaidApi;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PlaidGateway: typed result, classification on the exact code, one read of the error body,
 * IOException as TRANSIENT, the five-strike outage alert, and no call inside a transaction.
 */
class PlaidGatewayTest {

    private static final MediaType JSON = MediaType.parse("application/json");

    private PlaidOutageBreaker breaker;
    private OpsAlertService opsAlertService;
    private PlaidGateway gateway;

    @BeforeEach
    void setUp() {
        breaker = new PlaidOutageBreaker();
        opsAlertService = mock(OpsAlertService.class);
        gateway = new PlaidGateway(mock(PlaidApi.class), breaker, opsAlertService);
    }

    @SuppressWarnings("unchecked")
    private static <T> Call<T> answering(Response<T> response) {
        Call<T> call = mock(Call.class);
        try {
            when(call.execute()).thenReturn(response);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return call;
    }

    /** error_type is ITEM_ERROR for every code on purpose: classification must ignore it. */
    private static <T> Call<T> plaidError(int status, String errorCode) {
        return answering(Response.error(status, ResponseBody.create(
                "{\"error_type\":\"ITEM_ERROR\",\"error_code\":\"" + errorCode + "\","
                        + "\"error_message\":\"Bank of Sentinel is down\",\"request_id\":\"req-" + errorCode + "\"}",
                JSON)));
    }

    @SuppressWarnings("unchecked")
    private static <T> Call<T> transportFailure() {
        Call<T> call = mock(Call.class);
        try {
            when(call.execute()).thenThrow(new IOException("connection reset"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return call;
    }

    /** okhttp's ResponseBody.string() opens source() once per read, so source() calls count reads. */
    private static final class CountingBody extends ResponseBody {
        private final String json;
        private int sources;

        CountingBody(String json) {
            this.json = json;
        }

        @Override
        public MediaType contentType() {
            return JSON;
        }

        @Override
        public long contentLength() {
            return json.length();
        }

        @Override
        public BufferedSource source() {
            sources++;
            return new Buffer().writeUtf8(json);
        }
    }

    @Test
    void successReturnsTheBodyAndResetsTheBreaker() {
        for (int i = 0; i < 4; i++) {
            breaker.record(PlaidErrorClass.TRANSIENT);
        }
        ItemGetResponse body = new ItemGetResponse().requestId("req-ok-1");

        PlaidCallResult<ItemGetResponse> result = gateway.call("item_get", answering(Response.success(body)));

        assertThat(result.ok()).isTrue();
        assertThat(result.body()).isSameAs(body);
        assertThat(result.error()).isNull();
        for (int i = 0; i < 4; i++) {
            assertThat(breaker.record(PlaidErrorClass.TRANSIENT)).as("the success reset the count").isFalse();
        }
        assertThat(breaker.tripped()).isFalse();
        verifyNoInteractions(opsAlertService);
    }

    @Test
    void classifiesOnExactCodeNeverHttpStatus() {
        PlaidCallResult<TransactionsSyncResponse> broken = gateway.call("transactions_sync", plaidError(500, "ITEM_LOGIN_REQUIRED"));

        assertThat(broken.ok()).isFalse();
        assertThat(broken.error()).isEqualTo(new PlaidApiError("ITEM_ERROR", "ITEM_LOGIN_REQUIRED", null,
                "req-ITEM_LOGIN_REQUIRED", 500, PlaidErrorClass.ITEM_BROKEN));
        PlaidCallResult<Object> down = gateway.call("transactions_sync", plaidError(400, "INSTITUTION_DOWN"));
        assertThat(down.error().errorClass()).isEqualTo(PlaidErrorClass.TRANSIENT);
        PlaidCallResult<Object> mutation = gateway.call("transactions_sync", plaidError(400, "TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION"));
        assertThat(mutation.error().errorClass()).isEqualTo(PlaidErrorClass.MUTATION);
        PlaidCallResult<Object> gone = gateway.call("transactions_sync", plaidError(429, "INSTITUTION_NO_LONGER_SUPPORTED"));
        assertThat(gone.error().errorClass()).isEqualTo(PlaidErrorClass.PERMANENT);
    }

    @Test
    void unparsable5xxIsTransient() {
        PlaidCallResult<Object> result = gateway.call("transactions_sync", answering(Response.error(503,
                ResponseBody.create("<html>Service Unavailable</html>", MediaType.parse("text/html")))));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo(new PlaidApiError(null, null, null, null, 503, PlaidErrorClass.TRANSIENT));
    }

    @Test
    void errorBodyReadOnce() {
        CountingBody body = new CountingBody("{\"error_type\":\"TRANSACTIONS_ERROR\","
                + "\"error_code\":\"TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION\",\"request_id\":\"req-m\"}");

        PlaidCallResult<Object> result = gateway.call("transactions_sync", answering(Response.error(400, body)));

        assertThat(result.error().errorCode()).isEqualTo("TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION");
        assertThat(result.error().requestId()).isEqualTo("req-m");
        assertThat(body.sources).as("errorBody().string() called once").isEqualTo(1);
    }

    @Test
    void ioExceptionIsTransient() {
        PlaidCallResult<Object> result = gateway.call("item_get", transportFailure());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo(PlaidApiError.transport());

        for (int i = 0; i < 4; i++) {
            gateway.call("item_get", transportFailure());
        }
        verify(opsAlertService, times(1)).raise(eq(OpsAlerts.PLAID_OUTAGE), eq(OpsAlertService.GLOBAL_SUBJECT), anyString(), anyMap());
    }

    @Test
    void successWithEmptyBodyIsAPermanentError() {
        PlaidCallResult<ItemGetResponse> result = gateway.call("item_get", answering(Response.success((ItemGetResponse) null)));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo(new PlaidApiError(null, null, null, null, 200, PlaidErrorClass.PERMANENT));
    }

    @Test
    void tripsAfterFiveConsecutiveTransientOrRateLimit() {
        for (int i = 0; i < 3; i++) {
            gateway.call("transactions_sync", plaidError(400, "INSTITUTION_DOWN"));
        }
        gateway.call("transactions_sync", plaidError(429, "RATE_LIMIT"));
        verifyNoInteractions(opsAlertService);
        assertThat(breaker.tripped()).isFalse();

        gateway.call("transactions_sync", plaidError(429, "TRANSACTIONS_SYNC_LIMIT"));
        assertThat(breaker.tripped()).isTrue();
        gateway.call("transactions_sync", plaidError(400, "INSTITUTION_DOWN"));

        verify(opsAlertService, times(1)).raise(eq(OpsAlerts.PLAID_OUTAGE), eq(OpsAlertService.GLOBAL_SUBJECT), anyString(), anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void outageAlertCarriesCodesNeverPlaidText() {
        for (int i = 0; i < 5; i++) {
            gateway.call("transactions_sync", plaidError(400, "INSTITUTION_DOWN"));
        }
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);

        verify(opsAlertService).raise(eq(OpsAlerts.PLAID_OUTAGE), eq(OpsAlertService.GLOBAL_SUBJECT), message.capture(), context.capture());

        assertThat(message.getValue()).contains("op=transactions_sync").contains("code=INSTITUTION_DOWN").doesNotContain("Sentinel");
        assertThat(context.getValue()).containsExactly(entry("op", "transactions_sync"), entry("code", "INSTITUTION_DOWN"),
                entry("http_status", 400), entry("consecutive", 5));
        assertThat(context.getValue().toString()).doesNotContain("Sentinel");
    }

    @Test
    void refusesToCallInsideATransaction() throws Exception {
        RecordingTransactionManager tx = new RecordingTransactionManager();
        Call<ItemGetResponse> call = answering(Response.success(new ItemGetResponse().requestId("req-never")));

        new TransactionTemplate(tx).executeWithoutResult(status ->
                assertThatThrownBy(() -> gateway.call("item_get", call))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("op=item_get"));

        assertThat(tx.begins.get()).isEqualTo(1);
        verify(call, never()).execute();
        verifyNoInteractions(opsAlertService);
        assertThat(breaker.tripped()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void runsOnTheCallersThreadOutsideAnyTransaction() throws Exception {
        RecordingTransactionManager tx = new RecordingTransactionManager();
        new TransactionTemplate(tx).executeWithoutResult(status -> { });
        Thread caller = Thread.currentThread();
        AtomicReference<String> seen = new AtomicReference<>();
        Call<ItemGetResponse> call = mock(Call.class);
        when(call.execute()).thenAnswer(invocation -> {
            seen.set((Thread.currentThread() == caller) + " " + tx.open + " "
                    + TransactionSynchronizationManager.isActualTransactionActive());
            return Response.success(new ItemGetResponse().requestId("req-thread"));
        });

        assertThat(gateway.call("item_get", call).ok()).isTrue();

        assertThat(seen.get()).as("same thread, recorder closed, no actual transaction").isEqualTo("true false false");
        assertThat(tx.begins.get()).as("the recorder did run a transaction before the call").isEqualTo(1);
    }

    @Test
    void requestIdReadFromAnyPlaidResponse() {
        assertThat(PlaidGateway.requestIdOf(new ItemGetResponse().requestId("req-7"))).isEqualTo("req-7");
        assertThat(PlaidGateway.requestIdOf(new ItemGetResponse())).isEqualTo("-");
        assertThat(PlaidGateway.requestIdOf("a body with no request id")).isEqualTo("-");
    }
}
```

Run:

```
cd backend && ./gradlew test --tests 'com.investingapp.backend.plaid.PlaidGatewayTest' --console=plain
```

Expected: `> Task :compileTestJava FAILED` with `error: cannot find symbol` naming `class PlaidGateway` and `variable PLAID_OUTAGE` (location `class OpsAlerts`); `BUILD FAILED`.

- [ ] **Step 11: The alert name and PlaidGateway**

`backend/src/main/java/com/investingapp/backend/ops/OpsAlerts.java` (T04's file; T05, T13 and T15 add their own names the same way, so match the quoted text, not a line number). Insert immediately before the line `    private OpsAlerts() {`:

```java
    /** PlaidGateway: the fifth consecutive TRANSIENT or RATE_LIMIT Plaid result (spec 2.1, 12.3). Subject: global. */
    public static final String PLAID_OUTAGE = "plaid.outage";

```

Create `backend/src/main/java/com/investingapp/backend/plaid/PlaidGateway.java`:

```java
package com.investingapp.backend.plaid;

import com.investingapp.backend.ops.OpsAlertService;
import com.investingapp.backend.ops.OpsAlerts;
import com.plaid.client.request.PlaidApi;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one way Phase 1 code calls Plaid (spec 2.1). A caller builds its retrofit Call from
 * {@link #api()} and hands it to {@link #call(String, Call)}, which:
 * <ul>
 *   <li>refuses to run inside a database transaction (house rule: no Plaid HTTP inside one);</li>
 *   <li>executes the call on the caller's thread (Phase 1 callers already run on plaidExecutor or a
 *       scheduler thread);</li>
 *   <li>returns the body on a 2xx with a body, else a {@link PlaidApiError} classified on the exact
 *       error_code; an IOException is a synthetic TRANSIENT error and nothing is thrown for it;</li>
 *   <li>logs one {@code plaid.call} line with op, Plaid request_id, HTTP status and error_code, never
 *       a body, a Plaid message, a token or a cursor (spec 12.3);</li>
 *   <li>feeds {@link PlaidOutageBreaker} and raises {@link OpsAlerts#PLAID_OUTAGE} once, on the fifth
 *       consecutive TRANSIENT or RATE_LIMIT result.</li>
 * </ul>
 *
 * <p>{@code op} is a short constant naming the endpoint: {@code transactions_sync},
 * {@code transactions_recurring_get}, {@code item_get}, {@code item_webhook_update},
 * {@code sandbox_transactions_create}, {@code sandbox_item_fire_webhook},
 * {@code sandbox_item_reset_login}. Retry policy per class belongs to the caller.
 */
@Component
public class PlaidGateway {

    private static final Logger logger = LoggerFactory.getLogger(PlaidGateway.class);

    static final String NONE = "-";

    private final PlaidApi plaidApi;
    private final PlaidOutageBreaker breaker;
    private final OpsAlertService opsAlertService;

    public PlaidGateway(PlaidApi plaidApi, PlaidOutageBreaker breaker, OpsAlertService opsAlertService) {
        this.plaidApi = plaidApi;
        this.breaker = breaker;
        this.opsAlertService = opsAlertService;
    }

    /** The PlaidApi bean, for building the Call; creating a Call does no I/O, only execute() does. */
    public PlaidApi api() {
        return plaidApi;
    }

    public <T> PlaidCallResult<T> call(String op, Call<T> call) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("plaid.call refused inside a database transaction op=" + op);
        }
        PlaidCallResult<T> result = execute(op, call);
        PlaidErrorClass resultClass = result.ok() ? null : result.error().errorClass();
        if (breaker.record(resultClass)) {
            raiseOutage(op, result.error());
        }
        return result;
    }

    private <T> PlaidCallResult<T> execute(String op, Call<T> call) {
        Response<T> response;
        try {
            response = call.execute();
        } catch (IOException e) {
            logger.warn("plaid.call op={} request_id={} status=0 code={} class={} transport={}",
                    op, NONE, NONE, PlaidErrorClass.TRANSIENT, e.getClass().getSimpleName());
            return PlaidCallResult.failure(PlaidApiError.transport());
        }
        int status = response.code();
        T body = response.body();
        if (response.isSuccessful() && body != null) {
            logger.info("plaid.call op={} request_id={} status={} code={}", op, requestIdOf(body), status, NONE);
            return PlaidCallResult.success(body);
        }
        PlaidApiError apiError = PlaidApiError.fromBody(status, readOnce(response.errorBody()));
        logger.warn("plaid.call op={} request_id={} status={} code={} class={}",
                op, orNone(apiError.requestId()), status, orNone(apiError.errorCode()), apiError.errorClass());
        return PlaidCallResult.failure(apiError);
    }

    /** okhttp drains the body on the first string(); this is the only read. */
    private static String readOnce(ResponseBody errorBody) {
        if (errorBody == null) {
            return null;
        }
        try {
            return errorBody.string();
        } catch (IOException e) {
            return null;
        }
    }

    private void raiseOutage(String op, PlaidApiError last) {
        String code = orNone(last.errorCode());
        String outageMessage = "Plaid returned " + PlaidOutageBreaker.THRESHOLD
                + " consecutive transient or rate-limit results; last op=" + op + " code=" + code;
        Map<String, Object> outageContext = new LinkedHashMap<>();
        outageContext.put("op", op);
        outageContext.put("code", code);
        outageContext.put("http_status", last.httpStatus());
        outageContext.put("consecutive", PlaidOutageBreaker.THRESHOLD);
        logger.error("alert.plaid.outage op={} code={} consecutive={}", op, code, PlaidOutageBreaker.THRESHOLD);
        opsAlertService.raise(OpsAlerts.PLAID_OUTAGE, OpsAlertService.GLOBAL_SUBJECT, outageMessage, outageContext);
    }

    /** Every plaid-java response model has getRequestId(); anything else logs "-". */
    static String requestIdOf(Object body) {
        try {
            Object id = body.getClass().getMethod("getRequestId").invoke(body);
            return id == null ? NONE : id.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return NONE;
        }
    }

    private static String orNone(String value) {
        return value == null ? NONE : value;
    }
}
```

The `raise(` arguments are `OpsAlerts.PLAID_OUTAGE`, `OpsAlertService.GLOBAL_SUBJECT`, `outageMessage` and `outageContext` (no `total`, `amount`, `getLastError`, `getMessage` or `error`), and the `raise` sits on the line after `logger.error("alert.plaid.outage ...`, as T04's `AlertSitesRoutedTest` requires.

Run the Step 10 command, then:

```
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1/backend && grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.plaid.PlaidGatewayTest.xml
```

Expected: `BUILD SUCCESSFUL`; `tests="11" skipped="0" failures="0" errors="0"`.

- [ ] **Step 12: Neighbour suites (alert routing, money invariance, the package)**

```
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1/backend
./gradlew test --tests 'com.investingapp.backend.plaid.*' --tests 'com.investingapp.backend.support.RecordingTransactionManagerTest' --tests 'com.investingapp.backend.AlertSitesRoutedTest' --console=plain
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.plaid.*.xml build/test-results/test/TEST-com.investingapp.backend.support.RecordingTransactionManagerTest.xml build/test-results/test/TEST-com.investingapp.backend.AlertSitesRoutedTest.xml
ls src/test/java/com/investingapp/backend/PhaseOneMoneyInvarianceTest.java && ./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain
```

Expected: `BUILD SUCCESSFUL`; the five `plaid` files print in glob order `PlaidApiErrorTest` 4, `PlaidCallResultTest` 2, `PlaidErrorClassTest` 4, `PlaidGatewayTest` 11, `PlaidOutageBreakerTest` 4 (25), the recorder 4, all `skipped="0" failures="0" errors="0"`; `AlertSitesRoutedTest` passes with 0 failures (it now also sees the `alert.plaid.outage` site). The third line runs only when T03 has landed (T06 does not depend on T03); if the file is absent the `ls` fails and nothing runs, and T03's walk covers `plaid/**` when it lands. When it runs: `BUILD SUCCESSFUL` with 0 failures.

Em dash check on every file this task writes (the pattern is the Unicode code point, so this file stays free of the character itself):

```
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1
perl -CSD -ne 'print "$ARGV:$.\n" if /\x{2014}/; close ARGV if eof' backend/src/main/java/com/investingapp/backend/plaid/*.java backend/src/test/java/com/investingapp/backend/plaid/*.java backend/src/test/java/com/investingapp/backend/support/RecordingTransactionManager*.java backend/src/main/java/com/investingapp/backend/ops/OpsAlerts.java
```

Expected: prints nothing.

- [ ] **Step 13: Full backend suite on a scratch MySQL (the new beans must boot under ci)**

`PlaidGateway` and `PlaidOutageBreaker` are new Spring beans; `BackendApplicationTests` (`@RequiresCiDatabase`) is what proves they resolve under the `ci` profile, and it skips silently without a database. Run the whole suite against a scratch schema. The password is typed at the prompts and never echoed or written down:

```
mysql -u root -p -e "CREATE DATABASE fred_t06_scratch"
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1/backend
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t06_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME=root
read -s SPRING_DATASOURCE_PASSWORD && export SPRING_DATASOURCE_PASSWORD
export PLAID_WEBHOOK_URL='https://ci.invalid/api/plaid/webhook'
export PLAID_REDIRECT_URI='https://ci.invalid/plaid/oauth'
export PLAID_LINK_CUSTOMIZATION=default
./gradlew cleanTest test --console=plain
cat build/test-results/test/*.xml | grep -o '<testsuite [^>]*>' | sed -E 's/.*tests="([0-9]+)" skipped="([0-9]+)" failures="([0-9]+)" errors="([0-9]+)".*/\1 \2 \3 \4/' | awk '{t+=$1; s+=$2; f+=$3; e+=$4} END {print "tests=" t " skipped=" s " failures=" f " errors=" e}'
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml
unset SPRING_DATASOURCE_PASSWORD
mysql -u root -p -e "DROP DATABASE fred_t06_scratch"
```

Expected: `BUILD SUCCESSFUL`; the totals line reads `skipped=0 failures=0 errors=0`, and `ls build/test-results/test/ | grep -c 'TEST-com.investingapp.backend.plaid\.\|RecordingTransactionManagerTest'` prints `6` (the 29 new tests, 25 in `plaid` and 4 in `support`, are in the total; no pre-task baseline is needed because none was recorded); `BackendApplicationTests` reads `tests="1" skipped="0" failures="0"`. Read the counts from the XML, never the console. A run with any skip is not a pass: fix the datasource and run again.

- [ ] **Step 14: Commit**

Hunk-verify the one modified file with `git diff HEAD -- backend/src/main/java/com/investingapp/backend/ops/OpsAlerts.java` (the shared tree can carry another session's work; the diff must show only the `PLAID_OUTAGE` constant and its javadoc). Never stage `application-local.properties` or `ITPM/agent-memory/*`. Then:

```
cd /Users/andrewtimmer/PersonalTypeshit/FRED/.claude/worktrees/paycheck-autopilot-phase-1
git add backend/src/main/java/com/investingapp/backend/plaid/PlaidErrorClass.java backend/src/main/java/com/investingapp/backend/plaid/PlaidApiError.java backend/src/main/java/com/investingapp/backend/plaid/PlaidCallResult.java backend/src/main/java/com/investingapp/backend/plaid/PlaidOutageBreaker.java backend/src/main/java/com/investingapp/backend/plaid/PlaidGateway.java backend/src/main/java/com/investingapp/backend/ops/OpsAlerts.java backend/src/test/java/com/investingapp/backend/plaid/PlaidErrorClassTest.java backend/src/test/java/com/investingapp/backend/plaid/PlaidApiErrorTest.java backend/src/test/java/com/investingapp/backend/plaid/PlaidCallResultTest.java backend/src/test/java/com/investingapp/backend/plaid/PlaidOutageBreakerTest.java backend/src/test/java/com/investingapp/backend/plaid/PlaidGatewayTest.java backend/src/test/java/com/investingapp/backend/support/RecordingTransactionManager.java backend/src/test/java/com/investingapp/backend/support/RecordingTransactionManagerTest.java
git status --short
git commit -m "feat(plaid): one gateway for Plaid calls with spec 2.1 error classes and the outage breaker"
```

Expected: `git status --short` shows the 13 paths above as staged (`A ` for the 12 new files, `M ` for `OpsAlerts.java`) and nothing else staged; the commit succeeds with 13 files changed. (If Step 1 found `PLAID_OUTAGE` already present and Step 11's edit was skipped, drop `OpsAlerts.java` from the `git add` line; expect 12 `A ` paths and 12 files changed.)

### Verification

| command | expected |
|---|---|
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.plaid.PlaidErrorClassTest' --console=plain` | `BUILD SUCCESSFUL`; XML `tests="4" skipped="0" failures="0"` |
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.plaid.PlaidApiErrorTest' --console=plain` | `tests="4" skipped="0" failures="0"` |
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.plaid.PlaidCallResultTest' --console=plain` | `tests="2" skipped="0" failures="0"` |
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.plaid.PlaidOutageBreakerTest' --console=plain` | `tests="4" skipped="0" failures="0"` |
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.support.RecordingTransactionManagerTest' --console=plain` | `tests="4" skipped="0" failures="0"` |
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.plaid.PlaidGatewayTest' --console=plain` | `tests="11" skipped="0" failures="0"` |
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.AlertSitesRoutedTest' --console=plain` | `BUILD SUCCESSFUL`, 0 failures |
| `cd backend && ./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain` (only when T03 has landed) | `BUILD SUCCESSFUL`, 0 failures |
| Step 13 scratch-MySQL `./gradlew cleanTest test --console=plain` | totals `skipped=0 failures=0 errors=0`, the 6 new XML files present; `BackendApplicationTests` 1 test, 0 skipped |
| Step 12 `perl` em dash check | prints nothing |

Counts are read from `build/test-results/test/*.xml`, never the console. No frontend file changes, so no Karma run.

### Produces

- `com.investingapp.backend.plaid.PlaidErrorClass` `{TRANSIENT, RATE_LIMIT, CONSENT, ITEM_BROKEN, NOT_SUPPORTED, SYNC_LIMIT, MUTATION, PERMANENT}`; `static PlaidErrorClass classify(String errorCode, int httpStatus)` (exact code; MUTATION for `TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION`; unknown `INSTITUTION_*` PERMANENT; no parsable code with HTTP >= 500 TRANSIENT); `Set<String> codes()`.
- `com.investingapp.backend.plaid.PlaidApiError(String errorType, String errorCode, String errorCodeReason, String requestId, int httpStatus, PlaidErrorClass errorClass)`; `static PlaidApiError fromBody(int httpStatus, String body)` (Jackson `JsonNode`, body read once by the gateway); `static PlaidApiError transport()`; `TRANSPORT_ERROR_TYPE = "TRANSPORT"`.
- `com.investingapp.backend.plaid.PlaidCallResult<T>(T body, PlaidApiError error)`; `boolean ok()`; `static success(T)`, `static failure(PlaidApiError)`.
- `com.investingapp.backend.plaid.PlaidGateway(PlaidApi, PlaidOutageBreaker, OpsAlertService)`; `<T> PlaidCallResult<T> call(String op, Call<T> call)` (caller's thread; `IllegalStateException` inside an actual transaction; `IOException` is a TRANSIENT `transport()` error; logs `plaid.call op={} request_id={} status={} code={}` plus `class={}` on errors; feeds the breaker); `PlaidApi api()`.
- `com.investingapp.backend.plaid.PlaidOutageBreaker`: `THRESHOLD = 5`; `boolean record(PlaidErrorClass)` (null = success; true only on the tripping result); `boolean tripped()` (`count >= 5`); `void reset()` (raises nothing; T15 calls it at the start of each reconcile run).
- `com.investingapp.backend.ops.OpsAlerts.PLAID_OUTAGE = "plaid.outage"`, raised by the gateway with subject `OpsAlertService.GLOBAL_SUBJECT` and context `{op, code, http_status, consecutive}`.
- Test support `com.investingapp.backend.support.RecordingTransactionManager` (extends `AbstractPlatformTransactionManager`): public `volatile boolean open`, `AtomicInteger begins`, `commits`, `rollbacks`; used by T08, T10, T14 and T16 as `assert !tx.open` inside the mocked `Call.execute()` answer plus `tx.begins.get() > 0`.
