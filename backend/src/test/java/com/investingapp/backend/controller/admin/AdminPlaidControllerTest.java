package com.investingapp.backend.controller.admin;

import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.dto.admin.PlaidItemRow;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.AdminAuditService;
import com.investingapp.backend.service.PlaidItemLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminPlaidControllerTest {

    private static final String ACTOR = "andy";

    private JdbcTemplate jdbc;
    private UserRepository userRepository;
    private PlaidItemLifecycleService lifecycle;
    private AdminAuditService audit;
    private PlatformTransactionManager txManager;
    private AdminPlaidController controller;
    private User user;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        userRepository = mock(UserRepository.class);
        lifecycle = mock(PlaidItemLifecycleService.class);
        audit = mock(AdminAuditService.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(audit.actor(any())).thenReturn(ACTOR);
        controller = new AdminPlaidController(jdbc, userRepository, lifecycle, audit,
                new com.investingapp.backend.service.UserRowLock(userRepository, mock(jakarta.persistence.EntityManager.class)),
                txManager);
        user = new User("item-owner@fredvested.test");
        user.setId(7L);
        user.setPlaidAccessToken("ENC:tok");
        user.setPlaidItemId("item-1");
        user.setPlaidItemStatus(PlaidItemStatus.OK);
    }

    @Test
    void removeItemQueuesMarksRemovedAndAuditsInOneTransaction() {
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));

        ResponseEntity<?> response = controller.removeItem(7L, "beta cleanup", new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("result", "QUEUED"));
        assertThat(user.getPlaidItemStatus()).isEqualTo(PlaidItemStatus.REMOVED);
        InOrder order = inOrder(txManager, userRepository, lifecycle, audit);
        order.verify(txManager).getTransaction(any());
        order.verify(userRepository).findByIdForUpdate(7L);
        order.verify(lifecycle).enqueueRemoval(user);
        order.verify(userRepository).save(user);
        order.verify(audit).recordInTransaction(ACTOR, 7L, AdminPlaidController.VERB_REMOVE_ITEM, "item-1",
                Map.of("itemStatus", "OK", "hasItem", true, "removalQueued", false),
                Map.of("itemStatus", "REMOVED", "hasItem", true, "removalQueued", true), "beta cleanup");
        order.verify(txManager).commit(any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void removeItemRefusalsWriteNothingAndAreAudited() {
        when(userRepository.findByIdForUpdate(8L)).thenReturn(Optional.empty());
        assertThat(controller.removeItem(8L, null, new MockHttpServletRequest()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        verify(audit).record(ACTOR, 8L, AdminPlaidController.VERB_REMOVE_ITEM, null, null,
                Map.of("outcome", "NOT_FOUND"), null);

        User noItem = new User("no-item@fredvested.test");
        noItem.setId(9L);
        when(userRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(noItem));
        ResponseEntity<?> conflict = controller.removeItem(9L, null, new MockHttpServletRequest());
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(((ErrorCodeResponse) conflict.getBody()).code()).isEqualTo("NO_PLAID_ITEM");

        user.setPlaidItemStatus(PlaidItemStatus.REMOVED);
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        assertThat(((ErrorCodeResponse) controller.removeItem(7L, null, new MockHttpServletRequest()).getBody()).code())
                .isEqualTo("ITEM_ALREADY_REMOVED");

        verify(lifecycle, never()).enqueueRemoval(any());
        verify(userRepository, never()).save(any());
        verify(audit, never()).recordInTransaction(any(), any(), any(), any(), any(), any(), any());
        verify(audit, times(3)).record(eq(ACTOR), any(), eq(AdminPlaidController.VERB_REMOVE_ITEM),
                any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void itemsListingIsAudited() {
        PlaidItemRow row = new PlaidItemRow("pub-1", "OK", Boolean.TRUE, true, false, null, null, false);
        when(jdbc.query(eq(AdminPlaidController.ITEMS_SQL), any(RowMapper.class))).thenReturn(List.of(row));

        ResponseEntity<List<PlaidItemRow>> response = controller.items(new MockHttpServletRequest());

        assertThat(response.getBody()).containsExactly(row);
        verify(audit).record(ACTOR, null, AdminPlaidController.VERB_ITEMS, null, null, Map.of("rows", 1), null);
    }

    @Test
    void listingRowHasNoTokenOrCursorComponent() {
        assertThat(Arrays.stream(PlaidItemRow.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("publicId", "itemStatus", "transactionsConsented", "hasCursor",
                        "historyComplete", "lastSyncAt", "linkedAt", "pendingRemoval");
        assertThat(Pattern.compile("plaid_(access_token|sync_cursor)(?! IS NOT NULL)")
                .matcher(AdminPlaidController.ITEMS_SQL).find()).isFalse();
    }

    @Test
    void verbsRefuseWithoutFilterActor() {
        doThrow(new IllegalStateException("admin.actor is missing")).when(audit).actor(any());

        assertThatThrownBy(() -> controller.items(new MockHttpServletRequest())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> controller.removeItem(7L, null, new MockHttpServletRequest()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(jdbc, userRepository, lifecycle, txManager);
    }
}
