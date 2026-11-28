package com.investingapp.backend.controller;

import com.investingapp.backend.dto.MessageResponse;
import com.investingapp.backend.dto.PortfolioResponse;
import com.investingapp.backend.dto.UpdatePortfolioRequest;
import com.investingapp.backend.model.Portfolio;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.PortfolioService;
import com.investingapp.backend.service.PortfolioService.PortfolioItemRequest;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {
    
    private static final Logger logger = LoggerFactory.getLogger(PortfolioController.class);
    
    private final PortfolioService portfolioService;
    private final UserRepository userRepository;
    
    @Autowired
    public PortfolioController(PortfolioService portfolioService,
                             UserRepository userRepository) {
        this.portfolioService = portfolioService;
        this.userRepository = userRepository;
    }
    
    /**
     * Update portfolio allocations for the authenticated user
     */
    @PutMapping("/update")
    public ResponseEntity<?> updatePortfolio(@Valid @RequestBody UpdatePortfolioRequest request) {
        try {
            User user = getCurrentUser();
            if (user == null) {
                logger.error("No authenticated user found for portfolio update");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new MessageResponse("User not authenticated"));
            }
            
            logger.info("Updating portfolio for user: {} with {} items", 
                       user.getEmail(), request.getPortfolioItems().size());
            
            // Convert DTO to service request objects
            List<PortfolioItemRequest> portfolioItemRequests = request.getPortfolioItems().stream()
                    .map(dto -> new PortfolioItemRequest(
                            dto.getSymbol(),
                            dto.getName(), 
                            dto.getPercentage(),
                            dto.getAssetType()
                    ))
                    .collect(Collectors.toList());
            
            Portfolio updatedPortfolio = portfolioService.updatePortfolio(user, portfolioItemRequests);
            PortfolioResponse response = new PortfolioResponse(updatedPortfolio);
            
            logger.info("Successfully updated portfolio with ID: {} for user: {}", 
                       updatedPortfolio.getId(), user.getEmail());
            return ResponseEntity.ok(response);
            
        } catch (com.investingapp.backend.web.UserFacingException e) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("Portfolio validation failed: " + e.getMessage())); // user-facing
        } catch (Exception e) {
            logger.error("Error updating portfolio: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Failed to update portfolio"));
        }
    }
    
    /**
     * Get current portfolio for the authenticated user
     */
    @GetMapping("/current")
    public ResponseEntity<?> getCurrentPortfolio() {
        try {
            User user = getCurrentUser();
            if (user == null) {
                logger.error("No authenticated user found for portfolio retrieval");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new MessageResponse("User not authenticated"));
            }
            
            // This will create default portfolio if one doesn't exist
            Portfolio portfolio = portfolioService.getOrCreatePortfolio(user);
            PortfolioResponse response = new PortfolioResponse(portfolio);
            
            logger.info("Retrieved portfolio for user: {}", user.getEmail());
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error retrieving portfolio: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Failed to retrieve portfolio"));
        }
    }
    
    /**
     * Reset portfolio to default allocation for the authenticated user
     */
    @PostMapping("/reset-to-default")
    public ResponseEntity<?> resetToDefaultPortfolio() {
        try {
            User user = getCurrentUser();
            if (user == null) {
                logger.error("No authenticated user found for portfolio reset");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new MessageResponse("User not authenticated"));
            }
            
            logger.info("Resetting portfolio to default for user: {}", user.getEmail());
            
            // Delete existing portfolio if it exists
            portfolioService.deletePortfolio(user);
            
            // Create new default portfolio
            Portfolio defaultPortfolio = portfolioService.getOrCreatePortfolio(user);
            PortfolioResponse response = new PortfolioResponse(defaultPortfolio);
            
            logger.info("Successfully reset portfolio to default for user: {}", user.getEmail());
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error resetting portfolio to default: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Failed to reset portfolio"));
        }
    }
    
    /**
     * Helper method to get current authenticated user
     */
    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (!(authentication.getPrincipal() instanceof UserDetailsImpl)) {
            logger.error("Principal is not an instance of UserDetailsImpl");
            return null;
        }
        
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        Optional<User> userOpt = userRepository.findById(userDetails.getId());
        
        if (userOpt.isEmpty()) {
            logger.error("User not found with ID: {}", userDetails.getId());
            return null;
        }
        
        return userOpt.get();
    }
}