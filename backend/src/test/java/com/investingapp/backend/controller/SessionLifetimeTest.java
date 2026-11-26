package com.investingapp.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.security.DevToolsKeyValidator;
import com.investingapp.backend.security.jwt.JwtUtils;
import com.investingapp.backend.support.RequiresCiDatabase;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * A sign-in must not last forever, and every token must be one the account can take back.
 *
 * Two holes this pins shut:
 *
 *   1. Tokens with no session. A token issued at sign-up carried no session id, so signing a device
 *      out could never reach it.
 *   2. Endless refresh. POST /api/auth/refresh gave any live token a fresh hour, every time, with no
 *      limit. A stolen token could be kept alive for good without ever touching the passkey.
 *
 * Now every token belongs to a session, and carries the moment that session ends: 12 hours after
 * the passkey sign-in that started it. Refresh keeps that moment; it never moves it.
 *
 * Everything here runs through the real security filter chain, real controllers and real SQL.
 * Tokens that the server must refuse are signed in the test with the CI secret, because the server
 * itself can no longer be made to issue them.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class SessionLifetimeTest {

    private static final Duration SESSION_LIFETIME = Duration.ofHours(12);
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(1);

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtUtils jwtUtils;

    @Value("${jwt.secret}") private String jwtSecret;
    @Value("${app.dev-tools.key}") private String devToolsKey;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private String email;
    private Long userId;
    private String signUpEmail;

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        email = "sess-" + UUID.randomUUID() + "@fredvested.test";
        signUpEmail = "sess-new-" + UUID.randomUUID() + "@fredvested.test";
        jdbc.update("INSERT INTO users (email, create_date, public_id, analytics_opt_out, user_handle) "
                + "VALUES (?, ?, ?, 0, ?)", email, LocalDateTime.now(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", "").substring(0, 22));
        userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    @AfterEach
    void tearDown() {
        for (String address : List.of(email, signUpEmail)) {
            List<Long> ids = jdbc.queryForList("SELECT id FROM users WHERE email = ?", Long.class, address);
            for (Long id : ids) {
                jdbc.update("DELETE FROM user_sessions WHERE user_id = ?", id);
                jdbc.update("DELETE FROM user_progress WHERE user_id = ?", id);
                jdbc.update("DELETE FROM users WHERE id = ?", id);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request, String bearer, String deviceId)
            throws Exception {
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        if (deviceId != null) {
            request = request.header("X-Device-ID", deviceId);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    /** A protected endpoint: 200 when the token is honoured, 401 when it is not. */
    private int useToken(String bearer) throws Exception {
        return call(MockMvcRequestBuilders.get("/api/user/sessions"), bearer, null).getStatus();
    }

    private MockHttpServletResponse refresh(String bearer) throws Exception {
        return call(MockMvcRequestBuilders.post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), bearer, null);
    }

    private String refreshedToken(String bearer) throws Exception {
        MockHttpServletResponse response = refresh(bearer);
        assertThat(response.getStatus()).as("refresh of a live token").isEqualTo(200);
        return json.readTree(response.getContentAsString()).path("jwtToken").asText();
    }

    /** Signs the seeded user in the way the simulator does: a real sign-in endpoint, a real session. */
    private String signIn(String deviceId) throws Exception {
        MockHttpServletResponse response = call(MockMvcRequestBuilders.post("/api/dev/authenticate-as-user")
                .contentType(MediaType.APPLICATION_JSON)
                .header(DevToolsKeyValidator.HEADER, devToolsKey)
                .content(json.writeValueAsString(Map.of("email", email))), null, deviceId);
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.path("success").asBoolean()).as("sign-in").isTrue();
        return body.path("jwtToken").asText();
    }

    /** Signs a brand new user up through the passkey registration endpoint (simulated authenticator). */
    private String signUp() throws Exception {
        Map<String, Object> body = Map.of(
                "email", signUpEmail,
                "credential", Map.of(
                        "id", "sim", "rawId", "sim", "type", "public-key",
                        "response", Map.of("attestationObject", "SIMULATED_ATTESTATION", "clientDataJSON", "sim")));
        MockHttpServletResponse response = call(MockMvcRequestBuilders.post("/api/passkey/register/finish")
                .contentType(MediaType.APPLICATION_JSON)
                .header(DevToolsKeyValidator.HEADER, devToolsKey)
                .content(json.writeValueAsString(body)), null, "sign-up-phone");
        assertThat(response.getStatus()).as("sign-up: " + response.getContentAsString()).isEqualTo(200);
        return json.readTree(response.getContentAsString()).path("jwtToken").asText();
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    private Claims claims(String token) {
        return Jwts.parserBuilder().setSigningKey(key()).build().parseClaimsJws(token).getBody();
    }

    /** When the token's session ends, read from its "sx" claim. Null when the token has none. */
    private Instant sessionEnd(String token) {
        Number seconds = claims(token).get("sx", Number.class);
        return seconds == null ? null : Instant.ofEpochSecond(seconds.longValue());
    }

    /**
     * Signs a token the way the server would, with whatever the test wants in it. Stands in for
     * tokens issued before this fix, and for tokens the server must refuse.
     *
     * @param sessionId  session id claim, or null for none
     * @param sessionEnd "sx" claim, or null for none
     */
    private String forge(Long sessionId, Instant sessionEnd, Instant issuedAt, Instant expires) {
        JwtBuilder builder = Jwts.builder()
                .setSubject(email)
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(Date.from(issuedAt))
                .setExpiration(Date.from(expires))
                .setIssuer("FRED");
        if (sessionId != null) {
            builder.claim("sessionId", sessionId);
        }
        if (sessionEnd != null) {
            builder.claim("sx", sessionEnd.getEpochSecond());
        }
        return builder.signWith(key(), SignatureAlgorithm.HS256).compact();
    }

    private Long insertSession(String deviceId) {
        jdbc.update("INSERT INTO user_sessions (active, device_id, device_info, user_id, created_at) "
                + "VALUES (1, ?, 'test device', ?, NOW())", deviceId, userId);
        return jdbc.queryForObject("SELECT MAX(id) FROM user_sessions WHERE user_id = ? AND device_id = ?",
                Long.class, userId, deviceId);
    }

    /** True when the session row exists and is active. A removed session is not active. */
    private boolean sessionActive(Long sessionId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_sessions WHERE id = ? AND active = 1", Long.class, sessionId) > 0;
    }

    private long sessionRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions WHERE user_id = ?", Long.class, userId);
    }

    private int revoke(Long sessionId, String bearer) throws Exception {
        return call(MockMvcRequestBuilders.post("/api/user/sessions/" + sessionId + "/revoke"), bearer, null)
                .getStatus();
    }

    // ------------------------------------------------------------------ every token has a session

    @Test
    @DisplayName("the token from sign-up belongs to a session")
    void signUpTokenHasASession() throws Exception {
        String token = signUp();

        Long sessionId = jwtUtils.getSessionIdFromJwtToken(token);
        assertThat(sessionId).as("session id in the sign-up token").isNotNull();
        assertThat(sessionActive(sessionId)).isTrue();
        Long owner = jdbc.queryForObject("SELECT user_id FROM user_sessions WHERE id = ?", Long.class, sessionId);
        Long signedUp = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, signUpEmail);
        assertThat(owner).isEqualTo(signedUp);
        assertThat(sessionEnd(token)).as("when the sign-up session ends")
                .isCloseTo(Instant.now().plus(SESSION_LIFETIME), within(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("the token from sign-up stops working when its session is revoked")
    void signUpTokenCanBeRevoked() throws Exception {
        String token = signUp();
        assertThat(useToken(token)).isEqualTo(200);
        Long sessionId = jwtUtils.getSessionIdFromJwtToken(token);
        assertThat(sessionId).isNotNull();

        assertThat(revoke(sessionId, token)).isEqualTo(200);

        assertThat(useToken(token)).isEqualTo(401);
        assertThat(refresh(token).getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("a token with no session is refused")
    void tokenWithNoSessionIsRefused() throws Exception {
        Instant now = Instant.now();
        String loose = forge(null, null, now, now.plus(TOKEN_LIFETIME));
        String looseWithEnd = forge(null, now.plus(SESSION_LIFETIME), now, now.plus(TOKEN_LIFETIME));

        assertThat(useToken(loose)).isEqualTo(401);
        assertThat(refresh(loose).getStatus()).isEqualTo(401);
        assertThat(useToken(looseWithEnd)).isEqualTo(401);
        assertThat(refresh(looseWithEnd).getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("a token naming a session that does not exist, or another user's session, is refused")
    void tokenMustNameItsOwnLiveSession() throws Exception {
        Instant now = Instant.now();
        String ghost = forge(999_999_999L, now.plus(SESSION_LIFETIME), now, now.plus(TOKEN_LIFETIME));
        assertThat(useToken(ghost)).isEqualTo(401);

        // Someone else's live session does not vouch for this user's token.
        String otherToken = signUp();
        Long otherSession = jwtUtils.getSessionIdFromJwtToken(otherToken);
        assertThat(otherSession).isNotNull();
        String borrowed = forge(otherSession, now.plus(SESSION_LIFETIME), now, now.plus(TOKEN_LIFETIME));
        assertThat(useToken(borrowed)).isEqualTo(401);
        assertThat(refresh(borrowed).getStatus()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ no session lasts forever

    @Test
    @DisplayName("a sign-in is good for 12 hours and refresh never moves that")
    void refreshKeepsTheSessionEnd() throws Exception {
        String first = signIn("phone");
        Instant end = sessionEnd(first);
        assertThat(end).as("session end in the sign-in token")
                .isNotNull()
                .isCloseTo(Instant.now().plus(SESSION_LIFETIME), within(Duration.ofMinutes(1)));

        String second = refreshedToken(first);
        String third = refreshedToken(second);

        assertThat(sessionEnd(second)).isEqualTo(end);
        assertThat(sessionEnd(third)).isEqualTo(end);
        assertThat(jwtUtils.getSessionIdFromJwtToken(third)).isEqualTo(jwtUtils.getSessionIdFromJwtToken(first));
        assertThat(useToken(third)).isEqualTo(200);
    }

    @Test
    @DisplayName("a token whose session has run out cannot be refreshed or used")
    void anEndedSessionCannotBeRefreshed() throws Exception {
        Long sessionId = insertSession("phone");
        Instant now = Instant.now();
        // Signed in 13 hours ago and kept alive by refreshing ever since. The token itself is in date.
        String kept = forge(sessionId, now.minus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(5)),
                now.plus(Duration.ofMinutes(55)));

        assertThat(refresh(kept).getStatus()).isEqualTo(401);
        assertThat(useToken(kept)).isEqualTo(401);
    }

    @Test
    @DisplayName("near the end of a session, a refreshed token ends when the session does")
    void refreshNeverOutlivesTheSession() throws Exception {
        Long sessionId = insertSession("phone");
        Instant now = Instant.now();
        Instant end = now.plus(Duration.ofMinutes(10)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String late = forge(sessionId, end, now.minus(Duration.ofMinutes(50)), end);
        assertThat(useToken(late)).isEqualTo(200);

        String refreshed = refreshedToken(late);

        assertThat(sessionEnd(refreshed)).isEqualTo(end);
        assertThat(claims(refreshed).getExpiration().toInstant()).isBeforeOrEqualTo(end);
    }

    @Test
    @DisplayName("a token from before this fix works until it expires but cannot be refreshed")
    void aTokenWithNoSessionEndCannotBeRefreshed() throws Exception {
        Long sessionId = insertSession("phone");
        Instant now = Instant.now();
        String old = forge(sessionId, null, now.minus(Duration.ofMinutes(10)), now.plus(Duration.ofMinutes(50)));

        assertThat(useToken(old)).as("still signed in until the token expires").isEqualTo(200);
        assertThat(refresh(old).getStatus()).as("but it cannot be given more time").isEqualTo(401);
    }

    // ------------------------------------------------------------------ a revoked session stays dead

    @Test
    @DisplayName("signing in again on the same device starts a new session and ends the old one")
    void signingInAgainEndsTheOldSession() throws Exception {
        String first = signIn("phone");
        Long firstSession = jwtUtils.getSessionIdFromJwtToken(first);

        String second = signIn("phone");
        Long secondSession = jwtUtils.getSessionIdFromJwtToken(second);

        assertThat(secondSession).isNotEqualTo(firstSession);
        assertThat(sessionActive(firstSession)).isFalse();
        assertThat(useToken(first)).isEqualTo(401);
        assertThat(useToken(second)).isEqualTo(200);
    }

    @Test
    @DisplayName("a revoked token does not come back to life when the user signs in again")
    void aRevokedTokenStaysDeadAfterTheNextSignIn() throws Exception {
        String stolen = signIn("phone");
        Long session = jwtUtils.getSessionIdFromJwtToken(stolen);
        assertThat(revoke(session, stolen)).isEqualTo(200);
        assertThat(useToken(stolen)).isEqualTo(401);

        String fresh = signIn("phone");

        assertThat(useToken(fresh)).isEqualTo(200);
        assertThat(useToken(stolen)).as("the revoked token after a new sign-in on that device").isEqualTo(401);
        assertThat(refresh(stolen).getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("signing in on a second device leaves the first device signed in")
    void twoDevicesStaySignedIn() throws Exception {
        String phone = signIn("phone");
        String tablet = signIn("tablet");

        assertThat(useToken(phone)).isEqualTo(200);
        assertThat(useToken(tablet)).isEqualTo(200);
        assertThat(jwtUtils.getSessionIdFromJwtToken(phone)).isNotEqualTo(jwtUtils.getSessionIdFromJwtToken(tablet));
    }

    @Test
    @DisplayName("a device holding a dead token can still reach the sign-in endpoints")
    void aDeadTokenDoesNotBlockSigningIn() throws Exception {
        String dead = signIn("phone");
        assertThat(revoke(jwtUtils.getSessionIdFromJwtToken(dead), dead)).isEqualTo(200);

        // The app keeps sending the token it has. Public sign-in routes must answer anyway.
        MockHttpServletResponse start = call(MockMvcRequestBuilders.post("/api/passkey/authenticate/start")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), dead, "phone");
        assertThat(start.getStatus()).as("passkey sign-in start with a dead token attached").isEqualTo(200);
    }

    @Test
    @DisplayName("a refresh that lost the race with a new sign-in gets no token")
    void refreshIsRefusedOnceTheSessionIsReplaced() throws Exception {
        String old = signIn("phone");
        signIn("phone");

        MockHttpServletResponse response = refresh(old);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).doesNotContain("jwtToken");
    }

    @Test
    @DisplayName("signing in over and over on one device leaves one session row, not a pile")
    void sessionsDoNotPileUp() throws Exception {
        for (int i = 0; i < 5; i++) {
            signIn("phone");
        }
        String tablet = signIn("tablet");
        assertThat(sessionRows()).isEqualTo(2);

        // An ended session is cleared out by the next sign-in on any device.
        assertThat(revoke(jwtUtils.getSessionIdFromJwtToken(tablet), tablet)).isEqualTo(200);
        String phone = signIn("phone");
        assertThat(sessionRows()).isEqualTo(1);
        assertThat(useToken(phone)).isEqualTo(200);
        assertThat(useToken(tablet)).isEqualTo(401);
    }

    @Test
    @DisplayName("a session that started long ago is cleared out; one still running is left alone")
    void expiredSessionsAreClearedOut() throws Exception {
        // Real sign-ins, so the start time is written the way the app writes it; then moved back.
        Long longGone = jwtUtils.getSessionIdFromJwtToken(signIn("old-laptop"));
        Long running = jwtUtils.getSessionIdFromJwtToken(signIn("other-phone"));
        jdbc.update("UPDATE user_sessions SET last_active = last_active - INTERVAL 15 HOUR WHERE id = ?", longGone);
        jdbc.update("UPDATE user_sessions SET last_active = last_active - INTERVAL 11 HOUR WHERE id = ?", running);

        signIn(null); // a sign-in that sends no device id

        assertThat(sessionActive(longGone)).isFalse();
        assertThat(sessionActive(running)).isTrue();
        assertThat(sessionRows()).isEqualTo(2);
    }
}
