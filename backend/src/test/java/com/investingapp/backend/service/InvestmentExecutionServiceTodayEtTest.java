package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The 11:55 job works on the day the scheduler hands it, not on a clock it reads itself. 2031 dates
 * so no wall clock can satisfy them by accident.
 *
 * <p>T06 moved the per-schedule body into ScheduledInvestmentProcessor: the per-schedule due-date
 * and streak coverage this class used to carry now lives in ScheduledInvestmentProcessorTest
 * (createsOneExecutionWithKeys, advancesNextDateAndStreakOnQueue, usesThePassedEasternDateNotTheClock).
 * What stays here is the coordinator's own contract, that it asks for tonight's schedules by the
 * day it was handed and writes nothing itself.
 */
class InvestmentExecutionServiceTodayEtTest {

    private static final LocalDate TODAY_ET = LocalDate.of(2031, 3, 4);

    private InvestmentExecutionService service;
    private InvestmentScheduleService investmentScheduleService;
    private InvestmentScheduleRepository scheduleRepository;
    private InvestmentExecutionRepository executionRepository;

    @BeforeEach
    void setUp() {
        service = new InvestmentExecutionService();
        investmentScheduleService = mock(InvestmentScheduleService.class);
        scheduleRepository = mock(InvestmentScheduleRepository.class);
        executionRepository = mock(InvestmentExecutionRepository.class);
        ReflectionTestUtils.setField(service, "investmentScheduleService", investmentScheduleService);
        ReflectionTestUtils.setField(service, "investmentScheduleRepository", scheduleRepository);
        ReflectionTestUtils.setField(service, "executionRepository", executionRepository);
    }

    @Test
    void asksForSchedulesDueOnThePassedDay() {
        when(investmentScheduleService.getSchedulesReadyForInvestment(TODAY_ET)).thenReturn(List.of());

        service.processScheduledInvestments(TODAY_ET);

        verify(investmentScheduleService).getSchedulesReadyForInvestment(TODAY_ET);
        verify(executionRepository, never()).save(any(InvestmentExecution.class));
    }
}
