package com.investingapp.backend.controller;

import com.investingapp.backend.dto.MessageResponse;
import com.investingapp.backend.model.TlhUserState;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.TlhUserStateRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.TlhHarvestService;
import com.investingapp.backend.service.TlhSummaryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Year;
import java.time.ZonedDateTime;

/**
 * Tax Loss Harvesting endpoints — Piggy Pro gated.
 *
 * Tier guard: FRED's first per-request tier check, inline per house
 * convention (no aspects / @PreAuthorize). Write/trigger endpoints (enroll,
 * preview, run) require selectedTier == "pro" EXACTLY (case-sensitive,
 * matching the scheduler query's semantics — an off-case value fails closed).
 * Read endpoints (status, summary, events) allow pro OR any user with TLH
 * history: a lapsed subscriber must be able to see why a surviving wash-sale
 * window is still redirecting their buys, and read audit rows about their own
 * executed trades.
 *
 * Routes are under anyRequest().authenticated() — no SecurityConfig change.
 */
@RestController
@RequestMapping("/api/tlh")
@CrossOrigin(origins = "*", maxAge = 3600)
public class TlhController {

    private static final Logger logger = LoggerFactory.getLogger(TlhController.class);
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final TlhHarvestService harvestService;
    private final TlhSummaryService summaryService;
    private final TlhUserStateRepository userStateRepository;
    private final UserRepository userRepository;
    private final com.investingapp.backend.service.AlpacaService alpacaService;
    private final boolean manualRunEnabled;
    private final String mode;

    @Autowired
    public TlhController(TlhHarvestService harvestService,
            TlhSummaryService summaryService,
            TlhUserStateRepository userStateRepository,
            UserRepository userRepository,
            com.investingapp.backend.service.AlpacaService alpacaService,
            @Value("${app.tlh.manual-run-enabled:false}") boolean manualRunEnabled,
            @Value("${app.tlh.mode:off}") String mode) {
        this.harvestService = harvestService;
        this.summaryService = summaryService;
        this.userStateRepository = userStateRepository;
        this.userRepository = userRepository;
        this.alpacaService = alpacaService;
        this.manualRunEnabled = manualRunEnabled;
        this.mode = mode;
    }

