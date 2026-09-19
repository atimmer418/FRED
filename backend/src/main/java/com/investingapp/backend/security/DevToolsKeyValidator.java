package com.investingapp.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Compares a caller-supplied dev-tools key against the configured one.
 *
 * <p>Shared by {@link DevToolsKeyFilter} (which covers {@code /api/dev/**}) and the simulated
 * passkey registration path, so both dev tools are behind the same second lock. An unset configured
 * key never matches, which keeps the failure mode closed.
 */
public class DevToolsKeyValidator {

    /** Header carrying the key on both dev-tools surfaces. */
    public static final String HEADER = "X-Dev-Tools-Key";

    private final String expectedKey;

    public DevToolsKeyValidator(String expectedKey) {
        this.expectedKey = expectedKey;
    }

    public boolean isConfigured() {
        return expectedKey != null && !expectedKey.isBlank();
    }

    /** Constant-time comparison. */
    public boolean matches(String provided) {
        if (!isConfigured() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expectedKey.getBytes(StandardCharsets.UTF_8));
    }
}
