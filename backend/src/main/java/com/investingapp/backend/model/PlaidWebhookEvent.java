package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** One row per Plaid webhook delivery, written by the verification endpoint (spec 3.6). */
@Entity
@Table(name = "plaid_webhook_events")
@Getter
@Setter
@NoArgsConstructor
public class PlaidWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dedupe_key", nullable = false, unique = true, columnDefinition = "CHAR(64)")
    private String dedupeKey;

    @Column(name = "webhook_type", nullable = false, length = 40)
    private String webhookType;

    @Column(name = "webhook_code", nullable = false, length = 40)
    private String webhookCode;

    @Column(name = "plaid_item_id", length = 80)
    private String plaidItemId;

    /** No foreign key: the inbox row outlives the user (spec 3.6). */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "environment", nullable = false, length = 12)
    private String environment;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private PlaidWebhookEventStatus status;

    @Column(name = "attempts", nullable = false)
    private short attempts = 0;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "jwt_iat", nullable = false)
    private long jwtIat;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;
}
