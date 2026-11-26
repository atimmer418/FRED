package com.investingapp.backend.security;

import com.investingapp.backend.security.jwt.JwtUtils;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The rules every token follows: it belongs to a session, it carries the moment that session ends,
 * refreshing copies that moment and never moves it, and no token outlives its session.
 */
class JwtSessionTokenTest {

    private static final String SECRET =
            "test-secret-test-secret-test-secret-test-secret-test-secret-1234";
    private static final long HOUR = 3_600_000L;

    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        jwtUtils = utils(HOUR, 12 * HOUR);
    }

    private static JwtUtils utils(long tokenMs, long sessionMs) {
        JwtUtils utils = new JwtUtils();
        ReflectionTestUtils.setField(utils, "jwtSecretString", SECRET);
        ReflectionTestUtils.setField(utils, "jwtExpirationMs", (int) tokenMs);
        ReflectionTestUtils.setField(utils, "sessionMaxMs", sessionMs);
        utils.init();
        return utils;
    }

    /** A token signed with the same secret, holding whatever the test wants. */
    private static String forge(Long sessionId, Instant sessionEnd, Instant expires) {
        var builder = Jwts.builder().setSubject("user@fredvested.com")
                .setIssuedAt(new Date()).setExpiration(Date.from(expires));
        if (sessionId != null) {
            builder.claim("sessionId", sessionId);
        }
        if (sessionEnd != null) {
            builder.claim("sx", sessionEnd.getEpochSecond());
        }
        return builder.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)),
                SignatureAlgorithm.HS256).compact();
    }

    @Test
    @DisplayName("a sign-in token carries its session and a session end 12 hours away")
    void signInTokenCarriesSessionAndEnd() {
        String token = jwtUtils.startSessionToken("user@fredvested.com", 42L, "tabs", "pid");

        assertThat(jwtUtils.validateJwtToken(token)).isTrue();
        assertThat(jwtUtils.getSessionIdFromJwtToken(token)).isEqualTo(42L);
        assertThat(jwtUtils.getSessionEndFromJwtToken(token).toInstant())
                .isCloseTo(Instant.now().plus(Duration.ofHours(12)), within(Duration.ofSeconds(5)));
        assertThat(jwtUtils.getExpirationFromToken(token).toInstant())
                .isCloseTo(Instant.now().plus(Duration.ofHours(1)), within(Duration.ofSeconds(5)));
        assertThat(jwtUtils.sessionHasEnded(token)).isFalse();
    }

    @Test
    @DisplayName("a token cannot be built without a session")
    void noSessionNoToken() {
        assertThatThrownBy(() -> jwtUtils.startSessionToken("user@fredvested.com", null, "tabs", "pid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("continuing a session copies the session id and the session end exactly")
    void continuingKeepsTheEnd() throws Exception {
        String first = jwtUtils.startSessionToken("user@fredvested.com", 42L, "tabs", "pid");
        Thread.sleep(1100); // a later issue time must not move the session end

        String second = jwtUtils.continueSessionToken(first, "user@fredvested.com", "complete", "pid").orElseThrow();
        String third = jwtUtils.continueSessionToken(second, "renamed@fredvested.com", "complete", "pid").orElseThrow();

        assertThat(jwtUtils.getSessionEndFromJwtToken(second)).isEqualTo(jwtUtils.getSessionEndFromJwtToken(first));
        assertThat(jwtUtils.getSessionEndFromJwtToken(third)).isEqualTo(jwtUtils.getSessionEndFromJwtToken(first));
        assertThat(jwtUtils.getSessionIdFromJwtToken(third)).isEqualTo(42L);
        assertThat(jwtUtils.getUserNameFromJwtToken(third)).isEqualTo("renamed@fredvested.com");
        assertThat(jwtUtils.getNextStepFromJwtToken(third)).isEqualTo("complete");
    }

    @Test
    @DisplayName("a token never expires later than its session ends")
    void tokenNeverOutlivesSession() {
        Instant end = Instant.now().plus(Duration.ofMinutes(10)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String late = forge(42L, end, end);

        String next = jwtUtils.continueSessionToken(late, "user@fredvested.com", null, null).orElseThrow();

        assertThat(jwtUtils.getExpirationFromToken(next).toInstant()).isEqualTo(end);
        assertThat(jwtUtils.getSessionEndFromJwtToken(next).toInstant()).isEqualTo(end);
    }

    @Test
    @DisplayName("a session shorter than a token caps the very first token too")
    void shortSessionCapsFirstToken() {
        JwtUtils shortSession = utils(HOUR, 600_000L);
        String token = shortSession.startSessionToken("user@fredvested.com", 42L, null, null);

        assertThat(shortSession.getExpirationFromToken(token).toInstant())
                .isCloseTo(Instant.now().plus(Duration.ofMinutes(10)), within(Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("a session that has ended cannot be continued")
    void endedSessionCannotContinue() {
        Instant future = Instant.now().plus(Duration.ofMinutes(30));
        String ended = forge(42L, Instant.now().minusSeconds(1), future);

        assertThat(jwtUtils.sessionHasEnded(ended)).isTrue();
        assertThat(jwtUtils.continueSessionToken(ended, "user@fredvested.com", null, null)).isEmpty();
    }

    @Test
    @DisplayName("a token with no session, or no session end, cannot be continued")
    void incompleteTokensCannotContinue() {
        Instant future = Instant.now().plus(Duration.ofMinutes(30));
        String noSession = forge(null, Instant.now().plus(Duration.ofHours(5)), future);
        String noEnd = forge(42L, null, future);

        assertThat(jwtUtils.continueSessionToken(noSession, "user@fredvested.com", null, null)).isEmpty();
        assertThat(jwtUtils.continueSessionToken(noEnd, "user@fredvested.com", null, null)).isEmpty();
        assertThat(jwtUtils.sessionHasEnded(noEnd)).as("no end claim is not 'ended' by this rule").isFalse();
        assertThat(jwtUtils.getSessionEndFromJwtToken(noEnd)).isNull();
    }

    @Test
    @DisplayName("renaming a token for an email change keeps the session and never adds time")
    void renameNeverAddsTime() {
        String current = jwtUtils.startSessionToken("old@fredvested.com", 42L, "tabs", "pid");
        String renamed = jwtUtils.renameSessionToken(current, "new@fredvested.com", "tabs", "pid").orElseThrow();
        assertThat(jwtUtils.getUserNameFromJwtToken(renamed)).isEqualTo("new@fredvested.com");
        assertThat(jwtUtils.getSessionIdFromJwtToken(renamed)).isEqualTo(42L);
        assertThat(jwtUtils.getSessionEndFromJwtToken(renamed)).isEqualTo(jwtUtils.getSessionEndFromJwtToken(current));

        // A token from before sessions had an end: same session, same expiry, still no end,
        // so it still cannot be refreshed.
        Instant expires = Instant.now().plus(Duration.ofMinutes(20)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String old = forge(42L, null, expires);
        String oldRenamed = jwtUtils.renameSessionToken(old, "new@fredvested.com", null, null).orElseThrow();
        assertThat(jwtUtils.getUserNameFromJwtToken(oldRenamed)).isEqualTo("new@fredvested.com");
        assertThat(jwtUtils.getExpirationFromToken(oldRenamed).toInstant()).isEqualTo(expires);
        assertThat(jwtUtils.getSessionEndFromJwtToken(oldRenamed)).isNull();
        assertThat(jwtUtils.continueSessionToken(oldRenamed, "new@fredvested.com", null, null)).isEmpty();

        assertThat(jwtUtils.renameSessionToken(forge(null, null, expires), "new@fredvested.com", null, null)).isEmpty();
        assertThat(jwtUtils.renameSessionToken(forge(42L, Instant.now().minusSeconds(1), expires),
                "new@fredvested.com", null, null)).isEmpty();
    }

    @Test
    @DisplayName("an expired, garbled or wrongly signed token cannot be continued")
    void badTokensCannotContinue() {
        String expired = forge(42L, Instant.now().plus(Duration.ofHours(5)), Instant.now().minusSeconds(5));
        String otherKey = Jwts.builder().setSubject("user@fredvested.com")
                .setExpiration(Date.from(Instant.now().plusSeconds(600)))
                .claim("sessionId", 42L).claim("sx", Instant.now().plusSeconds(600).getEpochSecond())
                .signWith(Keys.hmacShaKeyFor("another-secret-another-secret-another-secret-1234567890"
                        .getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256).compact();

        assertThat(jwtUtils.continueSessionToken(expired, "user@fredvested.com", null, null)).isEmpty();
        assertThat(jwtUtils.continueSessionToken(otherKey, "user@fredvested.com", null, null)).isEmpty();
        assertThat(jwtUtils.continueSessionToken("not-a-token", "user@fredvested.com", null, null)).isEmpty();
    }
}
