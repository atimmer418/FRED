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
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Plaid's webhook verification keys, fetched by kid from /webhook_verification_key/get and cached
 * for an hour. A failed fetch is never cached: Caffeine drops the mapping when the loader throws.
 */
@Component
public class PlaidWebhookKeyCache {

    private final PlaidApi plaidApi;
    private final Cache<String, JWKPublicKey> keys = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(16)
            .build();

    public PlaidWebhookKeyCache(PlaidApi plaidApi) {
        this.plaidApi = plaidApi;
    }

    public JWKPublicKey get(String kid) {
        return keys.get(kid, this::fetch);
    }

    public void evict(String kid) {
        keys.invalidate(kid);
    }

    private JWKPublicKey fetch(String kid) {
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
