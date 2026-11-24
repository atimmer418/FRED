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
