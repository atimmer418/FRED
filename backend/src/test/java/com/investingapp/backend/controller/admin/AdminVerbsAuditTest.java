package com.investingapp.backend.controller.admin;

import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.AdminAuditService;
import com.investingapp.backend.service.PlaidItemLifecycleService;
import com.investingapp.backend.service.UserDeletionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminVerbsAuditTest {

    @Test
    @SuppressWarnings("unchecked")
    void everyVerbWritesAdminAction() {
        AdminAuditService audit = mock(AdminAuditService.class);
        when(audit.actor(any())).thenReturn("andy");
        UserRepository users = mock(UserRepository.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(eq(AdminPlaidController.ITEMS_SQL), any(RowMapper.class))).thenReturn(List.of());
        UserDeletionService deletion = mock(UserDeletionService.class);
        AdminPlaidController plaid = new AdminPlaidController(jdbc, users, mock(PlaidItemLifecycleService.class), audit,
                new com.investingapp.backend.service.UserRowLock(users, mock(jakarta.persistence.EntityManager.class)), tx);
        AdminUserController userVerbs = new AdminUserController(users, deletion, audit);
        User linked = new User("linked@fredvested.test");
        linked.setId(7L);
        linked.setPlaidAccessToken("ENC:tok");
        User leaving = new User("leaving@fredvested.test");
        leaving.setId(8L);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(linked));
        when(users.findById(8L)).thenReturn(Optional.of(leaving));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(2)).run();
            return null;
        }).when(deletion).delete(eq(leaving), eq(UserDeletionService.DeletionReason.ADMIN), any(Runnable.class));

        MockHttpServletRequest request = new MockHttpServletRequest();
        plaid.items(request);
        plaid.removeItem(7L, null, request);
        userVerbs.deleteUser(8L, null, request);

        verify(audit).record(eq("andy"), isNull(), eq(AdminPlaidController.VERB_ITEMS), any(), any(), any(), any());
        verify(audit).recordInTransaction(eq("andy"), eq(7L), eq(AdminPlaidController.VERB_REMOVE_ITEM),
                any(), any(), any(), any());
        verify(audit).recordInTransaction(eq("andy"), eq(8L), eq(AdminUserController.VERB_DELETE),
                any(), any(), any(), any());
        List<String> handlers = Stream.of(AdminPlaidController.class, AdminUserController.class)
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(m -> m.isAnnotationPresent(GetMapping.class) || m.isAnnotationPresent(PostMapping.class))
                .map(Method::getName).sorted().toList();
        assertThat(handlers).as("a new admin verb needs its audit assertion in this test")
                .containsExactly("deleteUser", "items", "removeItem");
    }
}
