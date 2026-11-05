### Task 03: autopilot-properties-gates-and-notices

One typed home for `paycheck.autopilot.*` (spec 12.1), the R8 exclusion rule, `NotificationGate` (spec 10), and the single notice sender with Phase 1's only user-facing notice, RECONNECT (R18); plus `PhaseOneMoneyInvarianceTest`, which T11 and T13 extend. Nothing here moves money, writes `InvestmentSchedule.status`, `mode` or `holdReason`, calls Plaid or Alpaca, opens a transaction, or records an analytics event (no schedule state changes, so nothing to emit).

`.../` = `backend/src/main/java/com/investingapp/backend/`; tests mirror it. Anchors were read at HEAD 137998c; T04 and T17 also edit the property files, so edits anchor on quoted text.

**Facts this task depends on (restated so this file stands alone):**

- **No `is_paused`.** `InvestmentSchedule.Status` is `PENDING_BANK | ACTIVE | PAUSED_BY_USER`. Read `getStatus()`, never the deprecated `getIsPaused()` or `isPausedForAnyReason()`. Phase 1 never writes `status` (its writers stay `pauseSchedule`, `resumeSchedule`, row creation, `AchRelationshipService.adopt`).
- **`hold_reason` is the system's stop, orthogonal to `status`.** `BLOCKING_HOLDS` (private, `model/InvestmentSchedule.java`) is exactly `ACH_RETURN_UNAUTHORIZED`, `ACH_RETURN_ACCOUNT`, `MANUAL_REVIEW`; `BANK_CHANGE` is not blocking. `NotificationGate` reads `HoldReason.KILL_SWITCH`.
- **The runnable rule lives only in `isReadyForInvestment(LocalDate todayET)`**, unchanged in Phase 1; `ScheduledInvestmentProcessor.processOne` refuses `mode != MANUAL` before reading it (`if (schedule.getMode() != ScheduleMode.MANUAL) {`). The invariance test pins both by text, since T02 shifts line numbers.
- **R8.** Detection skips `account_status = 'REJECTED'` (rejected applicants keep their row and a `MANUAL_REVIEW` hold since 2026-10-24), `paycheck_autopilot_opt_out = true`, no `plaid_access_token`, and `plaid_item_status` other than `OK` or `PENDING_DISCONNECT`. With `paycheck.autopilot.enabled=false` everyone is excluded (spec 12.1: off means inbox rows are stored and nothing else runs).
- **R18.** RECONNECT sends nothing for a `REJECTED` account or a user with no schedule row (still onboarding); this task also sends nothing when autopilot is disabled (12.1).
- **Spec 10 RECONNECT copy:** DETECTED "Reconnect your Chase account so FRED can keep spotting your paycheck. Paycheck investing is paused until then."; MANUAL "Reconnect your Chase account so your investing keeps running." Under shadow every row is MANUAL; the DETECTED line is written and tested for Phase 2. Key: the incident's ISO date.
- **The ledger is `email_log`:** `email_type VARCHAR(40)`, `period_key VARCHAR(10)`, V1 unique index `uq_email_type_user_period (email_type, user_id, period_key)`. `EmailService.hasBeenSent` is true for SENT or MOCKED; `sendEmail(EmailMessage, userId, type, key)` never throws and, with `app.email.enabled=false` (today's default everywhere), records MOCKED and dispatches nothing.
- **Templates** under `backend/src/main/resources/templates/email/` render through `EmailTemplateRenderer.render`, which inserts values literally (no escaping) and throws on an unresolved `{{placeholder}}`. `market-breakdown.html` contains `&mdash;`; the new templates must not.
- **Logs:** INFO lines carry closed words only, never a recipient, bank or payer name, descriptor, amount, id, period key or date. (`EmailService`'s pre-existing mocked-send INFO line prints recipient and key; outside this task's files.)
- **Properties:** Spring always loads `application.properties`, so a key with an env default resolves under `local` too; the gitignored `application-local.properties` is hand-edited only to change a value (Step 13).
- **Dependency:** T02 has mapped `User.paycheckAutopilotOptOut` (`Boolean`, default `false`).

**Files:**

- Create: `.../config/PaycheckAutopilotProperties.java`, `.../service/DetectionEligibility.java` (with nested `ExclusionReason`), `.../service/NotificationGate.java`, `.../service/NotificationService.java`, `backend/src/main/resources/templates/email/reconnect.html`, `backend/src/main/resources/templates/email/reconnect.txt`.
- Modify: `backend/src/main/resources/application.properties` (new block after the line `investment.max.amount=${INVESTMENT_MAX_AMOUNT:50000.00}`, HEAD line 58); `backend/src/main/resources/application-ci.properties` (one block after the line `investment.scheduler.enabled=false`, HEAD line 27).
- Test (create): `config/PaycheckAutopilotPropertiesTest.java`, `service/DetectionEligibilityTest.java`, `service/NotificationGateTest.java`, `service/NotificationServiceTest.java`, `PhaseOneMoneyInvarianceTest.java` (package `com.investingapp.backend`, at the test root). Gate: `BackendApplicationTests` (unchanged) on a scratch MySQL.

**Interfaces:**

- Consumes: T02's `User.get/setPaycheckAutopilotOptOut`; HEAD's `User` getters and setters for id, email, accountStatus, plaidAccessToken, plaidItemStatus, plaidInstitutionName; `PlaidItemStatus`; `InvestmentSchedule.getMode()`, `getHoldReason()`; `InvestmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(User)` (`Optional`); `EmailService.hasBeenSent(String, Long, String)`, `sendEmail(EmailMessage, Long, String, String)`, `recordFailedAttempt(Long, String, String, String, String)`; `EmailMessage(to, from, subject, htmlBody, textBody)`; `EmailTemplateRenderer.render`; `HtmlUtils.htmlEscape` (spring-web); `ScheduledInvestmentProcessor(InvestmentScheduleRepository, InvestmentExecutionRepository).processOne(Long, LocalDate)`.
- Produces: the exact signatures are listed once, in the Produces section at the end of this file.

- [ ] **Step 1: Preconditions (T02 landed, nothing of this task exists yet)**

From the repository root:

```bash
grep -n 'paycheckAutopilotOptOut' backend/src/main/java/com/investingapp/backend/model/User.java
ls backend/src/main/java/com/investingapp/backend/config/PaycheckAutopilotProperties.java backend/src/main/java/com/investingapp/backend/service/NotificationService.java 2>&1
grep -n 'paycheck.autopilot' backend/src/main/resources/application.properties backend/src/main/resources/application-ci.properties
```

Expected: the first grep prints T02's field line (nothing means T02 has not landed: stop); `ls` prints two `No such file or directory`; the last grep prints nothing.

- [ ] **Step 2: Failing test for the properties class**

Create `backend/src/test/java/com/investingapp/backend/config/PaycheckAutopilotPropertiesTest.java`:

```java
package com.investingapp.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaycheckAutopilotPropertiesTest {

    private static final Map<String, String> EXPRESSIONS = Map.of(
            "enabled", "${paycheck.autopilot.enabled:false}",
            "shadow", "${paycheck.autopilot.shadow:true}",
            "pullsEnabled", "${paycheck.autopilot.pulls.enabled:false}",
            "scoreProvisional", "${paycheck.autopilot.score.provisional:55}",
            "scoreConfirmed", "${paycheck.autopilot.score.confirmed:70}",
            "affordabilityShare", "${paycheck.autopilot.affordability.share:0.90}",
            "daysRequested", "${paycheck.autopilot.days-requested:365}");

    private static final Map<String, String> BASE_LINES = Map.of(
            "paycheck.autopilot.enabled", "${PAYCHECK_AUTOPILOT_ENABLED:false}",
            "paycheck.autopilot.shadow", "${PAYCHECK_AUTOPILOT_SHADOW:true}",
            "paycheck.autopilot.pulls.enabled", "${PAYCHECK_AUTOPILOT_PULLS_ENABLED:false}",
            "paycheck.autopilot.score.provisional", "${PAYCHECK_SCORE_PROVISIONAL:55}",
            "paycheck.autopilot.score.confirmed", "${PAYCHECK_SCORE_CONFIRMED:70}",
            "paycheck.autopilot.affordability.share", "${PAYCHECK_AFFORDABILITY_SHARE:0.90}",
            "paycheck.autopilot.days-requested", "${PAYCHECK_DAYS_REQUESTED:365}");

    private static PaycheckAutopilotProperties props(int provisional, int confirmed, String share, int days) {
        return new PaycheckAutopilotProperties(true, true, false, provisional, confirmed, new BigDecimal(share), days);
    }

    private static Properties load(String resource) throws IOException {
        Properties p = new Properties();
        try (InputStream in = PaycheckAutopilotPropertiesTest.class.getResourceAsStream("/" + resource)) {
            assertThat(in).as(resource + " on the test classpath").isNotNull();
            p.load(in);
        }
        return p;
    }

    @Test
    void everyFieldCarriesItsKeyAndDefault() throws Exception {
        for (Map.Entry<String, String> e : EXPRESSIONS.entrySet()) {
            Value value = PaycheckAutopilotProperties.class.getDeclaredField(e.getKey()).getAnnotation(Value.class);
            assertThat(value).as(e.getKey() + " carries @Value").isNotNull();
            assertThat(value.value()).as(e.getKey()).isEqualTo(e.getValue());
        }
    }

    @Test
    void bareInstanceIsTheSafeState() {
        PaycheckAutopilotProperties p = new PaycheckAutopilotProperties();
        assertThat(p.isEnabled()).isFalse();
        assertThat(p.isShadow()).isTrue();
        assertThat(p.isPullsEnabled()).isFalse();
        assertThat(p.getScoreProvisional()).isEqualTo(55);
        assertThat(p.getScoreConfirmed()).isEqualTo(70);
        assertThat(p.getAffordabilityShare()).isEqualByComparingTo("0.90");
        assertThat(p.getDaysRequested()).isEqualTo(365);
    }

    @Test
    void baseProfileCarriesEveryKeyWithAnEnvDefault() throws IOException {
        Properties base = load("application.properties");
        BASE_LINES.forEach((key, line) -> assertThat(base.getProperty(key)).as(key).isEqualTo(line));
    }

    @Test
    void ciProfileTurnsAutopilotOff() throws IOException {
        assertThat(load("application-ci.properties").getProperty("paycheck.autopilot.enabled")).isEqualTo("false");
    }

    @Test
    void inconsistentTunablesRefuseToStart() {
        assertThatThrownBy(() -> props(70, 70, "0.90", 365)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paycheck.autopilot.score.provisional");
        assertThatThrownBy(() -> props(55, 101, "0.90", 365)).hasMessageContaining("paycheck.autopilot.score.provisional");
        assertThatThrownBy(() -> props(-1, 70, "0.90", 365)).hasMessageContaining("paycheck.autopilot.score.provisional");
        assertThatThrownBy(() -> props(55, 70, "0", 365)).hasMessageContaining("paycheck.autopilot.affordability.share");
        assertThatThrownBy(() -> props(55, 70, "1.01", 365)).hasMessageContaining("paycheck.autopilot.affordability.share");
        assertThatThrownBy(() -> props(55, 70, "0.90", 0)).hasMessageContaining("paycheck.autopilot.days-requested");
        assertThatThrownBy(() -> props(55, 70, "0.90", 731)).hasMessageContaining("paycheck.autopilot.days-requested");
        assertThatCode(() -> props(0, 100, "1.00", 730)).doesNotThrowAnyException();
        PaycheckAutopilotProperties p =
                new PaycheckAutopilotProperties(true, false, true, 50, 80, new BigDecimal("0.85"), 1);
        assertThat(p.isEnabled() && !p.isShadow() && p.isPullsEnabled()).as("all-args carries the flags").isTrue();
        assertThat(p.getScoreProvisional() + "/" + p.getScoreConfirmed() + "/" + p.getAffordabilityShare() + "/"
                + p.getDaysRequested()).isEqualTo("50/80/0.85/1");
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.config.PaycheckAutopilotPropertiesTest' --console=plain`. Expected: `> Task :compileTestJava FAILED` with `error: cannot find symbol` and `symbol:   class PaycheckAutopilotProperties`.

- [ ] **Step 3: The properties class and both property-file blocks**

Create `backend/src/main/java/com/investingapp/backend/config/PaycheckAutopilotProperties.java`:

```java
package com.investingapp.backend.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The one typed home for paycheck.autopilot.* (spec 12.1). Spring uses the no-arg constructor and
 * fills the fields; tests use the all-args one. Field defaults equal the property defaults, so a
 * bare instance is the safe state: off, shadow, no pulls. Inconsistent tunables stop the boot,
 * because a silently wrong threshold would score every user.
 */
@Component
public class PaycheckAutopilotProperties {

    private static final Logger logger = LoggerFactory.getLogger(PaycheckAutopilotProperties.class);

    /** Plaid accepts days_requested from 1 to 730; it is honoured only on an Item's first sync. */
    static final int DAYS_REQUESTED_MIN = 1;
    static final int DAYS_REQUESTED_MAX = 730;

    @Value("${paycheck.autopilot.enabled:false}")
    private boolean enabled = false;

    @Value("${paycheck.autopilot.shadow:true}")
    private boolean shadow = true;

    @Value("${paycheck.autopilot.pulls.enabled:false}")
    private boolean pullsEnabled = false;

    @Value("${paycheck.autopilot.score.provisional:55}")
    private int scoreProvisional = 55;

    @Value("${paycheck.autopilot.score.confirmed:70}")
    private int scoreConfirmed = 70;

    @Value("${paycheck.autopilot.affordability.share:0.90}")
    private BigDecimal affordabilityShare = new BigDecimal("0.90");

    @Value("${paycheck.autopilot.days-requested:365}")
    private int daysRequested = 365;

    public PaycheckAutopilotProperties() {
    }

    public PaycheckAutopilotProperties(boolean enabled, boolean shadow, boolean pullsEnabled,
            int scoreProvisional, int scoreConfirmed, BigDecimal affordabilityShare, int daysRequested) {
        this.enabled = enabled;
        this.shadow = shadow;
        this.pullsEnabled = pullsEnabled;
        this.scoreProvisional = scoreProvisional;
        this.scoreConfirmed = scoreConfirmed;
        this.affordabilityShare = affordabilityShare;
        this.daysRequested = daysRequested;
        validate();
    }

    @PostConstruct
    void validateAndLog() {
        validate();
        logger.info("paycheck.autopilot.config enabled={} shadow={} pulls_enabled={} score_provisional={}"
                        + " score_confirmed={} affordability_share={} days_requested={}",
                enabled, shadow, pullsEnabled, scoreProvisional, scoreConfirmed, affordabilityShare, daysRequested);
    }

    void validate() {
        List<String> problems = new ArrayList<>();
        if (scoreProvisional < 0 || scoreConfirmed > 100 || scoreProvisional >= scoreConfirmed) {
            problems.add("paycheck.autopilot.score.provisional (" + scoreProvisional
                    + ") must be below paycheck.autopilot.score.confirmed (" + scoreConfirmed
                    + "), both within 0 to 100");
        }
        if (affordabilityShare == null || affordabilityShare.signum() <= 0
                || affordabilityShare.compareTo(BigDecimal.ONE) > 0) {
            problems.add("paycheck.autopilot.affordability.share (" + affordabilityShare
                    + ") must be above 0 and at most 1");
        }
        if (daysRequested < DAYS_REQUESTED_MIN || daysRequested > DAYS_REQUESTED_MAX) {
            problems.add("paycheck.autopilot.days-requested (" + daysRequested + ") must be from "
                    + DAYS_REQUESTED_MIN + " to " + DAYS_REQUESTED_MAX);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("paycheck.autopilot settings are inconsistent: " + problems);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isShadow() {
        return shadow;
    }

    public boolean isPullsEnabled() {
        return pullsEnabled;
    }

    public int getScoreProvisional() {
        return scoreProvisional;
    }

    public int getScoreConfirmed() {
        return scoreConfirmed;
    }

    public BigDecimal getAffordabilityShare() {
        return affordabilityShare;
    }

    public int getDaysRequested() {
        return daysRequested;
    }
}
```

`backend/src/main/resources/application.properties`: after the line `investment.max.amount=${INVESTMENT_MAX_AMOUNT:50000.00}` (HEAD line 58, the last line of the `# Investment Execution Configuration` block; the blank line that follows it stays), insert a blank line and then:

```properties
# --- Paycheck Autopilot (spec 12.1) -----------------------------------------
# enabled=false stores Plaid webhook rows and runs nothing else. Phase 1 = enabled, shadow, no pulls:
# no money moves and DETECTED notices are suppressed. Inconsistent tunables stop the boot.
paycheck.autopilot.enabled=${PAYCHECK_AUTOPILOT_ENABLED:false}
paycheck.autopilot.shadow=${PAYCHECK_AUTOPILOT_SHADOW:true}
paycheck.autopilot.pulls.enabled=${PAYCHECK_AUTOPILOT_PULLS_ENABLED:false}
paycheck.autopilot.score.provisional=${PAYCHECK_SCORE_PROVISIONAL:55}
paycheck.autopilot.score.confirmed=${PAYCHECK_SCORE_CONFIRMED:70}
paycheck.autopilot.affordability.share=${PAYCHECK_AFFORDABILITY_SHARE:0.90}
paycheck.autopilot.days-requested=${PAYCHECK_DAYS_REQUESTED:365}
```

`backend/src/main/resources/application-ci.properties`: after the line `investment.scheduler.enabled=false` (HEAD line 27; the blank line 28 and the `# --- JWT` block after it stay), insert a blank line and then:

```properties
# --- Paycheck Autopilot: off in CI -------------------------------------------
# A literal, not an env default: the offline CI context must never start detection.
paycheck.autopilot.enabled=false
```

`dev`, `test` and `prod` take the base defaults (off unless `PAYCHECK_AUTOPILOT_ENABLED` is set; Andy sets it on Railway dev).

Run the Step 2 command, then `grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.config.PaycheckAutopilotPropertiesTest.xml` from `backend/`. Expected: `BUILD SUCCESSFUL` and `tests="5" skipped="0" failures="0" errors="0"`.

- [ ] **Step 4: Failing test for the R8 exclusion rule**

Create `backend/src/test/java/com/investingapp/backend/service/DetectionEligibilityTest.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class DetectionEligibilityTest {

    private static final PaycheckAutopilotProperties ON = props(true);

    private User user;

    private static PaycheckAutopilotProperties props(boolean enabled) {
        return new PaycheckAutopilotProperties(enabled, true, false, 55, 70, new BigDecimal("0.90"), 365);
    }

    @BeforeEach
    void setUp() {
        user = new User("saver@fredvested.com");
        user.setId(42L);
        user.setAccountStatus("ACTIVE");
        user.setPlaidAccessToken("access-sandbox-token");
        user.setPlaidItemStatus(PlaidItemStatus.OK);
        user.setPaycheckAutopilotOptOut(false);
    }

    @Test
    void eligibleUserHasNoExclusion() {
        assertThat(DetectionEligibility.exclusion(user, ON)).isEmpty();
        for (String status : new String[] {null, "SUBMITTED", "APPROVAL_PENDING", "APPROVED", "ACTION_REQUIRED"}) {
            user.setAccountStatus(status);
            assertThat(DetectionEligibility.exclusion(user, ON)).as("account_status " + status).isEmpty();
        }
    }

    @Test
    void disabledExcludesEveryone() {
        assertThat(DetectionEligibility.exclusion(user, props(false))).contains(ExclusionReason.DISABLED);
    }

    @Test
    void rejectedAccountExcluded() {
        user.setAccountStatus("REJECTED");
        assertThat(DetectionEligibility.exclusion(user, ON)).contains(ExclusionReason.ACCOUNT_REJECTED);
    }

    @Test
    void optedOutExcluded() {
        user.setPaycheckAutopilotOptOut(true);
        assertThat(DetectionEligibility.exclusion(user, ON)).contains(ExclusionReason.OPTED_OUT);
        user.setPaycheckAutopilotOptOut(null);
        assertThat(DetectionEligibility.exclusion(user, ON)).as("null is not an opt-out").isEmpty();
    }

    @Test
    void missingOrBlankTokenExcluded() {
        user.setPlaidAccessToken(null);
        assertThat(DetectionEligibility.exclusion(user, ON)).contains(ExclusionReason.NO_TOKEN);
        user.setPlaidAccessToken("   ");
        assertThat(DetectionEligibility.exclusion(user, ON)).contains(ExclusionReason.NO_TOKEN);
    }

    @Test
    void brokenItemStatusExcluded() {
        for (PlaidItemStatus status : EnumSet.complementOf(EnumSet.of(PlaidItemStatus.OK, PlaidItemStatus.PENDING_DISCONNECT))) {
            user.setPlaidItemStatus(status);
            assertThat(DetectionEligibility.exclusion(user, ON)).as(status.name()).contains(ExclusionReason.ITEM_STATUS);
        }
    }

    @Test
    void pendingDisconnectStillEligible() {
        user.setPlaidItemStatus(PlaidItemStatus.PENDING_DISCONNECT);
        assertThat(DetectionEligibility.exclusion(user, ON)).isEmpty();
    }

    @Test
    void firstReasonWinsInEnumOrder() {
        user.setAccountStatus("REJECTED");
        user.setPaycheckAutopilotOptOut(true);
        user.setPlaidAccessToken(null);
        user.setPlaidItemStatus(PlaidItemStatus.REVOKED);
        assertThat(DetectionEligibility.exclusion(user, props(false))).contains(ExclusionReason.DISABLED);
        assertThat(DetectionEligibility.exclusion(user, ON)).contains(ExclusionReason.ACCOUNT_REJECTED);
        user.setAccountStatus("ACTIVE");
        assertThat(DetectionEligibility.exclusion(user, ON)).contains(ExclusionReason.OPTED_OUT);
        user.setPaycheckAutopilotOptOut(false);
        assertThat(DetectionEligibility.exclusion(user, ON)).contains(ExclusionReason.NO_TOKEN);
        user.setPlaidAccessToken("access-sandbox-token");
        assertThat(DetectionEligibility.exclusion(user, ON)).contains(ExclusionReason.ITEM_STATUS);
    }

    @Test
    void reasonsAreExactlyTheFiveOfRuleEight() {
        assertThat(ExclusionReason.values()).containsExactly(ExclusionReason.DISABLED,
                ExclusionReason.ACCOUNT_REJECTED, ExclusionReason.OPTED_OUT, ExclusionReason.NO_TOKEN,
                ExclusionReason.ITEM_STATUS);
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.DetectionEligibilityTest' --console=plain`. Expected: `> Task :compileTestJava FAILED` with `error: package com.investingapp.backend.service.DetectionEligibility does not exist` (the nested-enum import) and `error: cannot find symbol` for `DetectionEligibility`.

- [ ] **Step 5: DetectionEligibility**

Create `backend/src/main/java/com/investingapp/backend/service/DetectionEligibility.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;

import java.util.Objects;
import java.util.Optional;

/**
 * Ruling R8: who detection never runs for. Every entry point (evaluate, reconcile, sync pipeline,
 * INIT enqueue, RECONNECT) asks this one pure method, so the rule cannot drift. First match wins,
 * in enum order. PENDING_DISCONNECT stays eligible: the Item works until its disconnect time.
 */
public final class DetectionEligibility {

    /** Why a user is excluded from detection. DISABLED means paycheck.autopilot.enabled=false. */
    public enum ExclusionReason {
        DISABLED, ACCOUNT_REJECTED, OPTED_OUT, NO_TOKEN, ITEM_STATUS
    }

    static final String ACCOUNT_STATUS_REJECTED = "REJECTED";

    private DetectionEligibility() {
    }

    public static Optional<ExclusionReason> exclusion(User user, PaycheckAutopilotProperties props) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(props, "props");
        if (!props.isEnabled()) {
            return Optional.of(ExclusionReason.DISABLED);
        }
        if (ACCOUNT_STATUS_REJECTED.equals(user.getAccountStatus())) {
            return Optional.of(ExclusionReason.ACCOUNT_REJECTED);
        }
        if (Boolean.TRUE.equals(user.getPaycheckAutopilotOptOut())) {
            return Optional.of(ExclusionReason.OPTED_OUT);
        }
        String token = user.getPlaidAccessToken();
        if (token == null || token.isBlank()) {
            return Optional.of(ExclusionReason.NO_TOKEN);
        }
        PlaidItemStatus status = user.getPlaidItemStatus();
        // The column is NOT NULL DEFAULT 'OK'; a null can only be an unsaved entity, read as OK.
        if (status != null && status != PlaidItemStatus.OK && status != PlaidItemStatus.PENDING_DISCONNECT) {
            return Optional.of(ExclusionReason.ITEM_STATUS);
        }
        return Optional.empty();
    }
}
```

Run the Step 4 command, then the XML grep for `TEST-com.investingapp.backend.service.DetectionEligibilityTest.xml`. Expected: `BUILD SUCCESSFUL` and `tests="9" skipped="0" failures="0" errors="0"`.

- [ ] **Step 6: Failing test for NotificationGate**

Create `backend/src/test/java/com/investingapp/backend/service/NotificationGateTest.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationGateTest {

    private User user;

    private static NotificationGate gate(boolean enabled, boolean shadow) {
        return new NotificationGate(
                new PaycheckAutopilotProperties(enabled, shadow, false, 55, 70, new BigDecimal("0.90"), 365));
    }

    private static InvestmentSchedule schedule(HoldReason hold) {
        InvestmentSchedule s = new InvestmentSchedule();
        s.setHoldReason(hold);
        return s;
    }

    @BeforeEach
    void setUp() {
        user = new User("saver@fredvested.com");
        user.setId(42L);
    }

    @Test
    void shadowSuppresses() {
        assertThat(gate(true, true).suppressed(user, schedule(null))).isTrue();
        assertThat(gate(true, true).suppressed(user, null)).as("no schedule row yet").isTrue();
    }

    @Test
    void disabledSuppressesEvenOutsideShadow() {
        assertThat(gate(false, false).suppressed(user, schedule(null))).isTrue();
    }

    @Test
    void killSwitchHoldSuppresses() {
        assertThat(gate(true, false).suppressed(user, schedule(HoldReason.KILL_SWITCH))).isTrue();
    }

    @Test
    void openWhenEnabledLiveAndNotKillSwitched() {
        NotificationGate open = gate(true, false);
        assertThat(open.suppressed(user, null)).isFalse();
        assertThat(open.suppressed(user, schedule(null))).isFalse();
        for (HoldReason hold : EnumSet.complementOf(EnumSet.of(HoldReason.KILL_SWITCH))) {
            assertThat(open.suppressed(user, schedule(hold))).as(hold.name()).isFalse();
        }
    }

    @Test
    void reasonNamesTheFirstMatch() {
        InvestmentSchedule killed = schedule(HoldReason.KILL_SWITCH);
        assertThat(gate(false, true).reason(killed)).contains(NotificationGate.REASON_DISABLED);
        assertThat(gate(true, true).reason(killed)).contains(NotificationGate.REASON_SHADOW);
        assertThat(gate(true, false).reason(killed)).contains(NotificationGate.REASON_KILL_SWITCH);
        assertThat(NotificationGate.REASON_DISABLED).isEqualTo("disabled");
        assertThat(NotificationGate.REASON_SHADOW).isEqualTo("shadow");
        assertThat(NotificationGate.REASON_KILL_SWITCH).isEqualTo("kill_switch");
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.NotificationGateTest' --console=plain`. Expected: `> Task :compileTestJava FAILED` with `error: cannot find symbol` and `symbol:   class NotificationGate`.

- [ ] **Step 7: NotificationGate**

Create `backend/src/main/java/com/investingapp/backend/service/NotificationGate.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Spec 10 and 12.1: every DETECTED-only notice asks this first. Suppressed when disabled, under
 * shadow, or on the KILL_SWITCH hold; logged as paycheck.notice.suppressed with a closed reason.
 * Under Phase 1 (enabled, shadow) all are suppressed: the exit criterion "zero user-facing DETECTED
 * notices". Both-modes notices (RECONNECT) do not come through here.
 */
@Component
public class NotificationGate {

    private static final Logger logger = LoggerFactory.getLogger(NotificationGate.class);

    static final String REASON_DISABLED = "disabled";
    static final String REASON_SHADOW = "shadow";
    static final String REASON_KILL_SWITCH = "kill_switch";

    private final PaycheckAutopilotProperties props;

    public NotificationGate(PaycheckAutopilotProperties props) {
        this.props = props;
    }

    /** True when a DETECTED-only notice for this user must not be sent. Never writes anything. */
    public boolean suppressed(User user, InvestmentSchedule scheduleOrNull) {
        Objects.requireNonNull(user, "user");
        Optional<String> reason = reason(scheduleOrNull);
        reason.ifPresent(r -> {
            logger.info("paycheck.notice.suppressed reason={}", r);
            logger.debug("paycheck.notice.suppressed.detail user={} reason={}", user.getId(), r);
        });
        return reason.isPresent();
    }

    /** The first matching reason: the master switch, then shadow, then the schedule's kill switch. */
    Optional<String> reason(InvestmentSchedule scheduleOrNull) {
        if (!props.isEnabled()) {
            return Optional.of(REASON_DISABLED);
        }
        if (props.isShadow()) {
            return Optional.of(REASON_SHADOW);
        }
        if (scheduleOrNull != null && scheduleOrNull.getHoldReason() == HoldReason.KILL_SWITCH) {
            return Optional.of(REASON_KILL_SWITCH);
        }
        return Optional.empty();
    }
}
```

Run the Step 6 command, then the XML grep for `TEST-com.investingapp.backend.service.NotificationGateTest.xml`. Expected: `BUILD SUCCESSFUL` and `tests="5" skipped="0" failures="0" errors="0"`.

- [ ] **Step 8: Failing test for NotificationService**

Create `backend/src/test/java/com/investingapp/backend/service/NotificationServiceTest.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.dto.EmailMessage;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    private static final LocalDate INCIDENT = LocalDate.of(2026, 11, 4);
    private static final String MANUAL_LINE = "Reconnect your Chase account so your investing keeps running.";
    private static final String DETECTED_LINE = "Reconnect your Chase account so FRED can keep spotting your paycheck."
            + " Paycheck investing is paused until then.";

    /** Every DETECTED-only emailType of spec 10. Phase 1 has no caller for any of them. */
    private static final List<String> DETECTED_ONLY_TYPES = List.of("PAYDAY_D1_DETECTED", "PAYCHECK_FOUND",
            "SKIP_AFFORDABILITY", "SKIP_CATCHUP", "SKIP_HOLD_ACH", "SKIP_HOLD", "SKIP_NO_AUTH", "DORMANT",
            "INTERRUPTION", "WELCOME_BACK", "NEW_EMPLOYER", "CADENCE_CHANGE", "AUTHORIZATION_NEEDED",
            "DORMANT_120");

    private EmailService emailService;
    private InvestmentScheduleRepository schedules;
    private User user;
    private InvestmentSchedule schedule;

    @BeforeEach
    void setUp() {
        emailService = mock(EmailService.class);
        schedules = mock(InvestmentScheduleRepository.class);
        user = new User("saver@fredvested.com");
        user.setId(42L);
        user.setAccountStatus("ACTIVE");
        user.setPlaidAccessToken("access-sandbox-token");
        user.setPlaidInstitutionName("Chase");
        user.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED);
        user.setPaycheckAutopilotOptOut(false);
        schedule = new InvestmentSchedule();
        schedule.setUser(user);
        when(schedules.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(schedule));
        when(emailService.hasBeenSent(anyString(), anyLong(), anyString())).thenReturn(false);
    }

    private static PaycheckAutopilotProperties props(boolean enabled, boolean shadow) {
        return new PaycheckAutopilotProperties(enabled, shadow, false, 55, 70, new BigDecimal("0.90"), 365);
    }

    private NotificationService service(PaycheckAutopilotProperties props) {
        return new NotificationService(emailService, schedules, new NotificationGate(props), props);
    }

    /** Phase 1's configuration: enabled, shadow, no pulls. */
    private NotificationService phaseOne() {
        return service(props(true, true));
    }

    private static Supplier<EmailMessage> content() {
        return () -> new EmailMessage(null, null, "Subject", "<p>Body</p>", "Body");
    }

    private EmailMessage sent(String type, String key) {
        ArgumentCaptor<EmailMessage> cap = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService).sendEmail(cap.capture(), eq(42L), eq(type), eq(key));
        return cap.getValue();
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = NotificationServiceTest.class.getResourceAsStream("/templates/email/" + name)) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void periodKeyLongerThanTenRejected() {
        NotificationService s = phaseOne();
        assertThat(NotificationService.PERIOD_KEY_MAX).isEqualTo(10);
        assertThatThrownBy(() -> s.sendOnce(user, "RECONNECT", "2026-11-04X", content()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("periodKey");
        assertThatThrownBy(() -> s.sendOnce(user, "RECONNECT", null, content()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.sendOnce(user, "X".repeat(41), "2026-11-04", content()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("emailType");
        assertThatThrownBy(() -> s.sendDetectedOnce(user, schedule, "PAYCHECK_FOUND", "12345678901", content()))
                .as("checked before the gate").isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(emailService);
    }

    @Test
    void alreadySentSendsNothingAndNeverComposes() {
        when(emailService.hasBeenSent("RECONNECT", 42L, "2026-11-04")).thenReturn(true);
        boolean result = phaseOne().sendOnce(user, "RECONNECT", "2026-11-04",
                () -> { throw new AssertionError("must not compose a notice already in the ledger"); });
        assertThat(result).isFalse();
        verify(emailService, never()).sendEmail(any(EmailMessage.class), any(), any(), any());
    }

    @Test
    void sendOnceAddressesTheUserAndRecordsTypeAndKey() {
        assertThat(phaseOne().sendOnce(user, "RECONNECT", "2026-11-04", content())).isTrue();
        EmailMessage m = sent("RECONNECT", "2026-11-04");
        assertThat(m.getTo()).isEqualTo("saver@fredvested.com");
        assertThat(m.getSubject()).isEqualTo("Subject");
    }

    @Test
    void composeFailureRecordsFailedAttemptAndNeverThrows() {
        boolean result = phaseOne().sendOnce(user, "RECONNECT", "2026-11-04",
                () -> { throw new IllegalStateException("Unresolved placeholder"); });
        assertThat(result).isFalse();
        verify(emailService).recordFailedAttempt(42L, "saver@fredvested.com", "RECONNECT", "2026-11-04", "compose_failed");
        verify(emailService, never()).sendEmail(any(EmailMessage.class), any(), any(), any());
    }

    @Test
    void noRecipientSendsNothing() {
        user.setEmail(" ");
        assertThat(phaseOne().sendOnce(user, "RECONNECT", "2026-11-04", content())).isFalse();
        verifyNoInteractions(emailService);
    }

    @Test
    void ledgerFailureSendsNothing() {
        when(emailService.hasBeenSent(anyString(), anyLong(), anyString())).thenThrow(new RuntimeException("db down"));
        assertThat(phaseOne().sendOnce(user, "RECONNECT", "2026-11-04", content())).isFalse();
        verify(emailService, never()).sendEmail(any(EmailMessage.class), any(), any(), any());
    }

    @Test
    void reconnectManualCopy() {
        schedule.setMode(ScheduleMode.MANUAL);
        assertThat(phaseOne().sendReconnect(user, INCIDENT)).isTrue();
        EmailMessage m = sent(NotificationService.TYPE_RECONNECT, "2026-11-04");
        assertThat(m.getTo()).isEqualTo("saver@fredvested.com");
        assertThat(m.getSubject()).isEqualTo("Reconnect your bank account");
        assertThat(m.getTextBody()).startsWith(MANUAL_LINE);
        assertThat(m.getHtmlBody()).contains(MANUAL_LINE);
        assertThat(m.getTextBody()).doesNotContain("paused");
        assertThat(m.getHtmlBody()).doesNotContain("{{");
    }

    @Test
    void reconnectDetectedCopy() {
        schedule.setMode(ScheduleMode.DETECTED);
        assertThat(phaseOne().sendReconnect(user, INCIDENT)).isTrue();
        EmailMessage m = sent("RECONNECT", "2026-11-04");
        assertThat(m.getTextBody()).startsWith(DETECTED_LINE);
        assertThat(m.getHtmlBody()).contains(DETECTED_LINE);
    }

    @Test
    void reconnectEscapesInstitutionNameInHtmlOnly() {
        user.setPlaidInstitutionName("Smith & Sons <Bank>");
        phaseOne().sendReconnect(user, INCIDENT);
        EmailMessage m = sent("RECONNECT", "2026-11-04");
        assertThat(m.getHtmlBody()).contains("Smith &amp; Sons &lt;Bank&gt;").doesNotContain("<Bank>");
        assertThat(m.getTextBody()).startsWith("Reconnect your Smith & Sons <Bank> account");
    }

    @Test
    void reconnectFallsBackToYourBank() {
        user.setPlaidInstitutionName(null);
        phaseOne().sendReconnect(user, INCIDENT);
        assertThat(sent("RECONNECT", "2026-11-04").getTextBody())
                .startsWith("Reconnect your bank account so your investing keeps running.");
    }

    @Test
    void reconnectSkippedForRejectedApplicant() {
        user.setAccountStatus("REJECTED");
        assertThat(phaseOne().sendReconnect(user, INCIDENT)).isFalse();
        verifyNoInteractions(emailService);
    }

    @Test
    void reconnectSkippedWithoutScheduleRow() {
        when(schedules.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());
        assertThat(phaseOne().sendReconnect(user, INCIDENT)).isFalse();
        verifyNoInteractions(emailService);
    }

    @Test
    void reconnectSkippedWhenAutopilotDisabled() {
        assertThat(service(props(false, true)).sendReconnect(user, INCIDENT)).isFalse();
        verifyNoInteractions(emailService);
    }

    @Test
    void reconnectStillSentUnderShadowForOptedOutAndBrokenItem() {
        user.setPaycheckAutopilotOptOut(true);
        assertThat(phaseOne().sendReconnect(user, INCIDENT)).isTrue();
        sent("RECONNECT", "2026-11-04");
    }

    @Test
    void shadowSuppressesEveryDetectedNotice() {
        NotificationService s = phaseOne();
        for (String type : DETECTED_ONLY_TYPES) {
            assertThat(s.sendDetectedOnce(user, schedule, type, "2026-11",
                    () -> { throw new AssertionError("composed under shadow: " + type); }))
                    .as(type).isFalse();
        }
        verifyNoInteractions(emailService);
    }

    @Test
    void detectedNoticeSendsWhenGateOpen() {
        assertThat(service(props(true, false)).sendDetectedOnce(user, schedule, "PAYCHECK_FOUND", "917", content()))
                .isTrue();
        sent("PAYCHECK_FOUND", "917");
    }

    @Test
    void reconnectCopyAndTemplatesHaveNoEmDash() throws IOException {
        String emDash = String.valueOf((char) 0x2014);
        List<String> texts = List.of(NotificationService.RECONNECT_SUBJECT, NotificationService.RECONNECT_LINE_MANUAL,
                NotificationService.RECONNECT_LINE_DETECTED, resource("reconnect.html"), resource("reconnect.txt"),
                NotificationService.composeReconnect("Chase", ScheduleMode.MANUAL).getHtmlBody(),
                NotificationService.composeReconnect("Chase", ScheduleMode.DETECTED).getTextBody());
        for (String text : texts) {
            assertThat(text).doesNotContain(emDash).doesNotContain("&mdash;");
        }
    }
}
```

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.NotificationServiceTest' --console=plain`. Expected: `> Task :compileTestJava FAILED` with `error: cannot find symbol` and `symbol:   class NotificationService`.

- [ ] **Step 9: The two RECONNECT templates**

Create `backend/src/main/resources/templates/email/reconnect.html` (a reduced copy of `market-breakdown.html`'s shell: same colours and font stack, no `&mdash;`):

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Reconnect your bank account</title>
</head>
<body style="margin:0; padding:24px 0; background-color:#F3F4F6; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
  <table role="presentation" width="600" align="center" cellpadding="0" cellspacing="0" style="max-width:600px; width:100%; background-color:#FFFFFF; border-radius:16px;">
    <tr><td style="background-color:#2563EB; padding:28px 32px; border-radius:16px 16px 0 0; font-size:22px; font-weight:800; color:#FFFFFF;">FRED</td></tr>
    <tr><td style="padding:32px; font-size:15px; line-height:1.6; color:#111827;">
      <p style="margin:0 0 16px 0; font-size:17px; font-weight:700;">{{message}}</p>
      <p style="margin:0;">Open FRED, go to Recurring Investments, and tap Reconnect.</p>
    </td></tr>
    <tr><td style="padding:24px 32px; background-color:#F9FAFB; border-top:1px solid #E5E7EB; font-size:12px; color:#6B7280;">
      Questions? <a href="mailto:help@fredvested.com" style="color:#2563EB; text-decoration:none;">help@fredvested.com</a>
    </td></tr>
  </table>
</body>
</html>
```

Create `backend/src/main/resources/templates/email/reconnect.txt`:

```text
{{message}}

Open FRED, go to Recurring Investments, and tap Reconnect.

Questions? help@fredvested.com
```

"Reconnect" is the button T21 adds to the card on `/recurring-investments`, shown in both modes (R10).

- [ ] **Step 10: NotificationService**

Create `backend/src/main/java/com/investingapp/backend/service/NotificationService.java`:

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.dto.EmailMessage;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.service.DetectionEligibility.ExclusionReason;
import com.investingapp.backend.util.EmailTemplateRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The single sender for Paycheck Autopilot notices (spec 10), keyed in email_log by (email_type,
 * user_id, period_key). No lock: two concurrent attempts may both try, and the V1 unique index
 * uq_email_type_user_period keeps the ledger single (spec 10's one-transaction insert waits for
 * Phase 2's real sender). Callers invoke it after their commit; this class opens no transaction.
 * Logs carry the type and a closed reason only: never recipient, bank, copy, user id or key.
 */
@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    /** email_log.period_key and email_type widths. */
    public static final int PERIOD_KEY_MAX = 10;
    static final int EMAIL_TYPE_MAX = 40;

    public static final String TYPE_RECONNECT = "RECONNECT";

    static final String RECONNECT_SUBJECT = "Reconnect your bank account";
    /** Spec 10 RECONNECT copy; %s is the institution name, or "bank". */
    static final String RECONNECT_LINE_MANUAL = "Reconnect your %s account so your investing keeps running.";
    static final String RECONNECT_LINE_DETECTED = "Reconnect your %s account so FRED can keep spotting your paycheck."
            + " Paycheck investing is paused until then.";
    static final String BANK_FALLBACK = "bank";
    static final String FAILED_COMPOSE = "compose_failed";

    private final EmailService emailService;
    private final InvestmentScheduleRepository investmentScheduleRepository;
    private final NotificationGate notificationGate;
    private final PaycheckAutopilotProperties props;

    public NotificationService(EmailService emailService,
            InvestmentScheduleRepository investmentScheduleRepository,
            NotificationGate notificationGate,
            PaycheckAutopilotProperties props) {
        this.emailService = emailService;
        this.investmentScheduleRepository = investmentScheduleRepository;
        this.notificationGate = notificationGate;
        this.props = props;
    }

    /**
     * At most once per (emailType, user, periodKey). True when handed to EmailService, false when
     * skipped. Only a null builder, or a type or key that cannot fit the ledger, throws. The builder supplies subject
     * and bodies; the recipient is always the user's own address.
     */
    public boolean sendOnce(User user, String emailType, String periodKey, Supplier<EmailMessage> builder) {
        requireKey(emailType, periodKey);
        Objects.requireNonNull(builder, "builder");
        if (user == null || user.getId() == null || user.getEmail() == null || user.getEmail().isBlank()) {
            logger.info("paycheck.notice.skipped type={} reason=no_recipient", emailType);
            return false;
        }
        boolean alreadySent;
        try {
            alreadySent = emailService.hasBeenSent(emailType, user.getId(), periodKey);
        } catch (RuntimeException e) {
            logger.warn("paycheck.notice.skipped type={} reason=ledger_unavailable error_class={}",
                    emailType, e.getClass().getSimpleName());
            return false;
        }
        if (alreadySent) {
            logger.info("paycheck.notice.skipped type={} reason=already_sent", emailType);
            return false;
        }
        EmailMessage message;
        try {
            message = builder.get();
        } catch (RuntimeException e) {
            message = null;
            logger.warn("paycheck.notice.compose_failed type={} error_class={}", emailType, e.getClass().getSimpleName());
        }
        if (message == null) {
            emailService.recordFailedAttempt(user.getId(), user.getEmail(), emailType, periodKey, FAILED_COMPOSE);
            return false;
        }
        message.setTo(user.getEmail());
        emailService.sendEmail(message, user.getId(), emailType, periodKey);
        logger.info("paycheck.notice.sent type={}", emailType);
        return true;
    }

    /**
     * RECONNECT (spec 10, both modes), keyed by the incident's ET date. Nothing when disabled
     * (12.1), REJECTED or without a schedule row (R18). An opt-out or a broken Item does not stop
     * it, and the gate is not consulted: RECONNECT is not a DETECTED-only notice.
     */
    public boolean sendReconnect(User user, LocalDate incidentDate) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(incidentDate, "incidentDate");
        Optional<ExclusionReason> excluded = DetectionEligibility.exclusion(user, props);
        if (excluded.isPresent()
                && (excluded.get() == ExclusionReason.DISABLED || excluded.get() == ExclusionReason.ACCOUNT_REJECTED)) {
            logger.info("paycheck.notice.skipped type={} reason={}", TYPE_RECONNECT,
                    excluded.get().name().toLowerCase(Locale.ROOT));
            return false;
        }
        Optional<InvestmentSchedule> schedule = investmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(user);
        if (schedule.isEmpty()) {
            logger.info("paycheck.notice.skipped type={} reason=no_schedule", TYPE_RECONNECT);
            return false;
        }
        ScheduleMode mode = schedule.get().getMode() == null ? ScheduleMode.MANUAL : schedule.get().getMode();
        String bank = bankLabel(user.getPlaidInstitutionName());
        return sendOnce(user, TYPE_RECONNECT, incidentDate.toString(), () -> composeReconnect(bank, mode));
    }

    /** Every DETECTED-only notice of spec 10: key checked first, then the gate. No Phase 1 caller. */
    public boolean sendDetectedOnce(User user, InvestmentSchedule schedule, String emailType, String periodKey,
            Supplier<EmailMessage> builder) {
        requireKey(emailType, periodKey);
        if (notificationGate.suppressed(user, schedule)) {
            return false;
        }
        return sendOnce(user, emailType, periodKey, builder);
    }

    /** The RECONNECT message without a recipient; sendOnce fills it. The bank name is escaped for HTML. */
    static EmailMessage composeReconnect(String bank, ScheduleMode mode) {
        String line = String.format(mode == ScheduleMode.DETECTED ? RECONNECT_LINE_DETECTED : RECONNECT_LINE_MANUAL, bank);
        String html = EmailTemplateRenderer.render("reconnect.html", Map.of("message", HtmlUtils.htmlEscape(line)));
        String text = EmailTemplateRenderer.render("reconnect.txt", Map.of("message", line));
        return new EmailMessage(null, null, RECONNECT_SUBJECT, html, text);
    }

    static String bankLabel(String institutionName) {
        return institutionName == null || institutionName.isBlank() ? BANK_FALLBACK : institutionName.trim();
    }

    private static void requireKey(String emailType, String periodKey) {
        if (emailType == null || emailType.isBlank() || emailType.length() > EMAIL_TYPE_MAX) {
            throw new IllegalArgumentException("emailType must be 1 to " + EMAIL_TYPE_MAX
                    + " characters to fit email_log.email_type");
        }
        if (periodKey == null || periodKey.isBlank() || periodKey.length() > PERIOD_KEY_MAX) {
            throw new IllegalArgumentException("periodKey must be 1 to " + PERIOD_KEY_MAX
                    + " characters to fit email_log.period_key: an ISO date, a YYYY-MM bucket or an id");
        }
    }
}
```

Run the Step 8 command, then the XML grep for `TEST-com.investingapp.backend.service.NotificationServiceTest.xml`. Expected: `BUILD SUCCESSFUL` and `tests="17" skipped="0" failures="0" errors="0"`.

- [ ] **Step 11: The money-invariance guard**

Create `backend/src/test/java/com/investingapp/backend/PhaseOneMoneyInvarianceTest.java`:

```java
package com.investingapp.backend;

import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.service.ScheduledInvestmentProcessor;
import com.investingapp.backend.service.ScheduledInvestmentProcessor.ProcessOutcome;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 1 runs in shadow: it must not move money, write a schedule's status or mode, read the
 * deprecated pause getters, or alter the runnable rule. A task adding a source outside
 * PHASE_ONE_SOURCES extends the list in its own change.
 */
class PhaseOneMoneyInvarianceTest {

    static final Path MAIN = Paths.get("src", "main", "java", "com", "investingapp", "backend")
            .toAbsolutePath().normalize();

    /**
     * Every file a Phase 1 task creates or modifies, relative to MAIN. A trailing '/' walks a
     * directory (it may not exist yet); a trailing '*' is a file-name prefix in that directory.
     */
    static final List<String> PHASE_ONE_SOURCES = List.of(
            "service/Paycheck*", "service/Plaid*", "service/Admin*",
            "service/InitSyncEnqueuer.java", "service/NotificationService.java", "service/NotificationGate.java",
            "service/DetectionEligibility.java", "service/ShadowReportService.java",
            "config/PaycheckAutopilotProperties.java",
            "controller/admin/", "controller/PaycheckDetectionController.java", "controller/DevPaycheckController.java",
            "paycheck/", "plaid/", "ops/");

    /** An InvestmentSchedule.Status write; PlaidWebhookEvent.setStatus(status) stays legal. */
    static final Pattern SCHEDULE_STATUS_WRITE = Pattern.compile("setStatus\\(\\s*(InvestmentSchedule\\.)?Status\\.");
    static final List<String> FORBIDDEN_CALLS = List.of("setMode(", "getIsPaused(", "isPausedForAnyReason(");

    private static final String READY_SIGNATURE = "public boolean isReadyForInvestment(LocalDate todayET)";
    private static final String PINNED_READY_RULE = """
            public boolean isReadyForInvestment(LocalDate todayET) {
                if (status != Status.ACTIVE || achRequestId == null) {
                    return false;
                }
                if (holdReason != null && BLOCKING_HOLDS.contains(holdReason)) {
                    return false;
                }
                return (startDate != null && startDate.equals(todayET))
                        || (nextInvestmentDate != null && !nextInvestmentDate.isAfter(todayET));
            }""";

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    @Test
    void isReadyForInvestmentBodyUnchanged() throws IOException {
        String source = Files.readString(MAIN.resolve("model/InvestmentSchedule.java"));
        assertThat(normalize(methodText(source, READY_SIGNATURE)))
                .as("Phase 1 never changes the runnable rule")
                .isEqualTo(normalize(PINNED_READY_RULE));
    }

    @Test
    void processorStillRefusesDetectedMode() {
        InvestmentScheduleRepository schedules = mock(InvestmentScheduleRepository.class);
        InvestmentExecutionRepository executions = mock(InvestmentExecutionRepository.class);
        User user = new User("shadow@fredvested.com");
        user.setId(42L);
        user.setAccountStatus("ACTIVE");
        InvestmentSchedule s = runnable();
        s.setId(7L);
        s.setUser(user);
        s.setMode(ScheduleMode.DETECTED);
        when(schedules.findById(7L)).thenReturn(Optional.of(s));

        assertThat(s.isReadyForInvestment(TODAY)).as("the MANUAL rule alone would run this row").isTrue();
        assertThat(new ScheduledInvestmentProcessor(schedules, executions).processOne(7L, TODAY))
                .isEqualTo(ProcessOutcome.NOT_ELIGIBLE);
        verify(executions, never()).save(any());
        verify(schedules, never()).save(any());
    }

    @Test
    void blockingHoldsStillThree() {
        EnumSet<HoldReason> blocking = EnumSet.noneOf(HoldReason.class);
        for (HoldReason hold : HoldReason.values()) {
            InvestmentSchedule s = runnable();
            s.setHoldReason(hold);
            if (!s.isReadyForInvestment(TODAY)) {
                blocking.add(hold);
            }
        }
        assertThat(blocking).containsExactlyInAnyOrder(
                HoldReason.ACH_RETURN_UNAUTHORIZED, HoldReason.ACH_RETURN_ACCOUNT, HoldReason.MANUAL_REVIEW);
        assertThat(HoldReason.values()).as("a new hold reason needs a blocking decision and a view change").hasSize(10);
    }

    @Test
    void phaseOneSourcesNeverCallSetStatusOrSetMode() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : phaseOneFiles()) {
            offenders.addAll(forbiddenCalls(MAIN.relativize(file).toString().replace('\\', '/'), Files.readString(file)));
        }
        assertThat(offenders).as("Phase 1 never writes status or mode, nor reads the pause getters").isEmpty();
    }

    @Test
    void walkSeesThisTasksFilesAndThePhaseZeroPlaidServices() throws IOException {
        // A walk that matched nothing would pass vacuously.
        assertThat(phaseOneFiles()).extracting(p -> p.getFileName().toString()).contains(
                "PlaidService.java", "PlaidWebhookInboxService.java", "PlaidItemLifecycleService.java",
                "DetectionEligibility.java", "NotificationGate.java", "NotificationService.java",
                "PaycheckAutopilotProperties.java");
    }

    @Test
    void matcherCatchesScheduleWritesButNotWebhookStatus() {
        assertThat(forbiddenCalls("a.java", "event.setStatus(status);\nevent.setStatus(PlaidWebhookEventStatus.PROCESSED);"))
                .isEmpty();
        assertThat(forbiddenCalls("b.java", "s.setStatus(InvestmentSchedule.Status.ACTIVE);"))
                .containsExactly("b.java:1 writes InvestmentSchedule.status");
        assertThat(forbiddenCalls("c.java", "x();\ns.setStatus(\n        Status.PAUSED_BY_USER);"))
                .containsExactly("c.java:2 writes InvestmentSchedule.status");
        assertThat(forbiddenCalls("d.java", "s.setMode(ScheduleMode.DETECTED);\nif (s.getIsPaused()) {}\ns.isPausedForAnyReason();"))
                .containsExactly("d.java:1 calls setMode(", "d.java:2 calls getIsPaused(", "d.java:3 calls isPausedForAnyReason(");
    }

    /** An ACTIVE, linked MANUAL schedule due today, with no hold. */
    private static InvestmentSchedule runnable() {
        InvestmentSchedule s = new InvestmentSchedule();
        s.setStatus(InvestmentSchedule.Status.ACTIVE);
        s.setAchRequestId("rel-1");
        s.setFrequency("BIWEEKLY");
        s.setInvestmentAmount(new BigDecimal("150.00"));
        s.setMonthlyAmount(new BigDecimal("325.00"));
        s.setStartDate(TODAY.minusWeeks(6));
        s.setNextInvestmentDate(TODAY);
        return s;
    }

    static List<Path> phaseOneFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String entry : PHASE_ONE_SOURCES) {
            if (entry.endsWith("/")) {
                Path dir = MAIN.resolve(entry);
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> walk = Files.walk(dir)) {
                        walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
                    }
                }
            } else if (entry.endsWith("*")) {
                int slash = entry.lastIndexOf('/');
                Path dir = MAIN.resolve(entry.substring(0, slash));
                String prefix = entry.substring(slash + 1, entry.length() - 1);
                try (Stream<Path> list = Files.list(dir)) {
                    list.filter(p -> p.getFileName().toString().startsWith(prefix) && p.toString().endsWith(".java"))
                            .forEach(files::add);
                }
            } else {
                Path file = MAIN.resolve(entry);
                if (Files.isRegularFile(file)) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    static List<String> forbiddenCalls(String relPath, String source) {
        List<String> found = new ArrayList<>();
        Matcher m = SCHEDULE_STATUS_WRITE.matcher(source);
        while (m.find()) {
            found.add(relPath + ":" + lineOf(source, m.start()) + " writes InvestmentSchedule.status");
        }
        for (String call : FORBIDDEN_CALLS) {
            for (int i = source.indexOf(call); i >= 0; i = source.indexOf(call, i + 1)) {
                found.add(relPath + ":" + lineOf(source, i) + " calls " + call);
            }
        }
        return found;
    }

    static String methodText(String source, String signature) {
        int start = source.indexOf(signature);
        assertThat(start).as("signature not found: " + signature).isGreaterThanOrEqualTo(0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(start, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }

    static int lineOf(String source, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
```

A guard over code that already holds, so it passes on its first run; `matcherCatchesScheduleWritesButNotWebhookStatus` proves it bites. `PHASE_ONE_SOURCES` adds `config/PaycheckAutopilotProperties.java`, which this task creates outside the contract's list.

Run `cd backend && ./gradlew test --tests 'com.investingapp.backend.PhaseOneMoneyInvarianceTest' --console=plain`, then the XML grep for that class. Expected: `BUILD SUCCESSFUL` and `tests="6" skipped="0" failures="0" errors="0"`. A failure names `file:line`; fix that file, never the patterns.

- [ ] **Step 12: Boot gate on a scratch MySQL schema (BackendApplicationTests, 0 skipped)**

`BackendApplicationTests` is `@RequiresCiDatabase`: without a database it is skipped, not failed, and Gradle still says `BUILD SUCCESSFUL`. It proves every `@Value` resolves under `ci` and the new beans wire. Run the block as ONE shell invocation from the repository root: it reads the MySQL user and password from the gitignored `application-local.properties` into variables without printing them, creates a throwaway schema (never `investing_dev`), and drops it.

```bash
MYSQL_USER=$(sed -n 's/^spring\.datasource\.username=//p' backend/src/main/resources/application-local.properties)
MYSQL_PW=$(sed -n 's/^spring\.datasource\.password=//p' backend/src/main/resources/application-local.properties)
db() { MYSQL_PWD="$MYSQL_PW" mysql -h 127.0.0.1 -u "$MYSQL_USER" -N -B "$@"; }
db -e "DROP DATABASE IF EXISTS fred_t03_scratch; CREATE DATABASE fred_t03_scratch"
export SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_t03_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'
export SPRING_DATASOURCE_USERNAME="$MYSQL_USER"
export SPRING_DATASOURCE_PASSWORD="$MYSQL_PW"
export PLAID_WEBHOOK_URL='https://ci.invalid/api/plaid/webhook'
export PLAID_REDIRECT_URI='https://ci.invalid/plaid/oauth'
export PLAID_LINK_CUSTOMIZATION=default
(cd backend && ./gradlew cleanTest test --tests 'com.investingapp.backend.BackendApplicationTests' --console=plain)
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml
grep -c 'paycheck.autopilot.config enabled=false shadow=true pulls_enabled=false' backend/build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml
db -e "DROP DATABASE fred_t03_scratch"
unset SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD PLAID_WEBHOOK_URL PLAID_REDIRECT_URI PLAID_LINK_CUSTOMIZATION MYSQL_PW
```

Expected: `BUILD SUCCESSFUL`; exactly `tests="1" skipped="0" failures="0" errors="0"` (`skipped="1"` is not a pass: the `<skipped message=...>` names the cause); the count `1` (the config line in system-out, `enabled=false` from the ci literal); the drop prints nothing. `paycheck.autopilot settings are inconsistent` means a Step 3 line is mistyped.

- [ ] **Step 13: Local properties note (manual, not committed)**

A local boot needs nothing (the base file carries every key with a default). To run shadow mode locally, hand-add these seven lines to the gitignored `backend/src/main/resources/application-local.properties` (never staged) and restart:

```properties
# Paycheck Autopilot, Phase 1 shadow mode (hand-added; this file is gitignored)
paycheck.autopilot.enabled=${PAYCHECK_AUTOPILOT_ENABLED:true}
paycheck.autopilot.shadow=${PAYCHECK_AUTOPILOT_SHADOW:true}
paycheck.autopilot.pulls.enabled=${PAYCHECK_AUTOPILOT_PULLS_ENABLED:false}
paycheck.autopilot.score.provisional=${PAYCHECK_SCORE_PROVISIONAL:55}
paycheck.autopilot.score.confirmed=${PAYCHECK_SCORE_CONFIRMED:70}
paycheck.autopilot.affordability.share=${PAYCHECK_AFFORDABILITY_SHARE:0.90}
paycheck.autopilot.days-requested=${PAYCHECK_DAYS_REQUESTED:365}
```

Expected in the boot log: `paycheck.autopilot.config enabled=true shadow=true pulls_enabled=false score_provisional=55 score_confirmed=70 affordability_share=0.90 days_requested=365`. Never set `shadow=false` or `pulls.enabled=true` in Phase 1. The plan's manual prerequisites repeat this list.

- [ ] **Step 14: Whole backend suite on a fresh scratch schema, and house-style checks**

Re-run the Step 12 block (one invocation, repository root) with three mechanical changes only: `fred_t03_scratch` becomes `fred_t03_suite` (every occurrence); the Gradle line becomes `(cd backend && ./gradlew cleanTest test --console=plain)`; the two `grep` lines become this sum:

```bash
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' backend/build/test-results/test/*.xml \
  | awk -F'"' '{t+=$2; s+=$4; f+=$6; e+=$8} END {print "tests=" t, "skipped=" s, "failures=" f, "errors=" e}'
```

Expected: `BUILD SUCCESSFUL` and `skipped=0 failures=0 errors=0`; `tests=` is the total before this task plus 42 (5 + 9 + 5 + 17 + 6). A non-zero `skipped` means the scratch schema was not reached. Then, from the repository root:

```bash
M=backend/src/main/java/com/investingapp/backend; T=backend/src/test/java/com/investingapp/backend
grep -n -e $'\xe2\x80\x94' -e '&mdash;' $M/config/PaycheckAutopilotProperties.java $M/service/{DetectionEligibility,NotificationGate,NotificationService}.java backend/src/main/resources/templates/email/reconnect.{html,txt} $T/config/PaycheckAutopilotPropertiesTest.java $T/service/{DetectionEligibility,NotificationGate,NotificationService}Test.java $T/PhaseOneMoneyInvarianceTest.java
git diff HEAD -- backend/src/main/resources/application.properties backend/src/main/resources/application-ci.properties | grep -n $'^+.*\xe2\x80\x94'
grep -n -e 'setHoldReason(' -e 'setStatus(' -e 'setMode(' -e 'analyticsOutboxService' -e 'Transactional' $M/config/PaycheckAutopilotProperties.java $M/service/{DetectionEligibility,NotificationGate,NotificationService}.java
git diff --stat HEAD
git status --short
```

Expected: the three greps print nothing (no em dash; no hold, status or mode write, outbox call or transaction in this task's code). `git diff --stat HEAD` lists only `application.properties` (11 lines added) and `application-ci.properties` (4). `git status --short` shows those two as ` M` and the eleven new files as `??` (plus other sessions' files, which you do not stage); `application-local.properties` must not appear.

- [ ] **Step 15: Commit exactly these files**

The repository may be a shared dirty tree: hunk-verify each modified file with `git diff HEAD -- <file>` (only this task's block added), stage by full path only, never `git add -A`, and never `git checkout`, `git restore`, `git stash` or `git reset`. Never stage `ITPM/agent-memory/*` or `application-local.properties`.

```bash
M=backend/src/main/java/com/investingapp/backend; R=backend/src/main/resources; T=backend/src/test/java/com/investingapp/backend
git add $M/config/PaycheckAutopilotProperties.java $M/service/DetectionEligibility.java \
  $M/service/NotificationGate.java $M/service/NotificationService.java \
  $R/templates/email/reconnect.html $R/templates/email/reconnect.txt \
  $R/application.properties $R/application-ci.properties \
  $T/config/PaycheckAutopilotPropertiesTest.java $T/service/DetectionEligibilityTest.java \
  $T/service/NotificationGateTest.java $T/service/NotificationServiceTest.java $T/PhaseOneMoneyInvarianceTest.java
git status --short
git commit -m "feat(paycheck): add autopilot properties, detection eligibility, notice gate and the RECONNECT sender"
```

Expected: before the commit, `git status --short` shows the eleven new files as `A ` and the two property files as `M `, and nothing else staged.

### Verification

Each class runs alone with `cd backend && ./gradlew test --tests '<class>' --console=plain`, then `grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-<class>.xml`. Read counts from the XML, never the console.

| class or check | expected |
| --- | --- |
| Step 1 preconditions | T02's field present; no file of this task; no `paycheck.autopilot` key yet |
| `com.investingapp.backend.config.PaycheckAutopilotPropertiesTest` | `tests="5" skipped="0" failures="0" errors="0"` |
| `com.investingapp.backend.service.DetectionEligibilityTest` | `tests="9" skipped="0" failures="0" errors="0"` |
| `com.investingapp.backend.service.NotificationGateTest` | `tests="5" skipped="0" failures="0" errors="0"` |
| `com.investingapp.backend.service.NotificationServiceTest` | `tests="17" skipped="0" failures="0" errors="0"` |
| `com.investingapp.backend.PhaseOneMoneyInvarianceTest` | `tests="6" skipped="0" failures="0" errors="0"` |
| `com.investingapp.backend.BackendApplicationTests` (Step 12 block, scratch `fred_t03_scratch`) | `tests="1" skipped="0" failures="0" errors="0"`; config line count `1` |
| whole backend suite (Step 14, scratch `fred_t03_suite`) | `skipped=0 failures=0 errors=0`; total = previous + 42 |
| Step 14 greps and `git status --short` | no em dash; no hold, status, mode, outbox or transaction in this task's code; 2 modified, 11 new files |

No frontend file changes, so no Karma run belongs to this task.

### Produces

- `config.PaycheckAutopilotProperties` (`@Component`): `()` and `(boolean enabled, boolean shadow, boolean pullsEnabled, int scoreProvisional, int scoreConfirmed, BigDecimal affordabilityShare, int daysRequested)`; `isEnabled()`, `isShadow()`, `isPullsEnabled()`, `int getScoreProvisional()`, `int getScoreConfirmed()`, `BigDecimal getAffordabilityShare()`, `int getDaysRequested()`. Keys `paycheck.autopilot.{enabled, shadow, pulls.enabled, score.provisional, score.confirmed, affordability.share, days-requested}` with env defaults in `application.properties`; `paycheck.autopilot.enabled=false` in `application-ci.properties`.
- `service.DetectionEligibility`: nested `enum ExclusionReason {DISABLED, ACCOUNT_REJECTED, OPTED_OUT, NO_TOKEN, ITEM_STATUS}`; `static Optional<ExclusionReason> exclusion(User, PaycheckAutopilotProperties)` (ITEM_STATUS = not OK and not PENDING_DISCONNECT).
- `service.NotificationGate(PaycheckAutopilotProperties)`: `boolean suppressed(User, InvestmentSchedule scheduleOrNull)` (shadow, `!enabled` or hold KILL_SWITCH; logs `paycheck.notice.suppressed reason=`).
- `service.NotificationService(EmailService, InvestmentScheduleRepository, NotificationGate, PaycheckAutopilotProperties)`: `PERIOD_KEY_MAX = 10`, `TYPE_RECONNECT = "RECONNECT"`, `boolean sendOnce(User, String emailType, String periodKey, Supplier<EmailMessage>)`, `boolean sendReconnect(User, LocalDate incidentDate)`, `boolean sendDetectedOnce(User, InvestmentSchedule, String emailType, String periodKey, Supplier<EmailMessage>)`.
- Templates `email/reconnect.html` and `email/reconnect.txt` (placeholder `{{message}}`).
- `PhaseOneMoneyInvarianceTest` with `PHASE_ONE_SOURCES` and `isReadyForInvestmentBodyUnchanged`, `processorStillRefusesDetectedMode`, `blockingHoldsStillThree`, `phaseOneSourcesNeverCallSetStatusOrSetMode` (T11 and T13 add tests here).
