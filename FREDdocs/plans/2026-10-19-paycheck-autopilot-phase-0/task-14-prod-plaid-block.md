### Task 14: prod-plaid-block

Move the prod Plaid block to environment variables with `plaid.env=production`, extend `BrokerEnvironmentGuard` with the Phase 0 Plaid rules (exact `plaid.env`, https non-tunnel webhook URL in prod, refuse the ci bypass verifier bean and the once-committed sandbox secret), and write the manual rotation note. The guard is the only startup gate between a prod deploy and a laptop-shaped Plaid config, so every rule gets a red test first.

Scope, read against spec 12.1 and 2.10 before starting:
- Spec 12.1 writes `ci-bypass-key`; spec 2.8 and 12.2 say the ci webhook bypass is a `@Profile("ci")` verifier bean, not a property. The guard rule is therefore a bean-definition check on `ciBypassPlaidWebhookVerifier` (the T07 bean name), nothing keyed on a property.
- Spec 12.1 also lists refusing `app.ops.alert.provider=logging` and an unset `ADMIN_TOOLS_KEY`. Both belong to Phase 1 (the ops alert channel is a Phase 1 gate in spec 12.3; the admin surface is spec 8.10 / 12.2). Neither property exists today (`grep -rn "app.ops.alert\|ADMIN_TOOLS_KEY" backend/src` is empty). Do not add them here.
- Spec 2.10 says non-prod profiles need `plaid.env=sandbox` "and a public HTTPS receiver (the Cloudflare tunnel locally)". Outside prod the guard checks only `plaid.env`: the existing `defaultsAreSandbox` test runs the `test` profile with no properties at all and must keep passing, and Plaid itself returns 400 on `/link/token/create` for a bad webhook URL, which is the non-prod enforcement point.

**Files:**
- Create: `backend/src/test/java/com/investingapp/backend/config/ProdPlaidPropertiesTest.java`
- Create: `FREDdocs/plaid-sandbox-secret-rotation.md`
- Modify: `backend/src/main/java/com/investingapp/backend/config/BrokerEnvironmentGuard.java` (constants after :46; `checked` map :61-67; after the for loop :90; new helpers before `isSandboxLike` :102; class Javadoc after :36)
- Modify: `backend/src/test/java/com/investingapp/backend/config/BrokerEnvironmentGuardTest.java` (imports :5-6; constants + helpers :17-37; new tests after `defaultsAreSandbox` :115-124)
- Modify: `backend/src/main/resources/application-prod.properties:16-18` (the three Plaid literals become the 7-line env block)
- Test: `BrokerEnvironmentGuardTest` (11 today, 22 after), `ProdPlaidPropertiesTest` (new, 2), `DevToolsGatingTest` (unchanged, regression; it uses `ApplicationContextRunner` without this guard), `BackendApplicationTests` under ci (context gate)

**Interfaces:**
- Consumes (T07): bean name `ciBypassPlaidWebhookVerifier` (`@Component("ciBypassPlaidWebhookVerifier") @Profile("ci") CiBypassPlaidWebhookVerifier`); property `plaid.webhook.url` present in local/dev/test (`${PLAID_WEBHOOK_URL}`) and ci (`https://ci.invalid/api/plaid/webhook`).
- Consumes (T08): property keys `plaid.redirect.uri`, `plaid.link.customization` and the `@Value` fields `plaidWebhookUrl`, `plaidRedirectUri`, `plaidLinkCustomization` on `PlaidService` (prod must now supply them).
- Produces: `BrokerEnvironmentGuard`: `static final String PLAID_ENV_SANDBOX = "sandbox"`, `PLAID_ENV_PRODUCTION = "production"`, `KNOWN_SANDBOX_SECRET = "14256c166ee8a12a370a6ccf90abbf"`, `CI_BYPASS_VERIFIER_BEAN = "ciBypassPlaidWebhookVerifier"` (all package-private, like `PROD_PROFILE` at :41); `static boolean isTunnelUrl(String value)` (true when the lowercased value contains `trycloudflare.com` or `ngrok`); prod violations: `plaid.env` not exactly `production`, `plaid.webhook.url` blank / not `https://` / loopback / tunnel, `beanFactory.containsBeanDefinition(CI_BYPASS_VERIFIER_BEAN)`, `plaid.secret` equal to `KNOWN_SANDBOX_SECRET`; non-prod violation: `plaid.env` not exactly `sandbox`. Alpaca checks (:72-90) untouched. The webhook URL and secret are read only inside the prod branch, so an unresolved `${PLAID_WEBHOOK_URL}` in dev never fails inside the guard.
- Produces: `application-prod.properties` block `plaid.client.id=${PLAID_CLIENT_ID}`, `plaid.secret=${PLAID_SECRET_PRODUCTION}`, `plaid.env=production`, `plaid.client.name=FRED`, `plaid.webhook.url=${PLAID_WEBHOOK_URL}`, `plaid.link.customization=${PLAID_LINK_CUSTOMIZATION}`, `plaid.redirect.uri=${PLAID_REDIRECT_URI}`.
- Produces: `FREDdocs/plaid-sandbox-secret-rotation.md` (manual steps, no code).

