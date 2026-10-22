package com.investingapp.backend.controller;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentExecution.ExecutionStatus;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.InvestmentExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Scope: ONLY the lump-sum idempotency key on POST /api/investments/execute (decision 18). */
class InvestmentControllerLumpSumTest {

    private static final String CLIENT_UUID = "0f8fad5b-d9cb-469f-a165-70867728950e";

    private InvestmentController controller;
    private InvestmentExecutionRepository executionRepository;
    private InvestmentExecutionService investmentExecutionService;
    private Authentication authentication;
    private User user;

    @BeforeEach
    void setUp() {
        controller = new InvestmentController();
        executionRepository = mock(InvestmentExecutionRepository.class);
        investmentExecutionService = mock(InvestmentExecutionService.class);
        UserRepository userRepository = mock(UserRepository.class);
        ReflectionTestUtils.setField(controller, "executionRepository", executionRepository);
        ReflectionTestUtils.setField(controller, "investmentExecutionService", investmentExecutionService);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);

        user = new User("lump@fredvested.com");
        user.setId(42L);
        user.setAlpacaAccountId("ENC:acct");
        user.setAlpacaAchRelationshipId("ENC:rel");
        when(userRepository.findByEmail("lump@fredvested.com")).thenReturn(Optional.of(user));
        authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("lump@fredvested.com");

        when(executionRepository.findBySourceKey(anyString())).thenReturn(Optional.empty());
        when(executionRepository.save(any(InvestmentExecution.class))).thenAnswer(i -> {
            InvestmentExecution e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(101L);
            }
            return e;
        });
    }

    private Map<String, Object> body(Object clientUuid) {
        Map<String, Object> request = new HashMap<>();
        request.put("amount", 250);
        if (clientUuid != null) {
            request.put("clientUuid", clientUuid);
        }
        return request;
    }

    private InvestmentExecution saved() {
        ArgumentCaptor<InvestmentExecution> captor = ArgumentCaptor.forClass(InvestmentExecution.class);
        verify(executionRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void stampsLumpSourceKeyFromTheClientUuidAndNoNightKey() {
        ResponseEntity<Map<String, Object>> response = controller.createManualInvestment(body(CLIENT_UUID), authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        InvestmentExecution execution = saved();
        assertThat(execution.getSourceKey()).isEqualTo("LUMP:42:" + CLIENT_UUID);
        assertThat(execution.getNightKey()).isNull();
        verify(investmentExecutionService).processInvestmentExecutionImmediately(execution);
    }

    @Test
    void sameClientUuidTwiceReturnsTheFirstRowAndMakesNoSecond() {
        InvestmentExecution first = new InvestmentExecution(user, LocalDateTime.of(2026, 10, 19, 14, 0),
                new BigDecimal("250"), "portfolio", null, "bank");
        first.setId(77L);
        first.setStatus(ExecutionStatus.SCHEDULED);
        when(executionRepository.findBySourceKey("LUMP:42:" + CLIENT_UUID)).thenReturn(Optional.of(first));

        ResponseEntity<Map<String, Object>> response = controller.createManualInvestment(body(CLIENT_UUID), authentication);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("success", true)
                .containsEntry("executionId", 77L)
                .containsEntry("duplicate", true);
        verify(executionRepository, never()).save(any());
        verify(investmentExecutionService, never()).processInvestmentExecutionImmediately(any());
    }

    @Test
    void missingClientUuidGetsAServerGeneratedOne() {
        controller.createManualInvestment(body(null), authentication);
        String sourceKey = saved().getSourceKey();
        assertThat(sourceKey).startsWith("LUMP:42:");
        String suffix = sourceKey.substring("LUMP:42:".length());
        assertThat(UUID.fromString(suffix).toString()).isEqualTo(suffix);
    }

    @Test
    void clientUuidIsNormalizedToLowercase() {
        controller.createManualInvestment(body(CLIENT_UUID.toUpperCase()), authentication);
        assertThat(saved().getSourceKey()).isEqualTo("LUMP:42:" + CLIENT_UUID);
    }

    @Test
    void malformedClientUuidIs400AndWritesNothing() {
        ResponseEntity<Map<String, Object>> response = controller.createManualInvestment(body("not-a-uuid"), authentication);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("message", "Invalid clientUuid");
        verify(executionRepository, never()).save(any());
    }
}
