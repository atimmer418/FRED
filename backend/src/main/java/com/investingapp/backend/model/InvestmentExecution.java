package com.investingapp.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Table(name = "investment_executions")
public class InvestmentExecution {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    @Column(name = "scheduled_date", nullable = false)
    private LocalDateTime scheduledDate;
    
    @Column(name = "execution_date")
    private LocalDateTime executionDate;
    
    @Column(name = "amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal amount;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ExecutionStatus status;
    
    @Column(name = "alpaca_transfer_id")
    private String alpacaTransferId;
    
    @Column(name = "alpaca_account_id", length = 512)
    private String alpacaAccountId;
    
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
    
    @Column(name = "investment_type")
    private String investmentType; // "portfolio" or "stock"
    
    @Column(name = "target_symbol")
    private String targetSymbol; // Stock symbol for individual stock investments
    
    @Column(name = "funding_source")
    private String fundingSource; // "bank" or "buying_power"
    
    @Column(name = "funding_completed_at")
    private LocalDateTime fundingCompletedAt;
    
    @Column(name = "trading_completed_at")
    private LocalDateTime tradingCompletedAt;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Paycheck Autopilot, Phase 0 (V6).
    // JsonIgnore: InvestmentController serializes this entity directly (history list, dashboard).
    // schedule.user is itself a lazy association, and the schedule carries achRequestId, so letting
    // Jackson walk this edge would pull a second user object and a sensitive ACH id into the
    // response the first time a recurring execution's row gets serialized.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    @JsonIgnore
    private InvestmentSchedule schedule;

    /** SCHED:{scheduleId}:{dueDate} or LUMP:{userId}:{clientUuid}; UNIQUE in V6. */
    @Column(name = "source_key", length = 120, unique = true)
    private String sourceKey;

    /** {userId}:{queuedNightET}, recurring only; UNIQUE in V6, NULLs allowed. */
    @Column(name = "night_key", length = 40, unique = true)
    private String nightKey;

    @Column(name = "batch_night")
    private LocalDate batchNight;

    /** Stamped by the 11:59 batch claim before the Alpaca POST (spec 5.5). */
    @Column(name = "transfer_attempted_at")
    private LocalDateTime transferAttemptedAt;

    public enum ExecutionStatus {
        SCHEDULED,      // Investment is scheduled for execution
        FUNDING_INITIATED, // ACH transfer has been initiated
        FUNDING_COMPLETED, // ACH transfer completed successfully
        FUNDING_FAILED,    // ACH transfer failed
        TRADING_INITIATED, // Stock purchase orders have been placed
        TRADING_COMPLETED, // All stock purchases completed successfully
        TRADING_FAILED,    // Stock purchase failed
        COMPLETED,         // Everything completed successfully
        FAILED             // Final failure state
    }
    
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneId.of("America/New_York"));
        this.updatedAt = LocalDateTime.now(ZoneId.of("America/New_York"));
        if (this.status == null) {
            this.status = ExecutionStatus.SCHEDULED;
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now(ZoneId.of("America/New_York"));
    }
    
    // Constructors
    public InvestmentExecution() {}
    
    public InvestmentExecution(User user, LocalDateTime scheduledDate, BigDecimal amount) {
        this.user = user;
        this.scheduledDate = scheduledDate;
        this.amount = amount;
        this.status = ExecutionStatus.SCHEDULED;
        this.investmentType = "portfolio"; // default
    }
    
    public InvestmentExecution(User user, LocalDateTime scheduledDate, BigDecimal amount, String investmentType, String targetSymbol) {
        this.user = user;
        this.scheduledDate = scheduledDate;
        this.amount = amount;
        this.status = ExecutionStatus.SCHEDULED;
        this.investmentType = investmentType;
        this.targetSymbol = targetSymbol;
        this.fundingSource = "bank"; // default
    }

    public InvestmentExecution(User user, LocalDateTime scheduledDate, BigDecimal amount, String investmentType, String targetSymbol, String fundingSource) {
        this.user = user;
        this.scheduledDate = scheduledDate;
        this.amount = amount;
        this.status = ExecutionStatus.SCHEDULED;
        this.investmentType = investmentType;
        this.targetSymbol = targetSymbol;
        this.fundingSource = fundingSource;
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    
    public LocalDateTime getScheduledDate() { return scheduledDate; }
    public void setScheduledDate(LocalDateTime scheduledDate) { this.scheduledDate = scheduledDate; }
    
    public LocalDateTime getExecutionDate() { return executionDate; }
    public void setExecutionDate(LocalDateTime executionDate) { this.executionDate = executionDate; }
    
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    
    public ExecutionStatus getStatus() { return status; }
    public void setStatus(ExecutionStatus status) { this.status = status; }
    
    public String getAlpacaTransferId() { return alpacaTransferId; }
    public void setAlpacaTransferId(String alpacaTransferId) { this.alpacaTransferId = alpacaTransferId; }
    
    public String getAlpacaAccountId() { return alpacaAccountId; }
    public void setAlpacaAccountId(String alpacaAccountId) { this.alpacaAccountId = alpacaAccountId; }
    
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    
    public String getInvestmentType() { return investmentType; }
    public void setInvestmentType(String investmentType) { this.investmentType = investmentType; }
    
    public String getTargetSymbol() { return targetSymbol; }
    public void setTargetSymbol(String targetSymbol) { this.targetSymbol = targetSymbol; }
    
    public String getFundingSource() { return fundingSource; }
    public void setFundingSource(String fundingSource) { this.fundingSource = fundingSource; }

    public LocalDateTime getFundingCompletedAt() { return fundingCompletedAt; }
    public void setFundingCompletedAt(LocalDateTime fundingCompletedAt) { this.fundingCompletedAt = fundingCompletedAt; }
    
    public LocalDateTime getTradingCompletedAt() { return tradingCompletedAt; }
    public void setTradingCompletedAt(LocalDateTime tradingCompletedAt) { this.tradingCompletedAt = tradingCompletedAt; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public InvestmentSchedule getSchedule() { return schedule; }
    public void setSchedule(InvestmentSchedule schedule) { this.schedule = schedule; }

    public String getSourceKey() { return sourceKey; }
    public void setSourceKey(String sourceKey) { this.sourceKey = sourceKey; }

    public String getNightKey() { return nightKey; }
    public void setNightKey(String nightKey) { this.nightKey = nightKey; }

    public LocalDate getBatchNight() { return batchNight; }
    public void setBatchNight(LocalDate batchNight) { this.batchNight = batchNight; }

    public LocalDateTime getTransferAttemptedAt() { return transferAttemptedAt; }
    public void setTransferAttemptedAt(LocalDateTime transferAttemptedAt) { this.transferAttemptedAt = transferAttemptedAt; }
}