Note on prod with an unset env var: `environment.getProperty("plaid.webhook.url", "")` on an unresolvable `${PLAID_WEBHOOK_URL}` throws Spring's `IllegalArgumentException: Could not resolve placeholder` before the guard's own message (same for `plaid.secret`). Both refuse to start; the guard's "is not set" text covers the explicitly empty case (`PLAID_WEBHOOK_URL=""`).

- [ ] **Step 1: Green refactor: add the guard constants and the 6-arg test helper (11 tests stay green)**

In `backend/src/main/java/com/investingapp/backend/config/BrokerEnvironmentGuard.java`, insert after line 46 (`static final String DEFAULT_PLAID_ENV = "sandbox";`):

```java

    /** plaid.env is an exact name, never a hostname, so it gets its own rule instead of the sandbox/paper substring match. */
    static final String PLAID_ENV_SANDBOX = "sandbox";
    static final String PLAID_ENV_PRODUCTION = "production";

    /** The sandbox secret that sat in application-prod.properties from b08d9e3 (2025-06-28) until Phase 0. Git history keeps it, so prod refuses it. */
    static final String KNOWN_SANDBOX_SECRET = "14256c166ee8a12a370a6ccf90abbf";

    /** The @Profile("ci") webhook verifier that accepts any Plaid-Verification header (T07). Never in prod. */
    static final String CI_BYPASS_VERIFIER_BEAN = "ciBypassPlaidWebhookVerifier";
```

That block is 10 lines, so everything from the old line 47 down moves 10 lines further. Later steps anchor on code text, not on line numbers, for that reason.

In `backend/src/test/java/com/investingapp/backend/config/BrokerEnvironmentGuardTest.java`, replace lines 17-37 (the four constants, `run`, and `env`) with:

```java
    private static final String LIVE_BROKER = "https://broker-api.alpaca.markets/v1";
    private static final String LIVE_TRADING = "https://api.alpaca.markets/v2";
    private static final String SANDBOX_BROKER = "https://broker-api.sandbox.alpaca.markets/v1";
    private static final String PAPER_TRADING = "https://paper-api.alpaca.markets/v2";
    private static final String PROD_WEBHOOK = "https://api.fredvested.com/api/plaid/webhook";
    private static final String LOCAL_WEBHOOK = "https://local.fredvested.com/api/plaid/webhook";
    private static final String PROD_SECRET = "prod-secret-not-the-sandbox-one";
    private static final String CI_SECRET = "ci-disabled";

    private void run(MockEnvironment env) {
        run(env, new DefaultListableBeanFactory());
    }

    private void run(MockEnvironment env, DefaultListableBeanFactory beanFactory) {
        BrokerEnvironmentGuard guard = new BrokerEnvironmentGuard();
        guard.setEnvironment(env);
        guard.postProcessBeanFactory(beanFactory);
    }

    /** The 4-arg form keeps the original tests readable: prod gets a public https webhook and a non-sandbox secret. */
    private MockEnvironment env(String profile, String broker, String trading, String plaidEnv) {
        boolean prod = BrokerEnvironmentGuard.PROD_PROFILE.equals(profile);
        return env(profile, broker, trading, plaidEnv,
                prod ? PROD_WEBHOOK : LOCAL_WEBHOOK,
                prod ? PROD_SECRET : CI_SECRET);
    }

    private MockEnvironment env(String profile, String broker, String trading, String plaidEnv,
            String webhookUrl, String plaidSecret) {
        MockEnvironment env = new MockEnvironment();
        if (profile != null) {
            env.setActiveProfiles(profile);
        }
        env.withProperty("alpaca.broker.base-url", broker)
                .withProperty("alpaca.trading.base-url", trading)
                .withProperty("plaid.env", plaidEnv)
                .withProperty("plaid.webhook.url", webhookUrl)
                .withProperty("plaid.secret", plaidSecret);
        return env;
    }
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.config.BrokerEnvironmentGuardTest'`
Expected: `BUILD SUCCESSFUL`; `grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' build/test-results/test/TEST-com.investingapp.backend.config.BrokerEnvironmentGuardTest.xml` prints `tests="11" skipped="0" failures="0" errors="0"` (that attribute order is what Gradle writes; see any existing `TEST-*.xml` under `build/test-results/test/`).

