package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "portfolio_items")
public class PortfolioItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "portfolio_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore // never written into a response; see User
    private Portfolio portfolio;

    @Column(name = "symbol", length = 10, nullable = false)
    private String symbol; // Stock/ETF symbol (e.g., "VTI", "AAPL")

    @Column(name = "name", length = 200)
    private String name; // Human-readable name

    @Column(name = "percentage", precision = 5, scale = 2, nullable = false)
    private BigDecimal percentage; // Percentage allocation (0.00 to 100.00)

    @Column(name = "asset_type", length = 20)
    private String assetType = "STOCK"; // STOCK, ETF, BOND, etc.

    /**
     * Calculate dollar amount for this item based on total investment
     */
    public BigDecimal calculateInvestmentAmount(BigDecimal totalInvestmentAmount) {
        if (totalInvestmentAmount == null || percentage == null) {
            return BigDecimal.ZERO;
        }
        
        return totalInvestmentAmount
                .multiply(percentage)
                .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Calculate number of shares to buy based on current stock price
     */
    public int calculateSharesToBuy(BigDecimal totalInvestmentAmount, BigDecimal currentPrice) {
        if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        
        BigDecimal dollarsToInvest = calculateInvestmentAmount(totalInvestmentAmount);
        return dollarsToInvest.divide(currentPrice, 0, java.math.RoundingMode.DOWN).intValue();
    }
}