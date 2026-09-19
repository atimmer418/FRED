package com.investingapp.backend.controller;

import com.investingapp.backend.dto.RecoveryInitiateRequest;
import com.investingapp.backend.dto.RecoveryResponse;
import com.investingapp.backend.dto.RecoveryVerifyRequest;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.jwt.JwtUtils;
import com.investingapp.backend.service.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/auth/recovery")
@CrossOrigin(origins = "*", maxAge = 3600)
public class RecoveryController {

    private static final Logger logger = LoggerFactory.getLogger(RecoveryController.class);

    private final UserRepository userRepository;
    private final JwtUtils jwtUtils;
    private final EncryptionService encryptionService;

    public RecoveryController(UserRepository userRepository, JwtUtils jwtUtils, EncryptionService encryptionService) {
        this.userRepository = userRepository;
        this.jwtUtils = jwtUtils;
        this.encryptionService = encryptionService;
    }

    @PostMapping("/initiate")
    public ResponseEntity<RecoveryResponse> initiateRecovery(@RequestBody RecoveryInitiateRequest request) {
        User user = userRepository.findByEmail(request.getEmail()).orElse(null);

        if (user == null) {
            // Security: return success regardless to prevent enumeration
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return ResponseEntity.ok(new RecoveryResponse(true, "If an account exists, a recovery code has been sent.", null));
        }

        // Generate OTP and encrypt before storing
        String otp = String.format("%06d", new SecureRandom().nextInt(999999));
        user.setRecoveryOtp(encryptionService.encrypt(otp));
        user.setRecoveryOtpExpiry(LocalDateTime.now().plusMinutes(15));
        userRepository.save(user);

        // Mock email sending
        System.out.println("============================================");
        System.out.println("MOCK EMAIL TO: " + user.getEmail());
        System.out.println("SUBJECT: Account Recovery Code");
        System.out.println("BODY: Your recovery code is: " + otp);
        System.out.println("============================================");

        return ResponseEntity.ok(new RecoveryResponse(true, "If an account exists, a recovery code has been sent.", null));
    }

    @PostMapping("/verify")
    public ResponseEntity<RecoveryResponse> verifyRecovery(@RequestBody RecoveryVerifyRequest request) {
        User user = userRepository.findByEmail(request.getEmail()).orElse(null);

        if (user == null) {
            return ResponseEntity.status(401).body(new RecoveryResponse(false, "Invalid request", null));
        }

        if (user.getRecoveryOtp() == null || user.getRecoveryOtpExpiry() == null) {
            return ResponseEntity.status(401).body(new RecoveryResponse(false, "Invalid or expired code", null));
        }

        // Decrypt stored OTP before comparing
        String storedOtp;
        try {
            storedOtp = encryptionService.decrypt(user.getRecoveryOtp());
        } catch (Exception e) {
            logger.error("Failed to decrypt recovery OTP for user {}: {}", user.getEmail(), e.getMessage());
            return ResponseEntity.status(500).body(new RecoveryResponse(false, "Internal error verifying code", null));
        }

        if (!storedOtp.equals(request.getOtp()) || user.getRecoveryOtpExpiry().isBefore(LocalDateTime.now())) {
            return ResponseEntity.status(401).body(new RecoveryResponse(false, "Invalid or expired code", null));
        }

        // Clear OTP
        user.setRecoveryOtp(null);
        user.setRecoveryOtpExpiry(null);

        // Security: delete all existing passkeys so the user must register a new one
        user.getPasskeyCredentials().clear();
        userRepository.save(user);

        // Issue token — include next-step hint so cold-start navigation can optimise
        String nsRecovery = (user.getUserProgress() != null) ? user.getUserProgress().getNextStep() : "get-started";
        String token = jwtUtils.generateJwtTokenFromUsername(user.getEmail(), nsRecovery, user.getPublicId());

        return ResponseEntity.ok(new RecoveryResponse(true, "Recovery successful. Please register a new passkey.", token));
    }
}
