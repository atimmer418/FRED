package com.investingapp.backend.controller;

import com.investingapp.backend.config.PaycheckAutopilotProperties;
import com.investingapp.backend.dto.PaycheckDetectionStatusResponse;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.PlaidItemStatus;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PaycheckStreamRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.InitSyncEnqueuer;
import com.investingapp.backend.service.PaycheckDetectionStatusService;
import com.investingapp.backend.service.PaycheckSyncPipeline;
import com.investingapp.backend.support.RecordingTransactionManager;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaycheckDetectionControllerTest {

    private static final String EMAIL = "status@fredvested.com";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final InvestmentScheduleRepository scheduleRepository = mock(InvestmentScheduleRepository.class);
    private final PaycheckSyncPipeline pipeline = mock(PaycheckSyncPipeline.class);
    private final PaycheckAutopilotProperties props = mock(PaycheckAutopilotProperties.class);
    private final List<Runnable> queued = new ArrayList<>();
    private PaycheckDetectionStatusService statusService;
    private User user;

    @BeforeEach
    void setUp() {
        when(props.isEnabled()).thenReturn(true);
        when(props.getAffordabilityShare()).thenReturn(new BigDecimal("0.90"));
        statusService = new PaycheckDetectionStatusService(scheduleRepository, mock(PaycheckStreamRepository.class), props);
        user = new User(EMAIL);
        user.setId(7L);
        user.setAccountStatus("ACTIVE");
        user.setPlaidAccessToken("ENC:token");
        user.setPlaidItemId("item-1");
        user.setPlaidItemStatus(PlaidItemStatus.OK);
        user.setPaycheckAutopilotOptOut(false);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
    }

    private static Authentication authentication() {
        return new UsernamePasswordAuthenticationToken(new UserDetailsImpl(7L, EMAIL, List.of()), null, List.of());
    }

    private PaycheckDetectionController controller() {
        InitSyncEnqueuer enqueuer = new InitSyncEnqueuer(scheduleRepository, userRepository, pipeline, props,
                new RecordingTransactionManager(), queued::add);
        ReflectionTestUtils.setField(enqueuer, "entityManager", mock(EntityManager.class));
        return new PaycheckDetectionController(userRepository, enqueuer, statusService);
    }

    @Test
    void responseNeverWaitsOnPlaid() {
        InvestmentSchedule row = new InvestmentSchedule();
        row.setId(70L);
        row.setUser(user);
        row.setMode(ScheduleMode.MANUAL);
        row.setStatus(InvestmentSchedule.Status.ACTIVE);
        row.setInvestmentAmount(new BigDecimal("100.00"));
        when(scheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(row));

        ResponseEntity<?> response = controller().status(authentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PaycheckDetectionStatusResponse body = (PaycheckDetectionStatusResponse) response.getBody();
        assertThat(body.state()).isEqualTo("SEARCHING");
        assertThat(body.detectionStartedAt()).isNotNull();
        assertThat(queued).hasSize(1);
        verifyNoInteractions(pipeline);
    }

    @Test
    void unauthenticatedIs401() {
        assertThat(controller().status(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownUserIs404() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThat(controller().status(authentication()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(queued).isEmpty();
    }
}
