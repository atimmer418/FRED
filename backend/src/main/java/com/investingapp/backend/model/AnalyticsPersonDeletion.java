package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A request to delete a person record at the analytics vendor.
 *
 * Written in the same transaction as the account closure that obliges it, so the promise and the
 * record of the promise commit together. Drained by AnalyticsPublisherService with the same retry
 * and backoff as the event outbox.
 *
 * Only the opaque public_id is stored: by the time this is sent the user row is gone, so there is
 * nothing left to join to and nothing else worth keeping. The row itself survives completion, as
 * the answer to "did you actually delete it".
 */
@Entity
@Table(name = "analytics_person_deletions")
@Data
@NoArgsConstructor
public class AnalyticsPersonDeletion {

    /** Waiting to be sent. */
    public static final String STATUS_PENDING = "PENDING";
    /** The vendor confirmed the deletion. */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** Repeatedly refused. Needs a human, not another retry. */
    public static final String STATUS_PARKED = "PARKED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The distinct id to delete. Unique: asking twice is the same request, not two. */
    @Column(name = "public_id", nullable = false, length = 36, updatable = false)
    private String publicId;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;
}
