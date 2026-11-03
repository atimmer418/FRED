package com.investingapp.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.investingapp.backend.util.EasternTime;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A savings goal owned by one user. The type-specific settings are JSON in {@code configJson},
 * written and read by GoalConfigCodec.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "goals")
public class Goal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @JsonIgnore
    private User user;

    @Column(name = "client_id", length = 36)
    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private GoalType type;

    @Column(length = 50, nullable = false)
    private String name;

    @Column(length = 30, nullable = false)
    private String icon;

    @Column(name = "config", columnDefinition = "TEXT", nullable = false)
    private String configJson;

    @Column(name = "current_balance", precision = 12, scale = 2, nullable = false)
    private BigDecimal currentBalance = BigDecimal.ZERO;

    @Column(name = "balance_updated_at")
    private LocalDateTime balanceUpdatedAt;

    @Column(name = "anchor_balance", precision = 12, scale = 2, nullable = false)
    private BigDecimal anchorBalance = BigDecimal.ZERO;

    @Column(name = "anchor_monthly", precision = 10, scale = 2, nullable = false)
    private BigDecimal anchorMonthly = BigDecimal.ZERO;

    @Column(name = "plan_rate", precision = 5, scale = 4, nullable = false)
    private BigDecimal planRate;

    @Column(name = "plan_rate_kind", length = 10, nullable = false)
    private String planRateKind;

    @Column(name = "plan_anchored_at", nullable = false)
    private LocalDateTime planAnchoredAt;

    @Column(nullable = false)
    private boolean completed;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = EasternTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = EasternTime.now();
    }
}
