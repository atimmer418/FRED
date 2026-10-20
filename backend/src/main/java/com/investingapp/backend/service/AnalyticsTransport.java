package com.investingapp.backend.service;

import java.util.List;
import java.util.Map;

/**
 * How a batch of events leaves this server.
 *
 * An interface so the publisher's behaviour (consent, retry, backoff, idempotent resend) can be
 * tested without a network, and so swapping the vendor is one new implementation rather than a
 * rewrite of the publisher.
 */
public interface AnalyticsTransport {

    /**
     * Sends a batch. Must throw on anything that is not a confirmed success, because the publisher
     * treats a return as "delivered" and will not try again.
     */
    void send(List<Map<String, Object>> events) throws Exception;
}
