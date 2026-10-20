package com.investingapp.backend.config;

import com.plaid.client.model.LinkTokenCreateRequest;
import com.plaid.client.request.PlaidApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PlaidConfig picks the Plaid host from plaid.env. plaid-java 46 dropped the Development
 * environment, so sandbox and production are the only real targets and anything else must fall
 * back to sandbox rather than reach a host that no longer exists.
 *
 * Nothing here touches the network. A Retrofit proxy builds its okhttp Request lazily on
 * Call.request(), and that request carries the host the ApiClient was pointed at.
 */
class PlaidConfigTest {

    private static final String CLIENT_ID = "client-id-for-test";
    private static final String SECRET = "secret-for-test";

    private static PlaidConfig configFor(String clientId, String secret, String plaidEnv) {
        PlaidConfig config = new PlaidConfig();
        ReflectionTestUtils.setField(config, "plaidClientId", clientId);
        ReflectionTestUtils.setField(config, "plaidSecret", secret);
        ReflectionTestUtils.setField(config, "plaidEnv", plaidEnv);
        return config;
    }

    private static String hostFor(String plaidEnv) {
        PlaidApi plaidApi = configFor(CLIENT_ID, SECRET, plaidEnv).plaidApi();
        return plaidApi.linkTokenCreate(new LinkTokenCreateRequest()).request().url().host();
    }

    @Test
    @DisplayName("sandbox points the client at sandbox.plaid.com")
    void sandboxHost() {
        assertThat(hostFor("sandbox")).isEqualTo("sandbox.plaid.com");
    }

    @Test
    @DisplayName("production points the client at production.plaid.com")
    void productionHost() {
        assertThat(hostFor("production")).isEqualTo("production.plaid.com");
    }

    @Test
    @DisplayName("development is no longer a Plaid environment and falls back to sandbox")
    void developmentFallsBackToSandbox() {
        assertThat(hostFor("development")).isEqualTo("sandbox.plaid.com");
    }

    @Test
    @DisplayName("the env value is matched case-insensitively")
    void envIsCaseInsensitive() {
        assertThat(hostFor("PRODUCTION")).isEqualTo("production.plaid.com");
        assertThat(hostFor("Sandbox")).isEqualTo("sandbox.plaid.com");
    }

    @Test
    @DisplayName("a missing client id refuses to build a client")
    void missingClientIdRefuses() {
        assertThatThrownBy(() -> configFor("", SECRET, "sandbox").plaidApi())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PLAID_CLIENT_ID is not configured.");
    }

    @Test
    @DisplayName("a missing secret refuses to build a client")
    void missingSecretRefuses() {
        assertThatThrownBy(() -> configFor(CLIENT_ID, "", "sandbox").plaidApi())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PLAID_SECRET_KEY is not configured.");
    }
}
