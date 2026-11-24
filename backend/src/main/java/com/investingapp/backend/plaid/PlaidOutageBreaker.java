package com.investingapp.backend.plaid;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts consecutive TRANSIENT or RATE_LIMIT results across every Plaid call in this JVM (spec 2.1:
 * five in a row abort a run and raise plaid.outage). Any other result, a success included, resets
 * the count to 0. {@link PlaidGateway} feeds it and raises the alert on the tripping result; the
 * reconcile (T15) calls {@link #reset()} at the start of each run and stops dispatching while
 * {@link #tripped()} is true.
 */
@Component
public class PlaidOutageBreaker {

    public static final int THRESHOLD = 5;

    private final AtomicInteger consecutive = new AtomicInteger();

    /**
     * @param errorClassOrNullOnSuccess the call's class, or null for a success
     * @return true only for the result that brings the count to {@link #THRESHOLD}, so exactly one
     *         caller raises the alert however many threads record at once
     */
    public boolean record(PlaidErrorClass errorClassOrNullOnSuccess) {
        if (errorClassOrNullOnSuccess == PlaidErrorClass.TRANSIENT || errorClassOrNullOnSuccess == PlaidErrorClass.RATE_LIMIT) {
            return consecutive.updateAndGet(n -> n < Integer.MAX_VALUE ? n + 1 : n) == THRESHOLD;
        }
        consecutive.set(0);
        return false;
    }

    public boolean tripped() {
        return consecutive.get() >= THRESHOLD;
    }

    /** Clears the count and raises nothing. */
    public void reset() {
        consecutive.set(0);
    }
}
