package com.investingapp.backend.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class TokenCutoffRegistryTest {

    private static final Instant MOMENT = Instant.parse("2026-11-23T12:00:00.400Z");

    private final TokenCutoffRegistry registry = new TokenCutoffRegistry(3_600_000);

    private static Date issued(String instant) {
        return Date.from(Instant.parse(instant));
    }

    @Test
    @DisplayName("with no cutoff, nothing is ended")
    void noCutoffNothingEnded() {
        assertThat(registry.isEnded(1L, issued("2020-01-01T00:00:00Z"))).isFalse();
        assertThat(registry.isEnded(null, issued("2020-01-01T00:00:00Z"))).isFalse();
    }

    @Test
    @DisplayName("tokens from before the cutoff, and from the same second, are ended; later ones are not")
    void cutoffEndsEarlierTokens() {
        registry.endTokensIssuedUpTo(1L, MOMENT);

        assertThat(registry.isEnded(1L, issued("2026-11-23T11:59:59Z"))).isTrue();
        assertThat(registry.isEnded(1L, issued("2026-11-23T12:00:00Z"))).isTrue();
        assertThat(registry.isEnded(1L, issued("2026-11-23T12:00:01Z"))).isFalse();
        assertThat(registry.isEnded(1L, null)).as("a token with no issue time").isTrue();
    }

    @Test
    @DisplayName("one user's cutoff does not touch another user")
    void cutoffIsPerUser() {
        registry.endTokensIssuedUpTo(1L, MOMENT);

        assertThat(registry.isEnded(2L, issued("2026-11-23T11:00:00Z"))).isFalse();
    }

    @Test
    @DisplayName("a cutoff never moves back")
    void cutoffNeverMovesBack() {
        registry.endTokensIssuedUpTo(1L, MOMENT);
        registry.endTokensIssuedUpTo(1L, MOMENT.minusSeconds(600));

        assertThat(registry.isEnded(1L, issued("2026-11-23T11:59:00Z"))).isTrue();
    }
}
