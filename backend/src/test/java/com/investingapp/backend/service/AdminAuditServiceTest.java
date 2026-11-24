package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.investingapp.backend.model.AdminAction;
import com.investingapp.backend.repository.AdminActionRepository;
import com.investingapp.backend.security.AdminKeyFilter;
import com.investingapp.backend.security.AdminKeyValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminAuditServiceTest {

    private static final Class<?>[] ARGS =
            {String.class, Long.class, String.class, String.class, Object.class, Object.class, String.class};

    private AdminActionRepository repository;
    private PlatformTransactionManager txManager;
    private AdminAuditService service;

    @BeforeEach
    void setUp() {
        repository = mock(AdminActionRepository.class);
        txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new AdminAuditService(repository,
                JsonMapper.builder().addModule(new JavaTimeModule()).build(), txManager);
    }

    private AdminAction savedRow() {
        ArgumentCaptor<AdminAction> row = ArgumentCaptor.forClass(AdminAction.class);
        verify(repository).save(row.capture());
        return row.getValue();
    }

    @Test
    void recordWritesOneRowInItsOwnTransaction() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("itemStatus", "OK");

        service.record("andy", 7L, "plaid.remove-item", "item-1", before, Map.of("outcome", "NO_ITEM"), "beta cleanup");

        AdminAction row = savedRow();
        assertThat(row.getActor()).isEqualTo("andy");
        assertThat(row.getUserId()).isEqualTo(7L);
        assertThat(row.getVerb()).isEqualTo("plaid.remove-item");
        assertThat(row.getTargetId()).isEqualTo("item-1");
        assertThat(row.getBeforeJson()).isEqualTo("{\"itemStatus\":\"OK\"}");
        assertThat(row.getAfterJson()).isEqualTo("{\"outcome\":\"NO_ITEM\"}");
        assertThat(row.getReason()).isEqualTo("beta cleanup");
        assertThat(row.getCreatedAt()).isNotNull();
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(txManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(txManager).commit(any());
    }

    @Test
    void recordNeverThrows() {
        when(repository.save(any())).thenThrow(new DataAccessResourceFailureException("database down"));
        assertThatCode(() -> service.record("andy", 7L, "plaid.items", null, null, null, null))
                .doesNotThrowAnyException();

        reset(repository);
        doThrow(new TransactionSystemException("commit failed")).when(txManager).commit(any());
        assertThatCode(() -> service.record("andy", 7L, "plaid.items", null, null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void auditJsonNeverCarriesTokenCursorOrDescriptor() {
        Map<String, Object> stream = new LinkedHashMap<>();
        stream.put("payerToken", "ACME PAYROLL");
        stream.put("rawDescriptor", "ACME PAYROLL PPD 123");
        stream.put("state", "PRIMARY");
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("plaidAccessToken", "ENC:secret-token");
        before.put("plaid_sync_cursor", "cursor-value");
        before.put("accessTokenEnc", "ENC:queued-token");
        before.put("ssnHash", "abc");
        before.put("itemStatus", "OK");
        before.put("streams", List.of(stream));

        service.record("andy", 7L, "plaid.items", null, before, null, null);

        AdminAction row = savedRow();
        assertThat(row.getBeforeJson()).isEqualTo("{\"itemStatus\":\"OK\",\"streams\":[{\"state\":\"PRIMARY\"}]}");
        assertThat(row.getAfterJson()).isNull();
    }

    @Test
    void overlongFieldsAreCutToTheirColumns() {
        service.record("a".repeat(100), 7L, "v".repeat(70), "t".repeat(90), null, null, "r".repeat(300));

        AdminAction row = savedRow();
        assertThat(row.getActor()).hasSize(80);
        assertThat(row.getVerb()).hasSize(64);
        assertThat(row.getTargetId()).hasSize(80);
        assertThat(row.getReason()).hasSize(255);
    }

    @Test
    void recordInTransactionIsMandatory() throws Exception {
        Transactional joined = AdminAuditService.class.getMethod("recordInTransaction", ARGS)
                .getAnnotation(Transactional.class);
        assertThat(joined).isNotNull();
        assertThat(joined.propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(AdminAuditService.class.getMethod("record", ARGS).getAnnotation(Transactional.class)).isNull();
        assertThat(AdminAuditService.class.getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void recordInTransactionFailureRollsTheVerbBack() {
        when(repository.save(any())).thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatThrownBy(() -> service.recordInTransaction("andy", 7L, "users.delete", "pub-7", null, null, null))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(txManager);
    }

    @Test
    void actorComesOnlyFromTheFilterAttribute() {
        MockHttpServletRequest filtered = new MockHttpServletRequest();
        filtered.setAttribute(AdminKeyFilter.ACTOR_ATTRIBUTE, "andy");
        assertThat(service.actor(filtered)).isEqualTo("andy");

        MockHttpServletRequest unfiltered = new MockHttpServletRequest("POST", "/api/admin/users/7/delete");
        unfiltered.addHeader(AdminKeyValidator.ACTOR_HEADER, "mallory");
        assertThatThrownBy(() -> service.actor(unfiltered))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("admin.actor");
    }
}
