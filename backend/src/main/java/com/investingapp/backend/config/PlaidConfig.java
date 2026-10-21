// src/main/java/com/investingapp/backend/config/PlaidConfig.java
package com.investingapp.backend.config;

import com.plaid.client.ApiClient;
import com.plaid.client.request.PlaidApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.HashMap;
import java.util.Map;

@Configuration
@Profile("!ci") // Skip the real Plaid client in CI (offline boot). A ci stub bean is provided in CiStubConfig.
public class PlaidConfig {

    private static final Logger logger = LoggerFactory.getLogger(PlaidConfig.class);

    @Value("${plaid.client.id}")
    private String plaidClientId;

    @Value("${plaid.secret}")
    private String plaidSecret;

    @Value("${plaid.env}")
    private String plaidEnv;

    @Bean
    public PlaidApi plaidApi() {
        logger.info("Initializing Plaid API client. Client ID set: {}, Env: {}",
                (plaidClientId != null && !plaidClientId.isEmpty()), plaidEnv);

        if (plaidClientId == null || plaidClientId.isEmpty()) {
            logger.error("PLAID_CLIENT_ID is not set in properties. Plaid client cannot be initialized.");
            throw new IllegalStateException("PLAID_CLIENT_ID is not configured.");
        }
        if (plaidSecret == null || plaidSecret.isEmpty()) {
            logger.error("PLAID_SECRET_KEY is not set in properties. Plaid client cannot be initialized.");
            throw new IllegalStateException("PLAID_SECRET_KEY is not configured.");
        }

        // Try using the simpler keys "clientId" and "secret" as often expected by the SDK's map constructor
        // The SDK then typically maps these to the correct HTTP headers.
        Map<String, String> authCredentials = new HashMap<>();
        authCredentials.put("clientId", plaidClientId); // Use "clientId"
        authCredentials.put("secret", plaidSecret);     // Use "secret"

        ApiClient apiClient = new ApiClient(authCredentials);

        // Set the environment adapter. plaid-java 46 knows only Sandbox and Production: Plaid
        // retired the Development environment, so any other value falls back to Sandbox below.
        if ("sandbox".equalsIgnoreCase(plaidEnv)) {
            apiClient.setPlaidAdapter(ApiClient.Sandbox);
            logger.info("Plaid client configured for Sandbox environment.");
        } else if ("production".equalsIgnoreCase(plaidEnv)) {
            apiClient.setPlaidAdapter(ApiClient.Production);
            logger.info("Plaid client configured for Production environment.");
        } else {
            apiClient.setPlaidAdapter(ApiClient.Sandbox); // Default to sandbox
            logger.warn("Warning: Invalid PLAID_ENV value ('{}') in properties, defaulting to Sandbox.", plaidEnv);
        }

        // The Plaid-Version header is usually added by the client library automatically.
        // If you needed to force a specific version and the above doesn't work,
        // you might need to configure the underlying OkHttpClient used by ApiClient,
        // but that's more advanced and usually not required for basic setup.

        return apiClient.createService(PlaidApi.class);
    }
}