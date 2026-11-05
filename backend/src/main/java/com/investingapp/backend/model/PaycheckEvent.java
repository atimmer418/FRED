package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * paycheck_events (spec 3.4, V6): one row per Plaid transaction kept as an inflow candidate,
 * unique on the Plaid id so a replayed sync page is a no-op. Rows older than 24 months are deleted
 * by the reconcile (spec 7.9); every row of a user is purged when the Item breaks (spec 5.7).
 *
 * <p>Descriptor, names and amounts are payroll data: never in a log line at INFO or above, an ops
 * alert, or an analytics property. first_seen_at is Eastern wall time, set by the writer.
 */
@Entity
@Table(name = "paycheck_events")
@Getter
@Setter
@NoArgsConstructor
public class PaycheckEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stream_id")
    private Long streamId;

    @Column(name = "plaid_transaction_id", nullable = false, unique = true, length = 80)
    private String plaidTransactionId;

    /** Nullable in Plaid, never required for matching (spec 3.4). */
    @Column(name = "pending_transaction_id", length = 80)
    private String pendingTransactionId;

    @Column(name = "plaid_account_id", nullable = false, length = 80)
    private String plaidAccountId;

    /** Positive inflow amount. */
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** Plaid's amount unmodified: negative means money in. */
    @Column(name = "raw_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal rawAmount;

    @Column(name = "posted_date", nullable = false)
    private LocalDate postedDate;

    @Column(name = "authorized_date")
    private LocalDate authorizedDate;

    @Column(name = "pending", nullable = false, columnDefinition = "BOOLEAN")
    private boolean pending;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private PaycheckEventStatus status = PaycheckEventStatus.ACTIVE;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "removed_at")
    private LocalDateTime removedAt;

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;

    @Column(name = "raw_descriptor", length = 255)
    private String rawDescriptor;

    @Column(name = "merchant_name", length = 120)
    private String merchantName;

    @Column(name = "counterparty_name", length = 120)
    private String counterpartyName;

    @Column(name = "counterparty_type", length = 32)
    private String counterpartyType;

    @Column(name = "pfc_primary", length = 40)
    private String pfcPrimary;

    @Column(name = "pfc_detailed", length = 40)
    private String pfcDetailed;

    @Column(name = "pfc_confidence", length = 40)
    private String pfcConfidence;

    @Column(name = "payment_channel", length = 12)
    private String paymentChannel;

    @Column(name = "ppd_id", length = 64)
    private String ppdId;
}
