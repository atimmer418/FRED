package com.investingapp.backend.controller;

import com.investingapp.backend.security.ratelimit.ClientAddressResolver;
import com.investingapp.backend.dto.RecoveryInitiateRequest;
import com.investingapp.backend.dto.RecoveryResponse;
import com.investingapp.backend.dto.RecoveryVerifyRequest;
import com.investingapp.backend.service.AccountRecoveryService;
import com.investingapp.backend.service.AccountRecoveryService.SendStatus;
import com.investingapp.backend.service.AccountRecoveryService.VerifyOutcome;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Account recovery by emailed code. No sign-in is needed to call these, which is the point, so every
 * rule that keeps a stranger out lives in {@link AccountRecoveryService}: the limits on codes and
 * guesses, and the one-time recovery token a right code earns.
 *
 * The answers are deliberately flat. A wrong code, a dead code, a code for another account and an
 * email with no account all get the same reply, so none of them says whether an account exists.
 */
@RestController
@RequestMapping("/api/auth/recovery")
@CrossOrigin(origins = "*", maxAge = 3600)
public class RecoveryController {

    static final String SENT_MESSAGE = "If an account exists, a recovery code has been sent.";
    static final String WRONG_CODE_MESSAGE = "That code is wrong or has expired. Request a new one.";
    static final String THROTTLED_MESSAGE = "Too many attempts. Please wait a while and try again.";
    static final String UNAVAILABLE_MESSAGE =
            "Account recovery is not available right now. Contact help@fredvested.com.";

    private final AccountRecoveryService accountRecoveryService;
    private final ClientAddressResolver clientAddresses;

    public RecoveryController(AccountRecoveryService accountRecoveryService, ClientAddressResolver clientAddresses) {
        this.accountRecoveryService = accountRecoveryService;
        this.clientAddresses = clientAddresses;
    }

    @PostMapping("/initiate")
    public ResponseEntity<RecoveryResponse> initiateRecovery(
            @RequestBody(required = false) RecoveryInitiateRequest request, HttpServletRequest httpRequest) {
        String email = request == null ? null : request.getEmail();
        SendStatus status = accountRecoveryService.sendCode(email, clientAddresses.resolve(httpRequest));
        return switch (status) {
            case SENT -> ResponseEntity.ok(new RecoveryResponse(true, SENT_MESSAGE, null));
            case THROTTLED -> refused(HttpStatus.TOO_MANY_REQUESTS, THROTTLED_MESSAGE);
            case UNAVAILABLE -> refused(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE_MESSAGE);
        };
    }

    @PostMapping("/verify")
    public ResponseEntity<RecoveryResponse> verifyRecovery(
            @RequestBody(required = false) RecoveryVerifyRequest request, HttpServletRequest httpRequest) {
        String email = request == null ? null : request.getEmail();
        String code = request == null ? null : request.getOtp();
        VerifyOutcome outcome = accountRecoveryService.verifyCode(email, code, clientAddresses.resolve(httpRequest));
        return switch (outcome.status()) {
            case OK -> ResponseEntity.ok(new RecoveryResponse(true,
                    "Code accepted. Create a new passkey to finish.", outcome.recoveryToken()));
            case THROTTLED -> refused(HttpStatus.TOO_MANY_REQUESTS, THROTTLED_MESSAGE);
            case INVALID -> refused(HttpStatus.UNAUTHORIZED, WRONG_CODE_MESSAGE);
        };
    }

    private static ResponseEntity<RecoveryResponse> refused(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new RecoveryResponse(false, message, null));
    }
}
