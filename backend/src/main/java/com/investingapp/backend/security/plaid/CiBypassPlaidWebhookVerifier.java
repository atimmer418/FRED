package com.investingapp.backend.security.plaid;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * CI has no Plaid network, so the stub PlaidApi could never fetch a verification key. Under ci
 * every webhook is accepted as if signed now. Registered only under ci; BrokerEnvironmentGuard
 * refuses any bean of this type in prod.
 */
@Component("ciBypassPlaidWebhookVerifier")
@Profile("ci")
public class CiBypassPlaidWebhookVerifier implements PlaidWebhookVerifier {

    @Override
    public long verifyAndReturnIat(byte[] rawBody, String jwt) {
        return Instant.now().getEpochSecond();
    }
}
