# Task 16 appendix: full code for the compressed steps

This file completes `task-16-status-and-update-mode-endpoints.md`; read both, and where they differ the main file's code governs. It writes out whole the three classes the main file gives with `// [ ... ]` back-references: `PaycheckDetectionControllerTest` (Step 6), `PlaidControllerUpdateModeCompleteTest` (Step 11) and `PlaidController.java` as it stands after Steps 10 and 11 (the `completeUpdateMode` body, its import, field and the two `primary-bank-account` lines). It also expands the `pkg.{A, B}` import lists of Steps 2, 3, 4, 5, 7, 8 and 9 into the literal package and import header that goes above each of those blocks, so no import has to be worked out. Nothing here changes behaviour or adds scope: every name, signature, constant, endpoint path, error code, test name and count is the main file's. Paths use the main file's shorthand: `M/` = `backend/src/main/java/com/investingapp/backend/`, `T/` = `backend/src/test/java/com/investingapp/backend/`.

## Step 6: `T/controller/PaycheckDetectionControllerTest.java`, whole

The six lines the main file elides in `setUp()` are the setter lines of `InitSyncEnqueuerTest.eligibleUser()` (Step 4) with `user.` in place of `u.`. The class has 3 tests (`responseNeverWaitsOnPlaid`, `unauthenticatedIs401`, `unknownUserIs404`), as Step 7 and the Verification block expect.

```java
package com.investingapp.backend.controller;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.dto.PaycheckDetectionStatusResponse;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.InitSyncEnqueuer;
import com.investingapp.backend.service.PaycheckDetectionStatusService;
import com.investingapp.backend.service.PaycheckSyncPipeline;
import com.investingapp.backend.support.RecordingTransactionManager;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaycheckDetectionControllerTest {

    private static final String EMAIL = "status@fredvested.com";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final InvestmentScheduleRepository scheduleRepository = mock(InvestmentScheduleRepository.class);
    private final PaycheckSyncPipeline pipeline = mock(PaycheckSyncPipeline.class);
    private final PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
    private final List<Runnable> queued = new ArrayList<>();
    private PaycheckDetectionStatusService statusService;
    private User user;

    @BeforeEach
    void setUp() {
        when(props.isEnabled()).thenReturn(true);
        when(props.getAffordabilityShare()).thenReturn(new BigDecimal("0.90"));
        statusService = new PaycheckDetectionStatusService(scheduleRepository, mock(PaycheckStreamRepository.class), props);
        user = new User(EMAIL);
        user.setId(7L);
        user.setAccountStatus("ACTIVE");
        user.setPlaidAccessToken("ENC:token");
        user.setPlaidItemId("item-1");
        user.setPlaidItemStatus(PlaidItemStatus.OK);
        user.setPaycheckAutopilotOptOut(false);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
    }

    private static Authentication authentication() {
        return new UsernamePasswordAuthenticationToken(new UserDetailsImpl(7L, EMAIL, List.of()), null, List.of());
    }

    private PaycheckDetectionController controller() {
        InitSyncEnqueuer enqueuer = new InitSyncEnqueuer(scheduleRepository, userRepository, pipeline, props,
                new RecordingTransactionManager(), queued::add);
        ReflectionTestUtils.setField(enqueuer, "entityManager", mock(EntityManager.class));
        return new PaycheckDetectionController(userRepository, enqueuer, statusService);
    }

    @Test
    void responseNeverWaitsOnPlaid() {
        InvestmentSchedule row = new InvestmentSchedule();
        row.setId(70L);
        row.setUser(user);
        row.setMode(ScheduleMode.MANUAL);
        row.setStatus(InvestmentSchedule.Status.ACTIVE);
        row.setInvestmentAmount(new BigDecimal("100.00"));
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(row));

        ResponseEntity<?> response = controller().status(authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PaycheckDetectionStatusResponse body = (PaycheckDetectionStatusResponse) response.getBody();
        assertThat(body.state()).isEqualTo("SEARCHING");
        assertThat(body.detectionStartedAt()).isNotNull();
        assertThat(queued).hasSize(1);
        verifyNoInteractions(pipeline);
    }

    @Test
    void unauthenticatedIs401() {
        assertThat(controller().status(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownUserIs404() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThat(controller().status(authentication()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(queued).isEmpty();
    }
}
```

