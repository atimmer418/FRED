package com.investingapp.backend.config;

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

    /** A bean factory carrying the ci bypass verifier definition, to prove prod refuses it. */
    private DefaultListableBeanFactory withCiBypassBean() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(BrokerEnvironmentGuard.CI_BYPASS_VERIFIER_BEAN,
                new RootBeanDefinition(Object.class));
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
}
