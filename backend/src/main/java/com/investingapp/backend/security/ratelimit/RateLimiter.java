package com.investingapp.backend.security.ratelimit;

import com.github.benmanes.caffeine.cache.Ticker;
import com.investingapp.backend.service.AttemptLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The request limits outside account recovery, email change and PIN (those keep their own).
 *
 * Each {@link Rule} is "this many tries per key inside this window". The key is whatever the rule
 * is about: a network address, an email, or a user id. The numbers come from
 * {@code app.rate-limit.*} in application.properties and are the same in every environment.
 *
 * Counts live in memory, like the recovery limits: they assume one backend instance and start
 * again from zero after a restart.
 */
@Component
public class RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);

    /** Keys an outsider can invent (addresses, emails) are capped so they cannot fill memory. */
    private static final long MAX_KEYS = 200_000;

    public enum Rule {
        SIGN_UP_PER_ADDRESS("sign-up.per-address", 30, Duration.ofMinutes(10)),
        SIGN_UP_PER_EMAIL("sign-up.per-email-per-hour", 10, Duration.ofHours(1)),
        SIGN_IN_PER_ADDRESS("sign-in.per-address", 120, Duration.ofMinutes(10)),
        LOGIN_PER_ADDRESS("login.per-address", 10, Duration.ofMinutes(10)),
        LOGIN_PER_EMAIL("login.per-email-per-hour", 10, Duration.ofHours(1)),
        REFRESH_PER_ADDRESS("refresh.per-address", 300, Duration.ofMinutes(10)),
        REAUTH_CHECK_PER_ADDRESS("reauth-check.per-address", 120, Duration.ofMinutes(10)),
        CHAT_MESSAGES_PER_MINUTE("chat.messages-per-minute", 12, Duration.ofMinutes(1)),
        CHAT_MESSAGES_PER_DAY("chat.messages-per-day", 300, Duration.ofDays(1)),
        CHAT_READS_PER_MINUTE("chat.reads-per-minute", 120, Duration.ofMinutes(1));

        private final String property;
        private final int defaultLimit;
        private final Duration window;

        Rule(String property, int defaultLimit, Duration window) {
            this.property = property;
            this.defaultLimit = defaultLimit;
            this.window = window;
        }

        public Duration window() {
            return window;
        }

        /** The setting that holds this rule's limit: app.rate-limit.&lt;property&gt;. */
        public String property() {
            return "app.rate-limit." + property;
        }
    }

    private final boolean enabled;
    private final Map<Rule, AttemptLimiter<String>> limiters = new EnumMap<>(Rule.class);

    @Autowired
    public RateLimiter(Environment environment) {
        this(environment, Ticker.systemTicker());
    }

    RateLimiter(Environment environment, Ticker ticker) {
        this.enabled = environment.getProperty("app.rate-limit.enabled", Boolean.class, true);
        for (Rule rule : Rule.values()) {
            int limit = environment.getProperty(rule.property(), Integer.class, rule.defaultLimit);
            limiters.put(rule, new AttemptLimiter<>(limit, rule.window, MAX_KEYS, ticker));
        }
        if (!enabled) {
            logger.warn("Request rate limits are OFF (app.rate-limit.enabled=false).");
        }
    }

    /**
     * Counts one try for this key. False when the key has already had its limit in this window.
     * A blank key is counted under one shared key, so leaving the value out is not a way round.
     */
    public boolean allow(Rule rule, String key) {
        if (!enabled) {
            return true;
        }
        String normal = key == null || key.isBlank() ? "(none)" : key.trim().toLowerCase(Locale.ROOT);
        if (normal.length() > 320) {
            normal = normal.substring(0, 320);
        }
        boolean allowed = limiters.get(rule).allow(normal);
        if (!allowed) {
            // The rule only. The key can be an email address.
            logger.warn("Rate limit reached: {}", rule);
        }
        return allowed;
    }

    /** Thrown by a controller when a per-target rule says no. Becomes a 429 with Retry-After. */
    public void require(Rule rule, String key) {
        if (!allow(rule, key)) {
            throw new TooManyRequestsException(rule);
        }
    }
}
