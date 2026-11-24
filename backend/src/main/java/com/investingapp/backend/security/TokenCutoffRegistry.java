package com.investingapp.backend.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * "Every token this user was given up to now is dead."
 *
 * Ending a user's sessions in the database only reaches tokens that carry a session id. Tokens from
 * registration, and tokens refreshed before refresh kept the session id, carry none. This registry
 * covers those: account recovery records the moment it happened, and AuthTokenFilter refuses any
 * token for that user issued at or before that moment.
 *
 * Kept in memory, like the passkey challenges, so it assumes a single backend instance and is lost
 * on a restart. An entry only has to outlive the longest-lived token issued before it, and a refused
 * token cannot be refreshed, so entries are dropped a little after one token lifetime.
 */
@Component
public class TokenCutoffRegistry {

    private final Cache<Long, Long> cutoffSecondByUser;

    public TokenCutoffRegistry(@Value("${jwt.expiration.ms}") long tokenLifetimeMs) {
        this.cutoffSecondByUser = Caffeine.newBuilder()
                .expireAfterWrite(tokenLifetimeMs + TimeUnit.MINUTES.toMillis(5), TimeUnit.MILLISECONDS)
                .build();
    }

    /** Ends every token the user holds that was issued up to and including this moment. */
    public void endTokensIssuedUpTo(Long userId, Instant moment) {
        if (userId == null || moment == null) {
            return;
        }
        // A later cutoff never moves back to an earlier one.
        cutoffSecondByUser.asMap().merge(userId, moment.getEpochSecond(), Math::max);
    }

    /**
     * True when the token was issued at or before the user's cutoff. Token times are whole seconds,
     * so a token from the same second as the cutoff counts as ended: refusing one extra token is
     * safe, honouring one is not. A token with no issue time is treated as ended when a cutoff exists.
     */
    public boolean isEnded(Long userId, Date issuedAt) {
        if (userId == null) {
            return false;
        }
        Long cutoffSecond = cutoffSecondByUser.getIfPresent(userId);
        if (cutoffSecond == null) {
            return false;
        }
        return issuedAt == null || issuedAt.toInstant().getEpochSecond() <= cutoffSecond;
    }
}