- [ ] **Step 2: Red: plaid.env must be an exact name in both directions**

Append inside the test class, after the closing brace of `defaultsAreSandbox()` (the last test):

```java

    @Test
    @DisplayName("non-prod plaid.env must be exactly sandbox: a look-alike value refuses to start")
    void devPlaidEnvLookalikeRefuses() {
        assertThatThrownBy(() -> run(env("dev", SANDBOX_BROKER, PAPER_TRADING, "sandbox-v2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plaid.env=sandbox-v2")
                .hasMessageContaining("must be exactly sandbox");
    }

    @Test
    @DisplayName("prod plaid.env must be exactly production: any other non-sandbox word refuses to start")
    void prodPlaidEnvLiveRefuses() {
        assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "live")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plaid.env=live")
                .hasMessageContaining("must be exactly production");
    }
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.config.BrokerEnvironmentGuardTest'`
Expected: `BUILD FAILED`, `13 tests completed, 2 failed`. Both fail with "Expecting code to raise a throwable" because today's substring rule accepts `sandbox-v2` (contains "sandbox") and `live` (neither sandbox nor loopback) at the prod/non-prod branches (`isSandboxLike` / `isLoopback`).

- [ ] **Step 3: Green: give plaid.env its own exact-match check**

In `BrokerEnvironmentGuard.postProcessBeanFactory`, delete the statement that puts `plaid.env` into the `checked` map (lines 66-67 before this task's edits; after Step 1 it sits directly under the `alpaca.trading.base-url` put). The two lines to delete read exactly:

```java
        checked.put("plaid.env",
                environment.getProperty("plaid.env", DEFAULT_PLAID_ENV));
```

Then, between the for loop's closing brace and `if (!violations.isEmpty()) {`, insert:

```java
        checkPlaidEnv(prod, violations);

```

Then insert this method directly before `static boolean isSandboxLike(String value) {`:

```java
    /** plaid.env is an exact name, not a hostname: sandbox outside prod, production in prod, nothing else. */
    private void checkPlaidEnv(boolean prod, List<String> violations) {
        String value = environment.getProperty("plaid.env", DEFAULT_PLAID_ENV);
        if (value == null || value.isBlank()) {
            violations.add("plaid.env is not set");
            return;
        }
        if (prod && !PLAID_ENV_PRODUCTION.equals(value)) {
            violations.add("plaid.env=" + value + " must be exactly " + PLAID_ENV_PRODUCTION
                    + ", but the prod profile is active");
        } else if (!prod && !PLAID_ENV_SANDBOX.equals(value)) {
            violations.add("plaid.env=" + value + " must be exactly " + PLAID_ENV_SANDBOX + " outside prod");
        }
    }

```

Run the same command. Expected: `BUILD SUCCESSFUL`, XML `tests="13" skipped="0" failures="0"`. The existing `devPlaidProductionRefuses` (expects `plaid.env=production`) and `prodSandboxRefuses` (expects `prod profile is active`) still match the new messages, and `defaultsAreSandbox` still passes because `getProperty("plaid.env", DEFAULT_PLAID_ENV)` yields `sandbox` when nothing is set.

- [ ] **Step 4: Red: prod needs a public https webhook receiver**

Append after `prodPlaidEnvLiveRefuses()`:

```java

    @Test
    @DisplayName("prod with no webhook URL refuses to start")
    void prodBlankWebhookRefuses() {
        assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production", "", PROD_SECRET)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plaid.webhook.url is not set");
    }

    @Test
    @DisplayName("prod with a plain-http webhook URL refuses to start")
    void prodHttpWebhookRefuses() {
        assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production",
                "http://api.fredvested.com/api/plaid/webhook", PROD_SECRET)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must start with https://");
    }

    @Test
    @DisplayName("prod with a loopback webhook URL refuses to start")
    void prodLoopbackWebhookRefuses() {
        assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production",
                "https://localhost:8443/api/plaid/webhook", PROD_SECRET)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plaid.webhook.url=https://localhost:8443")
                .hasMessageContaining("points at this machine");
    }

    @Test
    @DisplayName("prod with a trycloudflare or ngrok webhook URL refuses to start")
    void prodTunnelWebhookRefuses() {
        for (String tunnel : new String[] {
                "https://tidy-lamps-sing.trycloudflare.com/api/plaid/webhook",
                "https://37de-2600-4040.ngrok-free.app/api/plaid/webhook"}) {
            assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production", tunnel, PROD_SECRET)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is a dev tunnel");
        }
    }

    @Test
    @DisplayName("outside prod the webhook URL is not judged: the local Cloudflare tunnel is the receiver")
    void devTunnelWebhookStarts() {
        assertThatCode(() -> run(env("dev", SANDBOX_BROKER, PAPER_TRADING, "sandbox",
                "https://tidy-lamps-sing.trycloudflare.com/api/plaid/webhook", CI_SECRET)))
                .doesNotThrowAnyException();
    }
```

Run the same command. Expected: `BUILD FAILED`, `18 tests completed, 4 failed` (the four prod cases; `devTunnelWebhookStarts` already passes).

- [ ] **Step 5: Green: the prod webhook rule and isTunnelUrl**

In `postProcessBeanFactory`, directly after `checkPlaidEnv(prod, violations);` insert:

```java
        if (prod) {
            checkProdWebhookUrl(violations);
        }
```

Insert directly before `static boolean isSandboxLike(String value) {`:

```java
    /** Plaid only delivers to public https receivers; a tunnel or loopback in prod means the receiver is a laptop. */
    private void checkProdWebhookUrl(List<String> violations) {
        String value = environment.getProperty("plaid.webhook.url", "");
        if (value.isBlank()) {
            violations.add("plaid.webhook.url is not set");
            return;
        }
        if (!value.toLowerCase().startsWith("https://")) {
            violations.add("plaid.webhook.url=" + value + " must start with https:// in prod");
        } else if (isLoopback(value)) {
            violations.add("plaid.webhook.url=" + value + " points at this machine, which cannot be right in prod");
        } else if (isTunnelUrl(value)) {
            violations.add("plaid.webhook.url=" + value + " is a dev tunnel, which cannot be right in prod");
        }
    }

```

Insert directly after the closing brace of `isLoopback` (the last method in the class):

```java

    static boolean isTunnelUrl(String value) {
        String v = value.toLowerCase();
        return v.contains("trycloudflare.com") || v.contains("ngrok");
    }
```

Run the same command. Expected: `BUILD SUCCESSFUL`, XML `tests="18" skipped="0" failures="0"`.

- [ ] **Step 6: Red: prod refuses the ci webhook-verifier bypass bean**

Add the import after line 5 (`import org.springframework.beans.factory.support.DefaultListableBeanFactory;`; Step 1 did not touch the imports):

```java
import org.springframework.beans.factory.support.RootBeanDefinition;
```

Add this helper directly after the 6-arg `env(...)` method:

```java

    /** A bean factory carrying the ci bypass verifier definition, to prove prod refuses it. */
    private DefaultListableBeanFactory withCiBypassBean() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(BrokerEnvironmentGuard.CI_BYPASS_VERIFIER_BEAN,
                new RootBeanDefinition(Object.class));
        return beanFactory;
    }
```

Append after `devTunnelWebhookStarts()`:

```java

    @Test
    @DisplayName("prod refuses to start while the ci webhook-verifier bypass bean is registered")
    void prodCiBypassVerifierRefuses() {
        assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production"), withCiBypassBean()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(BrokerEnvironmentGuard.CI_BYPASS_VERIFIER_BEAN)
                .hasMessageContaining("signatures would not be checked");
    }

    @Test
    @DisplayName("ci keeps its bypass verifier bean")
    void ciBypassVerifierStarts() {
        assertThatCode(() -> run(env("ci", "http://127.0.0.1:9", "http://127.0.0.1:9", "sandbox"), withCiBypassBean()))
                .doesNotThrowAnyException();
    }
```

Run the same command. Expected: `BUILD FAILED`, `20 tests completed, 1 failed` (`prodCiBypassVerifierRefuses`: no exception thrown).

- [ ] **Step 7: Green: the bypass-bean rule**

Inside the `if (prod) {` block, after `checkProdWebhookUrl(violations);` add:

```java
            checkProdBypassVerifier(beanFactory, violations);
```

Insert directly before `static boolean isSandboxLike(String value) {` (`containsBeanDefinition` comes from `ListableBeanFactory`, which `ConfigurableListableBeanFactory` already extends, so no new import):

```java
    /** The ci profile swaps the webhook verifier for one that accepts anything; in prod that bean means unsigned webhooks are trusted. */
    private static void checkProdBypassVerifier(ConfigurableListableBeanFactory beanFactory, List<String> violations) {
        if (beanFactory.containsBeanDefinition(CI_BYPASS_VERIFIER_BEAN)) {
            violations.add("bean " + CI_BYPASS_VERIFIER_BEAN
                    + " is registered, so Plaid webhook signatures would not be checked in prod");
        }
    }

```

Run the same command. Expected: `BUILD SUCCESSFUL`, XML `tests="20" skipped="0" failures="0"`.

- [ ] **Step 8: Red: prod refuses the once-committed sandbox secret and never echoes it**

Append after `ciBypassVerifierStarts()` (`hasMessageNotContaining` exists in the bundled AssertJ 3.27.3):

```java

    @Test
    @DisplayName("prod refuses the sandbox secret that was once committed, and never prints it")
    void prodKnownSandboxSecretRefuses() {
        assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production",
                PROD_WEBHOOK, BrokerEnvironmentGuard.KNOWN_SANDBOX_SECRET)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plaid.secret is the sandbox secret")
                .hasMessageNotContaining(BrokerEnvironmentGuard.KNOWN_SANDBOX_SECRET);
    }

    @Test
    @DisplayName("local keeps working on the old sandbox secret until it is rotated")
    void localKnownSandboxSecretStarts() {
        assertThatCode(() -> run(env("local", SANDBOX_BROKER, PAPER_TRADING, "sandbox",
                LOCAL_WEBHOOK, BrokerEnvironmentGuard.KNOWN_SANDBOX_SECRET)))
                .doesNotThrowAnyException();
    }
```

Run the same command. Expected: `BUILD FAILED`, `22 tests completed, 1 failed` (`prodKnownSandboxSecretRefuses`: no exception thrown).

- [ ] **Step 9: Green: the secret rule, plus the class Javadoc**

Inside the `if (prod) {` block, after `checkProdBypassVerifier(beanFactory, violations);` add:

```java
            checkProdPlaidSecret(violations);
```

Insert directly before `static boolean isSandboxLike(String value) {`:

```java
    /** The sandbox secret lived in application-prod.properties until Phase 0 and is in git history forever. Never print it. */
    private void checkProdPlaidSecret(List<String> violations) {
        String value = environment.getProperty("plaid.secret", "");
        if (KNOWN_SANDBOX_SECRET.equals(value)) {
            violations.add("plaid.secret is the sandbox secret that was once committed to this repo; "
                    + "set PLAID_SECRET_PRODUCTION and rotate the sandbox one (FREDdocs/plaid-sandbox-secret-rotation.md)");
        }
    }

```

In the class Javadoc, after the line ` * </ul>` (line 36 before any edits; the Javadoc sits above the constants, so Step 1 did not move it) and before ` */`, insert:

```java
 *
 * <p>Phase 0 of paycheck autopilot added the Plaid rules. {@code plaid.env} is an exact name rather
 * than a hostname, so it must be exactly {@code sandbox} outside prod and exactly {@code production}
 * in prod. Prod additionally needs a public https webhook receiver (no loopback, no trycloudflare or
 * ngrok tunnel), must not register the ci webhook-verifier bypass bean, and must not run on the
 * sandbox secret that was once committed to this repo (see FREDdocs/plaid-sandbox-secret-rotation.md).
```

Run the same command. Expected: `BUILD SUCCESSFUL`, XML `tests="22" skipped="0" failures="0"`.

- [ ] **Step 10: Red: the prod properties file must carry no Plaid literal**

Create `backend/src/test/java/com/investingapp/backend/config/ProdPlaidPropertiesTest.java` (main resources are on the test runtime classpath, see `backend/build/resources/main/application-prod.properties` after any build; `KNOWN_SANDBOX_SECRET` is package-private, so the test must stay in `com.investingapp.backend.config`):

```java
package com.investingapp.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prod profile must not carry a single Plaid literal: every value comes from the environment and
 * the environment is production. Reads the file as a plain .properties load, so a placeholder is
 * asserted as its literal {@code ${NAME}} text.
 */
class ProdPlaidPropertiesTest {

    private static Properties prodProperties() throws IOException {
        Properties props = new Properties();
        try (InputStream in = ProdPlaidPropertiesTest.class.getResourceAsStream("/application-prod.properties")) {
            assertThat(in).as("application-prod.properties on the test classpath").isNotNull();
            props.load(in);
        }
        return props;
    }

    @Test
    @DisplayName("prod reads every Plaid property from the environment and targets production")
    void prodPlaidBlockComesFromTheEnvironment() throws IOException {
        Properties props = prodProperties();
        assertThat(props.getProperty("plaid.client.id")).isEqualTo("${PLAID_CLIENT_ID}");
        assertThat(props.getProperty("plaid.secret")).isEqualTo("${PLAID_SECRET_PRODUCTION}");
        assertThat(props.getProperty("plaid.env")).isEqualTo("production");
        assertThat(props.getProperty("plaid.client.name")).isEqualTo("FRED");
        assertThat(props.getProperty("plaid.webhook.url")).isEqualTo("${PLAID_WEBHOOK_URL}");
        assertThat(props.getProperty("plaid.link.customization")).isEqualTo("${PLAID_LINK_CUSTOMIZATION}");
        assertThat(props.getProperty("plaid.redirect.uri")).isEqualTo("${PLAID_REDIRECT_URI}");
    }

    @Test
    @DisplayName("the once-committed sandbox secret is gone from the prod file")
    void prodFileDoesNotCarryTheSandboxSecret() throws IOException {
        Properties props = prodProperties();
        assertThat(props.values()).noneMatch(BrokerEnvironmentGuard.KNOWN_SANDBOX_SECRET::equals);
    }
}
```

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.config.ProdPlaidPropertiesTest'`
Expected: `BUILD FAILED`, `2 tests completed, 2 failed`. The first fails on its first assertion (`plaid.client.id` is the literal `6844e93b7fcffa0021bcfbb5` at :16 today); the second fails because `plaid.secret` at :17 is the sandbox literal.

- [ ] **Step 11: Green: the env-driven prod Plaid block**

In `backend/src/main/resources/application-prod.properties`, replace lines 16-18, which read exactly:

```properties
plaid.client.id=6844e93b7fcffa0021bcfbb5
plaid.secret=14256c166ee8a12a370a6ccf90abbf
plaid.env=sandbox
```

with:

```properties
# --- Plaid (production) ---
# Every value comes from the environment. Set these on the prod service before the first boot:
#   PLAID_CLIENT_ID, PLAID_SECRET_PRODUCTION, PLAID_WEBHOOK_URL (public https, no tunnel),
#   PLAID_LINK_CUSTOMIZATION, PLAID_REDIRECT_URI.
# BrokerEnvironmentGuard refuses to start on plaid.env other than production, a non-https, loopback
# or tunnel webhook URL, the once-committed sandbox secret, or the ci webhook-verifier bypass bean.
plaid.client.id=${PLAID_CLIENT_ID}
plaid.secret=${PLAID_SECRET_PRODUCTION}
plaid.env=production
plaid.client.name=FRED
plaid.webhook.url=${PLAID_WEBHOOK_URL}
plaid.link.customization=${PLAID_LINK_CUSTOMIZATION}
plaid.redirect.uri=${PLAID_REDIRECT_URI}
```

Leave everything above (lines 1-15: datasource, `jwt.secret`, the stale "application-dev" comment) and everything from `encryption.secret-key=${ENCRYPTION_SECRET_KEY}` down (encryption key, ngrok webauthn RP) exactly as it is. Those are not this task; the rotation note lists them under "Not covered here".

Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.config.ProdPlaidPropertiesTest'`
Expected: `BUILD SUCCESSFUL`, XML `build/test-results/test/TEST-com.investingapp.backend.config.ProdPlaidPropertiesTest.xml` shows `tests="2" skipped="0" failures="0"`.

- [ ] **Step 12: Write the rotation note**

Create `FREDdocs/plaid-sandbox-secret-rotation.md` with exactly this content:

```markdown
# Rotating the Plaid sandbox secret

Until Phase 0 of paycheck autopilot, `backend/src/main/resources/application-prod.properties`
carried the Plaid **sandbox** client id and secret as literals (commit b08d9e3, 2025-06-28, added
them). The file is tracked, so the secret is in git history for good; deleting the line does not
un-leak it. The only fix is to rotate the secret in the Plaid dashboard. This is a manual step for
Andy; nothing in the repo can do it.

The leaked value is pinned as `BrokerEnvironmentGuard.KNOWN_SANDBOX_SECRET`
(`backend/src/main/java/com/investingapp/backend/config/BrokerEnvironmentGuard.java`) and the prod
profile refuses to start with it. That constant only knows the pre-rotation literal: it is a
tripwire for the old value, not proof that the new one is safe. After rotation it stays in the code
unchanged. Until the rotation is confirmed in the dashboard, the old secret is still a working
sandbox credential for anyone who reads the git history.

Sandbox secrets cannot move real money, so this is hygiene, not an incident. It still has to happen
before beta, because the same dashboard holds the production keys.

## Where the secret is used today

| Place | Key | Tracked by git |
|---|---|---|
| `application-local.properties:18` | `plaid.secret=<literal>` | No (gitignored, `.gitignore:44`) |
| `application-dev.properties:38` | `plaid.secret=<plaid sandbox secret, see application-dev.properties>` | Yes; the value lives on the Railway dev service |
| `application-test.properties:32` | `plaid.secret=<plaid sandbox secret, see application-dev.properties>` | Yes; the value lives on the Railway test service |
| `application-ci.properties:51` | `plaid.secret=ci-disabled` | Yes; no real secret |
| `application-prod.properties` | `plaid.secret=${PLAID_SECRET_PRODUCTION}` | Yes; production key, separate from this rotation |

## Steps

1. Plaid dashboard (https://dashboard.plaid.com), **Developers**, **Keys**. Find the **Sandbox**
   secret and start a rotation for it. Plaid issues a new secret and keeps the old one working
   until you confirm the rotation, so the order below never breaks a running environment. Button
   names in the Plaid dashboard change from time to time; the flow is "rotate, copy the new value,
   confirm once everything is switched".
2. Copy the new secret. It is shown once.
3. Railway, dev service, **Variables**: set `PLAID_SECRET_SANDBOX` to the new value. Redeploy.
4. Railway, test service, **Variables**: same key, same value. Redeploy.
5. Local: open `backend/src/main/resources/application-local.properties` and replace the value on
   line 18 (`plaid.secret=`). The file is gitignored; `git status` must not list it afterwards.
6. Restart the local backend. The log line `Initializing Plaid API client. Client ID set: true,
   Env: sandbox` (PlaidConfig) shows the properties loaded. Link a sandbox bank in the app
   (`user_good` / `pass_good`) to prove the new secret is accepted.
7. Back in the Plaid dashboard, confirm the rotation so the old secret stops working.
8. Check nothing new leaked: from the repo root run
   `git grep -n "plaid.secret=" -- backend/src/main/resources` and confirm every tracked hit is a
   `${...}` placeholder or `ci-disabled`.

## Never commit the new value

- No tracked file gets a Plaid secret literal, ever. The dev, test and prod profiles read
  `<plaid sandbox secret, see application-dev.properties>` or `${PLAID_SECRET_PRODUCTION}` from the environment.
- `application-local.properties` is the only place a literal belongs, and it is gitignored.
- Do not update `KNOWN_SANDBOX_SECRET` to the new value. It exists to catch the leaked one.

## Production variables (first prod deploy, separate from this rotation)

`PLAID_CLIENT_ID`, `PLAID_SECRET_PRODUCTION`, `PLAID_WEBHOOK_URL` (public https, no loopback, no
trycloudflare or ngrok), `PLAID_LINK_CUSTOMIZATION`, `PLAID_REDIRECT_URI`. The guard refuses to
start when any of the webhook rules fail or when the secret equals the leaked sandbox value.

## Not covered here

`application-prod.properties` still carries a localhost datasource with a password (lines 1 to 3),
a hard-coded `jwt.secret` (line 10) and an ngrok WebAuthn relying party (the `webauthn.relyingparty.*`
lines at the bottom). Phase 0 only moved the Plaid block to the environment; the rest is tracked
separately before the first prod deploy. Two further guard rules from spec 12.1, refusing
`app.ops.alert.provider=logging` and an unset `ADMIN_TOOLS_KEY`, arrive with the Phase 1 ops alert
channel and admin surface.
```

Run: `grep -c "" FREDdocs/plaid-sandbox-secret-rotation.md` (expect a line count above 50) and `grep -n "—" FREDdocs/plaid-sandbox-secret-rotation.md` (expect no output).

- [ ] **Step 13: Full gate: compile, the three config suites, and the ci context test**

Run: `cd backend && ./gradlew compileJava compileTestJava && ./gradlew test --tests 'com.investingapp.backend.config.BrokerEnvironmentGuardTest' --tests 'com.investingapp.backend.config.ProdPlaidPropertiesTest' --tests 'com.investingapp.backend.config.DevToolsGatingTest'`
Expected: `BUILD SUCCESSFUL`; the three XML reports under `build/test-results/test/` show `failures="0" errors="0"` with `tests="22"`, `tests="2"`, and `DevToolsGatingTest` unchanged from today.

Then the ci context (proves the ci profile still boots with `plaid.env=sandbox` and the `ciBypassPlaidWebhookVerifier` bean present; the password literal is `application-local.properties:3`; never point this at `investing_dev`):

```bash
mysql -h 127.0.0.1 -u root -p'<password from application-local.properties:3>' -e "CREATE DATABASE IF NOT EXISTS fred_ci_scratch"
cd backend && SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_ci_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC' SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD='<same password>' ./gradlew test --tests 'com.investingapp.backend.BackendApplicationTests'
```

Expected: `BUILD SUCCESSFUL` and `build/test-results/test/TEST-com.investingapp.backend.BackendApplicationTests.xml` shows `skipped="0" failures="0"`. If it shows `skipped="1"` the ci MySQL was not reachable (`RequiresCiDatabase.java:64-69`) and the guard behaviour is proven only by the unit tests; say so in the commit body. Drop the scratch schema afterwards: `mysql -h 127.0.0.1 -u root -p'<password>' -e "DROP DATABASE fred_ci_scratch"`.

- [ ] **Step 14: House-style check, hunk-verify, commit**

Run (expect no output from either command):

```bash
grep -n "—" backend/src/main/java/com/investingapp/backend/config/BrokerEnvironmentGuard.java backend/src/test/java/com/investingapp/backend/config/BrokerEnvironmentGuardTest.java backend/src/test/java/com/investingapp/backend/config/ProdPlaidPropertiesTest.java backend/src/main/resources/application-prod.properties FREDdocs/plaid-sandbox-secret-rotation.md
git grep -n "14256c166ee8a12a370a6ccf90abbf" -- backend/src/main/resources
```

The second command searches tracked files only, so the gitignored `application-local.properties` is excluded by design; after Step 11 the literal lives only in `BrokerEnvironmentGuard.java` and that untracked local file. Then hunk-verify on the shared dirty tree (another session edits this checkout; never checkout/stash/reset):

```bash
git diff HEAD -- backend/src/main/java/com/investingapp/backend/config/BrokerEnvironmentGuard.java backend/src/test/java/com/investingapp/backend/config/BrokerEnvironmentGuardTest.java backend/src/main/resources/application-prod.properties
```

Every hunk must be one of the Step 1 to 11 edits; if a foreign hunk appears, stage with `git add -p` and skip it. Then:

```bash
git add backend/src/main/java/com/investingapp/backend/config/BrokerEnvironmentGuard.java backend/src/test/java/com/investingapp/backend/config/BrokerEnvironmentGuardTest.java backend/src/test/java/com/investingapp/backend/config/ProdPlaidPropertiesTest.java backend/src/main/resources/application-prod.properties FREDdocs/plaid-sandbox-secret-rotation.md
git commit -m "feat(plaid): read the prod Plaid block from the environment and harden the broker guard"
```

Expected: `git show --stat HEAD` lists exactly those five files.