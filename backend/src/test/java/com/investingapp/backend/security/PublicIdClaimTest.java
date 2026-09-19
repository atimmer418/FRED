package com.investingapp.backend.security;

import com.investingapp.backend.model.User;
import com.investingapp.backend.security.jwt.JwtUtils;
import com.investingapp.backend.security.services.UserDetailsImpl;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client needs the analytics identity on cold start, when it has a valid token but never goes
 * through the sign-in path. Carrying public_id as a JWT claim avoids an extra round trip; the
 * progress response carries it too, for tokens issued before the claim existed.
 */
class PublicIdClaimTest {

    /** Raw secret (JwtUtils uses the UTF-8 bytes directly); HS256 needs at least 256 bits. */
    private static final String SECRET =
            "test-secret-test-secret-test-secret-test-secret-test-secret-1234";

    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "jwtSecretString", SECRET);
        ReflectionTestUtils.setField(jwtUtils, "jwtExpirationMs", 300000);
        jwtUtils.init();
    }

    private Authentication authenticationFor(String publicId) {
        UserDetailsImpl principal = new UserDetailsImpl(
                1L, publicId, "user@fredvested.com", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    @DisplayName("a token minted from a principal carries the pid claim")
    void tokenCarriesPublicId() {
        String publicId = UUID.randomUUID().toString();

        String token = jwtUtils.generateJwtToken(authenticationFor(publicId), "complete");

        assertThat(jwtUtils.getPublicIdFromJwtToken(token)).isEqualTo(publicId);
        assertThat(jwtUtils.getUserNameFromJwtToken(token)).isEqualTo("user@fredvested.com");
    }

    @Test
    @DisplayName("the session-scoped overload carries it too")
    void sessionTokenCarriesPublicId() {
        String publicId = UUID.randomUUID().toString();

        String token = jwtUtils.generateJwtToken(authenticationFor(publicId), 42L, "complete");

        assertThat(jwtUtils.getPublicIdFromJwtToken(token)).isEqualTo(publicId);
        assertThat(jwtUtils.getSessionIdFromJwtToken(token)).isEqualTo(42L);
    }

    @Test
    @DisplayName("refresh and email-change tokens carry it without a principal")
    void tokenFromUsernameCarriesPublicId() {
        String publicId = UUID.randomUUID().toString();

        String token = jwtUtils.generateTokenFromUsername("user@fredvested.com", "complete", publicId);

        assertThat(jwtUtils.getPublicIdFromJwtToken(token)).isEqualTo(publicId);
    }

    @Test
    @DisplayName("an older token with no pid claim reads as null rather than failing")
    void olderTokenHasNoClaim() {
        String token = jwtUtils.generateTokenFromUsername("user@fredvested.com", "complete");

        assertThat(jwtUtils.getPublicIdFromJwtToken(token)).isNull();
    }

    @Test
    @DisplayName("the email is still the subject, and the pid claim is not the email or the row id")
    void claimIsOpaque() {
        String publicId = UUID.randomUUID().toString();

        String token = jwtUtils.generateJwtToken(authenticationFor(publicId), "complete");
        String pid = jwtUtils.getPublicIdFromJwtToken(token);

        assertThat(pid).doesNotContain("@").isNotEqualTo("1");
        assertThat(UUID.fromString(pid)).hasToString(pid);
    }

    @Test
    @DisplayName("every new user row is assigned a public id on insert")
    void newUsersGetAPublicId() {
        User user = new User("someone@fredvested.com");
        assertThat(user.getPublicId()).as("not assigned before persist").isNull();

        ReflectionTestUtils.invokeMethod(user, "assignPublicId");

        assertThat(user.getPublicId()).isNotBlank();
        assertThat(UUID.fromString(user.getPublicId())).isNotNull();
    }

    @Test
    @DisplayName("an existing public id is never regenerated")
    void existingPublicIdIsStable() {
        User user = new User("someone@fredvested.com");
        String assigned = UUID.randomUUID().toString();
        user.setPublicId(assigned);

        ReflectionTestUtils.invokeMethod(user, "assignPublicId");

        assertThat(user.getPublicId()).isEqualTo(assigned);
    }
}
