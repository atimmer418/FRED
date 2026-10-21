package com.investingapp.backend.service;

/**
 * Deletes a person record at the analytics vendor.
 *
 * An interface so the publisher's retry, backoff and parking can be tested without a network, and
 * so the obligation survives a change of vendor as one new implementation.
 */
public interface AnalyticsPersonGateway {

    /**
     * Deletes the person with this distinct id, and everything attached to it.
     *
     * Must return normally ONLY on a confirmed deletion, and must treat "no such person" as a
     * success: a person who never reached the vendor is already in the state we are asking for,
     * and retrying forever over it would bury the requests that matter.
     *
     * @throws Exception on anything else, which the publisher treats as "try again later"
     */
    void deletePerson(String publicId) throws Exception;
}
