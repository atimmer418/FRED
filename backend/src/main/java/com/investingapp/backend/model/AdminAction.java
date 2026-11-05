package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One call to the prod-registered admin surface (spec 12.2): who (actor, from X-Admin-Actor), on
 * which user, which verb, the state before and after, and why. Written by AdminAuditService for
 * every /api/admin/** call, refusals included.
 *
 * <p>beforeJson and afterJson carry state words and ids only (status, holdReason, detectionState,
 * primaryStreamId and the like): never a Plaid token or cursor, a descriptor, a payer name, an
 * amount or an email. createdAt is Eastern wall time, set by the service.
 */
@Entity
@Table(name = "admin_actions")
@Getter
@Setter
@NoArgsConstructor
public class AdminAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor", nullable = false, length = 80)
    private String actor;

    /**
     * No foreign key: the audit row outlives the user it names. Null for a verb that names no
     * user (the Item listing, the webhook verbs).
     */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "verb", nullable = false, length = 64)
    private String verb;

    /** The verb's own target when it has one: a stream id, a cycle id, a webhook row id. */
    @Column(name = "target_id", length = 80)
    private String targetId;

    @Column(name = "before_json", columnDefinition = "TEXT")
    private String beforeJson;

    @Column(name = "after_json", columnDefinition = "TEXT")
    private String afterJson;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
