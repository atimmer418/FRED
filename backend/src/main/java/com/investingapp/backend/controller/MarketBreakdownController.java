package com.investingapp.backend.controller;

import com.investingapp.backend.dto.MessageResponse;
import com.investingapp.backend.model.MarketBreakdown;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.MarketBreakdownRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.MarketBreakdownEmailComposer;
import com.investingapp.backend.service.MarketBreakdownService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;

/**
 * Verification surface for the Monthly Market Breakdown while the emailer is
 * disabled: preview renders the requesting user's own email HTML; generate is
 * config-gated (local/dev only) so production LLM calls can't be triggered
 * ad hoc.
 */
@RestController
@RequestMapping("/api/market-breakdown")
@CrossOrigin(origins = "*", maxAge = 3600)
public class MarketBreakdownController {

    private static final Logger logger = LoggerFactory.getLogger(MarketBreakdownController.class);

    private final MarketBreakdownRepository marketBreakdownRepository;
    private final MarketBreakdownService marketBreakdownService;
    private final MarketBreakdownEmailComposer composer;
    private final UserRepository userRepository;
    private final boolean manualGenerateEnabled;

    @Autowired
    public MarketBreakdownController(MarketBreakdownRepository marketBreakdownRepository,
            MarketBreakdownService marketBreakdownService,
            MarketBreakdownEmailComposer composer,
            UserRepository userRepository,
            @Value("${app.market-breakdown.manual-generate-enabled:false}") boolean manualGenerateEnabled) {
        this.marketBreakdownRepository = marketBreakdownRepository;
        this.marketBreakdownService = marketBreakdownService;
        this.composer = composer;
        this.userRepository = userRepository;
        this.manualGenerateEnabled = manualGenerateEnabled;
    }

    /**
     * Rendered email HTML for the requesting user. month defaults to the prior month.
     * produces lists JSON too so the mapping accepts Accept: application/json
     * clients (no 406). Error branches preset Content-Type: application/json —
     * without a preset, negotiation for Accept: * / * ties on the produces
     * declaration order, picks text/html, finds no converter for the
     * MessageResponse POJO, and 500s (HttpMessageNotWritableException),
     * masking the documented 400/404/401. The happy path pins text/html below.
     */
    @GetMapping(value = "/preview", produces = { MediaType.TEXT_HTML_VALUE, MediaType.APPLICATION_JSON_VALUE })
    public ResponseEntity<?> preview(@RequestParam(required = false) String month) {
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new MessageResponse("User not authenticated"));
            }
            YearMonth target = parseMonth(month);
            if (target == null) {
                return ResponseEntity.badRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new MessageResponse("month must look like 2026-06"));
            }

            var row = marketBreakdownRepository.findByPeriodKey(target.toString());
            if (row.isEmpty() || !MarketBreakdown.STATUS_GENERATED.equals(row.get().getStatus())) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new MessageResponse("No market breakdown generated for " + target + " yet."));
            }

            // Preview degrades to the market-only variant if this user's numbers
            // can't resolve right now (the real send records FAILED and retries)
            MarketBreakdownEmailComposer.UserMonthlyNumbers numbers = null;
            try {
                numbers = composer.resolveNumbers(user, target);
            } catch (Exception e) {
                logger.warn("Preview numbers unresolved for user {}: {}", user.getId(), e.getMessage());
            }
            MarketBreakdownEmailComposer.ComposedEmail email = composer.compose(row.get(), numbers);
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(email.htmlBody);
        } catch (Exception e) {
            logger.error("Market breakdown preview failed", e);
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new MessageResponse("Preview failed"));
        }
    }

    /** On-demand narrative generation — config-gated (local/dev only). */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestParam(required = false) String month) {
        if (!manualGenerateEnabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse("Manual generation is disabled in this environment."));
        }
        try {
            User user = getCurrentUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new MessageResponse("User not authenticated"));
            }
            YearMonth target = parseMonth(month);
            if (target == null) {
                return ResponseEntity.badRequest()
                        .body(new MessageResponse("month must look like 2026-06"));
            }
            MarketBreakdown breakdown = marketBreakdownService.getOrGenerate(target);
            return ResponseEntity.ok(new MessageResponse(
                    "Market breakdown for " + target + " is " + breakdown.getStatus()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new MessageResponse("Market breakdown can only be generated for completed months."));
        } catch (Exception e) {
            logger.error("Manual market breakdown generation failed", e);
            return ResponseEntity.internalServerError()
                    .body(new MessageResponse("Generation failed"));
        }
    }

    /** Default month = prior month in ET, matching the scheduler's clock. */
    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now(java.time.ZoneId.of("America/New_York")).minusMonths(1);
        }
        try {
            return YearMonth.parse(month);
        } catch (Exception e) {
            return null;
        }
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
