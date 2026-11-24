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
 * paycheck_cycles (spec 3.5, V6): one row per pay period per stream. Unique on
 * (stream_id, period_key) and on event_id. Every reference is a bare Long; the six foreign keys
 * live in the database (stream CASCADE, schedule, event and execution SET NULL, authorization
 * RESTRICT). Phase 1 writes no authorization_id, execution_id or queued_night.
 *
 * <p>deposit_amount and amount_to_pull are payroll data: never in a log line at INFO or above, an
 * ops alert, or an analytics property.
 */
@Entity
@Table(name = "paycheck_cycles")
@Getter
@Setter
@NoArgsConstructor
public class PaycheckCycle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "schedule_id")
    private Long scheduleId;

    @Column(name = "stream_id", nullable = false)
    private Long streamId;

    @Column(name = "period_key", nullable = false, length = 16)
    private String periodKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 10)
    private PaycheckCycleState state = PaycheckCycleState.OPEN;

    @Column(name = "expected_date", nullable = false)
    private LocalDate expectedDate;

    @Column(name = "window_start", nullable = false)
    private LocalDate windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDate windowEnd;

    @Column(name = "event_id", unique = true)
    private Long eventId;

    @Column(name = "deposit_amount", precision = 12, scale = 2)
    private BigDecimal depositAmount;

    /** Eastern wall time the deposit matched; under shadow, when the SHADOW_CONFIRMED skip was written. */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** Under shadow, the would-have-pulled amount (ruling R7). */
    @Column(name = "amount_to_pull", precision = 12, scale = 2)
    private BigDecimal amountToPull;

    @Column(name = "authorization_id")
    private Long authorizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "skip_reason", length = 32)
    private PaycheckSkipReason skipReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "voided_reason", length = 32)
    private PaycheckVoidedReason voidedReason;

    @Column(name = "execution_id")
    private Long executionId;

    @Column(name = "queued_night")
    private LocalDate queuedNight;

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
