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
import java.util.regex.Pattern;

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

    /** Plaid's own kid values are short alphanumerics; a kid is attacker-chosen on this unauthenticated path. */
    static final Pattern KID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

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
                            if (!KID_PATTERN.matcher(kid).matches()) {
                                throw new PlaidWebhookRejectedException("kid header malformed");
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
