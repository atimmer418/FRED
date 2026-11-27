package com.investingapp.backend.security.ratelimit;

/** A limit was reached. {@link TooManyRequestsAdvice} turns it into a 429. */
public class TooManyRequestsException extends RuntimeException {

    private final RateLimiter.Rule rule;

    public TooManyRequestsException(RateLimiter.Rule rule) {
        super("Rate limit reached: " + rule);
        this.rule = rule;
    }

    public RateLimiter.Rule rule() {
        return rule;
    }
}
