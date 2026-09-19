package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Per-user TLH ledger sync state. The ledger must be fully backfilled from
 * Alpaca FILL activity (BACKFILLING → READY) before any harvest is allowed;
 * DEGRADED marks a ledger that needed a position-level re-baseline.
 */
@Entity
@Table(name = "tlh_user_states",
        uniqueConstraints = @UniqueConstraint(name = "uq_tlh_user_states_user", columnNames = "user_id"))
@Getter
@Setter
@NoArgsConstructor
public class TlhUserState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "ledger_status", length = 15, nullable = false)
    private LedgerStatus ledgerStatus;

    /** ISO timestamp of the last ingested fill — the incremental-sync cursor. */
    @Column(name = "cursor_after", length = 40)
    private String cursorAfter;

    /** Alpaca activities page token when a backfill pass stopped mid-scan. */
    @Column(name = "cursor_activity_id", length = 64)
    private String cursorActivityId;

    @Column(name = "backfill_pages_done", nullable = false)
    private Integer backfillPagesDone = 0;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum LedgerStatus {
        BACKFILLING, READY, DEGRADED
    }

    public TlhUserState(Long userId) {
        this.userId = userId;
        this.ledgerStatus = LedgerStatus.BACKFILLING;
        this.backfillPagesDone = 0;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("America/New_York"));
        this.createdAt = now;
        this.updatedAt = now;
        if (this.ledgerStatus == null) this.ledgerStatus = LedgerStatus.BACKFILLING;
        if (this.backfillPagesDone == null) this.backfillPagesDone = 0;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneId.of("America/New_York"));
    }
}
