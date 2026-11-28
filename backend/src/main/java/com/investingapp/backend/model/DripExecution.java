package com.investingapp.backend.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Tracks DRIP (Dividend Reinvestment Plan) executions.
 * Each record represents a detected cash dividend and the corresponding reinvestment buy order.
 * The alpacaActivityId field is the deduplication key — ensures we never double-process a dividend.
 */
@Entity
@Table(name = "drip_executions", uniqueConstraints = {
        @UniqueConstraint(columnNames = "alpaca_activity_id")
})
public class DripExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore // never written into a response; see User
    private User user;

    /** Unique activity ID from Alpaca's DIV/CDIV response — dedup key */
    @Column(name = "alpaca_activity_id", nullable = false, unique = true)
    private String alpacaActivityId;

    /** The stock symbol that paid the dividend (and that we reinvest into) */
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    /** The net dollar amount of the dividend (what we reinvest) */
    @Column(name = "net_amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal netAmount;

    /** The date the dividend was paid (from Alpaca's 'date' field) */
    @Column(name = "dividend_date", length = 20)
    private String dividendDate;

    /** The Alpaca order ID for the reinvestment buy order */
    @Column(name = "alpaca_order_id")
    private String alpacaOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private DripStatus status;

    /** Descriptive text from Alpaca (e.g., "Cash DIV @ 0.54 Pos QTY:9.03...") */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Error message if order placement or fill failed */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    public enum DripStatus {
        DETECTED,       // Dividend detected from Alpaca, not yet reinvested
        ORDER_PLACED,   // Buy order placed with Alpaca
        FILLED,         // Buy order filled successfully
        FAILED,         // Order placement or fill failed
        SKIPPED         // Skipped (e.g., amount < $1.00 or asset not fractionable)
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneId.of("America/New_York"));
        this.updatedAt = LocalDateTime.now(ZoneId.of("America/New_York"));
        if (this.status == null) {
            this.status = DripStatus.DETECTED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneId.of("America/New_York"));
    }

    // Constructors
    public DripExecution() {}

    public DripExecution(User user, String alpacaActivityId, String symbol, BigDecimal netAmount, String dividendDate, String description) {
        this.user = user;
        this.alpacaActivityId = alpacaActivityId;
        this.symbol = symbol;
        this.netAmount = netAmount;
        this.dividendDate = dividendDate;
        this.description = description;
        this.status = DripStatus.DETECTED;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getAlpacaActivityId() { return alpacaActivityId; }
    public void setAlpacaActivityId(String alpacaActivityId) { this.alpacaActivityId = alpacaActivityId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(BigDecimal netAmount) { this.netAmount = netAmount; }

    public String getDividendDate() { return dividendDate; }
    public void setDividendDate(String dividendDate) { this.dividendDate = dividendDate; }

    public String getAlpacaOrderId() { return alpacaOrderId; }
    public void setAlpacaOrderId(String alpacaOrderId) { this.alpacaOrderId = alpacaOrderId; }

    public DripStatus getStatus() { return status; }
    public void setStatus(DripStatus status) { this.status = status; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }
}