## Step 11: `T/controller/PlaidControllerUpdateModeCompleteTest.java`, whole

The two tests the main file elides are `unauthenticatedIs401` and `unknownUserIs404` of Step 6, calling `controller.completeUpdateMode(...)` in place of `controller().status(...)`, each ending with `verifyNoInteractions(updateModeService)` (in the 404 test that line takes the place of the queue check). The class has 4 tests (`resultsMapToStatusCodes`, `noItemIs409UpdateModeUnavailable`, `unauthenticatedIs401`, `unknownUserIs404`), as Step 11 and the Verification block expect.

```java
package com.investingapp.backend.controller;

import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.PlaidUpdateModeService;
import com.investingapp.backend.service.PlaidUpdateModeService.UpdateModeResult;
import com.investingapp.backend.service.UpdateModeUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaidControllerUpdateModeCompleteTest {

    private static final String EMAIL = "reconnect@fredvested.com";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PlaidUpdateModeService updateModeService = mock(PlaidUpdateModeService.class);
    private PlaidController controller;
    private User user;

    @BeforeEach
    void setUp() {
        controller = new PlaidController();
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "plaidUpdateModeService", updateModeService);
        user = new User(EMAIL);
        user.setId(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
    }

    private static Authentication authentication() {
        return new UsernamePasswordAuthenticationToken(new UserDetailsImpl(7L, EMAIL, List.of()), null, List.of());
    }

    private String code(ResponseEntity<?> response) {
        return ((ErrorCodeResponse) response.getBody()).code();
    }

    @Test
    void resultsMapToStatusCodes() {
        for (UpdateModeResult ok : List.of(UpdateModeResult.CONSENT_GRANTED, UpdateModeResult.CONSENT_STILL_MISSING,
                UpdateModeResult.REPAIRED, UpdateModeResult.NO_CHANGE)) {
            when(updateModeService.complete(user)).thenReturn(ok);
            ResponseEntity<?> response = controller.completeUpdateMode(authentication());
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(Map.of("result", ok.name()));
        }
        when(updateModeService.complete(user)).thenReturn(UpdateModeResult.STALE_ITEM);
        ResponseEntity<?> stale = controller.completeUpdateMode(authentication());
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(stale)).isEqualTo("STALE_ITEM");

        when(updateModeService.complete(user)).thenReturn(UpdateModeResult.PLAID_ERROR);
        ResponseEntity<?> plaid = controller.completeUpdateMode(authentication());
        assertThat(plaid.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(code(plaid)).isEqualTo("PLAID_ERROR");
    }

    @Test
    void noItemIs409UpdateModeUnavailable() {
        when(updateModeService.complete(user)).thenThrow(new UpdateModeUnavailableException("No Plaid Item"));

        ResponseEntity<?> response = controller.completeUpdateMode(authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(response)).isEqualTo("UPDATE_MODE_UNAVAILABLE");
    }

    @Test
    void unauthenticatedIs401() {
        assertThat(controller.completeUpdateMode(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(updateModeService);
    }

    @Test
    void unknownUserIs404() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThat(controller.completeUpdateMode(authentication()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(updateModeService);
    }
}
```

## Steps 10 and 11: `M/controller/PlaidController.java`, whole, at the end of the task

This is HEAD 137998c (151 lines) with the main file's four edits applied and nothing else changed: the `PlaidUpdateModeService` import after HEAD `:10` (Step 11), the `EasternTime` import after HEAD `:11` (Step 10), the `plaidUpdateModeService` field after HEAD `:39` (Step 11), `completeUpdateMode` after HEAD `:120` (Step 11) and the two `bankAccount.put` lines with their comment after HEAD `:143` (Step 10). The eight lines the main file elides at the top of `completeUpdateMode` are lines 1 to 8 of the body of `PaycheckDetectionController.status` (Step 7), verbatim. Every other name the method uses (`UserDetailsImpl`, `User`, `MessageResponse`, `ErrorCodeResponse`, `UpdateModeUnavailableException`, `HttpStatus`, `ResponseEntity`, `Authentication`, `Map`, `@PostMapping` through `org.springframework.web.bind.annotation.*`, and `logger`) is already imported or declared at HEAD. The file is 191 lines.

