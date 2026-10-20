package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.AnalyticsOutboxEntry;
import com.investingapp.backend.repository.AnalyticsOutboxRepository;
import com.investingapp.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Drains the analytics outbox into PostHog.
 *
 * Everything here is designed so that the worst case is a LATE event, never a wrong one:
 *
 * - Each row carries its own uuid, assigned when it was written. PostHog de-duplicates on it, so
 *   a retry after a timeout that actually succeeded is not counted twice.
 * - Rows are never deleted and never edited in place beyond their status, so Checkpoint G can read
 *   the same table and get the same answer PostHog has.
 * - Consent is checked against the live user immediately before sending. An opted-out user's row
 *   is kept, marked SUPPRESSED, and never sent.
 * - A failure backs off exponentially rather than hammering, and a row that cannot be sent as it
 *   stands is PARKED for a human instead of retried forever.
 */
@Service
public class AnalyticsPublisherService {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsPublisherService.class);

    /** After this many failures a row stops being retried and waits for a human. */
    static final int MAX_ATTEMPTS = 10;
    /** Backoff is 2^attempts minutes, capped so a long outage still recovers promptly. */
    static final int MAX_BACKOFF_MINUTES = 60;

    private final AnalyticsOutboxRepository outboxRepository;
    private final UserRepository userRepository;
    private final AnalyticsTransport transport;
    private final ObjectMapper objectMapper;

    private final boolean enabled;
    private final int batchSize;

    public AnalyticsPublisherService(AnalyticsOutboxRepository outboxRepository,
                                     UserRepository userRepository,
                                     AnalyticsTransport transport,
                                     ObjectMapper objectMapper,
                                     @Value("${app.analytics.publisher.enabled:false}") boolean enabled,
                                     @Value("${app.analytics.publisher.batch-size:100}") int batchSize) {
        this.outboxRepository = outboxRepository;
        this.userRepository = userRepository;
        this.transport = transport;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    /**
     * One pass. Returns how many rows were sent, which is what the scheduler logs and what the
     * tests assert on.
     */
    public int publishPending() {
        if (!enabled) {
            return 0;
        }
        List<AnalyticsOutboxEntry> batch = outboxRepository.findPublishable(
                AnalyticsOutboxEntry.STATUS_PENDING, LocalDateTime.now(), PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return 0;
        }

        Map<Long, Boolean> optOutByUserId = loadConsent(batch);

        List<AnalyticsOutboxEntry> sendable = new ArrayList<>();
        List<AnalyticsOutboxEntry> suppressed = new ArrayList<>();
        List<AnalyticsOutboxEntry> parked = new ArrayList<>();

        for (AnalyticsOutboxEntry entry : batch) {
            if (entry.getPublicId() == null || entry.getPublicId().isBlank()) {
                // No distinct id to send it under. Sending null would create a junk person.
                parked.add(entry);
            } else if (isOptedOut(entry, optOutByUserId)) {
                suppressed.add(entry);
            } else {
                sendable.add(entry);
            }
        }

        markSuppressed(suppressed);
        markParked(parked);

        if (sendable.isEmpty()) {
            return 0;
        }

        try {
            transport.send(sendable.stream().map(this::toPayload).collect(Collectors.toList()));
            markSent(sendable);
            return sendable.size();
        } catch (Exception e) {
            // The whole batch failed together, so the whole batch backs off together. Each row
            // keeps its uuid, so the resend after the outage is de-duplicated, not doubled.
            logger.warn("analytics: batch of {} failed to publish; backing off", sendable.size(), e);
            markFailed(sendable, e);
            return 0;
        }
    }

    /**
     * Consent, read live, keyed by user id. A user who no longer exists is absent from the map and
     * falls back to the snapshot on the row: a KYC rejection deletes the user in the same
     * transaction that records the decision, so there is nothing left to ask.
     */
    private Map<Long, Boolean> loadConsent(List<AnalyticsOutboxEntry> batch) {
        Set<Long> userIds = batch.stream()
                .map(AnalyticsOutboxEntry::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Boolean> byId = new LinkedHashMap<>();
        userRepository.findAllById(userIds).forEach(user ->
                byId.put(user.getId(), Boolean.TRUE.equals(user.getAnalyticsOptOut())));
        return byId;
    }

    private boolean isOptedOut(AnalyticsOutboxEntry entry, Map<Long, Boolean> optOutByUserId) {
        Long userId = entry.getUserId();
        if (userId != null && optOutByUserId.containsKey(userId)) {
            return optOutByUserId.get(userId);
        }
        return entry.isConsentOptOutAtWrite();
    }

    /** The PostHog batch shape. distinct_id is the opaque public_id and nothing else ever is. */
    Map<String, Object> toPayload(AnalyticsOutboxEntry entry) {
        Map<String, Object> properties = new LinkedHashMap<>(readJson(entry.getProperties()));
        properties.put("distinct_id", entry.getPublicId());

        Map<String, Object> personProperties = readJson(entry.getPersonProperties());
        if (!personProperties.isEmpty()) {
            properties.put("$set", personProperties);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", entry.getEventName());
        payload.put("distinct_id", entry.getPublicId());
        // The row's own uuid. This is what makes a resend idempotent.
        payload.put("uuid", entry.getEventId());
        payload.put("timestamp", entry.getOccurredAt().atOffset(ZoneOffset.UTC).toString());
        payload.put("properties", properties);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            logger.error("analytics: unreadable properties json; sending the event without them", e);
            return Map.of();
        }
    }

    /**
     * Status updates run in Spring Data's own transaction around saveAll. They are deliberately
     * NOT annotated: they are called from publishPending() on `this`, so a @Transactional here
     * would be bypassed by self-invocation and would read as a guarantee that does not exist.
     */
    void markSent(List<AnalyticsOutboxEntry> entries) {
        LocalDateTime now = LocalDateTime.now();
        for (AnalyticsOutboxEntry entry : entries) {
            entry.setStatus(AnalyticsOutboxEntry.STATUS_SENT);
            entry.setSentAt(now);
            entry.setAttempts(entry.getAttempts() + 1);
            entry.setNextAttemptAt(null);
            entry.setLastError(null);
        }
        outboxRepository.saveAll(entries);
    }

    void markSuppressed(List<AnalyticsOutboxEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        for (AnalyticsOutboxEntry entry : entries) {
            entry.setStatus(AnalyticsOutboxEntry.STATUS_SUPPRESSED);
            entry.setNextAttemptAt(null);
        }
        outboxRepository.saveAll(entries);
        logger.info("analytics: suppressed {} row(s) for opted-out users", entries.size());
    }

    void markParked(List<AnalyticsOutboxEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        for (AnalyticsOutboxEntry entry : entries) {
            entry.setStatus(AnalyticsOutboxEntry.STATUS_PARKED);
            entry.setLastError("no public_id to use as the distinct id");
            entry.setNextAttemptAt(null);
        }
        outboxRepository.saveAll(entries);
        logger.error("analytics: parked {} row(s) with no public_id", entries.size());
    }

    void markFailed(List<AnalyticsOutboxEntry> entries, Exception cause) {
        LocalDateTime now = LocalDateTime.now();
        for (AnalyticsOutboxEntry entry : entries) {
            int attempts = entry.getAttempts() + 1;
            entry.setAttempts(attempts);
            entry.setLastError(describe(cause));
            if (attempts >= MAX_ATTEMPTS) {
                entry.setStatus(AnalyticsOutboxEntry.STATUS_PARKED);
                entry.setNextAttemptAt(null);
            } else {
                entry.setNextAttemptAt(now.plusMinutes(backoffMinutes(attempts)));
            }
        }
        outboxRepository.saveAll(entries);
    }

    /** 2, 4, 8 ... minutes, capped. Exposed for the test that pins the curve. */
    static long backoffMinutes(int attempts) {
        long minutes = 1L << Math.min(attempts, 20);
        return Math.min(minutes, MAX_BACKOFF_MINUTES);
    }

    /** The class and message, truncated. Never a response body, which could echo a payload. */
    private String describe(Exception e) {
        String described = e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return described.length() <= 500 ? described : described.substring(0, 500);
    }
}
