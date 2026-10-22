package com.investingapp.backend.config;

import com.investingapp.backend.security.plaid.CiBypassPlaidWebhookVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A wrong Railway variable is the whole distance between paper trades and real money, and the only
 * visible difference is a hostname. These tests pin the startup refusal in both directions.
 */
class BrokerEnvironmentGuardTest {

    private static final String LIVE_BROKER = "https://broker-api.alpaca.markets/v1";
    private static final String LIVE_TRADING = "https://api.alpaca.markets/v2";
    private static final String SANDBOX_BROKER = "https://broker-api.sandbox.alpaca.markets/v1";
    private static final String PAPER_TRADING = "https://paper-api.alpaca.markets/v2";
    private static final String PROD_WEBHOOK = "https://api.fredvested.com/api/plaid/webhook";
    private static final String LOCAL_WEBHOOK = "https://local.fredvested.com/api/plaid/webhook";
    private static final String PROD_SECRET = "prod-secret-not-the-sandbox-one";
    private static final String CI_SECRET = "ci-disabled";
    private static final String PROD_DATASOURCE = "jdbc:mysql://db.internal:3306/fred";
    private static final String PROD_JWT_SECRET = "prod-signing-key-not-the-dev-one";

    /** A fixture value standing in for the real leaked sandbox secret, so tests never carry it. */
    private static final String TEST_KNOWN_SANDBOX_SECRET = "test-fixture-sandbox-secret-not-the-real-one";
    private static final String TEST_KNOWN_SANDBOX_SECRET_SHA256 =
            BrokerEnvironmentGuard.sha256Hex(TEST_KNOWN_SANDBOX_SECRET);

