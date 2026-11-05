package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row of the ops alert ledger (spec 12.3), written only by OpsAlertService.raise. The row is
 * the dedupe key: one per (alertType, subjectId, alertDay), where alertDay is the Eastern calendar
 * day. A repeat on the same day bumps occurrences and lastRaisedAt and sends nothing.
 *
 * <p>message and contextJson carry ids, counts, error codes and closed-vocabulary words only:
 * never an amount, a payer name or descriptor, a Plaid token or cursor, an email, or a free-text
 * error body. The ledger is read by people and forwarded to a third-party receiver.
 *
 * <p>occurrences and deliveryStatus are initialised here because Hibernate binds every mapped
 * column on insert, so the V10 DEFAULTs never apply to a row the application writes.
 * firstRaisedAt and lastRaisedAt are Eastern wall time, set by the service.
 */
@Entity
@Table(name = "ops_alerts")
@Getter
@Setter
@NoArgsConstructor
public class OpsAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** An OpsAlerts constant, for example the Plaid outage alert. */
    @Column(name = "alert_type", nullable = false, length = 64)
    private String alertType;

    /** A user id, an Item id, a job name or "global". Not a foreign key: alerts outlive users. */
    @Column(name = "subject_id", nullable = false, length = 80)
    private String subjectId;

    /** The Eastern calendar day of the first raise; the third part of the dedupe key. */
    @Column(name = "alert_day", nullable = false)
    private LocalDate alertDay;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "context_json", columnDefinition = "TEXT")
    private String contextJson;

    @Column(name = "occurrences", nullable = false)
    private int occurrences = 1;

    @Column(name = "first_raised_at", nullable = false)
    private LocalDateTime firstRaisedAt;

    @Column(name = "last_raised_at", nullable = false)
    private LocalDateTime lastRaisedAt;

    /** PENDING until the delivery attempt records SENT, FAILED or LOGGED. */
    @Column(name = "delivery_status", length = 12, nullable = false)
    private String deliveryStatus = "PENDING";
}
