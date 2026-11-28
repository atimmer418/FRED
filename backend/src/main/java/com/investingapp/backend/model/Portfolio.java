package com.investingapp.backend.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "portfolios")
public class Portfolio {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @com.fasterxml.jackson.annotation.JsonIgnore // never written into a response; see User
    private User user;

    @Column(name = "name", length = 100)
    private String name = "My Portfolio";

    @Column(name = "total_percentage", precision = 5, scale = 2)
    private BigDecimal totalPercentage = BigDecimal.ZERO;

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    @OneToMany(mappedBy = "portfolio", cascade = CascadeType.ALL, fetch = FetchType.LAZY, orphanRemoval = true)
    private List<PortfolioItem> portfolioItems = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        // Recalculate total percentage
        recalculateTotalPercentage();
    }

    /**
     * Recalculate total percentage from portfolio items
     */
    public void recalculateTotalPercentage() {
        if (portfolioItems != null) {
            totalPercentage = portfolioItems.stream()
                    .map(PortfolioItem::getPercentage)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } else {
            totalPercentage = BigDecimal.ZERO;
        }
    }

    /**
     * Check if portfolio is valid (percentages add up to 100%)
     */
    public boolean isValid() {
        recalculateTotalPercentage();
        return totalPercentage.compareTo(new BigDecimal("100.00")) == 0;
    }

    /**
     * Add portfolio item
     */
    public void addPortfolioItem(PortfolioItem item) {
        portfolioItems.add(item);
        item.setPortfolio(this);
        recalculateTotalPercentage();
    }

    /**
     * Remove portfolio item
     */
    public void removePortfolioItem(PortfolioItem item) {
        portfolioItems.remove(item);
        item.setPortfolio(null);
        recalculateTotalPercentage();
    }

    /**
     * Create default portfolio with common ETFs
     */
    public static Portfolio createDefaultPortfolio(User user) {
        Portfolio portfolio = new Portfolio();
        portfolio.setUser(user);
        portfolio.setName("Default Portfolio");
        portfolio.setIsDefault(true);

        // Add default portfolio items (common ETF allocation)
        PortfolioItem vti = new PortfolioItem();
        vti.setSymbol("VTI");
        vti.setName("Vanguard Total Stock Market ETF");
        vti.setPercentage(new BigDecimal("75.00"));
        vti.setPortfolio(portfolio);

        PortfolioItem vxus = new PortfolioItem();
        vxus.setSymbol("VXUS");
        vxus.setName("Vanguard Total International Stock ETF");
        vxus.setPercentage(new BigDecimal("20.00"));
        vxus.setPortfolio(portfolio);

        PortfolioItem vbr = new PortfolioItem();
        vbr.setSymbol("VBR");
        vbr.setName("Vanguard Small-Cap Value ETF");
        vbr.setPercentage(new BigDecimal("5.00"));
        vbr.setPortfolio(portfolio);

        portfolio.getPortfolioItems().add(vti);
        portfolio.getPortfolioItems().add(vxus);
        portfolio.getPortfolioItems().add(vbr);
        
        portfolio.recalculateTotalPercentage();
        
        return portfolio;
    }
}