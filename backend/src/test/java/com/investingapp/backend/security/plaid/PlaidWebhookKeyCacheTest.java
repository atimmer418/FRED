package com.investingapp.backend.security.plaid;

import com.plaid.client.model.JWKPublicKey;
import com.plaid.client.model.WebhookVerificationKeyGetRequest;
import com.plaid.client.model.WebhookVerificationKeyGetResponse;
import com.plaid.client.request.PlaidApi;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /webhook_verification_key/get is called once per kid and cached for an hour. A failed fetch is a
 * 503 for the caller and is never cached, so the next webhook tries again. jjwt 0.11.5 has no JWK
 * parser, so the cache also rebuilds the secp256r1 point from the JWK's x and y.
 */
class PlaidWebhookKeyCacheTest {

    private PlaidApi plaidApi;
    private PlaidWebhookKeyCache cache;

    static KeyPair p256() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        return gen.generateKeyPair();
    }

    /** Base64URL of a P-256 coordinate padded to 32 bytes, the way a real JWK carries it. */
    static String b64url(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        int len = Math.min(32, raw.length);
        System.arraycopy(raw, raw.length - len, out, 32 - len, len);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }

    static JWKPublicKey jwkFor(ECPublicKey pub, String kid) {
        return new JWKPublicKey().kty("EC").crv("P-256").alg("ES256").use("sig").kid(kid)
                .x(b64url(pub.getW().getAffineX()))
                .y(b64url(pub.getW().getAffineY()))
                .createdAt(1)
                .expiredAt(null);
    }

    @SuppressWarnings("unchecked")
    private static Call<WebhookVerificationKeyGetResponse> call(Response<WebhookVerificationKeyGetResponse> response)
            throws IOException {
        Call<WebhookVerificationKeyGetResponse> call = mock(Call.class);
        when(call.execute()).thenReturn(response);
        return call;
    }

    @SuppressWarnings("unchecked")
    private static Call<WebhookVerificationKeyGetResponse> offlineCall() throws IOException {
        Call<WebhookVerificationKeyGetResponse> call = mock(Call.class);
        when(call.execute()).thenThrow(new IOException("connection refused"));
        return call;
    }

    private static Response<WebhookVerificationKeyGetResponse> plaidError(int status) {
        return Response.error(status, ResponseBody.create(
                "{\"error_type\":\"API_ERROR\",\"error_code\":\"INTERNAL_SERVER_ERROR\"}",
                MediaType.parse("application/json")));
    }

    private static Response<WebhookVerificationKeyGetResponse> plaidKey(JWKPublicKey jwk) {
        return Response.success(new WebhookVerificationKeyGetResponse().key(jwk));
    }

    @BeforeEach
    void setUp() {
        plaidApi = mock(PlaidApi.class);
        cache = new PlaidWebhookKeyCache(plaidApi);
    }

    @Test
    void fetchesOncePerKidAndSendsTheKidAsKeyId() throws Exception {
        JWKPublicKey jwk = jwkFor((ECPublicKey) p256().getPublic(), "kid-1");
        // Mockito cannot nest a when()/thenReturn() pair inside the argument of another, unfinished
        // when()/thenReturn(): build the inner Call mock in its own statement first.
        Call<WebhookVerificationKeyGetResponse> call = call(plaidKey(jwk));
        when(plaidApi.webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class))).thenReturn(call);

        assertThat(cache.get("kid-1")).isSameAs(jwk);
        assertThat(cache.get("kid-1")).isSameAs(jwk);

        ArgumentCaptor<WebhookVerificationKeyGetRequest> request =
                ArgumentCaptor.forClass(WebhookVerificationKeyGetRequest.class);
        verify(plaidApi, times(1)).webhookVerificationKeyGet(request.capture());
        assertThat(request.getValue().getKeyId()).isEqualTo("kid-1");
    }

    @Test
    void plaidErrorIsUnavailableAndNotCached() throws Exception {
        JWKPublicKey jwk = jwkFor((ECPublicKey) p256().getPublic(), "kid-1");
        Call<WebhookVerificationKeyGetResponse> errorCall = call(plaidError(500));
        Call<WebhookVerificationKeyGetResponse> successCall = call(plaidKey(jwk));
        when(plaidApi.webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class)))
                .thenReturn(errorCall)
                .thenReturn(successCall);

        assertThatThrownBy(() -> cache.get("kid-1")).isInstanceOf(PlaidWebhookUnavailableException.class);
        assertThat(cache.get("kid-1")).isSameAs(jwk);
        verify(plaidApi, times(2)).webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class));
    }

    @Test
    void networkFailureIsUnavailable() throws Exception {
        Call<WebhookVerificationKeyGetResponse> call = offlineCall();
        when(plaidApi.webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class))).thenReturn(call);

        assertThatThrownBy(() -> cache.get("kid-1")).isInstanceOf(PlaidWebhookUnavailableException.class);
    }

    @Test
    void evictForcesARefetch() throws Exception {
        JWKPublicKey jwk = jwkFor((ECPublicKey) p256().getPublic(), "kid-1");
        Call<WebhookVerificationKeyGetResponse> call = call(plaidKey(jwk));
        when(plaidApi.webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class))).thenReturn(call);

        cache.get("kid-1");
        cache.evict("kid-1");
        cache.get("kid-1");

        verify(plaidApi, times(2)).webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class));
    }

    @Test
    void toEcPublicKeyRebuildsTheSamePoint() throws Exception {
        ECPublicKey pub = (ECPublicKey) p256().getPublic();

        assertThat(PlaidWebhookKeyCache.toEcPublicKey(jwkFor(pub, "kid-1")).getW()).isEqualTo(pub.getW());
    }

    @Test
    void toEcPublicKeyRefusesAnythingButP256() {
        JWKPublicKey rsa = new JWKPublicKey().kty("RSA").kid("kid-rsa").x("AA").y("AA");
        JWKPublicKey noPoint = new JWKPublicKey().kty("EC").crv("P-256").kid("kid-empty");

        assertThatThrownBy(() -> PlaidWebhookKeyCache.toEcPublicKey(rsa))
                .isInstanceOf(PlaidWebhookUnavailableException.class);
        assertThatThrownBy(() -> PlaidWebhookKeyCache.toEcPublicKey(noPoint))
                .isInstanceOf(PlaidWebhookUnavailableException.class);
    }
}
