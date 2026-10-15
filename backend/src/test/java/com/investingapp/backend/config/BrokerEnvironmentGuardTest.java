package com.investingapp.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
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

    private void run(MockEnvironment env) {
        BrokerEnvironmentGuard guard = new BrokerEnvironmentGuard();
        guard.setEnvironment(env);
        guard.postProcessBeanFactory(new DefaultListableBeanFactory());
    }

    private MockEnvironment env(String profile, String broker, String trading, String plaidEnv) {
        MockEnvironment env = new MockEnvironment();
        if (profile != null) {
            env.setActiveProfiles(profile);
        }
        env.withProperty("alpaca.broker.base-url", broker)
                .withProperty("alpaca.trading.base-url", trading)
                .withProperty("plaid.env", plaidEnv);
        return env;
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
}
