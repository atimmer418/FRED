package com.investingapp.backend.security.plaid;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.plaid.client.model.JWKPublicKey;
import com.plaid.client.model.WebhookVerificationKeyGetRequest;
import com.plaid.client.model.WebhookVerificationKeyGetResponse;
import com.plaid.client.request.PlaidApi;
import org.springframework.stereotype.Component;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Plaid's webhook verification keys, fetched by kid from /webhook_verification_key/get and cached
 * for an hour. A failed fetch is never cached: Caffeine drops the mapping when the loader throws.
 *
 * <p>Uncached fetches are also bounded process-wide to {@link #MAX_FETCHES_PER_WINDOW} per {@link
 * #FETCH_WINDOW}: {@code /api/plaid/webhook} is unauthenticated and the kid in the Plaid-Verification
 * header is attacker-chosen, so an attacker who sends a flood of distinct kids can otherwise force
 * this cache to call Plaid once per request. Twenty fetches a minute is far above Plaid's real key
 * rotation rate, so a legitimate deploy never notices the bound; a legitimate fetch refused while the
 * budget is exhausted under attack simply answers 503, and Plaid retries.
 */
@Component
public class PlaidWebhookKeyCache {

    static final int MAX_FETCHES_PER_WINDOW = 20;
    static final Duration FETCH_WINDOW = Duration.ofMinutes(1);

    private final PlaidApi plaidApi;
    private final Clock clock;
    private final ArrayDeque<Instant> fetchTimestamps = new ArrayDeque<>();
    private final Cache<String, JWKPublicKey> keys = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(16)
            .build();

    public PlaidWebhookKeyCache(PlaidApi plaidApi) {
        this(plaidApi, Clock.systemUTC());
    }

    PlaidWebhookKeyCache(PlaidApi plaidApi, Clock clock) {
        this.plaidApi = plaidApi;
        this.clock = clock;
    }

    public JWKPublicKey get(String kid) {
        return keys.get(kid, this::fetch);
    }

    public void evict(String kid) {
        keys.invalidate(kid);
    }

    private JWKPublicKey fetch(String kid) {
        recordFetchOrThrow(kid);
        try {
            Response<WebhookVerificationKeyGetResponse> response = plaidApi
                    .webhookVerificationKeyGet(new WebhookVerificationKeyGetRequest().keyId(kid))
                    .execute();
            if (!response.isSuccessful() || response.body() == null || response.body().getKey() == null) {
                throw new PlaidWebhookUnavailableException(
                        "webhook verification key fetch returned HTTP " + response.code() + " for kid " + kid);
            }
            return response.body().getKey();
        } catch (IOException e) {
            throw new PlaidWebhookUnavailableException(
                    "webhook verification key fetch unreachable for kid " + kid, e);
        }
    }

    /** Cache hits never reach this method; a failed Plaid call below still consumed its slot. */
    private void recordFetchOrThrow(String kid) {
        synchronized (fetchTimestamps) {
            Instant cutoff = clock.instant().minus(FETCH_WINDOW);
            while (!fetchTimestamps.isEmpty() && fetchTimestamps.peekFirst().isBefore(cutoff)) {
                fetchTimestamps.pollFirst();
            }
            if (fetchTimestamps.size() >= MAX_FETCHES_PER_WINDOW) {
                throw new PlaidWebhookUnavailableException(
                        "webhook verification key fetch budget exhausted; refusing to call Plaid for kid " + kid);
            }
            fetchTimestamps.addLast(clock.instant());
        }
    }

    /** secp256r1 point from the JWK's Base64URL x and y: jjwt 0.11.5 has no JWK parser. */
    public static ECPublicKey toEcPublicKey(JWKPublicKey key) {
        if (!"EC".equals(key.getKty()) || !"P-256".equals(key.getCrv()) || key.getX() == null || key.getY() == null) {
            throw new PlaidWebhookUnavailableException("unusable JWK (kty=" + key.getKty() + ", crv=" + key.getCrv()
                    + ") for kid " + key.getKid());
        }
        try {
            BigInteger x = new BigInteger(1, Base64.getUrlDecoder().decode(key.getX()));
            BigInteger y = new BigInteger(1, Base64.getUrlDecoder().decode(key.getY()));
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec curve = params.getParameterSpec(ECParameterSpec.class);
            return (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), curve));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new PlaidWebhookUnavailableException("cannot build an EC key for kid " + key.getKid(), e);
        }
    }
}
