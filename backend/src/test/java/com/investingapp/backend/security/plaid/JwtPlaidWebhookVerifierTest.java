package com.investingapp.backend.security.plaid;

import com.plaid.client.model.JWKPublicKey;
import com.plaid.client.model.WebhookVerificationKeyGetRequest;
import com.plaid.client.model.WebhookVerificationKeyGetResponse;
import com.plaid.client.request.PlaidApi;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plaid signs each webhook with an ES256 JWT in Plaid-Verification: kid in the header, iat and
 * request_body_sha256 as claims. Anything that fails on the request is a 401 (permanent for
 * Plaid); anything that fails on FRED's side is a 503 (Plaid retries). A key with expired_at is
 * still trusted for tokens issued before it expired, but is evicted so the next webhook refetches.
 */
class JwtPlaidWebhookVerifierTest {

    private static final byte[] BODY =
            "{\"webhook_type\":\"ITEM\",\"webhook_code\":\"ERROR\"}".getBytes(StandardCharsets.UTF_8);

    private PlaidApi plaidApi;
    private JwtPlaidWebhookVerifier verifier;
    private KeyPair pair;
    private JWKPublicKey jwk;
    private long now;

    @SuppressWarnings("unchecked")
    private void plaidAnswers(Response<WebhookVerificationKeyGetResponse> response) throws IOException {
        Call<WebhookVerificationKeyGetResponse> call = mock(Call.class);
        when(call.execute()).thenReturn(response);
        when(plaidApi.webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class))).thenReturn(call);
    }

    @BeforeEach
    void setUp() throws Exception {
        plaidApi = mock(PlaidApi.class);
        verifier = new JwtPlaidWebhookVerifier(new PlaidWebhookKeyCache(plaidApi));
        pair = PlaidWebhookKeyCacheTest.p256();
        now = Instant.now().getEpochSecond();
        jwk = PlaidWebhookKeyCacheTest.jwkFor((ECPublicKey) pair.getPublic(), "kid-1");
        plaidAnswers(Response.success(new WebhookVerificationKeyGetResponse().key(jwk)));
    }

    private static String jwt(String kid, long iat, String bodyHash, Key key, SignatureAlgorithm alg) {
        return Jwts.builder()
                .setHeaderParam(JwsHeader.KEY_ID, kid)
                .setIssuedAt(Date.from(Instant.ofEpochSecond(iat)))
                .claim("request_body_sha256", bodyHash)
                .signWith(key, alg)
                .compact();
    }

    private String goodJwt(long iat) {
        return jwt("kid-1", iat, JwtPlaidWebhookVerifier.sha256Hex(BODY), pair.getPrivate(), SignatureAlgorithm.ES256);
    }

    private void rejects(String token, String messageFragment) {
        assertThatThrownBy(() -> verifier.verifyAndReturnIat(BODY, token))
                .isInstanceOf(PlaidWebhookRejectedException.class)
                .hasMessageContaining(messageFragment);
    }

    @Test
    void validSignatureReturnsIatAndFetchesTheKeyOnce() {
        assertThat(verifier.verifyAndReturnIat(BODY, goodJwt(now - 10))).isEqualTo(now - 10);
        assertThat(verifier.verifyAndReturnIat(BODY, goodJwt(now - 5))).isEqualTo(now - 5);

        verify(plaidApi, times(1)).webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class));
    }

    @Test
    void rejectsNonEs256() {
        String hs256 = jwt("kid-1", now, JwtPlaidWebhookVerifier.sha256Hex(BODY),
                Keys.hmacShaKeyFor(new byte[32]), SignatureAlgorithm.HS256);

        rejects(hs256, "ES256");
        verify(plaidApi, never()).webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class));
    }

    @Test
    void rejectsMissingKid() {
        String noKid = Jwts.builder()
                .setIssuedAt(Date.from(Instant.ofEpochSecond(now)))
                .claim("request_body_sha256", JwtPlaidWebhookVerifier.sha256Hex(BODY))
                .signWith(pair.getPrivate(), SignatureAlgorithm.ES256)
                .compact();

        rejects(noKid, "kid");
    }

    @Test
    void rejectsOldIat() {
        rejects(goodJwt(now - 600), "iat");
    }

    @Test
    void rejectsBodyHashMismatch() {
        String hashOfAnotherBody = JwtPlaidWebhookVerifier.sha256Hex("{}".getBytes(StandardCharsets.UTF_8));

        rejects(jwt("kid-1", now, hashOfAnotherBody, pair.getPrivate(), SignatureAlgorithm.ES256), "request_body_sha256");
    }

    @Test
    void rejectsSignatureFromAnotherKey() throws Exception {
        String foreign = jwt("kid-1", now, JwtPlaidWebhookVerifier.sha256Hex(BODY),
                PlaidWebhookKeyCacheTest.p256().getPrivate(), SignatureAlgorithm.ES256);

        rejects(foreign, "JWT rejected");
    }

    @Test
    void rejectsMissingHeaderAndGarbage() {
        rejects(null, "missing");
        rejects("", "missing");
        rejects("not.a.jwt", "JWT rejected");
    }

    @Test
    void keyFetchFailureReturns503() throws Exception {
        plaidAnswers(Response.error(500, ResponseBody.create(
                "{\"error_type\":\"API_ERROR\",\"error_code\":\"INTERNAL_SERVER_ERROR\"}",
                MediaType.parse("application/json"))));
        JwtPlaidWebhookVerifier coldCache = new JwtPlaidWebhookVerifier(new PlaidWebhookKeyCache(plaidApi));
        String token = goodJwt(now);

        assertThatThrownBy(() -> coldCache.verifyAndReturnIat(BODY, token))
                .isInstanceOf(PlaidWebhookUnavailableException.class);
    }

    @Test
    void expiredKeyAcceptedWithinIatWindow() {
        jwk.expiredAt((int) (now + 50));

        assertThat(verifier.verifyAndReturnIat(BODY, goodJwt(now - 10))).isEqualTo(now - 10);
        assertThat(verifier.verifyAndReturnIat(BODY, goodJwt(now - 10))).isEqualTo(now - 10);

        // expired_at only evicts the cache entry, so the second webhook fetched the key again
        verify(plaidApi, times(2)).webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class));
    }

    @Test
    void expiredKeyRejectedWhenIatAfterExpiry() {
        jwk.expiredAt((int) (now - 70));

        rejects(goodJwt(now - 10), "expired");
    }
}
