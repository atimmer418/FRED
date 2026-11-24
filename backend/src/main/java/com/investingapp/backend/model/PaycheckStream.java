package com.investingapp.backend.model;

import com.investingapp.backend.util.EasternTime;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * paycheck_streams (spec 3.3, V6): one inflow stream per user, keyed by FRED on
 * (user_id, plaid_account_id, payer_token) because Plaid's stream_id is not stable.
 *
 * <p>Every V6 column is mapped exactly; PaycheckEntityMappingTest pins that against the migration
 * text. Ids are bare Longs, as in PlaidWebhookEvent: the foreign key (ON DELETE CASCADE to users)
 * lives in the database and nothing here navigates it.
 *
 * <p>payer_display_name, raw_descriptor and the amounts are payroll data: never in a log line at
 * INFO or above, an ops alert, or an analytics property.
 */
@Entity
@Table(name = "paycheck_streams")
@Getter
@Setter
@NoArgsConstructor
public class PaycheckStream {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "plaid_item_id", length = 80)
    private String plaidItemId;

    @Column(name = "plaid_account_id", nullable = false, length = 80)
    private String plaidAccountId;

    @Column(name = "plaid_stream_id", length = 80)
    private String plaidStreamId;

    @Column(name = "payer_token", nullable = false, length = 64)
    private String payerToken;

    @Column(name = "payer_display_name", nullable = false, length = 120)
    private String payerDisplayName;

    /** Nulled when the Item breaks (spec 5.7, 7.9); the stream skeleton survives. */
    @Column(name = "raw_descriptor", length = 255)
    private String rawDescriptor;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 12)
    private PaycheckStreamState state = PaycheckStreamState.CANDIDATE;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 12)
    private PaycheckStreamRole role = PaycheckStreamRole.NONE;

    /** The one mapped use of InvestmentFrequency; SEMI_MONTHLY (12 characters) is the longest name. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cadence", length = 12)
    private InvestmentFrequency cadence;

    @Enumerated(EnumType.STRING)
    @Column(name = "cadence_source", length = 10)
    private PaycheckCadenceSource cadenceSource;

    /** ISO day of week, 1 to 7. V6 tinyint: a bare Short validates as smallint and fails at boot. */
    @Column(name = "anchor_dow", columnDefinition = "TINYINT")
    private Short anchorDow;

    @Column(name = "anchor_parity_date")
    private LocalDate anchorParityDate;

    /** A day number or LAST_BD. */
    @Column(name = "anchor_day_1", length = 8)
    private String anchorDay1;

    @Column(name = "anchor_day_2", length = 8)
    private String anchorDay2;

    /** smallint NOT NULL DEFAULT 0. Callers narrow with (short) Math.min(value, Short.MAX_VALUE). */
    @Column(name = "score", nullable = false)
    private short score = 0;

    @Column(name = "score_breakdown_json", columnDefinition = "TEXT")
    private String scoreBreakdownJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "reject_reason", length = 32)
    private PaycheckRejectReason rejectReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "retired_reason", length = 32)
    private PaycheckRetiredReason retiredReason;

    /** Plaid's strings from /transactions/recurring/get, stored as sent (getValue()). */
    @Column(name = "plaid_status", length = 32)
    private String plaidStatus;

    @Column(name = "plaid_frequency", length = 32)
    private String plaidFrequency;

    @Column(name = "plaid_pfc_detailed", length = 32)
    private String plaidPfcDetailed;

    @Column(name = "plaid_pfc_confidence", length = 32)
    private String plaidPfcConfidence;

    /** Tri-state like users.plaid_transactions_consented: null means Plaid has not said. */
    @Column(name = "plaid_is_active", columnDefinition = "BOOLEAN")
    private Boolean plaidIsActive;

    @Column(name = "plaid_predicted_next_date")
    private LocalDate plaidPredictedNextDate;

    @Column(name = "deposits_found", nullable = false)
    private short depositsFound = 0;

    @Column(name = "first_date")
    private LocalDate firstDate;

    @Column(name = "last_date")
    private LocalDate lastDate;

    @Column(name = "last_amount", precision = 12, scale = 2)
    private BigDecimal lastAmount;

    /** Positive. */
    @Column(name = "trailing_median", precision = 12, scale = 2)
    private BigDecimal trailingMedian;

    @Column(name = "trailing_cv", precision = 6, scale = 4)
    private BigDecimal trailingCv;

    @Column(name = "predicted_next_date")
    private LocalDate predictedNextDate;

    @Column(name = "missed_count", nullable = false)
    private short missedCount = 0;

    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    @Column(name = "plaid_snapshot_json", columnDefinition = "TEXT")
    private String plaidSnapshotJson;

    /** Eastern wall time. Services set it from their nowET; onCreate only fills a forgotten one. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = EasternTime.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = EasternTime.now();
    }
}
