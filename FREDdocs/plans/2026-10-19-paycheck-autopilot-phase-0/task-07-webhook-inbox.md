### Task 7: webhook-inbox

Stores every verified Plaid webhook in `plaid_webhook_events` and answers 200. No processor (Phase 1). Depends on T01 (plaid-java 46: `JWKPublicKey`, `WebhookVerificationKeyGetRequest`), T04 (`PlaidWebhookEvent`, `PlaidWebhookEventStatus` in `com.investingapp.backend.model`, table `plaid_webhook_events` in V6) and T02 (`com.investingapp.backend.util.EasternTime.now()`, required by the contract for `receivedAt`). Notation: `P` = `backend/src/main/java/com/investingapp/backend`, `T` = `backend/src/test/java/com/investingapp/backend`. Every listing below is complete (package, imports, body); `Response` is always `retrofit2.Response`. Before a class under test exists, the run command fails at `compileTestJava` with `error: cannot find symbol` and `BUILD FAILED`; that is the expected red.

**Files:**
- Create: `P/security/plaid/RereadableRequestWrapper.java`, `P/security/plaid/PlaidWebhookRejectedException.java`, `P/security/plaid/PlaidWebhookUnavailableException.java`, `P/security/plaid/PlaidWebhookKeyCache.java`, `P/security/plaid/PlaidWebhookVerifier.java`, `P/security/plaid/JwtPlaidWebhookVerifier.java`, `P/security/plaid/CiBypassPlaidWebhookVerifier.java`, `P/security/plaid/PlaidWebhookVerificationFilter.java`, `P/config/PlaidWebhookFilterConfig.java`, `P/repository/PlaidWebhookEventRepository.java`, `P/service/PlaidWebhookInboxService.java`, `P/controller/PlaidWebhookController.java`
- Modify: `P/config/SecurityConfig.java:113` (insert one line after `.requestMatchers("/api/auth/**").permitAll()`); `backend/src/main/resources/application-local.properties:19`, `application-dev.properties:39`, `application-test.properties:33`, `application-ci.properties:52` (append one line after each file's `plaid.env=sandbox`)
- Test: `T/security/plaid/RereadableRequestWrapperTest.java`, `T/security/plaid/PlaidWebhookKeyCacheTest.java`, `T/security/plaid/JwtPlaidWebhookVerifierTest.java`, `T/security/plaid/PlaidWebhookVerificationFilterTest.java`, `T/service/PlaidWebhookInboxServiceTest.java`, `T/controller/PlaidWebhookControllerTest.java`; existing `T/BackendApplicationTests.java` as the ci context gate

**Interfaces:**
- Consumes: `PlaidApi.webhookVerificationKeyGet(WebhookVerificationKeyGetRequest) -> Call<WebhookVerificationKeyGetResponse>` (request `.keyId(String)`, response `.getKey() -> JWKPublicKey{getKty,getCrv,getKid,getX,getY,getExpiredAt():Integer}`); `UserRepository.findByPlaidItemId(String) -> User` (UserRepository.java:40, bare `User`, null when absent, `IncorrectResultSizeDataAccessException` when two users share an item id); `PlaidWebhookEvent` (T04, Lombok `@Getter @Setter @NoArgsConstructor`; accessors used here: `setDedupeKey(String)`, `setWebhookType(String)`, `setWebhookCode(String)`, `setPlaidItemId(String)`, `setUserId(Long)`, `setEnvironment(String)`, `setPayloadJson(String)`, `setStatus(PlaidWebhookEventStatus)`, `setAttempts(short)`, `setJwtIat(long)`, `setReceivedAt(LocalDateTime)` and the matching getters); `PlaidWebhookEventStatus { RECEIVED, PROCESSING, PROCESSED, FAILED, IGNORED }` (T04); `EasternTime.now() -> LocalDateTime` (T02); property `plaid.env` (every profile already defines it: ci:52, dev:39, test:33, local:19).
- Produces: `public interface PlaidWebhookVerifier { long verifyAndReturnIat(byte[] rawBody, String jwt) }` throwing `PlaidWebhookRejectedException` (401) or `PlaidWebhookUnavailableException` (503), both `RuntimeException`; `@Component @Profile("!ci") JwtPlaidWebhookVerifier(PlaidWebhookKeyCache)`; `@Component("ciBypassPlaidWebhookVerifier") @Profile("ci") CiBypassPlaidWebhookVerifier` (bean name consumed by T14); `@Component PlaidWebhookKeyCache(PlaidApi) { JWKPublicKey get(String kid); void evict(String kid); static ECPublicKey toEcPublicKey(JWKPublicKey) }`; `PlaidWebhookVerificationFilter(PlaidWebhookVerifier)` with `HEADER = "Plaid-Verification"`, `ATTR_RAW_BODY = "plaid.webhook.rawBody"` (byte[]), `ATTR_JWT_IAT = "plaid.webhook.iat"` (Long); `RereadableRequestWrapper(HttpServletRequest) { byte[] body() }`; `PlaidWebhookController.receive(HttpServletRequest) -> ResponseEntity<Void>` on `POST /api/plaid/webhook` (200 or 503, empty body); `@Transactional PlaidWebhookInboxService.record(byte[] rawBody, long iat) -> PlaidWebhookEvent`; `PlaidWebhookEventRepository extends JpaRepository<PlaidWebhookEvent, Long> { Optional<PlaidWebhookEvent> findByDedupeKey(String); long deleteByUserId(Long) }` (consumed by T11); property `plaid.webhook.url` in the four non-prod profiles (consumed by T08, T09, T14).

- [ ] **Step 1: Failing test for the re-readable wrapper**

`T/security/plaid/RereadableRequestWrapperTest.java`:
```java
package com.investingapp.backend.security.plaid;

import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verification filter consumes the body to hash it; the controller must still be able to read
 * it. Spring's ContentCachingRequestWrapper only caches what was already read and leaves a second
 * reader with an empty stream, so FRED buffers the bytes itself.
 */
class RereadableRequestWrapperTest {

    private static final byte[] BODY = "{\"webhook_type\":\"ITEM\"}".getBytes(StandardCharsets.UTF_8);

    private static RereadableRequestWrapper wrap() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/plaid/webhook");
        request.setContent(BODY);
        return new RereadableRequestWrapper(request);
    }

    @Test
    void bodyIsReadableMoreThanOnce() throws Exception {
        RereadableRequestWrapper wrapped = wrap();

        assertThat(wrapped.body()).isEqualTo(BODY);
        assertThat(wrapped.getInputStream().readAllBytes()).isEqualTo(BODY);
        assertThat(wrapped.getInputStream().readAllBytes()).isEqualTo(BODY);
        assertThat(wrapped.getReader().readLine()).isEqualTo(new String(BODY, StandardCharsets.UTF_8));
    }

    @Test
    void streamReportsFinishedOnlyAfterTheLastByte() throws Exception {
        ServletInputStream stream = wrap().getInputStream();

        assertThat(stream.isFinished()).isFalse();
        stream.readAllBytes();
        assertThat(stream.isFinished()).isTrue();
    }
}
```
Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.security.plaid.RereadableRequestWrapperTest'`. Expected: `BUILD FAILED`, `error: cannot find symbol` for `RereadableRequestWrapper`.

- [ ] **Step 2: Implement the wrapper**

`P/security/plaid/RereadableRequestWrapper.java`:
```java
package com.investingapp.backend.security.plaid;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Buffers the request body once and serves a fresh stream on every read, so the verification
 * filter can hash the bytes and the controller can still read them. Spring's
 * ContentCachingRequestWrapper is not re-readable and would leave the controller an empty body,
 * a 400, and a permanently dropped webhook (Plaid never retries a 4xx).
 */
public class RereadableRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    public RereadableRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    /** The buffered bytes, shared with every caller: read them, never mutate them. */
    public byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("async reads are not supported");
            }

            @Override
            public int read() {
                return source.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return source.read(b, off, len);
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
```
Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.security.plaid.RereadableRequestWrapperTest'`. Expected: `BUILD SUCCESSFUL`, 2 tests pass.

- [ ] **Step 3: Failing test for the JWK key cache**

`T/security/plaid/PlaidWebhookKeyCacheTest.java` (its `p256`, `b64url` and `jwkFor` helpers are package-private so Step 5 reuses them):
```java
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
        when(plaidApi.webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class)))
                .thenReturn(call(plaidKey(jwk)));

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
        when(plaidApi.webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class)))
                .thenReturn(call(plaidError(500)))
                .thenReturn(call(plaidKey(jwk)));

        assertThatThrownBy(() -> cache.get("kid-1")).isInstanceOf(PlaidWebhookUnavailableException.class);
        assertThat(cache.get("kid-1")).isSameAs(jwk);
        verify(plaidApi, times(2)).webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class));
    }

    @Test
    void networkFailureIsUnavailable() throws Exception {
        when(plaidApi.webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class)))
                .thenReturn(offlineCall());

        assertThatThrownBy(() -> cache.get("kid-1")).isInstanceOf(PlaidWebhookUnavailableException.class);
    }

    @Test
    void evictForcesARefetch() throws Exception {
        JWKPublicKey jwk = jwkFor((ECPublicKey) p256().getPublic(), "kid-1");
        when(plaidApi.webhookVerificationKeyGet(any(WebhookVerificationKeyGetRequest.class)))
                .thenReturn(call(plaidKey(jwk)));

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
```
Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.security.plaid.PlaidWebhookKeyCacheTest'`. Expected: `BUILD FAILED`, `cannot find symbol` for `PlaidWebhookKeyCache` and `PlaidWebhookUnavailableException`.

- [ ] **Step 4: Implement the two exceptions and the key cache**

`P/security/plaid/PlaidWebhookRejectedException.java`:
```java
package com.investingapp.backend.security.plaid;

/** The request itself failed verification. Answered 401, which Plaid treats as permanent (no retry). */
public class PlaidWebhookRejectedException extends RuntimeException {

    public PlaidWebhookRejectedException(String message) {
        super(message);
    }

    public PlaidWebhookRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
```
`P/security/plaid/PlaidWebhookUnavailableException.java`:
```java
package com.investingapp.backend.security.plaid;

/** FRED could not verify (key fetch, Plaid 5xx, unusable JWK). Answered 503 so Plaid retries. */
public class PlaidWebhookUnavailableException extends RuntimeException {

    public PlaidWebhookUnavailableException(String message) {
        super(message);
    }

    public PlaidWebhookUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```
`P/security/plaid/PlaidWebhookKeyCache.java`:
```java
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
```
Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.security.plaid.PlaidWebhookKeyCacheTest'`. Expected: `BUILD SUCCESSFUL`, 6 tests pass.

- [ ] **Step 5: Failing tests for the JWT verifier**

`T/security/plaid/JwtPlaidWebhookVerifierTest.java` (signs real ES256 tokens with a generated P-256 pair; the cache is real, `PlaidApi` is mocked):
```java
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
```
Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.security.plaid.JwtPlaidWebhookVerifierTest'`. Expected: `BUILD FAILED`, `cannot find symbol` for `JwtPlaidWebhookVerifier`.

- [ ] **Step 6: Implement the verifier interface, the JWT verifier and the ci bypass**

`P/security/plaid/PlaidWebhookVerifier.java`:
```java
package com.investingapp.backend.security.plaid;

/**
 * Verifies a Plaid webhook and returns the JWT's iat (seconds since the epoch), which the inbox
 * folds into the dedupe key. Exactly one implementation is registered per profile: the JWT
 * verifier everywhere except ci, the bypass under ci (there is no Plaid network in CI).
 */
public interface PlaidWebhookVerifier {

    /**
     * @throws PlaidWebhookRejectedException    the request fails verification (answered 401)
     * @throws PlaidWebhookUnavailableException FRED cannot verify right now (answered 503)
     */
    long verifyAndReturnIat(byte[] rawBody, String jwt);
}
```
`P/security/plaid/JwtPlaidWebhookVerifier.java`:
```java
package com.investingapp.backend.security.plaid;

import com.plaid.client.model.JWKPublicKey;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SigningKeyResolverAdapter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;

/**
 * Plaid webhook verification (https://plaid.com/docs/api/webhooks/webhook-verification/): the
 * Plaid-Verification header is an ES256 JWT whose kid names a key at
 * /webhook_verification_key/get; iat must be at most 5 minutes old; request_body_sha256 must equal
 * the SHA-256 of the raw bytes (constant-time compare). A key carrying expired_at is accepted only
 * for iat before that instant and is evicted afterwards.
 */
@Component
@Profile("!ci")
public class JwtPlaidWebhookVerifier implements PlaidWebhookVerifier {

    static final String REQUIRED_ALG = "ES256";
    static final long MAX_IAT_AGE_SECONDS = 300;

    private final PlaidWebhookKeyCache keyCache;

    public JwtPlaidWebhookVerifier(PlaidWebhookKeyCache keyCache) {
        this.keyCache = keyCache;
    }

    @Override
    public long verifyAndReturnIat(byte[] rawBody, String jwt) {
        if (jwt == null || jwt.isBlank()) {
            throw new PlaidWebhookRejectedException("Plaid-Verification header missing");
        }
        JWKPublicKey[] usedKey = new JWKPublicKey[1];
        Jws<Claims> jws;
        try {
            jws = Jwts.parserBuilder()
                    .setSigningKeyResolver(new SigningKeyResolverAdapter() {
                        @Override
                        public Key resolveSigningKey(JwsHeader header, Claims claims) {
                            if (!REQUIRED_ALG.equals(header.getAlgorithm())) {
                                throw new PlaidWebhookRejectedException("alg is not ES256: " + header.getAlgorithm());
                            }
                            String kid = header.getKeyId();
                            if (kid == null || kid.isBlank()) {
                                throw new PlaidWebhookRejectedException("kid header missing");
                            }
                            usedKey[0] = keyCache.get(kid);
                            return PlaidWebhookKeyCache.toEcPublicKey(usedKey[0]);
                        }
                    })
                    .build()
                    .parseClaimsJws(jwt);
        } catch (JwtException | IllegalArgumentException e) {
            // Rejected and Unavailable thrown inside the resolver are neither type: they pass through unchanged.
            throw new PlaidWebhookRejectedException("JWT rejected: " + e.getMessage(), e);
        }

        Claims claims = jws.getBody();
        Date issuedAt = claims.getIssuedAt();
        if (issuedAt == null) {
            throw new PlaidWebhookRejectedException("iat claim missing");
        }
        long iat = issuedAt.getTime() / 1000;
        if (iat < Instant.now().getEpochSecond() - MAX_IAT_AGE_SECONDS) {
            throw new PlaidWebhookRejectedException("iat older than 5 minutes: " + iat);
        }

        JWKPublicKey key = usedKey[0];
        if (key != null && key.getExpiredAt() != null) {
            keyCache.evict(key.getKid());
            if (iat >= key.getExpiredAt().longValue()) {
                throw new PlaidWebhookRejectedException("signed with an expired key: kid " + key.getKid());
            }
        }

        Object hashClaim = claims.get("request_body_sha256");
        String expectedHash = hashClaim instanceof String s ? s : null;
        if (expectedHash == null || !MessageDigest.isEqual(
                sha256Hex(rawBody).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new PlaidWebhookRejectedException("request_body_sha256 does not match the raw body");
        }
        return iat;
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
```
`P/security/plaid/CiBypassPlaidWebhookVerifier.java` (bean name is pinned: T14's `BrokerEnvironmentGuard` refuses `ciBypassPlaidWebhookVerifier` in prod):
```java
package com.investingapp.backend.security.plaid;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * CI has no Plaid network, so the stub PlaidApi could never fetch a verification key. Under ci
 * every webhook is accepted as if signed now. Registered only under ci; BrokerEnvironmentGuard
 * refuses this bean name in prod.
 */
@Component("ciBypassPlaidWebhookVerifier")
@Profile("ci")
public class CiBypassPlaidWebhookVerifier implements PlaidWebhookVerifier {

    @Override
    public long verifyAndReturnIat(byte[] rawBody, String jwt) {
        return Instant.now().getEpochSecond();
    }
}
```
Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.security.plaid.JwtPlaidWebhookVerifierTest'`. Expected: `BUILD SUCCESSFUL`, 10 tests pass.

- [ ] **Step 7: Failing tests for the filter (status per failure class, body re-read)**

`T/security/plaid/PlaidWebhookVerificationFilterTest.java`:
```java
package com.investingapp.backend.security.plaid;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 401 means Plaid stops retrying; 503 means it retries for 24 hours. Only a request that fails
 * verification gets the 401. Everything the filter cannot verify is a 503, and nothing reaches the
 * controller unless the body hash and signature passed.
 */
class PlaidWebhookVerificationFilterTest {

    private static final byte[] BODY = "{\"webhook_type\":\"TRANSACTIONS\"}".getBytes(StandardCharsets.UTF_8);

    private final PlaidWebhookVerifier verifier = mock(PlaidWebhookVerifier.class);
    private final PlaidWebhookVerificationFilter filter = new PlaidWebhookVerificationFilter(verifier);

    private static MockHttpServletRequest webhookRequest(String jwt) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/plaid/webhook");
        request.setRequestURI("/api/plaid/webhook");
        request.setContentType("application/json");
        request.setContent(BODY);
        if (jwt != null) {
            request.addHeader(PlaidWebhookVerificationFilter.HEADER, jwt);
        }
        return request;
    }

    /** Runs the filter and asserts the chain was NOT continued; returns the status it wrote. */
    private int blockedStatus(String jwt) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(webhookRequest(jwt), response, chain);

        assertThat(response.getContentAsString()).isEmpty();
        assertThat(chain.getRequest()).as("filter chain must not continue").isNull();
        return response.getStatus();
    }

    @Test
    void missingHeaderIs401() throws Exception {
        assertThat(blockedStatus(null)).isEqualTo(401);
        assertThat(blockedStatus("")).isEqualTo(401);
    }

    @Test
    void rejectedVerificationIs401() throws Exception {
        when(verifier.verifyAndReturnIat(eq(BODY), eq("bad"))).thenThrow(new PlaidWebhookRejectedException("hash"));

        assertThat(blockedStatus("bad")).isEqualTo(401);
    }

    @Test
    void unavailableVerifierIs503() throws Exception {
        when(verifier.verifyAndReturnIat(eq(BODY), eq("down"))).thenThrow(new PlaidWebhookUnavailableException("key fetch"));

        assertThat(blockedStatus("down")).isEqualTo(503);
    }

    @Test
    void unexpectedVerifierErrorIs503() throws Exception {
        when(verifier.verifyAndReturnIat(eq(BODY), eq("boom"))).thenThrow(new IllegalStateException("boom"));

        assertThat(blockedStatus("boom")).isEqualTo(503);
    }

    @Test
    void controllerReceivesFullBodyAfterFilter() throws Exception {
        when(verifier.verifyAndReturnIat(eq(BODY), eq("jwt"))).thenReturn(1_700_000_000L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(webhookRequest("jwt"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isInstanceOf(RereadableRequestWrapper.class);
        assertThat((byte[]) chain.getRequest().getAttribute(PlaidWebhookVerificationFilter.ATTR_RAW_BODY)).isEqualTo(BODY);
        assertThat(chain.getRequest().getAttribute(PlaidWebhookVerificationFilter.ATTR_JWT_IAT)).isEqualTo(1_700_000_000L);
        assertThat(chain.getRequest().getInputStream().readAllBytes()).isEqualTo(BODY);
        assertThat(chain.getRequest().getInputStream().readAllBytes()).isEqualTo(BODY);
    }
}
```
Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.security.plaid.PlaidWebhookVerificationFilterTest'`. Expected: `BUILD FAILED`, `cannot find symbol` for `PlaidWebhookVerificationFilter`.

- [ ] **Step 8: Implement the filter and register it on the webhook path**

`P/security/plaid/PlaidWebhookVerificationFilter.java` (rejection idiom copied from `DevToolsKeyFilter.java:49-51`: set the status, zero content length, do not continue the chain; the pass-through MUST hand the wrapper, not the original request, to `filterChain.doFilter`):
```java
package com.investingapp.backend.security.plaid;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Runs ahead of the Spring Security chain on POST /api/plaid/webhook (see PlaidWebhookFilterConfig).
 * Buffers the body, verifies the Plaid-Verification JWT against the raw bytes, then hands the
 * controller the bytes and the JWT iat as request attributes. 401 on a failed verification (Plaid
 * never retries a 4xx), 503 when FRED cannot verify (Plaid retries).
 */
public class PlaidWebhookVerificationFilter extends OncePerRequestFilter {

    public static final String HEADER = "Plaid-Verification";
    public static final String ATTR_RAW_BODY = "plaid.webhook.rawBody";
    public static final String ATTR_JWT_IAT = "plaid.webhook.iat";

    private static final Logger logger = LoggerFactory.getLogger(PlaidWebhookVerificationFilter.class);

    private final PlaidWebhookVerifier verifier;

    public PlaidWebhookVerificationFilter(PlaidWebhookVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String jwt = request.getHeader(HEADER);
        if (jwt == null || jwt.isBlank()) {
            logger.warn("plaid.webhook.rejected reason=header_missing");
            reject(response, HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        RereadableRequestWrapper wrapped = new RereadableRequestWrapper(request);
        long iat;
        try {
            iat = verifier.verifyAndReturnIat(wrapped.body(), jwt);
        } catch (PlaidWebhookRejectedException e) {
            logger.warn("plaid.webhook.rejected reason=verification detail=\"{}\"", e.getMessage());
            reject(response, HttpServletResponse.SC_UNAUTHORIZED);
            return;
        } catch (PlaidWebhookUnavailableException e) {
            logger.warn("plaid.webhook.rejected reason=unavailable detail=\"{}\"", e.getMessage());
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        } catch (RuntimeException e) {
            logger.error("plaid.webhook.rejected reason=verifier_error", e);
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }

        wrapped.setAttribute(ATTR_RAW_BODY, wrapped.body());
        wrapped.setAttribute(ATTR_JWT_IAT, iat);
        filterChain.doFilter(wrapped, response);
    }

    private static void reject(HttpServletResponse response, int status) {
        response.setStatus(status);
        response.setContentLength(0);
    }
}
```
`P/config/PlaidWebhookFilterConfig.java` (registration idiom copied from `DevToolsConfig.java:48-57`; not profile gated, per spec 2.8):
```java
package com.investingapp.backend.config;

import com.investingapp.backend.security.plaid.PlaidWebhookVerificationFilter;
import com.investingapp.backend.security.plaid.PlaidWebhookVerifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the Plaid webhook verification filter on its single path, ahead of the Spring Security
 * chain (order -100), so an unverified webhook never reaches the application. Registered in every
 * profile; only the verifier implementation behind it is profile specific.
 */
@Configuration
public class PlaidWebhookFilterConfig {

    @Bean
    public FilterRegistrationBean<PlaidWebhookVerificationFilter> plaidWebhookVerificationFilter(PlaidWebhookVerifier verifier) {
        FilterRegistrationBean<PlaidWebhookVerificationFilter> registration =
                new FilterRegistrationBean<>(new PlaidWebhookVerificationFilter(verifier));
        registration.addUrlPatterns("/api/plaid/webhook");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("plaidWebhookVerificationFilter");
        return registration;
    }
}
```
Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.security.plaid.PlaidWebhookVerificationFilterTest'`. Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 9: Failing tests for the inbox service**

`T/service/PlaidWebhookInboxServiceTest.java` (field-injected service, so collaborators go in by reflection, as in `PlaidLinkedAnalyticsTest.java:62-78`):
```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.PlaidWebhookEvent;
import com.investingapp.backend.model.PlaidWebhookEventStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.PlaidWebhookEventRepository;
import com.investingapp.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every verified webhook becomes one inbox row. The dedupe key folds in the JWT iat: a Plaid retry
 * has a fresh iat and is a new row (handlers are idempotent), while a body-only key would wrongly
 * drop the byte-identical daily SYNC_UPDATES_AVAILABLE. Rows for an unknown Item or another Plaid
 * environment are stored IGNORED so the receiver still answers 200.
 */
class PlaidWebhookInboxServiceTest {

    private static final String ITEM_ID = "item-id-abcdef123456";
    private static final byte[] SYNC_BODY = body("TRANSACTIONS", "SYNC_UPDATES_AVAILABLE", ITEM_ID, "sandbox");

    private PlaidWebhookInboxService service;
    private PlaidWebhookEventRepository eventRepository;
    private UserRepository userRepository;

    static byte[] body(String type, String code, String itemId, String env) {
        return ("{\"webhook_type\":\"" + type + "\",\"webhook_code\":\"" + code + "\",\"item_id\":\"" + itemId
                + "\",\"environment\":\"" + env + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /** sha256(raw body || ":" || iat) as lowercase hex, computed on the bytes exactly as the spec says. */
    static String expectedKey(byte[] body, long iat) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(body);
        digest.update(":".getBytes(StandardCharsets.US_ASCII));
        digest.update(Long.toString(iat).getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest.digest());
    }

    @BeforeEach
    void setUp() {
        service = new PlaidWebhookInboxService();
        eventRepository = mock(PlaidWebhookEventRepository.class);
        userRepository = mock(UserRepository.class);
        ReflectionTestUtils.setField(service, "eventRepository", eventRepository);
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
        ReflectionTestUtils.setField(service, "plaidEnv", "sandbox");

        when(eventRepository.findByDedupeKey(anyString())).thenReturn(Optional.empty());
        when(eventRepository.save(any(PlaidWebhookEvent.class))).thenAnswer(i -> i.getArgument(0));

        User user = new User("inbox@fredvested.com");
        user.setId(99L);
        when(userRepository.findByPlaidItemId(ITEM_ID)).thenReturn(user);
    }

    @Test
    void knownItemIsStoredReceived() throws Exception {
        PlaidWebhookEvent event = service.record(SYNC_BODY, 1_700_000_000L);

        assertThat(event.getStatus()).isEqualTo(PlaidWebhookEventStatus.RECEIVED);
        assertThat(event.getDedupeKey()).isEqualTo(expectedKey(SYNC_BODY, 1_700_000_000L));
        assertThat(event.getWebhookType()).isEqualTo("TRANSACTIONS");
        assertThat(event.getWebhookCode()).isEqualTo("SYNC_UPDATES_AVAILABLE");
        assertThat(event.getPlaidItemId()).isEqualTo(ITEM_ID);
        assertThat(event.getUserId()).isEqualTo(99L);
        assertThat(event.getEnvironment()).isEqualTo("sandbox");
        assertThat(event.getPayloadJson()).isEqualTo(new String(SYNC_BODY, StandardCharsets.UTF_8));
        assertThat(event.getAttempts()).isEqualTo((short) 0);
        assertThat(event.getJwtIat()).isEqualTo(1_700_000_000L);
        assertThat(event.getReceivedAt()).isNotNull();
        verify(eventRepository).save(event);
    }

    @Test
    void identicalDailySyncBodiesAreNotDeduped() throws Exception {
        PlaidWebhookEvent first = service.record(SYNC_BODY, 1_700_000_000L);
        PlaidWebhookEvent second = service.record(SYNC_BODY, 1_700_086_400L);

        assertThat(first.getDedupeKey()).isNotEqualTo(second.getDedupeKey());
        verify(eventRepository, times(2)).save(any(PlaidWebhookEvent.class));
    }

    @Test
    void sameBodyAndIatReturnsTheExistingRow() throws Exception {
        PlaidWebhookEvent existing = new PlaidWebhookEvent();
        when(eventRepository.findByDedupeKey(expectedKey(SYNC_BODY, 1_700_000_000L))).thenReturn(Optional.of(existing));

        assertThat(service.record(SYNC_BODY, 1_700_000_000L)).isSameAs(existing);
        verify(eventRepository, times(0)).save(any(PlaidWebhookEvent.class));
    }

    @Test
    void unknownItemIsIgnored() {
        PlaidWebhookEvent event = service.record(body("ITEM", "ERROR", "item-unknown", "sandbox"), 1L);

        assertThat(event.getStatus()).isEqualTo(PlaidWebhookEventStatus.IGNORED);
        assertThat(event.getUserId()).isNull();
        assertThat(event.getPlaidItemId()).isEqualTo("item-unknown");
    }

    @Test
    void otherEnvironmentIsIgnored() {
        PlaidWebhookEvent event = service.record(body("ITEM", "ERROR", ITEM_ID, "production"), 1L);

        assertThat(event.getStatus()).isEqualTo(PlaidWebhookEventStatus.IGNORED);
        assertThat(event.getUserId()).isEqualTo(99L);
    }

    @Test
    void ambiguousItemIsIgnoredNotDropped() {
        when(userRepository.findByPlaidItemId(ITEM_ID)).thenThrow(new IncorrectResultSizeDataAccessException(1, 2));

        PlaidWebhookEvent event = service.record(SYNC_BODY, 1L);

        assertThat(event.getStatus()).isEqualTo(PlaidWebhookEventStatus.IGNORED);
        assertThat(event.getUserId()).isNull();
        verify(eventRepository).save(event);
    }

    @Test
    void mappingFailureThrowsIllegalArgument() {
        byte[] noCode = "{\"webhook_type\":\"ITEM\",\"item_id\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        byte[] notJson = "not json".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.record(noCode, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.record(notJson, 1L)).isInstanceOf(IllegalArgumentException.class);
        verify(eventRepository, times(0)).save(any(PlaidWebhookEvent.class));
    }
}
```
Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidWebhookInboxServiceTest'`. Expected: `BUILD FAILED`, `cannot find symbol` for `PlaidWebhookInboxService` and `PlaidWebhookEventRepository`.

- [ ] **Step 10: Implement the repository and the inbox service**

`P/repository/PlaidWebhookEventRepository.java`:
```java
package com.investingapp.backend.repository;

import com.investingapp.backend.model.PlaidWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface PlaidWebhookEventRepository extends JpaRepository<PlaidWebhookEvent, Long> {

    Optional<PlaidWebhookEvent> findByDedupeKey(String dedupeKey);

    /** Used by UserDeletionService (T11). A derived delete needs a read-write transaction. */
    @Transactional
    long deleteByUserId(Long userId);
}
```
`P/service/PlaidWebhookInboxService.java`:
```java
package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.PlaidWebhookEvent;
import com.investingapp.backend.model.PlaidWebhookEventStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.PlaidWebhookEventRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.EasternTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The Plaid webhook inbox. Every verified webhook becomes one plaid_webhook_events row; nothing is
 * processed here (the processor is Phase 1). dedupe_key = sha256(raw body || ":" || jwt iat): a
 * Plaid retry carries a fresh iat and is a new row, while a body-only key would wrongly drop the
 * byte-identical daily SYNC_UPDATES_AVAILABLE. Rows for an unknown or removed item_id, or for
 * another Plaid environment, are stored IGNORED so the receiver still answers 200.
 */
@Service
public class PlaidWebhookInboxService {

    private static final Logger logger = LoggerFactory.getLogger(PlaidWebhookInboxService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private PlaidWebhookEventRepository eventRepository;

    @Autowired
    private UserRepository userRepository;

    @Value("${plaid.env}")
    private String plaidEnv;

    /**
     * @throws IllegalArgumentException the body is not JSON or lacks webhook_type / webhook_code
     *                                  (the controller answers 503 so Plaid retries)
     */
    @Transactional
    public PlaidWebhookEvent record(byte[] rawBody, long iat) {
        String dedupeKey = dedupeKey(rawBody, iat);
        Optional<PlaidWebhookEvent> existing = eventRepository.findByDedupeKey(dedupeKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (IOException e) {
            throw new IllegalArgumentException("webhook body is not JSON", e);
        }
        String webhookType = field(payload, "webhook_type", true);
        String webhookCode = field(payload, "webhook_code", true);
        String itemId = field(payload, "item_id", false);
        String environment = field(payload, "environment", false);
        Long userId = resolveUserId(itemId);
        boolean sameEnvironment = plaidEnv != null && plaidEnv.equalsIgnoreCase(environment);
        PlaidWebhookEventStatus status = userId != null && sameEnvironment
                ? PlaidWebhookEventStatus.RECEIVED
                : PlaidWebhookEventStatus.IGNORED;

        PlaidWebhookEvent event = new PlaidWebhookEvent();
        event.setDedupeKey(dedupeKey);
        event.setWebhookType(webhookType);
        event.setWebhookCode(webhookCode);
        event.setPlaidItemId(itemId);
        event.setUserId(userId);
        event.setEnvironment(environment == null ? "unknown" : environment);
        event.setPayloadJson(new String(rawBody, StandardCharsets.UTF_8));
        event.setStatus(status);
        event.setAttempts((short) 0);
        event.setJwtIat(iat);
        event.setReceivedAt(EasternTime.now());

        PlaidWebhookEvent saved = eventRepository.save(event);
        logger.info("plaid.webhook.received type={} code={} status={} user_id={}",
                webhookType, webhookCode, status, userId);
        return saved;
    }

    private Long resolveUserId(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        try {
            User user = userRepository.findByPlaidItemId(itemId);
            return user == null ? null : user.getId();
        } catch (IncorrectResultSizeDataAccessException e) {
            // plaid_item_id has no unique constraint; a sandbox Item reused across users must not drop the row
            logger.error("alert.plaid.webhook.ambiguous_item item_id={}", itemId);
            return null;
        }
    }

    private static String field(JsonNode payload, String name, boolean required) {
        JsonNode node = payload.get(name);
        String value = node == null || node.isNull() ? null : node.asText();
        if (required && (value == null || value.isBlank())) {
            throw new IllegalArgumentException("webhook body is missing " + name);
        }
        return value;
    }

    static String dedupeKey(byte[] rawBody, long iat) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(rawBody);
            digest.update(":".getBytes(StandardCharsets.US_ASCII));
            digest.update(Long.toString(iat).getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }
}
```
Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.service.PlaidWebhookInboxServiceTest'`. Expected: `BUILD SUCCESSFUL`, 7 tests pass.

- [ ] **Step 11: Failing test for the controller**

`T/controller/PlaidWebhookControllerTest.java`:
```java
package com.investingapp.backend.controller;

import com.investingapp.backend.model.PlaidWebhookEvent;
import com.investingapp.backend.security.plaid.PlaidWebhookVerificationFilter;
import com.investingapp.backend.service.PlaidWebhookInboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The controller never reads @RequestBody: it stores the bytes the filter verified. Any failure to
 * store is a 503 so Plaid retries; a 4xx would be permanent.
 */
class PlaidWebhookControllerTest {

    private static final byte[] BODY =
            "{\"webhook_type\":\"ITEM\",\"webhook_code\":\"ERROR\"}".getBytes(StandardCharsets.UTF_8);

    private PlaidWebhookInboxService inboxService;
    private PlaidWebhookController controller;

    @BeforeEach
    void setUp() {
        inboxService = mock(PlaidWebhookInboxService.class);
        controller = new PlaidWebhookController();
        ReflectionTestUtils.setField(controller, "inboxService", inboxService);
    }

    private static MockHttpServletRequest verifiedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/plaid/webhook");
        request.setAttribute(PlaidWebhookVerificationFilter.ATTR_RAW_BODY, BODY);
        request.setAttribute(PlaidWebhookVerificationFilter.ATTR_JWT_IAT, 1_700_000_000L);
        return request;
    }

    @Test
    void storedEventAnswers200WithNoBody() {
        when(inboxService.record(eq(BODY), eq(1_700_000_000L))).thenReturn(new PlaidWebhookEvent());

        var response = controller.receive(verifiedRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void mappingFailureReturns503() {
        when(inboxService.record(eq(BODY), eq(1_700_000_000L)))
                .thenThrow(new IllegalArgumentException("webhook body is missing webhook_code"));

        assertThat(controller.receive(verifiedRequest()).getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void databaseFailureReturns503() {
        when(inboxService.record(eq(BODY), eq(1_700_000_000L)))
                .thenThrow(new RuntimeException("could not execute statement"));

        assertThat(controller.receive(verifiedRequest()).getStatusCode().value()).isEqualTo(503);
    }

    @Test
    void requestWithoutFilterAttributesIs503AndNeverStored() {
        MockHttpServletRequest unverified = new MockHttpServletRequest("POST", "/api/plaid/webhook");

        assertThat(controller.receive(unverified).getStatusCode().value()).isEqualTo(503);
        verifyNoInteractions(inboxService);
    }
}
```
Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.PlaidWebhookControllerTest'`. Expected: `BUILD FAILED`, `cannot find symbol` for `PlaidWebhookController`.

- [ ] **Step 12: Implement the controller**

`P/controller/PlaidWebhookController.java` (its own class, not a method on `PlaidController`, because `PlaidController` is `@CrossOrigin(origins = "*")` and carries the authenticated-user helpers; the webhook has neither):
```java
package com.investingapp.backend.controller;

import com.investingapp.backend.security.plaid.PlaidWebhookVerificationFilter;
import com.investingapp.backend.service.PlaidWebhookInboxService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/plaid/webhook: permitAll in SecurityConfig, verified by PlaidWebhookVerificationFilter.
 * Reads the verified bytes and JWT iat from the filter's request attributes, never @RequestBody,
 * stores the row and answers 200 within Plaid's 10 seconds. Any failure to store is a 503 so Plaid
 * retries; only the filter answers 401.
 */
@RestController
public class PlaidWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(PlaidWebhookController.class);

    @Autowired
    private PlaidWebhookInboxService inboxService;

    @PostMapping("/api/plaid/webhook")
    public ResponseEntity<Void> receive(HttpServletRequest request) {
        Object body = request.getAttribute(PlaidWebhookVerificationFilter.ATTR_RAW_BODY);
        Object iat = request.getAttribute(PlaidWebhookVerificationFilter.ATTR_JWT_IAT);
        if (!(body instanceof byte[] rawBody) || !(iat instanceof Long jwtIat)) {
            logger.error("plaid.webhook.rejected reason=unverified_request_reached_controller");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        try {
            inboxService.record(rawBody, jwtIat);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            logger.error("plaid.webhook.rejected reason=not_stored detail=\"{}\"", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }
}
```
Run: `cd backend && ./gradlew test --tests 'com.investingapp.backend.controller.PlaidWebhookControllerTest'`. Expected: `BUILD SUCCESSFUL`, 4 tests pass.

- [ ] **Step 13: permitAll the path and add `plaid.webhook.url` to the non-prod profiles**

`P/config/SecurityConfig.java`: the authorize block currently reads (lines 112-113)
```java
                .requestMatchers("/.well-known/apple-app-site-association").permitAll()
                .requestMatchers("/api/auth/**").permitAll()
```
Insert directly after line 113 (`.requestMatchers("/api/auth/**").permitAll()`), before the `/api/plaid/create_link_token_anonymous` line:
```java
                .requestMatchers("/api/plaid/webhook").permitAll() // signature-verified by PlaidWebhookVerificationFilter
```
Without it `AuthEntryPointJwt` answers 401 to every Plaid call, even after a valid signature, and Plaid never retries a 4xx. `AuthTokenFilter` (security/jwt/AuthTokenFilter.java:52-88) only reads an `Authorization` header and continues the chain when none is present, so it needs no change.

Append one line directly after `plaid.env=sandbox` in each of `backend/src/main/resources/application-local.properties` (line 19), `application-dev.properties` (line 39) and `application-test.properties` (line 33):
```properties
plaid.webhook.url=${PLAID_WEBHOOK_URL}
```
and after `plaid.env=sandbox` in `backend/src/main/resources/application-ci.properties` (line 52):
```properties
plaid.webhook.url=https://ci.invalid/api/plaid/webhook
```
Nothing reads `plaid.webhook.url` until T08 and T14 (Spring resolves a placeholder only when the key is read), so an unset `PLAID_WEBHOOK_URL` does not break boot in this task. Note for T08: from then on local, dev and test boot need `PLAID_WEBHOOK_URL` exported (locally the Cloudflare tunnel URL, for example `https://local.fredvested.com/api/plaid/webhook`).

Verify (expect exactly one hit per file):
```
grep -n "api/plaid/webhook" backend/src/main/java/com/investingapp/backend/config/SecurityConfig.java
grep -n "plaid.webhook.url" backend/src/main/resources/application-local.properties backend/src/main/resources/application-dev.properties backend/src/main/resources/application-test.properties backend/src/main/resources/application-ci.properties
```

- [ ] **Step 14: Full gate, hygiene checks, commit**

Gate:
```
cd backend && ./gradlew test --tests 'com.investingapp.backend.security.plaid.*' --tests 'com.investingapp.backend.service.PlaidWebhookInboxServiceTest' --tests 'com.investingapp.backend.controller.PlaidWebhookControllerTest' --tests 'com.investingapp.backend.security.DevToolsKeyFilterTest' --tests 'com.investingapp.backend.BackendApplicationTests'
```
Expected: `BUILD SUCCESSFUL`; 34 new tests pass (2 + 6 + 10 + 5 + 7 + 4); `DevToolsKeyFilterTest` still passes (same filter order, different path). `BackendApplicationTests.contextLoads` must show PASSED in `backend/build/reports/tests/test/index.html` to prove the ci context boots with `CiBypassPlaidWebhookVerifier`, `PlaidWebhookKeyCache` (wired to `CiStubConfig.plaidApi`), the filter registration and the repository against the V6 table; without a reachable ci MySQL it is SKIPPED (`RequiresCiDatabase.java:64-69`) and proves nothing. To get PASSED locally, use the T04 scratch schema: `SPRING_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/fred_ci_scratch?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC' SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD='<application-local.properties:3>' ./gradlew test --tests 'com.investingapp.backend.BackendApplicationTests'`.

House-style check (expect no output):
```
LC_ALL=C grep -rln $'\xe2\x80\x94' backend/src/main/java/com/investingapp/backend/security/plaid backend/src/test/java/com/investingapp/backend/security/plaid backend/src/main/java/com/investingapp/backend/config/PlaidWebhookFilterConfig.java backend/src/main/java/com/investingapp/backend/controller/PlaidWebhookController.java backend/src/main/java/com/investingapp/backend/service/PlaidWebhookInboxService.java backend/src/main/java/com/investingapp/backend/repository/PlaidWebhookEventRepository.java backend/src/test/java/com/investingapp/backend/service/PlaidWebhookInboxServiceTest.java backend/src/test/java/com/investingapp/backend/controller/PlaidWebhookControllerTest.java
```
Shared dirty tree check: `git diff HEAD -- backend/src/main/java/com/investingapp/backend/config/SecurityConfig.java backend/src/main/resources/application-local.properties backend/src/main/resources/application-dev.properties backend/src/main/resources/application-test.properties backend/src/main/resources/application-ci.properties` must show exactly one added line per file; any other hunk is foreign WIP, so stage those files with `git add -p` and take only your line. Never `git add backend/src/main/resources` as a directory (7 untracked `R__v_*.sql` files and `db/roles/` live there).

```
git add backend/src/main/java/com/investingapp/backend/security/plaid/RereadableRequestWrapper.java backend/src/main/java/com/investingapp/backend/security/plaid/PlaidWebhookRejectedException.java backend/src/main/java/com/investingapp/backend/security/plaid/PlaidWebhookUnavailableException.java backend/src/main/java/com/investingapp/backend/security/plaid/PlaidWebhookKeyCache.java backend/src/main/java/com/investingapp/backend/security/plaid/PlaidWebhookVerifier.java backend/src/main/java/com/investingapp/backend/security/plaid/JwtPlaidWebhookVerifier.java backend/src/main/java/com/investingapp/backend/security/plaid/CiBypassPlaidWebhookVerifier.java backend/src/main/java/com/investingapp/backend/security/plaid/PlaidWebhookVerificationFilter.java backend/src/main/java/com/investingapp/backend/config/PlaidWebhookFilterConfig.java backend/src/main/java/com/investingapp/backend/config/SecurityConfig.java backend/src/main/java/com/investingapp/backend/controller/PlaidWebhookController.java backend/src/main/java/com/investingapp/backend/service/PlaidWebhookInboxService.java backend/src/main/java/com/investingapp/backend/repository/PlaidWebhookEventRepository.java backend/src/test/java/com/investingapp/backend/security/plaid/RereadableRequestWrapperTest.java backend/src/test/java/com/investingapp/backend/security/plaid/PlaidWebhookKeyCacheTest.java backend/src/test/java/com/investingapp/backend/security/plaid/JwtPlaidWebhookVerifierTest.java backend/src/test/java/com/investingapp/backend/security/plaid/PlaidWebhookVerificationFilterTest.java backend/src/test/java/com/investingapp/backend/service/PlaidWebhookInboxServiceTest.java backend/src/test/java/com/investingapp/backend/controller/PlaidWebhookControllerTest.java backend/src/main/resources/application-local.properties backend/src/main/resources/application-dev.properties backend/src/main/resources/application-test.properties backend/src/main/resources/application-ci.properties
git commit -m "feat(plaid): verified webhook inbox on POST /api/plaid/webhook"
```