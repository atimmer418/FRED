package com.investingapp.backend.controller;

import com.investingapp.backend.dto.CreateInvestmentScheduleRequest;
import com.investingapp.backend.dto.InvestmentScheduleResponse;
import com.investingapp.backend.dto.MessageResponse;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.InvestmentScheduleService;

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
@RequestMapping("/api/investment-schedule")
public class InvestmentScheduleController {
    
    private static final Logger logger = LoggerFactory.getLogger(InvestmentScheduleController.class);
    
    private final InvestmentScheduleService investmentScheduleService;
    private final UserRepository userRepository;
    
    @Autowired
    public InvestmentScheduleController(InvestmentScheduleService investmentScheduleService,
                                      UserRepository userRepository) {
        this.investmentScheduleService = investmentScheduleService;
        this.userRepository = userRepository;
    }
    
    /**
     * Create or update investment schedule (upsert operation)
     * Creates new schedule if none exists, updates existing schedule if found
     */
    @PostMapping("/create")
    public ResponseEntity<?> createInvestmentSchedule(@Valid @RequestBody CreateInvestmentScheduleRequest request) {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new MessageResponse("User not authenticated"));
            }
            
            logger.info("Creating/updating investment schedule for user: {} with amount: {}", 
                       user.getEmail(), request.getInvestmentAmount());
            logger.info("Received start date from frontend: {}", request.getStartDate());
            if (request.getNextInvestmentDate() != null) {
                logger.info("Received next investment date from frontend: {}", request.getNextInvestmentDate());
            }
            
            InvestmentSchedule schedule = investmentScheduleService.createInvestmentSchedule(
                    user, 
                    request.getInvestmentAmount(), 
                    request.getFrequency(),
                    request.getStartDate(),
                    request.getNextInvestmentDate()
            );
            
            logger.info("Saved schedule with start date: {} and next investment date: {}", 
                       schedule.getStartDate(), schedule.getNextInvestmentDate());
            
            InvestmentScheduleResponse response = new InvestmentScheduleResponse(schedule);
            
            logger.info("Successfully created/updated investment schedule with ID: {} for user: {}", 
                       schedule.getId(), user.getEmail());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error creating investment schedule: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Error creating investment schedule: " + e.getMessage()));
        }
    }
    
    /**
     * Get current investment schedule for the user
     */
    @GetMapping("/current")
    public ResponseEntity<?> getCurrentSchedule() {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new MessageResponse("User not authenticated"));
            }
            
            Optional<InvestmentSchedule> scheduleOpt = investmentScheduleService.getCurrentSchedule(user);
            
            if (scheduleOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new MessageResponse("No investment schedule found"));
            }
            
            InvestmentScheduleResponse response = new InvestmentScheduleResponse(scheduleOpt.get());
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error getting current investment schedule: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Error retrieving investment schedule"));
        }
    }
    
    /**
     * Get all investment schedules for the user
     */
    @GetMapping("/all")
    public ResponseEntity<?> getAllSchedules() {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new MessageResponse("User not authenticated"));
            }
            
            List<InvestmentSchedule> schedules = investmentScheduleService.getAllSchedules(user);
            List<InvestmentScheduleResponse> responses = schedules.stream()
                    .map(InvestmentScheduleResponse::new)
                    .collect(Collectors.toList());
            
            return ResponseEntity.ok(responses);
            
        } catch (Exception e) {
            logger.error("Error getting all investment schedules: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Error retrieving investment schedules"));
        }
    }
    
    /**
     * Pause an investment schedule
     */
    @PostMapping("/{scheduleId}/pause")
    public ResponseEntity<?> pauseSchedule(@PathVariable Long scheduleId) {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new MessageResponse("User not authenticated"));
            }
            
            InvestmentSchedule schedule = investmentScheduleService.pauseSchedule(user, scheduleId);
            InvestmentScheduleResponse response = new InvestmentScheduleResponse(schedule);
            
            return ResponseEntity.ok(response);
            
        } catch (RuntimeException e) {
            logger.error("Runtime error pausing schedule: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse(e.getMessage()));
        } catch (Exception e) {
            logger.error("Error pausing investment schedule: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Error pausing investment schedule"));
        }
    }
    
    /**
     * Resume an investment schedule.
     * Returns 403 if the user's Alpaca account is not yet active.
     */
    @PostMapping("/{scheduleId}/resume")
    public ResponseEntity<?> resumeSchedule(@PathVariable Long scheduleId) {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new MessageResponse("User not authenticated"));
            }

            InvestmentSchedule schedule = investmentScheduleService.resumeSchedule(user, scheduleId);
            InvestmentScheduleResponse response = new InvestmentScheduleResponse(schedule);

            return ResponseEntity.ok(response);

        } catch (IllegalStateException e) {
            if ("ACCOUNT_NOT_ACTIVE".equals(e.getMessage())) {
                logger.warn("Resume blocked: account not active for schedule ID {}", scheduleId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new MessageResponse("Your account is not yet active."));
            }
            logger.error("Illegal state error resuming schedule: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse(e.getMessage()));
        } catch (RuntimeException e) {
            logger.error("Runtime error resuming schedule: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse(e.getMessage()));
        } catch (Exception e) {
            logger.error("Error resuming investment schedule: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Error resuming investment schedule"));
        }
    }
    
    /**
     * Helper method to get current authenticated user
     */
    private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (!(authentication.getPrincipal() instanceof UserDetailsImpl)) {
            logger.warn("Authentication principal is not an instance of UserDetailsImpl");
            return null;
        }
        
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        Optional<User> userOpt = userRepository.findById(userDetails.getId());
        
        if (userOpt.isEmpty()) {
            logger.warn("User ID {} not found from authenticated principal", userDetails.getId());
            return null;
        }
        
        return userOpt.get();
    }
}