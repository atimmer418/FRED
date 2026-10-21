package com.investingapp.backend.controller;

import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.AlpacaService;
import com.investingapp.backend.service.AnalyticsOutboxService;
import com.investingapp.backend.service.DeletionDeferredException;
import com.investingapp.backend.service.EncryptionService;
import com.investingapp.backend.service.UserDeletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Close-account must refuse BEFORE closing the brokerage account: a closed Alpaca account with a
 * surviving FRED row is a half-deleted person nobody can finish.
 *
 * <p>The analytics collaborators are wired because closeAccount records schedule_cancelled on its
 * way through; what that event must and must not say is
 * {@link TradingControllerScheduleCancelledTest}'s subject, not this class's.
 */
class TradingControllerCloseAccountTest {

    private static final String EMAIL = "close@fredvested.com";

    private TradingController controller;
    private AlpacaService alpacaService;
    private UserDeletionService userDeletionService;
    private AnalyticsOutboxService analyticsOutboxService;
    private Authentication authentication;
    private User user;

    @BeforeEach
    void setUp() {
        controller = new TradingController();
        UserRepository userRepository = mock(UserRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        alpacaService = mock(AlpacaService.class);
        userDeletionService = mock(UserDeletionService.class);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "encryptionService", encryptionService);
        ReflectionTestUtils.setField(controller, "alpacaService", alpacaService);
        ReflectionTestUtils.setField(controller, "userDeletionService", userDeletionService);
        analyticsOutboxService = mock(AnalyticsOutboxService.class);
        ReflectionTestUtils.setField(controller, "investmentScheduleRepository",
                mock(InvestmentScheduleRepository.class));
        ReflectionTestUtils.setField(controller, "analyticsOutboxService", analyticsOutboxService);

        user = new User(EMAIL);
        user.setId(42L);
        user.setAlpacaAccountId("ENC:acct");

        UserDetails principal = mock(UserDetails.class);
        when(principal.getUsername()).thenReturn(EMAIL);
        authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(encryptionService.decrypt("ENC:acct")).thenReturn("acct");
    }

    @Test
    @DisplayName("a deferred deletion answers 409 and never closes the brokerage account")
    void deferredIs409AndTheBrokerIsNeverCalled() {
        when(userDeletionService.deferralReason(user)).thenReturn(Optional.of("FUNDING_INITIATED"));

        ResponseEntity<?> response = controller.closeAccount(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(new ErrorCodeResponse("DELETION_DEFERRED", "FUNDING_INITIATED"));
        verify(alpacaService, never()).closeAccount(anyString());
        verify(userDeletionService, never()).delete(any(), any(), any());
    }

    @Test
    @DisplayName("closes the brokerage account, then deletes through the one path")
    @SuppressWarnings("unchecked")
    void closesTheBrokerAccountThenDeletes() {
        when(userDeletionService.deferralReason(user)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.closeAccount(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("success", true);
        InOrder order = inOrder(alpacaService, userDeletionService);
        order.verify(alpacaService).closeAccount("acct");
        order.verify(userDeletionService).delete(eq(user),
                eq(UserDeletionService.DeletionReason.ACCOUNT_CLOSED), any(Runnable.class));
    }

    @Test
    @DisplayName("a deferral raised by the delete itself still answers 409, not 500")
    void lateDeferralIs409() {
        when(userDeletionService.deferralReason(user)).thenReturn(Optional.empty());
        doThrow(new DeletionDeferredException("SETTLEMENT_WINDOW"))
                .when(userDeletionService).delete(eq(user),
                        eq(UserDeletionService.DeletionReason.ACCOUNT_CLOSED), any(Runnable.class));

        ResponseEntity<?> response = controller.closeAccount(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(new ErrorCodeResponse("DELETION_DEFERRED", "SETTLEMENT_WINDOW"));
        // The deferral is raised before the hook runs, so no cancellation was recorded for a
        // deletion that did not happen. That is the whole point of handing the event to the
        // deletion transaction instead of committing it here first.
        verify(analyticsOutboxService, never()).record(anyString(), any(), anyMap());
    }
}
