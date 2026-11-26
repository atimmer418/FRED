// src/main/java/com/investingapp/backend/security/jwt/JwtUtils.java
package com.investingapp.backend.security.jwt;

import io.jsonwebtoken.*; // Keep this
import io.jsonwebtoken.io.Decoders; // For decoding base64 secret
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException; // Keep this
import jakarta.annotation.PostConstruct; // Correct PostConstruct import
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import com.investingapp.backend.security.services.UserDetailsImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey; // Standard Java crypto
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtils {
    private static final Logger logger = LoggerFactory.getLogger(JwtUtils.class);

    @Value("${jwt.secret}")
    private String jwtSecretString; // This should be a Base64 encoded string or a very strong raw string

    @Value("${jwt.expiration.ms}")
    private int jwtExpirationMs;

    /**
     * How long one passkey sign-in lasts, however often its token is refreshed. 12 hours by default.
     * After that the user signs in again with their passkey.
     */
    @Value("${jwt.session.max.ms:43200000}")
    private long sessionMaxMs;

    private SecretKey key;

    @PostConstruct
    public void init() {
        // CRITICAL LOGGING FOR DEBUGGING:
        logger.info("============================================================");
        logger.info("JwtUtils jwt.expiration.ms: {}", jwtExpirationMs);
        logger.info("JwtUtils jwt.session.max.ms: {}", sessionMaxMs);
        if (sessionMaxMs < jwtExpirationMs) {
            logger.warn("jwt.session.max.ms ({}) is shorter than jwt.expiration.ms ({}): every token will last "
                    + "only as long as the session.", sessionMaxMs, jwtExpirationMs);
        }
        logger.info("============================================================");

        byte[] keyBytes;
        try {
            keyBytes = jwtSecretString.getBytes("UTF-8");
        } catch (Exception e) {
            logger.error("Error encoding JWT secret to UTF-8 bytes: {}", e.getMessage());
            keyBytes = "FallbackDefaultDevSecretKeyMustBeLongEnough32BytesPlus".getBytes();
            logger.warn("Using a default fallback JWT secret due to encoding error. THIS IS NOT SECURE.");
        }

        if (keyBytes.length < 32) { // HS256 requires at least 256 bits (32 bytes)
            logger.warn("WARNING: JWT secret key (after UTF-8 encoding) is shorter than 32 bytes ({} bytes). " +
                        "This is NOT SECURE for HS256. Please provide a stronger key in your properties.", keyBytes.length);
            // Consider throwing an error or using a securely generated default for dev if this happens
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        logger.info("JwtUtils SecretKey initialized successfully.");
    }

    /** Claim holding the session a token belongs to. */
    public static final String SESSION_ID_CLAIM = "sessionId";
    /** Claim holding the moment the session ends for good, in seconds since the epoch. */
    public static final String SESSION_END_CLAIM = "sx";

    /**
     * The one place a token is built.
     *
     * <p>Every token belongs to a session and carries the moment that session ends ("sx"). The
     * session end is fixed when the user signs in with their passkey and is copied, never moved,
     * each time the token is refreshed. A token never outlives its session: its own expiry is the
     * usual lifetime or the session end, whichever comes first.
     */
    private String buildToken(String subject, long sessionId, Date sessionEnd, String nextStep, String publicId) {
        String jwtId = UUID.randomUUID().toString();
        Date issuedAt = new Date();
        Date expiration = new Date(Math.min(issuedAt.getTime() + jwtExpirationMs, sessionEnd.getTime()));

        JwtBuilder builder = Jwts.builder()
                .setSubject(subject)
                .setId(jwtId)
                .setIssuedAt(issuedAt)
                .setExpiration(expiration)
                .setIssuer("FRED")
                .claim(SESSION_ID_CLAIM, sessionId)
                .claim(SESSION_END_CLAIM, sessionEnd.getTime() / 1000);

        if (nextStep != null && !nextStep.isEmpty()) {
            builder.claim("ns", nextStep);
        }
        if (publicId != null && !publicId.isEmpty()) {
            builder.claim("pid", publicId);
        }

        return builder.signWith(key, SignatureAlgorithm.HS256).compact();
    }

    /**
     * The first token of a new sign-in. Call this only after the user has proved who they are
     * (passkey sign-in or sign-up), with the session that sign-in started. The session clock
     * starts now and runs for {@code jwt.session.max.ms}.
     *
     * @param sessionId the session row this sign-in created; required, because a token with no
     *                  session could never be revoked
     * @param nextStep  optional "ns" next-step hint; null to omit
     * @param publicId  opaque analytics identity ("pid" claim); null to omit
     */
    public String startSessionToken(String username, Long sessionId, String nextStep, String publicId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("A token must belong to a session");
        }
        Date sessionEnd = new Date(System.currentTimeMillis() + sessionMaxMs);
        logger.info("Starting session {} (ns={}), good until {}", sessionId, nextStep, sessionEnd.toInstant());
        return buildToken(username, sessionId, sessionEnd, nextStep, publicId);
    }

    /** Same, taking the user from an Authentication (its principal supplies the name and public id). */
    public String generateJwtToken(Authentication authentication, Long sessionId, String nextStep) {
        UserDetails userPrincipal = (UserDetails) authentication.getPrincipal();
        return startSessionToken(userPrincipal.getUsername(), sessionId, nextStep, publicIdOf(userPrincipal));
    }

    /**
     * A new token for a session that is already running: token refresh, or an email change that
     * renames the token's subject. The session id and the session end are copied from the current
     * token unchanged, so refreshing never buys a session more time.
     *
     * @return the new token, or empty when the current token has no session, has no session end
     *         (issued before sessions had one), or its session has already ended. The user then
     *         signs in again with their passkey.
     */
    public java.util.Optional<String> continueSessionToken(String currentToken, String username,
            String nextStep, String publicId) {
        Claims claims;
        try {
            claims = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(currentToken).getBody();
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
        Long sessionId = claims.get(SESSION_ID_CLAIM, Long.class);
        Date sessionEnd = sessionEndOf(claims);
        if (sessionId == null || sessionEnd == null || !new Date().before(sessionEnd)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(buildToken(username, sessionId, sessionEnd, nextStep, publicId));
    }

    /**
     * A token for the same session under a new name, for an email change (the email is the
     * token's subject). Never lasts longer than the token it replaces would have:
     * a token with a session end is continued as usual; a token from before sessions had an end
     * gets a replacement that expires exactly when the old one would, still with no session end,
     * so it still cannot be refreshed.
     *
     * @return the new token, or empty when the current token has no session or its session has ended
     */
    public java.util.Optional<String> renameSessionToken(String currentToken, String newUsername,
            String nextStep, String publicId) {
        Claims claims;
        try {
            claims = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(currentToken).getBody();
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
        Long sessionId = claims.get(SESSION_ID_CLAIM, Long.class);
        if (sessionId == null) {
            return java.util.Optional.empty();
        }
        if (sessionEndOf(claims) != null) {
            return continueSessionToken(currentToken, newUsername, nextStep, publicId);
        }
        JwtBuilder builder = Jwts.builder()
                .setSubject(newUsername)
                .setId(UUID.randomUUID().toString())
                .setIssuedAt(new Date())
                .setExpiration(claims.getExpiration())
                .setIssuer("FRED")
                .claim(SESSION_ID_CLAIM, sessionId);
        if (nextStep != null && !nextStep.isEmpty()) {
            builder.claim("ns", nextStep);
        }
        if (publicId != null && !publicId.isEmpty()) {
            builder.claim("pid", publicId);
        }
        return java.util.Optional.of(builder.signWith(key, SignatureAlgorithm.HS256).compact());
    }

    /** When the token's session ends, or null when the token carries no session end or cannot be read. */
    public Date getSessionEndFromJwtToken(String token) {
        try {
            return sessionEndOf(Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody());
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the token says its session is over. A token with no session end is not ended by this rule. */
    public boolean sessionHasEnded(String token) {
        Date sessionEnd = getSessionEndFromJwtToken(token);
        return sessionEnd != null && !new Date().before(sessionEnd);
    }

    private static Date sessionEndOf(Claims claims) {
        Number seconds = claims.get(SESSION_END_CLAIM, Number.class);
        return seconds == null ? null : new Date(seconds.longValue() * 1000);
    }

    public Long getSessionIdFromJwtToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
            return claims.get(SESSION_ID_CLAIM, Long.class);
        } catch (Exception e) {
            logger.error("Could not extract sessionId from token: {}", e.getMessage());
            return null;
        }
    }

    /** Opaque analytics identity, or null on an older token issued before the claim existed. */
    public String getPublicIdFromJwtToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
            return claims.get("pid", String.class);
        } catch (Exception e) {
            logger.error("Could not extract pid from token: {}", e.getMessage());
            return null;
        }
    }

    private String publicIdOf(UserDetails userPrincipal) {
        return (userPrincipal instanceof UserDetailsImpl impl) ? impl.getPublicId() : null;
    }

    public String getUserNameFromJwtToken(String token) {
        return Jwts.parserBuilder()      // Use parserBuilder()
                .setSigningKey(key)      // Use setSigningKey(Key)
                .build()
                .parseClaimsJws(token)   // Use parseClaimsJws for signed tokens
                .getBody()               // Use getBody() to get Claims
                .getSubject();
    }
    
    /**
     * Extract the unique JWT ID from a token
     */
    public String getJwtIdFromToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getId();
    }
    
    /**
     * Extract the issued-at timestamp from a token
     */
    public Date getIssuedAtFromToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getIssuedAt();
    }
    
    /**
     * Extract the expiration timestamp from a token
     */
    public Date getExpirationFromToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody()
                .getExpiration();
    }

    public boolean validateJwtToken(String authToken) {
        try {
            Jwts.parserBuilder()
                .setSigningKey(this.key) // Ensure it's using this.key
                .build()
                .parseClaimsJws(authToken);
            return true;
        } catch (SignatureException e) {
            logger.error("JwtUtils validateJwtToken - Invalid JWT signature: {} for token: {}", e.getMessage(), authToken);
        } catch (MalformedJwtException e) {
            logger.error("JwtUtils validateJwtToken - Invalid JWT token: {} for token: {}", e.getMessage(), authToken);
        } catch (ExpiredJwtException e) {
            logger.error("JwtUtils validateJwtToken - JWT token is expired: {} for token: {}", e.getMessage(), authToken);
        } catch (UnsupportedJwtException e) {
            logger.error("JwtUtils validateJwtToken - JWT token is unsupported: {} for token: {}", e.getMessage(), authToken);
        } catch (IllegalArgumentException e) {
            logger.error("JwtUtils validateJwtToken - JWT claims string is empty or invalid: {} for token: {}", e.getMessage(), authToken);
        }
        return false;
    }

    public String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");

        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }
        return null;
    }

    /**
     * Extract the next-step hint ("ns" claim) from a token.
     * Returns null if the token is invalid or the claim is absent.
     */
    public String getNextStepFromJwtToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
            return claims.get("ns", String.class);
        } catch (Exception e) {
            logger.warn("Could not extract ns claim from token: {}", e.getMessage());
            return null;
        }
    }
}