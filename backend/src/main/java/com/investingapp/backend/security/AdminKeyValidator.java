package com.investingapp.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Compares a caller's admin key with app.admin-tools.key (spec 12.2); never shares the dev-tools secret; unset never matches. */
public class AdminKeyValidator {

    public static final String KEY_HEADER = "X-Admin-Key";

    /** Names the person behind the call; it becomes admin_actions.actor. */
    public static final String ACTOR_HEADER = "X-Admin-Actor";

    private final String expectedKey;

    public AdminKeyValidator(String expectedKey) {
        this.expectedKey = expectedKey;
    }

    public boolean isConfigured() {
        return expectedKey != null && !expectedKey.isBlank();
    }

    /** Constant-time comparison; false when no key is configured or none was sent. */
    public boolean matches(String provided) {
        if (!isConfigured() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                expectedKey.getBytes(StandardCharsets.UTF_8));
    }
}
