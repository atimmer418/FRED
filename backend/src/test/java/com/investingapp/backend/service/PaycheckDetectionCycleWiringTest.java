package com.investingapp.backend.service;

import com.investingapp.backend.model.DetectionState;
import com.investingapp.backend.paycheck.*;
import com.investingapp.backend.paycheck.cycle.MatchDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaycheckDetectionCycleWiringTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);
    private static final EvaluationOutcome PROVISIONAL =
            new EvaluationOutcome(DetectionState.PROVISIONAL, 7L, true, false, Optional.empty());

    @Mock
    private PaycheckCycleService cycleService;
    @InjectMocks
    private PaycheckDetectionService real;
    private PaycheckDetectionService detection;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        detection = spy(real);
    }

    @Test
    void evaluateOpensCycleForNewPrimary() {
        doReturn(PROVISIONAL).when(detection).evaluateWithoutCycles(42L, EvaluationTrigger.FULL, TODAY);
        assertThat(detection.evaluate(42L, EvaluationTrigger.FULL, TODAY)).isEqualTo(PROVISIONAL);
        verify(cycleService).openCycleForPrimary(42L, 7L);
        EvaluationOutcome searching = new EvaluationOutcome(DetectionState.SEARCHING, null, false, false, Optional.empty());
        doReturn(searching).when(detection).evaluateWithoutCycles(43L, EvaluationTrigger.FULL, TODAY);
        assertThat(detection.evaluate(43L, EvaluationTrigger.FULL, TODAY)).isEqualTo(searching);
        EvaluationOutcome consent = new EvaluationOutcome(DetectionState.NEEDS_CONSENT, 7L, false, false, Optional.empty());
        doReturn(consent).when(detection).evaluateWithoutCycles(44L, EvaluationTrigger.FULL, TODAY);
        assertThat(detection.evaluate(44L, EvaluationTrigger.FULL, TODAY)).isEqualTo(consent); // T13 owns that mirror
        verify(cycleService, never()).openCycleForPrimary(eq(43L), any());
        verify(cycleService, never()).openCycleForPrimary(eq(44L), any());
    }

    @Test
    void evaluateAndApplyRunsIncrementalThenAppliesInOneTransaction() throws Exception {
        doReturn(PROVISIONAL).when(detection).evaluate(42L, EvaluationTrigger.INCREMENTAL, TODAY);
        when(cycleService.applyNewDeposits(eq(42L), eq(7L), eq(List.of(500L)), any(LocalDateTime.class)))
                .thenReturn(List.of(MatchDecision.CONFIRMABLE));
        assertThat(detection.evaluateAndApply(42L, List.of(500L), TODAY)).isEqualTo(PROVISIONAL);
        InOrder order = inOrder(detection, cycleService);
        order.verify(detection).evaluate(42L, EvaluationTrigger.INCREMENTAL, TODAY);
        order.verify(cycleService).applyNewDeposits(eq(42L), eq(7L), eq(List.of(500L)), any(LocalDateTime.class));
        assertThat(PaycheckDetectionService.class.getMethod("evaluateAndApply", Long.class, List.class, LocalDate.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void appliedDepositClearsADormantOutcomeAndNoDepositsApplyNothing() {
        EvaluationOutcome dormant = new EvaluationOutcome(DetectionState.DORMANT, 7L, false, false, Optional.empty());
        doReturn(dormant).when(detection).evaluate(42L, EvaluationTrigger.INCREMENTAL, TODAY);
        when(cycleService.applyNewDeposits(eq(42L), eq(7L), eq(List.of(500L)), any(LocalDateTime.class)))
                .thenReturn(List.of(MatchDecision.CONFIRMABLE));
        assertThat(detection.evaluateAndApply(42L, List.of(500L), TODAY).state()).isEqualTo(DetectionState.PROVISIONAL);
        assertThat(detection.evaluateAndApply(42L, List.of(), TODAY)).isEqualTo(dormant);
        verify(cycleService, never()).applyNewDeposits(anyLong(), anyLong(), eq(List.of()), any(LocalDateTime.class));
    }
}
