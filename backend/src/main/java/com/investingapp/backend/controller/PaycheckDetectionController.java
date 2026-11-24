package com.investingapp.backend.controller;

import com.investingapp.backend.dto.MessageResponse;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.InitSyncEnqueuer;
import com.investingapp.backend.service.PaycheckDetectionStatusService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/paycheck-detection")
public class PaycheckDetectionController {

    private final UserRepository userRepository;
    private final InitSyncEnqueuer initSyncEnqueuer;
    private final PaycheckDetectionStatusService statusService;

    public PaycheckDetectionController(UserRepository userRepository, InitSyncEnqueuer initSyncEnqueuer,
            PaycheckDetectionStatusService statusService) {
        this.userRepository = userRepository;
        this.initSyncEnqueuer = initSyncEnqueuer;
        this.statusService = statusService;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserDetailsImpl userDetails)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse("User not authenticated"));
        }
        User user = userRepository.findById(userDetails.getId()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse("User not found in database"));
        }
        initSyncEnqueuer.enqueueIfNeeded(user);
        return ResponseEntity.ok(statusService.status(user));
    }
}
