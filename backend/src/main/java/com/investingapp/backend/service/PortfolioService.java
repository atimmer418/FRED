package com.investingapp.backend.service;

import com.investingapp.backend.model.Portfolio;
import com.investingapp.backend.model.PortfolioItem;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.PortfolioRepository;
import com.investingapp.backend.repository.PortfolioItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class PortfolioService {
    
    private static final Logger logger = LoggerFactory.getLogger(PortfolioService.class);
    
    @Autowired
    private PortfolioRepository portfolioRepository;
    
    @Autowired
    private PortfolioItemRepository portfolioItemRepository;

    /**
     * Get or create portfolio for user (upsert operation)
     */
    @Transactional
    public Portfolio getOrCreatePortfolio(User user) {
        logger.info("Getting or creating portfolio for user: {}", user.getEmail());
        
        Optional<Portfolio> existingPortfolio = portfolioRepository.findByUser(user);
        
        if (existingPortfolio.isPresent()) {
            logger.info("Found existing portfolio for user: {}", user.getEmail());
            return existingPortfolio.get();
        }
        
        // Create default portfolio
        logger.info("Creating default portfolio for user: {}", user.getEmail());
        Portfolio defaultPortfolio = Portfolio.createDefaultPortfolio(user);
        Portfolio savedPortfolio = portfolioRepository.save(defaultPortfolio);
        
        logger.info("Successfully created default portfolio with ID: {} for user: {}", 
                   savedPortfolio.getId(), user.getEmail());
        return savedPortfolio;
    }

    /**
     * Update portfolio items (upsert operation)
     */
    @Transactional
    public Portfolio updatePortfolio(User user, List<PortfolioItemRequest> portfolioItemRequests) {
        logger.info("Updating portfolio for user: {} with {} items", 
                   user.getEmail(), portfolioItemRequests.size());
        
        Portfolio portfolio = getOrCreatePortfolio(user);
        
        // Clear existing items
        portfolio.getPortfolioItems().clear();
        
        // Add new items
        for (PortfolioItemRequest request : portfolioItemRequests) {
            PortfolioItem item = new PortfolioItem();
            item.setSymbol(request.getSymbol().toUpperCase());
            item.setName(request.getName());
            item.setPercentage(request.getPercentage());
            item.setAssetType(request.getAssetType() != null ? request.getAssetType() : "STOCK");
            
            portfolio.addPortfolioItem(item);
        }
        
        // Validate portfolio
        if (!portfolio.isValid()) {
            logger.error("Portfolio validation failed for user: {}. Total percentage: {}",
                        user.getEmail(), portfolio.getTotalPercentage());
            throw new com.investingapp.backend.web.UserFacingException("Portfolio percentages must add up to 100%");
        }

        portfolio.setIsDefault(isDefaultAllocation(portfolioItemRequests));

        Portfolio savedPortfolio = portfolioRepository.save(portfolio);
        logger.info("Successfully updated portfolio for user: {}", user.getEmail());
        return savedPortfolio;
    }

    /**
     * Get portfolio for user
     */
    @Transactional(readOnly = true)
    public Optional<Portfolio> getPortfolio(User user) {
        return portfolioRepository.findByUser(user);
    }

    /**
     * Get portfolio by user ID
     */
    @Transactional(readOnly = true)
    public Optional<Portfolio> getPortfolioByUserId(Long userId) {
        return portfolioRepository.findByUserId(userId);
    }

    /**
     * Delete portfolio for user
     */
    @Transactional
    public void deletePortfolio(User user) {
        logger.info("Deleting portfolio for user: {}", user.getEmail());
        
        Optional<Portfolio> portfolio = portfolioRepository.findByUser(user);
        if (portfolio.isPresent()) {
            portfolioRepository.delete(portfolio.get());
            logger.info("Successfully deleted portfolio for user: {}", user.getEmail());
        } else {
            logger.warn("No portfolio found to delete for user: {}", user.getEmail());
        }
    }

    /**
     * Calculate trade allocations for a given investment amount
     */
    @Transactional(readOnly = true)
    public List<TradeAllocation> calculateTradeAllocations(User user, BigDecimal investmentAmount) {
        Portfolio portfolio = getOrCreatePortfolio(user);
        
        return portfolio.getPortfolioItems().stream()
                .map(item -> {
                    BigDecimal allocation = item.calculateInvestmentAmount(investmentAmount);
                    return new TradeAllocation(item.getSymbol(), item.getName(), 
                                             item.getPercentage(), allocation);
                })
                .toList();
    }

    private boolean isDefaultAllocation(List<PortfolioItemRequest> items) {
        if (items.size() != 3) return false;
        java.util.Map<String, BigDecimal> defaults = java.util.Map.of(
            "VTI",  new BigDecimal("75.00"),
            "VXUS", new BigDecimal("20.00"),
            "VBR",  new BigDecimal("5.00")
        );
        return items.stream().allMatch(item -> {
            BigDecimal expected = defaults.get(item.getSymbol().toUpperCase());
            return expected != null && expected.compareTo(item.getPercentage()) == 0;
        });
    }

    // Helper classes for requests and responses
    public static class PortfolioItemRequest {
        private String symbol;
        private String name;
        private BigDecimal percentage;
        private String assetType;
        
        // Constructors, getters, setters
        public PortfolioItemRequest() {}
        
        public PortfolioItemRequest(String symbol, String name, BigDecimal percentage, String assetType) {
            this.symbol = symbol;
            this.name = name;
            this.percentage = percentage;
            this.assetType = assetType;
        }
        
        public String getSymbol() { return symbol; }
        public void setSymbol(String symbol) { this.symbol = symbol; }
        
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        
        public BigDecimal getPercentage() { return percentage; }
        public void setPercentage(BigDecimal percentage) { this.percentage = percentage; }
        
        public String getAssetType() { return assetType; }
        public void setAssetType(String assetType) { this.assetType = assetType; }
    }

    public static class TradeAllocation {
        private final String symbol;
        private final String name;
        private final BigDecimal percentage;
        private final BigDecimal dollarsToInvest;
        
        public TradeAllocation(String symbol, String name, BigDecimal percentage, BigDecimal dollarsToInvest) {
            this.symbol = symbol;
            this.name = name;
            this.percentage = percentage;
            this.dollarsToInvest = dollarsToInvest;
        }
        
        public String getSymbol() { return symbol; }
        public String getName() { return name; }
        public BigDecimal getPercentage() { return percentage; }
        public BigDecimal getDollarsToInvest() { return dollarsToInvest; }
    }
}