package com.investingapp.backend.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts tries per key inside a fixed window and says no after the limit.
 *
 * The window for a key starts at its first try and ends a fixed time later, however many tries
 * follow. Kept in memory: it assumes a single backend instance and starts empty after a restart.
 * maxKeys bounds memory; when it is reached the least recently used keys are forgotten, so use a
 * separate limiter for keys an outsider can invent freely.
 */
public final class AttemptLimiter<K> {

    private final int limit;
    private final Cache<K, AtomicInteger> counts;

    public AttemptLimiter(int limit, Duration window, long maxKeys, Ticker ticker) {
        this.limit = limit;
        this.counts = Caffeine.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(window)
                .maximumSize(maxKeys)
                .build();
    }

    /** True once the key has used its whole limit in this window. Does not count as a try. */
    public boolean usedUp(K key) {
        AtomicInteger count = counts.getIfPresent(key);
        return count != null && count.get() >= limit;
    }

    /** Counts one try. False once the key has had more than the limit in this window. */
    public boolean allow(K key) {
        return counts.get(key, k -> new AtomicInteger()).incrementAndGet() <= limit;
    }
}
