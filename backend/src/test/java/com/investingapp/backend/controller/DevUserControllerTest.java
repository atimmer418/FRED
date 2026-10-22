package com.investingapp.backend.controller;

import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.DeletionDeferredException;
import com.investingapp.backend.service.UserDeletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DevUserControllerTest {

    private UserRepository userRepository;
    private UserDeletionService userDeletionService;
    private DevUserController controller;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userDeletionService = mock(UserDeletionService.class);
        controller = new DevUserController(userRepository, userDeletionService);
        user = new User("admin-delete@fredvested.com");
        user.setId(7L);
    }

    @Test
    @DisplayName("an unknown id is 404 and nothing is deleted")
    void unknownUserIs404() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThat(controller.deleteUser(7L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(userDeletionService);
    }

    @Test
    @DisplayName("a known id is deleted with reason ADMIN")
    @SuppressWarnings("unchecked")
    void deletesAsAdmin() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        ResponseEntity<?> response = controller.deleteUser(7L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("deleted", true).containsEntry("userId", 7L);
        verify(userDeletionService).delete(user, UserDeletionService.DeletionReason.ADMIN);
    }

    @Test
    @DisplayName("a deferral is 409 with the DELETION_DEFERRED code")
    void deferredIs409() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        doThrow(new DeletionDeferredException("SETTLEMENT_WINDOW"))
                .when(userDeletionService).delete(user, UserDeletionService.DeletionReason.ADMIN);

        ResponseEntity<?> response = controller.deleteUser(7L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(new ErrorCodeResponse("DELETION_DEFERRED", "SETTLEMENT_WINDOW"));
    }
}
