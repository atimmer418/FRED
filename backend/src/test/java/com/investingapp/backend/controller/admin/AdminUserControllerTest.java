package com.investingapp.backend.controller.admin;

import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.AdminAuditService;
import com.investingapp.backend.service.DeletionDeferredException;
import com.investingapp.backend.service.UserDeletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminUserControllerTest {

    private static final String ACTOR = "andy";
    private static final Map<String, Object> BEFORE =
            Map.of("accountStatus", "ACTIVE", "hasPlaidItem", false, "plaidItemStatus", "OK");

    private UserRepository userRepository;
    private UserDeletionService userDeletionService;
    private AdminAuditService audit;
    private AdminUserController controller;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userDeletionService = mock(UserDeletionService.class);
        audit = mock(AdminAuditService.class);
        when(audit.actor(any())).thenReturn(ACTOR);
        controller = new AdminUserController(userRepository, userDeletionService, audit);
        user = new User("admin-delete@fredvested.test");
        user.setId(7L);
        user.setPublicId("pub-7");
        user.setAccountStatus("ACTIVE");
    }

    @Test
    void unknownUserIs404AndAudited() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThat(controller.deleteUser(7L, "cleanup", new MockHttpServletRequest()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(userDeletionService);
        verify(audit).record(ACTOR, 7L, AdminUserController.VERB_DELETE, null, null,
                Map.of("outcome", "NOT_FOUND"), "cleanup");
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletesAsAdminWithAuditInsideTheDeletionTransaction() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        ResponseEntity<?> response = controller.deleteUser(7L, null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("deleted", true).containsEntry("userId", 7L);
        ArgumentCaptor<Runnable> hook = ArgumentCaptor.forClass(Runnable.class);
        verify(userDeletionService).delete(eq(user), eq(UserDeletionService.DeletionReason.ADMIN), hook.capture());
        verify(audit, never()).recordInTransaction(any(), any(), any(), any(), any(), any(), any());

        hook.getValue().run();

        verify(audit).recordInTransaction(ACTOR, 7L, AdminUserController.VERB_DELETE, "pub-7", BEFORE,
                Map.of("outcome", "DELETED"), null);
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deferredIs409AndAudited() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        doThrow(new DeletionDeferredException("SETTLEMENT_WINDOW")).when(userDeletionService)
                .delete(eq(user), eq(UserDeletionService.DeletionReason.ADMIN), any(Runnable.class));

        ResponseEntity<?> response = controller.deleteUser(7L, null, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isEqualTo(new ErrorCodeResponse("DELETION_DEFERRED", "SETTLEMENT_WINDOW"));
        verify(audit).record(ACTOR, 7L, AdminUserController.VERB_DELETE, "pub-7", BEFORE,
                Map.of("outcome", "DEFERRED", "deferral", "SETTLEMENT_WINDOW"), null);
    }

    @Test
    void failureIsAuditedAndRethrown() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        IllegalStateException failure = new IllegalStateException("database down");
        doThrow(failure).when(userDeletionService)
                .delete(eq(user), eq(UserDeletionService.DeletionReason.ADMIN), any(Runnable.class));

        assertThatThrownBy(() -> controller.deleteUser(7L, null, new MockHttpServletRequest())).isSameAs(failure);
        verify(audit).record(ACTOR, 7L, AdminUserController.VERB_DELETE, "pub-7", BEFORE,
                Map.of("outcome", "FAILED"), null);
    }
}
