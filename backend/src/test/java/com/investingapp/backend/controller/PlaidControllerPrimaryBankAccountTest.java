package com.investingapp.backend.controller;

import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GET /api/plaid/primary-bank-account is the one bank endpoint the app still calls
 * (plaid.service.ts and change-bank-account.page.ts). It must hand the UI display fields only:
 * the Plaid access token never leaves the server, and mask plus itemStatus replace it
 * (FRED-218, spec 8.8). No EncryptionService is wired here on purpose: answering must not
 * need one.
 */
class PlaidControllerPrimaryBankAccountTest {

    private static final String EMAIL = "bank@fredvested.com";

    private PlaidController controller;
    private UserRepository userRepository;
    private User user;

    @BeforeEach
    void setUp() {
        controller = new PlaidController();
        userRepository = mock(UserRepository.class);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);

        user = new User(EMAIL);
        user.setId(7L);
        user.setPlaidAccountId("plaid-acct-1");
        user.setPlaidInstitutionName("Chase");
        user.setPlaidAccountName("Chase Checking");
        user.setPlaidAccountType("depository");
        user.setPlaidAccountSubtype("checking");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
    }

    private Authentication authentication() {
        UserDetailsImpl principal = new UserDetailsImpl(7L, EMAIL, List.of());
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @Test
    @DisplayName("the response carries the display fields plus mask and itemStatus")
    void carriesMaskAndItemStatus() {
        user.setPlaidAccountMask("1234");
        user.setPlaidItemStatus(PlaidItemStatus.LOGIN_REQUIRED);

        ResponseEntity<?> response = controller.getPrimaryBankAccount(authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response))
                .containsEntry("accountId", "plaid-acct-1")
                .containsEntry("institutionName", "Chase")
                .containsEntry("accountName", "Chase Checking")
                .containsEntry("accountType", "depository")
                .containsEntry("accountSubtype", "checking")
                .containsEntry("mask", "1234")
                .containsEntry("itemStatus", "LOGIN_REQUIRED");
    }

    @Test
    @DisplayName("the Plaid access token never leaves the server, and no decryption is needed to answer")
    void accessTokenNeverLeavesTheServer() {
        user.setPlaidAccessToken("ENC:encrypted-access-token");

        ResponseEntity<?> response = controller.getPrimaryBankAccount(authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response)).doesNotContainKey("accessToken");
        assertThat(body(response).values()).doesNotContain("ENC:encrypted-access-token");
    }

    @Test
    @DisplayName("a link made before the mask was stored still answers: null mask, OK status")
    void olderLinksAnswerWithNullMaskAndOkStatus() {
        ResponseEntity<?> response = controller.getPrimaryBankAccount(authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(response)).containsKey("mask");
        assertThat(body(response).get("mask")).isNull();
        assertThat(body(response)).containsEntry("itemStatus", "OK");
    }

    @Test
    @DisplayName("an unknown user is a 400, as before")
    void unknownUserIs400() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getPrimaryBankAccount(authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
