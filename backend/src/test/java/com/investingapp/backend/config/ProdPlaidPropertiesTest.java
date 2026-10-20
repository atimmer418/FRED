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