```java
package com.investingapp.backend.controller;

import com.investingapp.backend.dto.MessageResponse;
import com.investingapp.backend.dto.CreateLinkTokenRequest;
import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.service.LinkInProgressException;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.PlaidService;
import com.investingapp.backend.service.PlaidUpdateModeService;
import com.investingapp.backend.service.UpdateModeUnavailableException;
import com.investingapp.backend.util.EasternTime;
import com.plaid.client.model.ItemPublicTokenExchangeResponse; // Make sure this is imported
import com.plaid.client.model.LinkTokenCreateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@CrossOrigin(origins = "*", maxAge = 3600) // For development, restrict in production
@RestController
@RequestMapping("/api/plaid")
public class PlaidController {

    private static final Logger logger = LoggerFactory.getLogger(PlaidController.class);

    @Autowired
    private PlaidService plaidService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlaidUpdateModeService plaidUpdateModeService;

    // --- AUTHENTICATED FLOW ENDPOINTS ---
    
    @PostMapping("/create_link_token")
    public ResponseEntity<?> createLinkTokenAuthenticated(@RequestBody(required = false) CreateLinkTokenRequest body) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof UserDetailsImpl)) {
            logger.warn("/create_link_token: User not authenticated properly.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse("User not authenticated"));
        }
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();

        PlaidService.LinkMode mode;
        try {
            mode = PlaidService.LinkMode.fromRequest(body == null ? null : body.getMode());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new MessageResponse(e.getMessage()));
        }
        logger.info("Request received for /create_link_token (mode={}). User ID: {}", mode, userDetails.getId());

        User user = userRepository.findById(userDetails.getId()).orElse(null);
        if (user == null) {
            logger.error("/create_link_token: Authenticated user ID {} not found in database.", userDetails.getId());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse("User not found in database"));
        }

        try {
            LinkTokenCreateResponse response = plaidService.createLinkToken(user, mode);
            return ResponseEntity.ok(Map.of(
                    "link_token", response.getLinkToken(),
                    "expiration", response.getExpiration().toString()));
        } catch (UpdateModeUnavailableException e) {
            logger.warn("/create_link_token: update mode unavailable for user {}: {}", user.getId(), e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorCodeResponse(
                    "UPDATE_MODE_UNAVAILABLE", "Plaid could not reopen this bank connection. Link the bank again."));
        } catch (IOException e) {
            logger.error("Error creating Plaid link token for user {}: {}", user.getId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Error creating Plaid link token: " + e.getMessage()));
        }
    }

    @PostMapping("/exchange_public_token") // No suffix, implies authenticated
    public ResponseEntity<?> exchangePublicTokenAuthenticated(@RequestBody Map<String, String> payload) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof UserDetailsImpl)) {
            logger.warn("/exchange_public_token: User not authenticated properly.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse("User not authenticated"));
        }
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        User appUser = userRepository.findById(userDetails.getId())
                .orElse(null);

        if (appUser == null) {
            logger.error("/exchange_public_token: Authenticated user ID {} not found in database.", userDetails.getId());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse("User not found in database"));
        }
        logger.info("Request received for /exchange_public_token (authenticated). User ID: {}", appUser.getId());

        String publicToken = payload.get("public_token");
        if (publicToken == null || publicToken.isEmpty()) {
            logger.warn("/exchange_public_token: public_token is required for user ID {}.", appUser.getId());
            return ResponseEntity.badRequest().body(new MessageResponse("public_token is required"));
        }

        try {
            // This service method directly links to the authenticated appUser
            ItemPublicTokenExchangeResponse plaidResponse = plaidService.exchangePublicTokenAndLinkUser(publicToken, appUser);
            // The service method already updates the user and saves.

            return ResponseEntity.ok(new MessageResponse("Plaid public token exchanged successfully and linked to user."));
        } catch (LinkInProgressException e) {
            logger.info("/exchange_public_token: user {} already has a link in progress", appUser.getId());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorCodeResponse("LINK_IN_PROGRESS", "A bank link is already in progress"));
        } catch (IOException e) {
            logger.error("Error exchanging Plaid public token for user {}: {}", appUser.getId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Error exchanging Plaid public token: " + e.getMessage()));
        }
    }

    /** After an update-mode Link onSuccess (spec 2.3, 8.8): no body, no exchange. */
    @PostMapping("/update-mode-complete")
    public ResponseEntity<?> completeUpdateMode(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserDetailsImpl userDetails)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new MessageResponse("User not authenticated"));
        }
        User user = userRepository.findById(userDetails.getId()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new MessageResponse("User not found in database"));
        }
        try {
            PlaidUpdateModeService.UpdateModeResult result = plaidUpdateModeService.complete(user);
            return switch (result) {
                case STALE_ITEM -> ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorCodeResponse(
                        "STALE_ITEM", "This bank connection changed while you were reconnecting. Try again."));
                case PLAID_ERROR -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorCodeResponse(
                        "PLAID_ERROR", "Plaid could not confirm the bank connection. Try again in a moment."));
                default -> ResponseEntity.ok(Map.of("result", result.name()));
            };
        } catch (UpdateModeUnavailableException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorCodeResponse(
                    "UPDATE_MODE_UNAVAILABLE", "Plaid could not reopen this bank connection. Link the bank again."));
        } catch (RuntimeException e) {
            logger.error("plaid.update_mode.failed user_id={} error_class={}", user.getId(), e.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new MessageResponse("Error completing the bank reconnection"));
        }
    }

    @GetMapping("/primary-bank-account")
    public ResponseEntity<?> getPrimaryBankAccount(Authentication authentication) {
        try {
            UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
            Optional<User> userOpt = userRepository.findByEmail(userDetails.getUsername());

            if (userOpt.isEmpty()) {
                return ResponseEntity.badRequest().body("User not found");
            }

            User user = userOpt.get();

            // Display fields only. The Plaid access token never leaves the server (FRED-218).
            // HashMap rather than Map.of: mask is null for links made before it was stored.
            Map<String, Object> bankAccount = new HashMap<>();
            bankAccount.put("accountId", user.getPlaidAccountId());
            bankAccount.put("institutionName", user.getPlaidInstitutionName());
            bankAccount.put("accountName", user.getPlaidAccountName());
            bankAccount.put("accountType", user.getPlaidAccountType());
            bankAccount.put("accountSubtype", user.getPlaidAccountSubtype());
            bankAccount.put("mask", user.getPlaidAccountMask());
            bankAccount.put("itemStatus", user.getPlaidItemStatus().name());
            // Phase 1 (spec 8.8, R10): the reconnect card reads both. consentExpiresAt is
            // users.plaid_disconnect_at, an Eastern wall time, sent with its offset.
            bankAccount.put("transactionsConsented", user.getPlaidTransactionsConsented());
            bankAccount.put("consentExpiresAt", user.getPlaidDisconnectAt() == null ? null
                    : user.getPlaidDisconnectAt().atZone(EasternTime.ZONE).toOffsetDateTime().toString());

            return ResponseEntity.ok(bankAccount);
        } catch (Exception e) {
            logger.error("Error retrieving primary bank account", e);
            return ResponseEntity.status(500).body("Error retrieving bank account");
        }
    }
}
```

