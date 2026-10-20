package com.investingapp.backend.controller;

import com.investingapp.backend.dto.CreateAlpacaAccountRequest;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.AlpacaApiService;
import com.investingapp.backend.service.KycSubmissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A second POST /create-account used to create a SECOND brokerage account at the broker and then
 * overwrite the stored account id with it, orphaning the first: a real account, belonging to a real
 * person, that our database no longer knew about.
 *
 * The expensive half is the broker call, not the local write, so the assertion that matters here is
 * that the broker is never called at all. Once it succeeds the duplicate exists whatever we do.
 */
class AlpacaControllerDuplicateAccountTest {

    private AlpacaApiService alpacaApiService;
    private UserRepository userRepository;
    private KycSubmissionService kycSubmissionService;
    private AlpacaController controller;

    private static final Long USER_ID = 42L;

    @BeforeEach
    void setUp() {
        alpacaApiService = mock(AlpacaApiService.class);
        userRepository = mock(UserRepository.class);
        kycSubmissionService = mock(KycSubmissionService.class);

        controller = new AlpacaController(
                alpacaApiService,
                mock(com.investingapp.backend.service.AlpacaService.class),
                mock(com.investingapp.backend.service.PlaidToAlpacaService.class),
                userRepository,
                mock(com.investingapp.backend.service.EncryptionService.class),
                kycSubmissionService);

        UserDetailsImpl principal = mock(UserDetailsImpl.class);
        when(principal.getId()).thenReturn(USER_ID);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private CreateAlpacaAccountRequest request() {
        CreateAlpacaAccountRequest request = new CreateAlpacaAccountRequest();
        request.setEmailAddress("beta.tester@fredvested.test");
        return request;
    }

    private User userWithAccount(String accountId) {
        User user = new User();
        user.setId(USER_ID);
        user.setAlpacaAccountId(accountId);
        return user;
    }

    @Test
    @DisplayName("a second submission is refused, and the broker is never called")
    void refusesASecondSubmission() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(userWithAccount("ENC:already-has-one")));

        ResponseEntity<Map<String, Object>> response =
                controller.createAccount(request(), new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "ACCOUNT_ALREADY_EXISTS");
        verifyNoInteractions(alpacaApiService);
        verifyNoInteractions(kycSubmissionService);
    }

    @Test
    @DisplayName("the first submission still goes through")
    void allowsTheFirstSubmission() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithAccount(null)));
        when(alpacaApiService.createAccount(any(), anyString()))
                .thenReturn(Map.of("id", "acct-1", "account_number", "123"));
        when(kycSubmissionService.persistSubmission(any(), any(), any())).thenReturn(true);

        ResponseEntity<Map<String, Object>> response =
                controller.createAccount(request(), new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(alpacaApiService).createAccount(any(), anyString());
        verify(kycSubmissionService).persistSubmission(eq(USER_ID), any(), any());
    }

    @Test
    @DisplayName("a blank stored account id is not a real account, so it does not block")
    void blankAccountIdDoesNotBlock() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithAccount("   ")));
        when(alpacaApiService.createAccount(any(), anyString()))
                .thenReturn(Map.of("id", "acct-1", "account_number", "123"));
        when(kycSubmissionService.persistSubmission(any(), any(), any())).thenReturn(true);

        ResponseEntity<Map<String, Object>> response =
                controller.createAccount(request(), new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(alpacaApiService).createAccount(any(), anyString());
    }

    @Test
    @DisplayName("an unknown user is left to the existing path rather than silently refused")
    void unknownUserFallsThrough() {
        // The guard is about duplicates, not about authentication. Refusing here would change an
        // unrelated failure mode, and persistSubmission already reports a user it cannot find.
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
        when(alpacaApiService.createAccount(any(), anyString()))
                .thenReturn(Map.of("id", "acct-1", "account_number", "123"));
        when(kycSubmissionService.persistSubmission(any(), any(), any())).thenReturn(false);

        ResponseEntity<Map<String, Object>> response =
                controller.createAccount(request(), new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(alpacaApiService).createAccount(any(), anyString());
    }
}
