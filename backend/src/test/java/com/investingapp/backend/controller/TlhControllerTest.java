package com.investingapp.backend.controller;

import com.investingapp.backend.dto.MessageResponse;
import com.investingapp.backend.dto.TlhSummaryResponse;
import com.investingapp.backend.model.TlhUserState;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.TlhUserStateRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.TlhHarvestService;
import com.investingapp.backend.service.TlhSummaryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TlhControllerTest {

    private TlhHarvestService harvestService;
    private TlhSummaryService summaryService;
    private TlhUserStateRepository stateRepository;
    private UserRepository userRepository;
    private com.investingapp.backend.service.AlpacaService alpacaService;
    private User user;

    @BeforeEach
    void setUp() {
        harvestService = mock(TlhHarvestService.class);
        summaryService = mock(TlhSummaryService.class);
        stateRepository = mock(TlhUserStateRepository.class);
        userRepository = mock(UserRepository.class);
        alpacaService = mock(com.investingapp.backend.service.AlpacaService.class);
        when(alpacaService.getMarketClock()).thenReturn(true);

        user = new User();
        user.setId(7L);
        user.setEmail("andy@fredvested.com");
        UserDetailsImpl principal = mock(UserDetailsImpl.class);
        when(principal.getId()).thenReturn(7L);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(principal);
        SecurityContextHolder.getContext().setAuthentication(auth);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(stateRepository.findByUserId(7L)).thenReturn(Optional.empty());
        when(stateRepository.save(any(TlhUserState.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private TlhController controller(boolean manualRunEnabled, String mode) {
        return new TlhController(harvestService, summaryService, stateRepository,
                userRepository, alpacaService, manualRunEnabled, mode);
    }

    @Test
    void unauthenticatedRequestsGet401() {
        SecurityContextHolder.clearContext();

        assertEquals(401, controller(false, "off").status().getStatusCode().value());
        assertEquals(401, controller(false, "off").enroll().getStatusCode().value());
        assertEquals(401, controller(false, "off").summary(null).getStatusCode().value());
    }

    @Test
    void nonProTiersGet403OnWriteEndpoints() {
        for (String tier : new String[] { null, "core", "plus" }) {
            user.setSelectedTier(tier);
            assertEquals(403, controller(true, "dry-run").enroll().getStatusCode().value(),
                    "enroll should 403 for tier=" + tier);
            assertEquals(403, controller(true, "dry-run").preview().getStatusCode().value(),
                    "preview should 403 for tier=" + tier);
            assertEquals(403, controller(true, "dry-run").run().getStatusCode().value(),
                    "run should 403 for tier=" + tier);
        }
    }

    @Test
    void tierMatchIsExactAndCaseSensitiveFailingClosed() {
        // An off-case 'Pro' from an unvalidated write path must NOT unlock trading
        user.setSelectedTier("Pro");

        assertEquals(403, controller(true, "dry-run").enroll().getStatusCode().value());
    }

    @Test
    void enrollSetsOptInAndCreatesBackfillingState() {
        user.setSelectedTier("pro");

        ResponseEntity<?> response = controller(false, "dry-run").enroll();

        assertEquals(200, response.getStatusCode().value());
        assertTrue(Boolean.TRUE.equals(user.getTlhEnabled()));
        assertNotNull(user.getTlhEnrolledAt());
        ArgumentCaptor<TlhUserState> captor = ArgumentCaptor.forClass(TlhUserState.class);
        verify(stateRepository).save(captor.capture());
        assertEquals(TlhUserState.LedgerStatus.BACKFILLING, captor.getValue().getLedgerStatus());
    }

    @Test
    void summaryAllowsDowngradedUserWithHistoryButNotWithout() {
        user.setSelectedTier(null); // lapsed subscription
        when(summaryService.getSummary(eq(user), anyInt())).thenReturn(new TlhSummaryResponse(
                2026, new BigDecimal("120.00"), BigDecimal.ZERO, new BigDecimal("28.80"),
                new BigDecimal("0.24"), 2, "disclaimer"));

        when(summaryService.hasTlhHistory(user)).thenReturn(true);
        ResponseEntity<?> withHistory = controller(false, "live").summary("2026");
        assertEquals(200, withHistory.getStatusCode().value());
        TlhSummaryResponse body = (TlhSummaryResponse) withHistory.getBody();
        assertEquals(0, body.harvestedLossYtd().compareTo(new BigDecimal("120.00")));

        when(summaryService.hasTlhHistory(user)).thenReturn(false);
        assertEquals(403, controller(false, "live").summary("2026").getStatusCode().value());
    }

    @Test
    void summaryRejectsUnparseableYear() {
        user.setSelectedTier("pro");

        ResponseEntity<?> response = controller(false, "live").summary("next-year");

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void eventsValidatesLimitBounds() {
        user.setSelectedTier("pro");

        assertEquals(400, controller(false, "live").events(0).getStatusCode().value());
        assertEquals(400, controller(false, "live").events(500).getStatusCode().value());
    }

    @Test
    void manualRunIsConfigGatedThenModeGated() {
        user.setSelectedTier("pro");
        user.setTlhEnabled(true);

        // Flag off ⇒ 403 regardless of tier (Market Breakdown generate pattern)
        ResponseEntity<?> flagOff = controller(false, "live").run();
        assertEquals(403, flagOff.getStatusCode().value());
        assertTrue(((MessageResponse) flagOff.getBody()).getMessage().contains("disabled"));

        // Mode off ⇒ 403 even with the flag on
        assertEquals(403, controller(true, "off").run().getStatusCode().value());
        verify(harvestService, never()).runCycleForUser(any(), any(), anyString());
    }

    @Test
    void manualRunExecutesForEnrolledPro() {
        user.setSelectedTier("pro");
        user.setTlhEnabled(true);
        when(harvestService.runCycleForUser(eq(user), any(), eq("MANUAL")))
                .thenReturn(new TlhHarvestService.UserCycleResult(1, 1, 0, false));

        ResponseEntity<?> response = controller(true, "dry-run").run();

        assertEquals(200, response.getStatusCode().value());
        verify(harvestService).runCycleForUser(eq(user), any(), eq("MANUAL"));
    }

    @Test
    void liveManualRunFailsClosedWhenMarketIsShut() {
        user.setSelectedTier("pro");
        user.setTlhEnabled(true);
        when(alpacaService.getMarketClock()).thenReturn(false);

        assertEquals(400, controller(true, "live").run().getStatusCode().value());
        verify(harvestService, never()).runCycleForUser(any(), any(), anyString());

        // Dry-run manual runs don't need the clock — no orders are possible
        when(harvestService.runCycleForUser(eq(user), any(), eq("MANUAL")))
                .thenReturn(new TlhHarvestService.UserCycleResult(0, 0, 0, false));
        assertEquals(200, controller(true, "dry-run").run().getStatusCode().value());
    }

    @Test
    void manualRunRequiresEnrollment() {
        user.setSelectedTier("pro");
        user.setTlhEnabled(false);

        assertEquals(400, controller(true, "dry-run").run().getStatusCode().value());
        verify(harvestService, never()).runCycleForUser(any(), any(), anyString());
    }

    @Test
    void unenrollKeepsWindowsEnforcedMessaging() {
        user.setSelectedTier("pro");
        user.setTlhEnabled(true);

        ResponseEntity<?> response = controller(false, "live").unenroll();

        assertEquals(200, response.getStatusCode().value());
        assertFalse(user.getTlhEnabled());
        assertTrue(((MessageResponse) response.getBody()).getMessage().contains("wash-sale windows"));
    }
}