    /** A fixture value standing in for the real committed dev JWT secret, so tests never carry it. */
    private static final String TEST_KNOWN_DEV_JWT_SECRET = "test-fixture-dev-jwt-secret-not-the-real-one";
    private static final String TEST_KNOWN_DEV_JWT_SECRET_SHA256 =
            BrokerEnvironmentGuard.sha256Hex(TEST_KNOWN_DEV_JWT_SECRET);

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
        boolean prod = BrokerEnvironmentGuard.PROD_PROFILE.equals(profile);
        MockEnvironment env = new MockEnvironment();
        if (profile != null) {
            env.setActiveProfiles(profile);
        }
        env.withProperty("alpaca.broker.base-url", broker)
                .withProperty("alpaca.trading.base-url", trading)
                .withProperty("plaid.env", plaidEnv)
                .withProperty("plaid.webhook.url", webhookUrl)
                .withProperty("plaid.secret", plaidSecret);
        if (prod) {
            // Valid-by-default prod fixtures for the datasource/jwt rules, so existing prod tests
            // that do not care about these two properties keep passing unchanged.
            env.withProperty("spring.datasource.url", PROD_DATASOURCE)
                    .withProperty("jwt.secret", PROD_JWT_SECRET);
        }
        return env;
    }

    /**
     * A bean factory carrying a {@link CiBypassPlaidWebhookVerifier} definition under a bean name that
     * has nothing to do with the class, to prove prod refuses it by type rather than by bean name.
     */
    private DefaultListableBeanFactory withCiBypassBean() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition("someUnrelatedBeanName",
                new RootBeanDefinition(CiBypassPlaidWebhookVerifier.class));
        return beanFactory;
    }

    @Test
    @DisplayName("dev against sandbox and paper starts")
    void devSandboxStarts() {
        assertThatCode(() -> run(env("dev", SANDBOX_BROKER, PAPER_TRADING, "sandbox"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ci against a dead local port starts: loopback counts as non-live")
    void ciLoopbackStarts() {
        assertThatCode(() -> run(env("ci", "http://127.0.0.1:9", "http://127.0.0.1:9", "sandbox")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("dev pointed at the LIVE broker refuses to start, and says which property")
    void devLiveBrokerRefuses() {
        assertThatThrownBy(() -> run(env("dev", LIVE_BROKER, PAPER_TRADING, "sandbox")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alpaca.broker.base-url")
                .hasMessageContaining("not a sandbox/paper target");
    }

    @Test
    @DisplayName("dev pointed at the LIVE trading API refuses to start")
    void devLiveTradingRefuses() {
        assertThatThrownBy(() -> run(env("dev", SANDBOX_BROKER, LIVE_TRADING, "sandbox")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alpaca.trading.base-url");
    }

    @Test
    @DisplayName("dev with Plaid production refuses to start")
    void devPlaidProductionRefuses() {
        assertThatThrownBy(() -> run(env("dev", SANDBOX_BROKER, PAPER_TRADING, "production")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plaid.env=production");
    }

    @Test
    @DisplayName("no active profile is treated as non-prod and must still be sandbox")
    void noProfileIsTreatedAsNonProd() {
        assertThatThrownBy(() -> run(env(null, LIVE_BROKER, LIVE_TRADING, "production")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alpaca.broker.base-url");
    }

    @Test
    @DisplayName("prod against live hosts starts")
    void prodLiveStarts() {
        assertThatCode(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod still pointed at sandbox refuses to start")
    void prodSandboxRefuses() {
        assertThatThrownBy(() -> run(env("prod", SANDBOX_BROKER, PAPER_TRADING, "sandbox")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod profile is active");
    }

    @Test
    @DisplayName("prod pointed at this machine refuses to start")
    void prodLoopbackRefuses() {
        assertThatThrownBy(() -> run(env("prod", "http://localhost:9", LIVE_TRADING, "production")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("points at this machine");
    }

    @Test
    @DisplayName("prod with a localhost datasource URL refuses to start")
    void prodLocalhostDatasourceRefuses() {
        assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production")
                .withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/fred")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url points at this machine");
    }

    @Test
    @DisplayName("prod with no datasource URL refuses to start")
    void prodBlankDatasourceRefuses() {
        assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production")
                .withProperty("spring.datasource.url", "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url is not set");
    }

    @Test
    @DisplayName("prod refuses the development JWT secret that has been in git history since 2025, and never prints it")
    void prodKnownDevJwtSecretRefuses() {
        assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production")
                .withProperty("jwt.secret", TEST_KNOWN_DEV_JWT_SECRET)
                .withProperty("jwt.known-dev-secret-sha256", TEST_KNOWN_DEV_JWT_SECRET_SHA256)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret is the development signing key")
                .hasMessageNotContaining(TEST_KNOWN_DEV_JWT_SECRET);
    }

    @Test
    @DisplayName("a malformed digest override for either tripwire refuses to start with the hygiene message")
    void malformedDigestOverrideRefuses() {
        assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production")
                .withProperty("plaid.known-sandbox-secret-sha256", "not-64-hex-characters")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "plaid.known-sandbox-secret-sha256 is set but is not a 64-character hex SHA-256 digest");

        assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production")
                .withProperty("jwt.known-dev-secret-sha256", "also-not-valid-hex")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "jwt.known-dev-secret-sha256 is set but is not a 64-character hex SHA-256 digest");
    }

    @Test
    @DisplayName("a whitespace-padded but valid 64-hex digest override is accepted, trimmed")
    void whitespacePaddedDigestOverrideIsAccepted() {
        assertThatCode(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production")
                .withProperty("plaid.known-sandbox-secret-sha256", "  " + TEST_KNOWN_SANDBOX_SECRET_SHA256 + "  ")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("non-prod with a localhost datasource and a literal jwt secret still starts: these rules are prod-only")
    void nonProdLocalhostDatasourceAndLiteralJwtSecretStarts() {
        assertThatCode(() -> run(env("dev", SANDBOX_BROKER, PAPER_TRADING, "sandbox")
                .withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/investing_dev")
                .withProperty("jwt.secret", "some-literal-dev-secret")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a blank base URL refuses to start rather than falling back to anything")
    void blankValueRefuses() {
        assertThatThrownBy(() -> run(env("dev", "", PAPER_TRADING, "sandbox")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alpaca.broker.base-url is not set");
    }

    @Test
    @DisplayName("the committed defaults are sandbox/paper, so an unset property is safe in non-prod")
    void defaultsAreSandbox() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("test");

        assertThatCode(() -> run(env)).doesNotThrowAnyException();
        assertThatCode(() -> BrokerEnvironmentGuard.isSandboxLike(BrokerEnvironmentGuard.DEFAULT_BROKER_URL))
                .doesNotThrowAnyException();
    }

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

    @Test
    @DisplayName("prod refuses to start while a ci webhook-verifier bypass bean is registered, by type, under any bean name")
    void prodCiBypassVerifierRefuses() {
        assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production"), withCiBypassBean()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CiBypassPlaidWebhookVerifier.class.getSimpleName())
                .hasMessageContaining("signatures would not be checked");
    }

    @Test
    @DisplayName("ci keeps its bypass verifier bean")
    void ciBypassVerifierStarts() {
        assertThatCode(() -> run(env("ci", "http://127.0.0.1:9", "http://127.0.0.1:9", "sandbox"), withCiBypassBean()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod refuses the sandbox secret that was once committed, and never prints it")
    void prodKnownSandboxSecretRefuses() {
        assertThatThrownBy(() -> run(env("prod", LIVE_BROKER, LIVE_TRADING, "production",
                PROD_WEBHOOK, TEST_KNOWN_SANDBOX_SECRET)
                .withProperty("plaid.known-sandbox-secret-sha256", TEST_KNOWN_SANDBOX_SECRET_SHA256)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("plaid.secret is the sandbox secret")
                .hasMessageNotContaining(TEST_KNOWN_SANDBOX_SECRET);
    }

    @Test
    @DisplayName("local keeps working on the old sandbox secret until it is rotated")
    void localKnownSandboxSecretStarts() {
        assertThatCode(() -> run(env("local", SANDBOX_BROKER, PAPER_TRADING, "sandbox",
                LOCAL_WEBHOOK, TEST_KNOWN_SANDBOX_SECRET)
                .withProperty("plaid.known-sandbox-secret-sha256", TEST_KNOWN_SANDBOX_SECRET_SHA256)))
                .doesNotThrowAnyException();
    }
}
