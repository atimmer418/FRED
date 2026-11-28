package com.investingapp.backend.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "investment_trades")
public class InvestmentTrade {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id", nullable = false)
    private Long userId;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "investment_execution_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore // never written into a response; see User
    private InvestmentExecution investmentExecution;
    
    @Column(name = "symbol", nullable = false)
    private String symbol;
    
    @Column(name = "quantity", precision = 10, scale = 6)
    private BigDecimal quantity;
    
    @Column(name = "notional_amount", precision = 10, scale = 2)
    private BigDecimal notionalAmount;
    
    @Column(name = "filled_quantity", precision = 10, scale = 6, nullable = false, columnDefinition = "DECIMAL(10,6) DEFAULT 0")
    private BigDecimal filledQuantity = BigDecimal.ZERO;
    
    @Column(name = "filled_avg_price", precision = 10, scale = 2)
    private BigDecimal filledAvgPrice;
    
    @Column(name = "alpaca_order_id")
    @com.fasterxml.jackson.annotation.JsonIgnore // internal; responses use the DTOs in dto/
    private String alpacaOrderId;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TradeStatus status;
    
    @Column(name = "error_message", columnDefinition = "TEXT")
    @com.fasterxml.jackson.annotation.JsonIgnore // internal; responses use the DTOs in dto/
    private String errorMessage;
    
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;
    
    @Column(name = "filled_at")
    private LocalDateTime filledAt;
    
    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;
    
    @Column(name = "failed_at")
    private LocalDateTime failedAt;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    public enum TradeStatus {
        PENDING,        // Order is being prepared
        SUBMITTED,      // Order submitted to Alpaca
        PARTIALLY_FILLED, // Order is partially filled
        FILLED,         // Order is completely filled
        CANCELED,       // Order was canceled
        REJECTED,       // Order was rejected
        FAILED          // Order failed
    }
    
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = TradeStatus.PENDING;
        }
        if (this.filledQuantity == null) {
            this.filledQuantity = BigDecimal.ZERO;
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
    
    // Constructors
    public InvestmentTrade() {}
    
    public InvestmentTrade(InvestmentExecution execution, String symbol, BigDecimal notionalAmount) {
        this.investmentExecution = execution;
        this.userId = execution.getUser().getId();
        this.symbol = symbol;
        this.notionalAmount = notionalAmount;
        this.status = TradeStatus.PENDING;
        this.filledQuantity = BigDecimal.ZERO;
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    
    public InvestmentExecution getInvestmentExecution() { return investmentExecution; }
    public void setInvestmentExecution(InvestmentExecution investmentExecution) { this.investmentExecution = investmentExecution; }
    
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    
    public BigDecimal getNotionalAmount() { return notionalAmount; }
    public void setNotionalAmount(BigDecimal notionalAmount) { this.notionalAmount = notionalAmount; }
    
    public BigDecimal getFilledQuantity() { return filledQuantity; }
    public void setFilledQuantity(BigDecimal filledQuantity) { this.filledQuantity = filledQuantity; }
    
    public BigDecimal getFilledAvgPrice() { return filledAvgPrice; }
    public void setFilledAvgPrice(BigDecimal filledAvgPrice) { this.filledAvgPrice = filledAvgPrice; }
    
    public String getAlpacaOrderId() { return alpacaOrderId; }
    public void setAlpacaOrderId(String alpacaOrderId) { this.alpacaOrderId = alpacaOrderId; }
    
    public TradeStatus getStatus() { return status; }
    public void setStatus(TradeStatus status) { this.status = status; }
    
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    
    public LocalDateTime getFilledAt() { return filledAt; }
    public void setFilledAt(LocalDateTime filledAt) { this.filledAt = filledAt; }
    
    public LocalDateTime getCanceledAt() { return canceledAt; }
    public void setCanceledAt(LocalDateTime canceledAt) { this.canceledAt = canceledAt; }
    
    public LocalDateTime getFailedAt() { return failedAt; }
    public void setFailedAt(LocalDateTime failedAt) { this.failedAt = failedAt; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
