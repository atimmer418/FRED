package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.service.ScheduledInvestmentProcessor.ProcessOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Scope: ONLY the 11:55 coordinator shape: list ids, hand each to processOne with the same todayET, never stop on one failure, write nothing itself. */
class InvestmentExecutionServiceCoordinatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    private InvestmentExecutionService service;
    private InvestmentScheduleService investmentScheduleService;
    private ScheduledInvestmentProcessor processor;
    private InvestmentExecutionRepository executionRepository;

    @BeforeEach
    void setUp() {
        service = new InvestmentExecutionService();
        investmentScheduleService = mock(InvestmentScheduleService.class);
        processor = mock(ScheduledInvestmentProcessor.class);
        executionRepository = mock(InvestmentExecutionRepository.class);
        ReflectionTestUtils.setField(service, "investmentScheduleService", investmentScheduleService);
        ReflectionTestUtils.setField(service, "scheduledInvestmentProcessor", processor);
        ReflectionTestUtils.setField(service, "executionRepository", executionRepository);
    }

    private static InvestmentSchedule schedule(long id) {
        InvestmentSchedule s = new InvestmentSchedule();
        s.setId(id);
        return s;
    }

    @Test
    void duplicateKeyOnUserADoesNotRollBackUserB() {
        when(investmentScheduleService.getSchedulesReadyForInvestment(TODAY)).thenReturn(List.of(schedule(1L), schedule(2L)));
        when(processor.processOne(1L, TODAY)).thenThrow(new DataIntegrityViolationException("uk_investment_executions_source_key"));
        when(processor.processOne(2L, TODAY)).thenReturn(ProcessOutcome.QUEUED);

        assertThatCode(() -> service.processScheduledInvestments(TODAY)).doesNotThrowAnyException();

        verify(processor).processOne(1L, TODAY);
        verify(processor).processOne(2L, TODAY);
    }

    @Test
    void passesTodayEtThroughAndWritesNothingItself() {
        when(investmentScheduleService.getSchedulesReadyForInvestment(TODAY)).thenReturn(List.of(schedule(5L)));
        when(processor.processOne(anyLong(), any())).thenReturn(ProcessOutcome.NOT_DUE);

        service.processScheduledInvestments(TODAY);

        verify(investmentScheduleService).getSchedulesReadyForInvestment(TODAY);
        verify(processor).processOne(5L, TODAY);
        verifyNoInteractions(executionRepository);
    }
}
