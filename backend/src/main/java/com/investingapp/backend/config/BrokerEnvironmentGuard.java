package com.investingapp.backend.config;

import com.investingapp.backend.security.plaid.CiBypassPlaidWebhookVerifier;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
 *
 * <p>The final fix wave before Phase 0 shipped extended prod to also refuse a loopback datasource URL
 * and the development {@code jwt.secret} that has sat in {@code application-prod.properties} since
 * before this guard existed. The guard does not, and cannot, check whether the environment's actual
 * values are correct: it only proves that the known-wrong ones (localhost, the leaked sandbox secret,
 * the committed dev JWT secret) are absent.
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

    /**
     * SHA-256 digest of the sandbox secret that sat in application-prod.properties from b08d9e3
     * (2025-06-28) until Phase 0. Git history keeps the plaintext forever, so prod refuses it; this
     * constant holds only the digest, never the secret itself, so a reader of this source file (or
     * the compiled jar) does not get a working credential out of it. Overridable via
     * {@code plaid.known-sandbox-secret-sha256} so tests can prove the refusal path with a fixture
     * value instead of the real secret.
     */
    static final String KNOWN_SANDBOX_SECRET_SHA256 =
            "eb6a0b8ac8ea858d54d48239cde42bf5d5c29c6c2edab0f96754c1b4ab22d86d";

    /**
     * SHA-256 digest of the jwt.secret value that sat in application-prod.properties at commit
     * 932344d, before this final fix wave moved it to an environment placeholder. Git history keeps
     * the plaintext forever, so prod refuses it; this constant holds only the digest. Overridable via
     * {@code jwt.known-dev-secret-sha256} so tests can prove the refusal path with a fixture value
     * instead of the real secret.
     */
    static final String KNOWN_DEV_JWT_SECRET_SHA256 =
            "4b79a3fc0365a540f6a991f59de187a7ce485b09203c12f7d4b65d4c9a59531f";

    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");

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
            checkProdDatasourceUrl(violations);
            checkProdJwtSecret(violations);
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

    /**
     * The ci profile swaps the webhook verifier for one that accepts anything; in prod a bean of that
     * type means unsigned webhooks are trusted. Looked up by type, not by bean name, so a differently
     * named bean of the same class is still caught; {@code allowEagerInit=false} avoids instantiating
     * anything just to run this check.
     */
    private static void checkProdBypassVerifier(ConfigurableListableBeanFactory beanFactory, List<String> violations) {
        String[] names = beanFactory.getBeanNamesForType(CiBypassPlaidWebhookVerifier.class, true, false);
        if (names.length > 0) {
            violations.add("a bean of type " + CiBypassPlaidWebhookVerifier.class.getSimpleName()
                    + " is registered (" + String.join(", ", names)
                    + "), so Plaid webhook signatures would not be checked in prod");
        }
    }

    /**
     * The sandbox secret lived in application-prod.properties until Phase 0 and is in git history
     * forever. Compared as a SHA-256 digest, never as plaintext, so neither this source file nor a
     * violation message can leak a working credential.
     */
    private void checkProdPlaidSecret(List<String> violations) {
        String value = environment.getProperty("plaid.secret", "");
        String knownDigest = resolveKnownDigest("plaid.known-sandbox-secret-sha256", KNOWN_SANDBOX_SECRET_SHA256, violations);
        if (sha256Hex(value).equalsIgnoreCase(knownDigest)) {
            violations.add("plaid.secret is the sandbox secret that was once committed to this repo; "
                    + "set PLAID_SECRET_PRODUCTION and rotate the sandbox one (FREDdocs/plaid-sandbox-secret-rotation.md)");
        }
    }

    /**
     * {@code spring.datasource.url} may carry credentials in its query string, so a violation never
     * echoes the value, only the property name.
     */
    private void checkProdDatasourceUrl(List<String> violations) {
        String value = environment.getProperty("spring.datasource.url", "");
        if (value.isBlank()) {
            violations.add("spring.datasource.url is not set");
            return;
        }
        if (isLoopback(value)) {
            violations.add("spring.datasource.url points at this machine, which cannot be right in prod");
        }
    }

    /**
     * jwt.secret hard-coded the same development signing key in application-prod.properties since
     * before this guard existed; it is in git history forever. Compared as a SHA-256 digest, never as
     * plaintext.
     */
    private void checkProdJwtSecret(List<String> violations) {
        String value = environment.getProperty("jwt.secret", "");
        String knownDigest = resolveKnownDigest("jwt.known-dev-secret-sha256", KNOWN_DEV_JWT_SECRET_SHA256, violations);
        if (sha256Hex(value).equalsIgnoreCase(knownDigest)) {
            violations.add("jwt.secret is the development signing key that has been in git history since 2025; "
                    + "set JWT_SECRET_PROD");
        }
    }

    /**
     * Shared override hygiene for {@code plaid.known-sandbox-secret-sha256} and
     * {@code jwt.known-dev-secret-sha256}: blank or absent means use the built-in digest; present but
     * not exactly 64 hex characters is itself a violation, so a typo cannot silently disable the
     * tripwire, and the built-in digest is still used for the comparison that follows.
     */
    private String resolveKnownDigest(String overrideProperty, String defaultDigest, List<String> violations) {
        String override = environment.getProperty(overrideProperty);
        if (override == null) {
            return defaultDigest;
        }
        String trimmed = override.trim();
        if (trimmed.isEmpty()) {
            return defaultDigest;
        }
        if (!SHA256_HEX.matcher(trimmed).matches()) {
            violations.add(overrideProperty + " is set but is not a 64-character hex SHA-256 digest");
            return defaultDigest;
        }
        return trimmed;
    }

    /** SHA-256 of the given value as lowercase hex. SHA-256 is a guaranteed JDK algorithm, so this never throws in practice. */
    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available on this JVM", e);
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