## Steps 2, 3, 4, 5, 7, 8 and 9: the import header above each block

Each block below is the literal start of its file: the `package` line and the imports the main file lists as `pkg.{A, B}` groups, one `import` per name, plus the three standard test imports for test classes. The main file's code block for that step follows it, starting from the class's Javadoc or declaration (drop the block's own `package` line, which is repeated here). The types the blocks use without an import (`DetectionEligibility`, `PaycheckSyncPipeline`, `PlaidItemStateService`, `EncryptionService`, `UpdateModeUnavailableException`, `InitSyncEnqueuer`, `PaycheckDetectionStatusService`, `PlaidUpdateModeService`) are all in `com.investingapp.backend.service`, the package of every file below that uses them (Steps 2, 3's service, 4, 5, 8 and 9), so none is missing.

### Step 2: `T/service/PaycheckDetectionStatusServiceTest.java`

```java
package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.dto.PaycheckDetectionStatusResponse;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckCadenceSource;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
```

### Step 3: `M/dto/PaycheckDetectionStatusResponse.java`

```java
package com.investingapp.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
```

### Step 3: `M/service/PaycheckDetectionStatusService.java`

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.dto.PaycheckDetectionStatusResponse;
import com.investingapp.backend.dto.PaycheckDetectionStatusResponse.ManualPrefillView;
import com.investingapp.backend.dto.PaycheckDetectionStatusResponse.PrimaryStreamView;
import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.model.InvestmentFrequency;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PaycheckStream;
import com.investingapp.backend.model.PaycheckStreamState;
import com.investingapp.backend.model.User;
import com.investingapp.backend.paycheck.detect.AmountPrefill;
import com.investingapp.backend.paycheck.detect.CadenceResult;
import com.investingapp.backend.paycheck.detect.PayCalendar;
import com.investingapp.backend.paycheck.detect.PrimarySelector;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.util.EasternTime;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
```

### Step 4: `T/service/InitSyncEnqueuerTest.java`

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.support.RecordingTransactionManager;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
```

