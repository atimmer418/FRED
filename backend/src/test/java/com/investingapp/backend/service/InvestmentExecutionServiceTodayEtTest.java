package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The 11:55 job works on the day the scheduler hands it, not on a clock it reads itself. 2031 dates
 * so no wall clock can satisfy them by accident; 2031-03-04 is a Tuesday and 2031-03-11 (one week
 * on, the WEEKLY next date) is a Tuesday with no US holiday, so adjustForBusinessDay leaves it alone.
 *
 * <p>T06 moves the per-schedule body into ScheduledInvestmentProcessor: carry
 * aScheduleDueOnThePassedDayIsQueuedAgainstThatDay into ScheduledInvestmentProcessorTest then, and
 * keep asksForSchedulesDueOnThePassedDay on the coordinator.
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
        when(executionRepository.save(any(InvestmentExecution.class))).thenAnswer(i -> i.getArgument(0));
        when(scheduleRepository.save(any(InvestmentSchedule.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static InvestmentSchedule weeklyDueOn(LocalDate day) {
        User user = new User("today-et@fredvested.com");
        user.setId(7L);
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setId(11L);
        schedule.setUser(user);
        schedule.setFrequency("WEEKLY");
        schedule.setInvestmentAmount(new BigDecimal("50.00"));
        schedule.setMonthlyAmount(new BigDecimal("216.50"));
        schedule.setStartDate(day.minusWeeks(4));
        schedule.setChosenDate(day.minusWeeks(4));
        schedule.setNextInvestmentDate(day);
        schedule.setIsPaused(false);
        schedule.setAchRequestId("ach-11");
        return schedule;
    }

    @Test
    void asksForSchedulesDueOnThePassedDay() {
        when(investmentScheduleService.getSchedulesReadyForInvestment(TODAY_ET)).thenReturn(List.of());

        service.processScheduledInvestments(TODAY_ET);

        verify(investmentScheduleService).getSchedulesReadyForInvestment(TODAY_ET);
        verify(executionRepository, never()).save(any());
    }

    @Test
    void aScheduleDueOnThePassedDayIsQueuedAgainstThatDay() {
        InvestmentSchedule schedule = weeklyDueOn(TODAY_ET);
        when(investmentScheduleService.getSchedulesReadyForInvestment(TODAY_ET)).thenReturn(List.of(schedule));

        service.processScheduledInvestments(TODAY_ET);

        ArgumentCaptor<InvestmentExecution> saved = ArgumentCaptor.forClass(InvestmentExecution.class);
        verify(executionRepository, atLeastOnce()).save(saved.capture());
        InvestmentExecution execution = saved.getValue();
        assertEquals(InvestmentExecution.ExecutionStatus.SCHEDULED, execution.getStatus());
        assertEquals("recurring", execution.getInvestmentType());
        assertEquals("Queued for end-of-day batch processing (recurring investment)", execution.getErrorMessage());
        // Streak date and next date come from todayET, not from the wall clock
        assertEquals(TODAY_ET, schedule.getLastStreakIncrementDate());
        assertEquals(Integer.valueOf(1), schedule.getMonthlyStreak());
        assertEquals(TODAY_ET.plusWeeks(1), schedule.getNextInvestmentDate());
    }
}
