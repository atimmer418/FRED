package com.investingapp.backend.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Refuses to start when the broker environment does not match the Spring profile.
 *
 * <p>Nothing in the repo pins which Alpaca environment a deployed service talks to: the dev profile
 * takes its base URLs from environment variables with no defaults, and the Alpaca credential names
 * do not say which environment they belong to. So a single wrong Railway variable is all it takes
 * for a non-prod service to place real orders against real money, and the only visible difference is
 * a hostname.
 *
 * <p>The rule, enforced at startup rather than trusted: every non-prod profile must point at
 * sandbox/paper hosts, and prod must not. A crash on deploy is loud, a silent live connection is
 * not. Same pattern as {@link DevToolsProdGuard}.
 *
 * <p>Two deliberate exclusions:
 * <ul>
 *   <li><b>Market data</b> ({@code alpaca.market.base-url}) is not checked: {@code
 *       data.alpaca.markets} is the same host for paper and live accounts, so its value carries no
 *       environment signal. It is read-only price data either way.
 *   <li><b>Loopback hosts</b> count as non-live in non-prod, because CI points every Alpaca URL at
 *       a dead local port. In prod a loopback host is itself a misconfiguration, so it fails there.
 * </ul>
 *
 * <p>Phase 0 of paycheck autopilot added the Plaid rules. {@code plaid.env} is an exact name rather
 * than a hostname, so it must be exactly {@code sandbox} outside prod and exactly {@code production}
 * in prod. Prod additionally needs a public https webhook receiver (no loopback, no trycloudflare or
 * ngrok tunnel), must not register the ci webhook-verifier bypass bean, and must not run on the
 * sandbox secret that was once committed to this repo (see FREDdocs/plaid-sandbox-secret-rotation.md).
 */
@Component
public class BrokerEnvironmentGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    static final String PROD_PROFILE = "prod";

    /** Defaults mirror the @Value fallbacks in AlpacaService / AlpacaApiService / PortfolioDashboardService. */
    static final String DEFAULT_BROKER_URL = "https://broker-api.sandbox.alpaca.markets/v1";
    static final String DEFAULT_TRADING_URL = "https://paper-api.alpaca.markets/v2";
    static final String DEFAULT_PLAID_ENV = "sandbox";

    /** plaid.env is an exact name, never a hostname, so it gets its own rule instead of the sandbox/paper substring match. */
    static final String PLAID_ENV_SANDBOX = "sandbox";
    static final String PLAID_ENV_PRODUCTION = "production";

    /** The sandbox secret that sat in application-prod.properties from b08d9e3 (2025-06-28) until Phase 0. Git history keeps it, so prod refuses it. */
    static final String KNOWN_SANDBOX_SECRET = "14256c166ee8a12a370a6ccf90abbf";

    /** The @Profile("ci") webhook verifier that accepts any Plaid-Verification header (T07). Never in prod. */
    static final String CI_BYPASS_VERIFIER_BEAN = "ciBypassPlaidWebhookVerifier";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (environment == null) {
            return;
        }

        Map<String, String> checked = new LinkedHashMap<>();
        checked.put("alpaca.broker.base-url",
                environment.getProperty("alpaca.broker.base-url", DEFAULT_BROKER_URL));
        checked.put("alpaca.trading.base-url",
                environment.getProperty("alpaca.trading.base-url", DEFAULT_TRADING_URL));

        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains(PROD_PROFILE);
        List<String> violations = new ArrayList<>();

        for (Map.Entry<String, String> entry : checked.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (value == null || value.isBlank()) {
                violations.add(key + " is not set");
                continue;
            }

            if (prod) {
                if (isSandboxLike(value)) {
                    violations.add(key + "=" + value + " is a sandbox/paper target, but the prod profile is active");
                } else if (isLoopback(value)) {
                    violations.add(key + "=" + value + " points at this machine, which cannot be right in prod");
                }
            } else if (!isSandboxLike(value) && !isLoopback(value)) {
                violations.add(key + "=" + value + " is not a sandbox/paper target");
            }
        }

        checkPlaidEnv(prod, violations);
        if (prod) {
            checkProdWebhookUrl(violations);
            checkProdBypassVerifier(beanFactory, violations);
            checkProdPlaidSecret(violations);
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Broker environment does not match the active Spring profile "
                            + Arrays.toString(environment.getActiveProfiles())
                            + ". Refusing to start, because this is the difference between paper trades and real money. "
                            + "Problems: " + violations
                            + ". (alpaca.market.base-url is not checked: data.alpaca.markets serves paper and live alike.)");
        }
    }

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

    /** The ci profile swaps the webhook verifier for one that accepts anything; in prod that bean means unsigned webhooks are trusted. */
    private static void checkProdBypassVerifier(ConfigurableListableBeanFactory beanFactory, List<String> violations) {
        if (beanFactory.containsBeanDefinition(CI_BYPASS_VERIFIER_BEAN)) {
            violations.add("bean " + CI_BYPASS_VERIFIER_BEAN
                    + " is registered, so Plaid webhook signatures would not be checked in prod");
        }
    }

    /** The sandbox secret lived in application-prod.properties until Phase 0 and is in git history forever. Never print it. */
    private void checkProdPlaidSecret(List<String> violations) {
        String value = environment.getProperty("plaid.secret", "");
        if (KNOWN_SANDBOX_SECRET.equals(value)) {
            violations.add("plaid.secret is the sandbox secret that was once committed to this repo; "
                    + "set PLAID_SECRET_PRODUCTION and rotate the sandbox one (FREDdocs/plaid-sandbox-secret-rotation.md)");
        }
    }

    static boolean isSandboxLike(String value) {
        String v = value.toLowerCase();
        return v.contains("sandbox") || v.contains("paper");
    }

    static boolean isLoopback(String value) {
        String v = value.toLowerCase();
        return v.contains("localhost") || v.contains("127.0.0.1") || v.contains("[::1]");
    }

    static boolean isTunnelUrl(String value) {
        String v = value.toLowerCase();
        return v.contains("trycloudflare.com") || v.contains("ngrok");
    }
}