    /** Enrollment + ledger + active wash-window status. Access: pro OR has history. */
    @GetMapping("/status")
    public ResponseEntity<?> status() {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return unauthorized();
            }
            if (!isPro(user) && !summaryService.hasTlhHistory(user)) {
                return proRequired();
            }
            return ResponseEntity.ok(summaryService.getStatus(user, isPro(user)));
        } catch (Exception e) {
            logger.error("TLH status failed", e);
            return ResponseEntity.internalServerError()
                    .body(new MessageResponse("TLH status failed: " + e.getMessage()));
        }
    }

    /** Explicit opt-in on top of tier — tier alone never sells. Pro only. */
    @PostMapping("/enroll")
    public ResponseEntity<?> enroll() {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return unauthorized();
            }
            if (!isPro(user)) {
                return proRequired();
            }
            user.setTlhEnabled(true);
            if (user.getTlhEnrolledAt() == null) {
                user.setTlhEnrolledAt(LocalDateTime.now(ET));
            }
            userRepository.save(user);
            // Ledger backfill runs on poller ticks, never in this request
            userStateRepository.findByUserId(user.getId())
                    .orElseGet(() -> userStateRepository.save(new TlhUserState(user.getId())));
            return ResponseEntity.ok(new MessageResponse(
                    "Enrolled. Your trade-history ledger is being built; harvesting begins once it is reconciled."));
        } catch (Exception e) {
            logger.error("TLH enroll failed", e);
            return ResponseEntity.internalServerError()
                    .body(new MessageResponse("TLH enroll failed: " + e.getMessage()));
        }
    }

    /** Unenroll. Active wash-sale windows stay enforced; in-flight harvests complete. */
    @DeleteMapping("/enroll")
    public ResponseEntity<?> unenroll() {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return unauthorized();
            }
            if (!isPro(user) && !Boolean.TRUE.equals(user.getTlhEnabled())) {
                return proRequired();
            }
            user.setTlhEnabled(false);
            userRepository.save(user);
            return ResponseEntity.ok(new MessageResponse(
                    "Tax loss harvesting is off. Any active wash-sale windows remain enforced until they "
                    + "expire, and an in-progress harvest will finish safely."));
        } catch (Exception e) {
            logger.error("TLH unenroll failed", e);
            return ResponseEntity.internalServerError()
                    .body(new MessageResponse("TLH unenroll failed: " + e.getMessage()));
        }
    }

    /** "Saved this year" — the my-profile feed. Access: pro OR has history. */
    @GetMapping("/summary")
    public ResponseEntity<?> summary(@RequestParam(required = false) String year) {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return unauthorized();
            }
            if (!isPro(user) && !summaryService.hasTlhHistory(user)) {
                return proRequired();
            }
            int targetYear;
            if (year == null || year.isBlank()) {
                targetYear = Year.now(ET).getValue();
            } else {
                try {
                    targetYear = Integer.parseInt(year.trim());
                } catch (NumberFormatException e) {
                    return ResponseEntity.badRequest()
                            .body(new MessageResponse("year must look like 2026"));
                }
            }
            return ResponseEntity.ok(summaryService.getSummary(user, targetYear));
        } catch (Exception e) {
            logger.error("TLH summary failed", e);
            return ResponseEntity.internalServerError()
                    .body(new MessageResponse("TLH summary failed: " + e.getMessage()));
        }
    }

    /** Harvest-event audit history. Access: pro OR has history. */
    @GetMapping("/events")
    public ResponseEntity<?> events(@RequestParam(defaultValue = "50") int limit) {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return unauthorized();
            }
            if (!isPro(user) && !summaryService.hasTlhHistory(user)) {
                return proRequired();
            }
            if (limit < 1 || limit > 200) {
                return ResponseEntity.badRequest()
                        .body(new MessageResponse("limit must be between 1 and 200"));
            }
            return ResponseEntity.ok(summaryService.getEvents(user, limit));
        } catch (Exception e) {
            logger.error("TLH events failed", e);
            return ResponseEntity.internalServerError()
                    .body(new MessageResponse("TLH events failed: " + e.getMessage()));
        }
    }

    /** What would the next cycle do? No orders, no persistence. Pro + enrolled. */
    @GetMapping("/preview")
    public ResponseEntity<?> preview() {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return unauthorized();
            }
            if (!isPro(user)) {
                return proRequired();
            }
            if (!Boolean.TRUE.equals(user.getTlhEnabled())) {
                return ResponseEntity.badRequest()
                        .body(new MessageResponse("Enroll in tax loss harvesting first."));
            }
            return ResponseEntity.ok(harvestService.previewForUser(user));
        } catch (Exception e) {
            logger.error("TLH preview failed", e);
            return ResponseEntity.internalServerError()
                    .body(new MessageResponse("TLH preview failed: " + e.getMessage()));
        }
    }

    /**
     * Manual own-account cycle trigger — config-gated per the Market Breakdown
     * manual-generate precedent (local/dev verification surface).
     */
    @PostMapping("/run")
    public ResponseEntity<?> run() {
        if (!manualRunEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse("Manual TLH runs are disabled in this environment."));
        }
        try {
            User user = getCurrentUser();
            if (user == null) {
                return unauthorized();
            }
            if (!isPro(user)) {
                return proRequired();
            }
            if (!Boolean.TRUE.equals(user.getTlhEnabled())) {
                return ResponseEntity.badRequest()
                        .body(new MessageResponse("Enroll in tax loss harvesting first."));
            }
            if ("off".equalsIgnoreCase(mode)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new MessageResponse("TLH mode is off in this environment."));
            }
            // A live manual run must never rest a market order overnight —
            // same fail-closed clock gate as the scheduled cycle.
            if ("live".equalsIgnoreCase(mode) && !alpacaService.getMarketClock()) {
                return ResponseEntity.badRequest()
                        .body(new MessageResponse("Market is closed. Try again during market hours."));
            }
            TlhHarvestService.UserCycleResult result = harvestService.runCycleForUser(
                    user, ZonedDateTime.now(ET).toLocalDate(), "MANUAL");
            return ResponseEntity.ok(new MessageResponse(String.format(
                    "TLH run complete: %d harvested, %d vetoed%s.",
                    result.harvested(), result.vetoed(),
                    result.skipped() ? " (cycle skipped: ledger busy or backfilling)" : "")));
        } catch (Exception e) {
            logger.error("Manual TLH run failed", e);
            return ResponseEntity.internalServerError()
                    .body(new MessageResponse("TLH run failed: " + e.getMessage()));
        }
    }

    // ==================== Guards ====================

    /**
     * Exact-match tier check, consistent with the scheduler query's
     * case-sensitive semantics: an off-case 'Pro' from an unvalidated write
     * path fails CLOSED (the safe direction).
     */
    private boolean isPro(User user) {
        return user != null && "pro".equals(user.getSelectedTier());
    }

    private ResponseEntity<MessageResponse> proRequired() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new MessageResponse("Tax loss harvesting requires Piggy Pro."));
    }

    private ResponseEntity<MessageResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new MessageResponse("User not authenticated"));
    }

    private User getCurrentUser() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !(auth.getPrincipal() instanceof UserDetailsImpl)) {
                return null;
            }
            UserDetailsImpl userDetails = (UserDetailsImpl) auth.getPrincipal();
            return userRepository.findById(userDetails.getId()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
