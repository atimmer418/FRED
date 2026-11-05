package com.investingapp.backend.plaid;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Five consecutive TRANSIENT or RATE_LIMIT results trip it; any other result resets it (spec 2.1, 11). */
class PlaidOutageBreakerTest {

    @Test
    void tripsAfterFiveConsecutiveTransientOrRateLimit() {
        PlaidOutageBreaker breaker = new PlaidOutageBreaker();

        assertThat(breaker.record(PlaidErrorClass.TRANSIENT)).isFalse();
        assertThat(breaker.record(PlaidErrorClass.RATE_LIMIT)).isFalse();
        assertThat(breaker.record(PlaidErrorClass.TRANSIENT)).isFalse();
        assertThat(breaker.record(PlaidErrorClass.RATE_LIMIT)).isFalse();
        assertThat(breaker.tripped()).isFalse();

        assertThat(breaker.record(PlaidErrorClass.TRANSIENT)).as("the fifth trips").isTrue();
        assertThat(breaker.tripped()).isTrue();

        assertThat(breaker.record(PlaidErrorClass.TRANSIENT)).as("only the fifth reports the trip").isFalse();
        assertThat(breaker.tripped()).isTrue();
        assertThat(PlaidOutageBreaker.THRESHOLD).isEqualTo(5);
    }

    @Test
    void anyOtherResultResetsTheCount() {
        List<PlaidErrorClass> others = Arrays.asList(PlaidErrorClass.CONSENT, PlaidErrorClass.ITEM_BROKEN,
                PlaidErrorClass.NOT_SUPPORTED, PlaidErrorClass.SYNC_LIMIT, PlaidErrorClass.MUTATION,
                PlaidErrorClass.PERMANENT, null);
        for (PlaidErrorClass other : others) {
            PlaidOutageBreaker breaker = new PlaidOutageBreaker();
            for (int i = 0; i < 4; i++) {
                breaker.record(PlaidErrorClass.TRANSIENT);
            }
            assertThat(breaker.record(other)).as(String.valueOf(other)).isFalse();
            for (int i = 0; i < 4; i++) {
                assertThat(breaker.record(PlaidErrorClass.RATE_LIMIT)).as(String.valueOf(other)).isFalse();
            }
            assertThat(breaker.tripped()).as(other + " resets, so 4 + 4 never trips").isFalse();
        }
    }

    @Test
    void resetClearsATrippedBreaker() {
        PlaidOutageBreaker breaker = new PlaidOutageBreaker();
        for (int i = 0; i < 5; i++) {
            breaker.record(PlaidErrorClass.TRANSIENT);
        }
        assertThat(breaker.tripped()).isTrue();

        breaker.reset();

        assertThat(breaker.tripped()).isFalse();
        for (int i = 0; i < 4; i++) {
            assertThat(breaker.record(PlaidErrorClass.TRANSIENT)).isFalse();
        }
        assertThat(breaker.record(PlaidErrorClass.TRANSIENT)).as("a new run can trip again").isTrue();
    }

    @Test
    void concurrentRecordsTripExactlyOnce() throws Exception {
        PlaidOutageBreaker breaker = new PlaidOutageBreaker();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger trips = new AtomicInteger();
        List<Future<Object>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 10; i++) {
                    if (breaker.record(PlaidErrorClass.TRANSIENT)) {
                        trips.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<Object> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(trips.get()).isEqualTo(1);
        assertThat(breaker.tripped()).isTrue();
    }
}
