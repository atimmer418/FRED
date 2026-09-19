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

    private SecretKey key;

    @PostConstruct
    public void init() {
        // CRITICAL LOGGING FOR DEBUGGING:
        logger.info("============================================================");
        logger.info("JwtUtils jwt.expiration.ms: {}", jwtExpirationMs);
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

    /**
     * Core token builder. All generate* methods funnel through here so the
     * ns (next-step) claim is applied consistently.
     *
     * @param subject   JWT subject (user email)
     * @param sessionId optional session ID claim; null to omit
     * @param nextStep  optional "ns" next-step hint; null to omit
     */
    private String buildToken(String subject, Long sessionId, String nextStep) {
        return buildToken(subject, sessionId, nextStep, null);
    }

    /**
     * @param publicId opaque analytics identity ("pid" claim); null to omit. The client reads it on
     *                 cold start so it can identify the user without an extra round trip.
     */
    private String buildToken(String subject, Long sessionId, String nextStep, String publicId) {
        String jwtId = UUID.randomUUID().toString();
        Date issuedAt = new Date();
        Date expiration = new Date(issuedAt.getTime() + jwtExpirationMs);

        JwtBuilder builder = Jwts.builder()
                .setSubject(subject)
                .setId(jwtId)
                .setIssuedAt(issuedAt)
                .setExpiration(expiration)
                .setIssuer("FRED");

        if (sessionId != null) {
            builder.claim("sessionId", sessionId);
        }
        if (nextStep != null && !nextStep.isEmpty()) {
            builder.claim("ns", nextStep);
        }
        if (publicId != null && !publicId.isEmpty()) {
            builder.claim("pid", publicId);
        }

        return builder.signWith(key, SignatureAlgorithm.HS256).compact();
    }

    public String generateJwtToken(Authentication authentication) {
        UserDetails userPrincipal = (UserDetails) authentication.getPrincipal();

        logger.info("JwtUtils generateJwtToken - Using key object: {}", System.identityHashCode(this.key));
        logger.info("JwtUtils generateJwtToken - for user: {}", userPrincipal.getUsername());

        return buildToken(userPrincipal.getUsername(), null, null, publicIdOf(userPrincipal));
    }

    public String generateJwtToken(Authentication authentication, String nextStep) {
        UserDetails userPrincipal = (UserDetails) authentication.getPrincipal();

        logger.info("JwtUtils generateJwtToken(ns) - for user: {}, ns: {}", userPrincipal.getUsername(), nextStep);

        return buildToken(userPrincipal.getUsername(), null, nextStep, publicIdOf(userPrincipal));
    }

    public String generateJwtToken(Authentication authentication, Long sessionId) {
        UserDetails userPrincipal = (UserDetails) authentication.getPrincipal();

        return buildToken(userPrincipal.getUsername(), sessionId, null, publicIdOf(userPrincipal));
    }

    public String generateJwtToken(Authentication authentication, Long sessionId, String nextStep) {
        UserDetails userPrincipal = (UserDetails) authentication.getPrincipal();

        logger.info("JwtUtils generateJwtToken(sessionId, ns) - for user: {}, ns: {}", userPrincipal.getUsername(), nextStep);

        return buildToken(userPrincipal.getUsername(), sessionId, nextStep, publicIdOf(userPrincipal));
    }

    public Long getSessionIdFromJwtToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
            return claims.get("sessionId", Long.class);
        } catch (Exception e) {
            logger.error("Could not extract sessionId from token: {}", e.getMessage());
            return null;
        }
    }

    public String generateTokenFromUsername(String username) {
        logger.info("JwtUtils generateTokenFromUsername - for user: {}", username);
        return buildToken(username, null, null);
    }

    public String generateTokenFromUsername(String username, String nextStep) {
        logger.info("JwtUtils generateTokenFromUsername(ns) - for user: {}, ns: {}", username, nextStep);
        return buildToken(username, null, nextStep);
    }

    /** Used where there is no Authentication principal to read the public id from (refresh, email change). */
    public String generateTokenFromUsername(String username, String nextStep, String publicId) {
        logger.info("JwtUtils generateTokenFromUsername(ns,pid) - for user: {}, ns: {}", username, nextStep);
        return buildToken(username, null, nextStep, publicId);
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

    public String generateJwtTokenFromUsername(String username) {
        return buildToken(username, null, null);
    }

    /** Used where there is no Authentication principal (account recovery). */
    public String generateJwtTokenFromUsername(String username, String nextStep, String publicId) {
        logger.info("JwtUtils generateJwtTokenFromUsername(ns,pid) - for user: {}, ns: {}", username, nextStep);
        return buildToken(username, null, nextStep, publicId);
    }

    public String generateJwtTokenFromUsername(String username, String nextStep) {
        logger.info("JwtUtils generateJwtTokenFromUsername(ns) - for user: {}, ns: {}", username, nextStep);
        return buildToken(username, null, nextStep);
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