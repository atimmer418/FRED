package com.investingapp.backend.controller;

import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.AlpacaApiService;
import com.investingapp.backend.service.EncryptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The three brokerage read endpoints used to trust the client. GET /account returned the broker's
 * list of every FRED customer account to any logged-in user, and the two {accountId} routes passed
 * whatever id was in the URL straight to the broker with the firm's credentials.
 *
 * User A is signed in throughout. User B's account id is the one A must never be able to read. The
 * assertion that matters is that the broker is never asked about B at all.
 */
class AlpacaControllerAccountOwnershipTest {

    private static final Long USER_A_ID = 1L;
    private static final String ACCOUNT_A = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String ACCOUNT_B = "bbbbbbbb-0000-0000-0000-000000000002";

    private AlpacaApiService alpacaApiService;
    private UserRepository userRepository;
    private AlpacaController controller;

    @BeforeEach
    void setUp() {
        alpacaApiService = mock(AlpacaApiService.class);
        userRepository = mock(UserRepository.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        when(encryptionService.decrypt("ENC:account-a")).thenReturn(ACCOUNT_A);

        controller = new AlpacaController(
                alpacaApiService,
                mock(com.investingapp.backend.service.AlpacaService.class),
                mock(com.investingapp.backend.service.PlaidToAlpacaService.class),
                userRepository,
                encryptionService,
                mock(com.investingapp.backend.service.KycSubmissionService.class));

        UserDetailsImpl principal = mock(UserDetailsImpl.class);
        when(principal.getId()).thenReturn(USER_A_ID);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void userAHasAccount(String storedAccountId) {
        User user = new User();
        user.setId(USER_A_ID);
        user.setAlpacaAccountId(storedAccountId);
        when(userRepository.findById(USER_A_ID)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("GET /account returns the caller's own account and never the firm-wide list")
    void accountReturnsOnlyTheCallersOwnAccount() {
        userAHasAccount("ENC:account-a");
        when(alpacaApiService.getAccountStatus(ACCOUNT_A)).thenReturn("{\"id\":\"" + ACCOUNT_A + "\"}");

        ResponseEntity<String> response = controller.getAccount();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(ACCOUNT_A);
        verify(alpacaApiService).getAccountStatus(ACCOUNT_A);
        // Only the caller's own account was asked for. Nothing else went to the broker.
        verifyNoMoreInteractions(alpacaApiService);
    }

    @Test
    @DisplayName("GET /account is 404 for a user with no brokerage account, and the broker is not called")
    void accountIsNotFoundWithoutABrokerageAccount() {
        userAHasAccount(null);

        ResponseEntity<String> response = controller.getAccount();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(alpacaApiService);
    }

    @Test
    @DisplayName("user A cannot read user B's account: 404, and the broker is never asked")
    void cannotReadAnotherUsersAccount() {
        userAHasAccount("ENC:account-a");

        ResponseEntity<String> response = controller.getAccountStatus(ACCOUNT_B);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).doesNotContain(ACCOUNT_B);
        verifyNoInteractions(alpacaApiService);
    }

    @Test
    @DisplayName("user A cannot read user B's bank links: 404, and the broker is never asked")
    void cannotReadAnotherUsersAchRelationships() {
        userAHasAccount("ENC:account-a");

        ResponseEntity<String> response = controller.getAchRelationships(ACCOUNT_B);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(alpacaApiService);
    }

    @Test
    @DisplayName("a user with no brokerage account cannot read anyone's account by id")
    void userWithoutAnAccountCannotReadById() {
        userAHasAccount(null);

        assertThat(controller.getAccountStatus(ACCOUNT_B).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(controller.getAchRelationships(ACCOUNT_B).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(alpacaApiService);
    }

    @Test
    @DisplayName("the caller's own account id still works on both routes")
    void ownAccountIdStillWorks() {
        userAHasAccount("ENC:account-a");
        when(alpacaApiService.getAccountStatus(ACCOUNT_A)).thenReturn("{\"status\":\"ACTIVE\"}");
        when(alpacaApiService.getAchRelationships(ACCOUNT_A)).thenReturn("[]");

        assertThat(controller.getAccountStatus(ACCOUNT_A).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.getAchRelationships(ACCOUNT_A).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(alpacaApiService, never()).getAccountStatus(ACCOUNT_B);
        verify(alpacaApiService, never()).getAchRelationships(ACCOUNT_B);
    }

    @Test
    @DisplayName("no signed-in user is 401 on all three routes")
    void unauthenticatedIsRefused() {
        SecurityContextHolder.clearContext();

        assertThat(controller.getAccount().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.getAccountStatus(ACCOUNT_A).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.getAchRelationships(ACCOUNT_A).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(alpacaApiService, never()).getAccountStatus(anyString());
        verifyNoInteractions(alpacaApiService);
    }
}