### Step 5: `M/service/InitSyncEnqueuer.java`

```java
package com.investingapp.backend.service;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.util.EasternTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
```

### Step 8: `T/service/PlaidUpdateModeServiceTest.java`

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.plaid.PlaidApiError;
import com.investingapp.backend.plaid.PlaidCallResult;
import com.investingapp.backend.plaid.PlaidErrorClass;
import com.investingapp.backend.plaid.PlaidGateway;
import com.investingapp.backend.service.PlaidItemStateService.ItemStateResult;
import com.investingapp.backend.service.PlaidItemStateService.RepairPath;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.investingapp.backend.service.PlaidUpdateModeService.UpdateModeResult;
import com.plaid.client.model.ItemGetResponse;
import com.plaid.client.model.ItemWebhookUpdateResponse;
import com.plaid.client.model.ItemWithConsentFields;
import com.plaid.client.model.PlaidError;
import com.plaid.client.model.Products;
import com.plaid.client.request.PlaidApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
```

### Step 9: `M/service/PlaidUpdateModeService.java`

```java
package com.investingapp.backend.service;

import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.plaid.PlaidCallResult;
import com.investingapp.backend.plaid.PlaidGateway;
import com.investingapp.backend.service.PlaidItemStateService.ItemStateResult;
import com.investingapp.backend.service.PlaidItemStateService.RepairPath;
import com.investingapp.backend.service.PlaidTransactionsSyncService.SyncReason;
import com.plaid.client.model.ItemGetRequest;
import com.plaid.client.model.ItemGetResponse;
import com.plaid.client.model.ItemWebhookUpdateRequest;
import com.plaid.client.model.ItemWebhookUpdateResponse;
import com.plaid.client.model.ItemWithConsentFields;
import com.plaid.client.model.Products;
import com.plaid.client.request.PlaidApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executor;
```

### Step 7: `M/controller/PaycheckDetectionController.java`

```java
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
```

## Disagreements found while writing this

The main file has no table standing in for code (its one table-shaped block, the Step 12 `API_ENDPOINTS.md` rows, is the literal text to paste, and it agrees with the Step 11 code: `CONSENT_GRANTED`, `CONSENT_STILL_MISSING`, `REPAIRED`, `NO_CHANGE`; 409 `STALE_ITEM` and `UPDATE_MODE_UNAVAILABLE`; 502 `PLAID_ERROR`). The code here follows the main file's code in every case below.

1. Fact 6 says "user ids on WARN only", but the Step 11 `completeUpdateMode` code logs `plaid.update_mode.failed user_id={} error_class={}` at ERROR. The Global Constraints allow ids at INFO and above, so the line is within the plan's rules; the code is kept as written.
2. The design entry for T16 places `POST /update-mode-complete` "after 119"; the main file says after HEAD `:120`. HEAD line 120 is the `}` closing `exchangePublicTokenAuthenticated` (line 119 is the `}` closing its `catch`), so the main file is right.
3. The design entry says "consent still FALSE takes `onConsentRequired()`"; the main file's Fact 4 and Step 9 code also call it when the stored flag was TRUE and `/item/get` no longer lists `TRANSACTIONS` (the scope was dropped inside update mode), and its test `absentConsentIsMissingUnlessTheFlagWasUnknown` pins that. The main file's code governs.
