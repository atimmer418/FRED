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
 */
@Component
public class BrokerEnvironmentGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    static final String PROD_PROFILE = "prod";

    /** Defaults mirror the @Value fallbacks in AlpacaService / AlpacaApiService / PortfolioDashboardService. */
    static final String DEFAULT_BROKER_URL = "https://broker-api.sandbox.alpaca.markets/v1";
    static final String DEFAULT_TRADING_URL = "https://paper-api.alpaca.markets/v2";
    static final String DEFAULT_PLAID_ENV = "sandbox";

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
        checked.put("plaid.env",
                environment.getProperty("plaid.env", DEFAULT_PLAID_ENV));

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

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Broker environment does not match the active Spring profile "
                            + Arrays.toString(environment.getActiveProfiles())
                            + ". Refusing to start, because this is the difference between paper trades and real money. "
                            + "Problems: " + violations
                            + ". (alpaca.market.base-url is not checked: data.alpaca.markets serves paper and live alike.)");
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
}
