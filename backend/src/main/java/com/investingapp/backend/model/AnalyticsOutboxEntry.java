package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One server-side analytics event, written in the same transaction as the state change it
 * describes and published afterwards by AnalyticsPublisherService.
 *
 * Rows are never deleted. Checkpoint G's metrics read this table directly, so it stays the source
 * of truth for the money and compliance funnel regardless of what PostHog does with its copy.
 *
 * public_id is denormalised rather than joined: a rejected KYC deletes the user row, and the
 * ledger must still be able to describe what happened. For the same reason there is no foreign
 * key to users.
 */
@Entity
@Table(name = "analytics_outbox")
@Data
@NoArgsConstructor
public class AnalyticsOutboxEntry {

    /** Waiting to be published. */
    public static final String STATUS_PENDING = "PENDING";
    /** Delivered to PostHog. */
    public static final String STATUS_SENT = "SENT";
    /** The user had opted out when this came up for publishing. Kept, never sent. */
    public static final String STATUS_SUPPRESSED = "SUPPRESSED";
    /** Cannot be sent as it stands (no distinct id). Needs a human, not another retry. */
    public static final String STATUS_PARKED = "PARKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The uuid sent to PostHog as this event's own id, assigned when the row is written and never
     * changed. A retry after a timeout that actually succeeded is de-duplicated by PostHog on this
     * value rather than counted twice by us.
     */
    @Column(name = "event_id", nullable = false, length = 36, updatable = false)
    private String eventId;

    @Column(name = "event_name", nullable = false, length = 64, updatable = false)
    private String eventName;

    /** The distinct id. Nullable only because users.public_id is still nullable until F. */
    @Column(name = "public_id", length = 36)
    private String publicId;

    @Column(name = "user_id")
    private Long userId;

    /** The event properties, as JSON. Hand-serialised, matching the house pattern. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String properties;

    /** The $set person properties, as JSON. Null when there are none to set. */
    @Column(name = "person_properties", columnDefinition = "TEXT")
    private String personProperties;

    /** When the state change happened, not when it was published. */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(nullable = false)
    private int attempts = 0;

    /** Backoff. Null means eligible now. */
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /**
     * Consent as it stood when this row was written. The publisher checks the LIVE user, which is
     * the promise; this is the fallback for a user who no longer exists, which happens on exactly
     * one path: a KYC rejection hard-deletes the user in the same transaction that records the
     * decision.
     */
    @Column(name = "consent_opt_out_at_write", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private boolean consentOptOutAtWrite = false;
}
