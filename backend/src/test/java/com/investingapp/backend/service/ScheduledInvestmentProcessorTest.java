package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.service.ScheduledInvestmentProcessor.ProcessOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scope: ONLY what processOne writes for one MANUAL schedule on one Eastern night. The
 * REQUIRES_NEW boundary cannot be proven without a context; what is pinned is that every
 * outcome short of QUEUED writes nothing, the keys are stamped exactly, and the marker string
 * the 11:59 batch selects on is unchanged.
 */
class ScheduledInvestmentProcessorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    private InvestmentScheduleRepository scheduleRepository;
    private InvestmentExecutionRepository executionRepository;
    private ScheduledInvestmentProcessor processor;
    private User user;
    private InvestmentSchedule schedule;

    @BeforeEach
    void setUp() {
        scheduleRepository = mock(InvestmentScheduleRepository.class);
        executionRepository = mock(InvestmentExecutionRepository.class);
        processor = new ScheduledInvestmentProcessor(scheduleRepository, executionRepository);

        user = new User("recurring@fredvested.com");
        user.setId(42L);
        user.setAccountStatus("ACTIVE");

        schedule = new InvestmentSchedule();
        schedule.setId(7L);
        schedule.setUser(user);
        schedule.setFrequency("BIWEEKLY");
        schedule.setInvestmentAmount(new BigDecimal("150.00"));
        schedule.setMonthlyAmount(new BigDecimal("325.50"));
        schedule.setStartDate(LocalDate.of(2026, 9, 7));
        schedule.setNextInvestmentDate(TODAY);
        schedule.setStatus(InvestmentSchedule.Status.ACTIVE);
        schedule.setAchRequestId("rel-1");
        schedule.setMonthlyStreak(3);
        schedule.setLastStreakIncrementDate(LocalDate.of(2026, 9, 21));

        when(scheduleRepository.findById(7L)).thenReturn(Optional.of(schedule));
        when(scheduleRepository.save(any(InvestmentSchedule.class))).thenAnswer(i -> i.getArgument(0));
        when(executionRepository.save(any(InvestmentExecution.class))).thenAnswer(i -> {
            InvestmentExecution e = i.getArgument(0);
            e.setId(1001L);
            return e;
        });
        when(executionRepository.existsBySourceKey(anyString())).thenReturn(false);
        when(executionRepository.existsByNightKey(anyString())).thenReturn(false);
    }

    private InvestmentExecution savedExecution() {
        ArgumentCaptor<InvestmentExecution> captor = ArgumentCaptor.forClass(InvestmentExecution.class);
        verify(executionRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void createsOneExecutionWithKeys() {
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.QUEUED);

        InvestmentExecution saved = savedExecution();
        assertThat(saved.getSourceKey()).isEqualTo("SCHED:7:2026-10-19");
        assertThat(saved.getNightKey()).isEqualTo("42:2026-10-19");
        assertThat(saved.getSchedule()).isSameAs(schedule);
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getAmount()).isEqualByComparingTo("150.00");
        assertThat(saved.getStatus()).isEqualTo(InvestmentExecution.ExecutionStatus.SCHEDULED);
        assertThat(saved.getInvestmentType()).isEqualTo("recurring");
        assertThat(saved.getFundingSource()).isEqualTo("bank");
        assertThat(saved.getScheduledDate()).isNotNull();
    }

    @Test
    void markerStringUnchanged() {
        processor.processOne(7L, TODAY);
        // The 11:59 batch selects on contains("Queued for end-of-day"); changing this drops recurring rows.
        assertThat(savedExecution().getErrorMessage())
                .isEqualTo("Queued for end-of-day batch processing (recurring investment)");
    }

    @Test
    void advancesNextDateAndStreakOnQueue() {
        processor.processOne(7L, TODAY);

        assertThat(schedule.getNextInvestmentDate()).isAfter(TODAY);
        assertThat(schedule.getStartDate()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(schedule.getMonthlyStreak()).isEqualTo(4);
        assertThat(schedule.getLastStreakIncrementDate()).isEqualTo(TODAY);
        verify(scheduleRepository).save(schedule);
    }

    @Test
    void streakIncrementsAtMostOncePerCalendarMonth() {
        schedule.setLastStreakIncrementDate(LocalDate.of(2026, 10, 5));
        processor.processOne(7L, TODAY);
        assertThat(schedule.getMonthlyStreak()).isEqualTo(3);
        assertThat(schedule.getLastStreakIncrementDate()).isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void duplicateSourceKeyWritesNothing() {
        when(executionRepository.existsBySourceKey("SCHED:7:2026-10-19")).thenReturn(true);
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.DUPLICATE_SOURCE_KEY);
        verify(executionRepository, never()).save(any());
        verify(scheduleRepository, never()).save(any());
        assertThat(schedule.getNextInvestmentDate()).isEqualTo(TODAY);
    }

    @Test
    void duplicateNightKeyWritesNothing() {
        when(executionRepository.existsByNightKey("42:2026-10-19")).thenReturn(true);
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.DUPLICATE_NIGHT_KEY);
        verify(executionRepository, never()).save(any());
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void notDueTonightWritesNothing() {
        schedule.setNextInvestmentDate(TODAY.plusDays(3));
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.NOT_DUE);
        verify(executionRepository, never()).existsBySourceKey(anyString());
        verify(executionRepository, never()).save(any());
    }

    @Test
    void startDateTonightIsDueEvenWhenNextDateIsLater() {
        schedule.setStartDate(TODAY);
        schedule.setNextInvestmentDate(TODAY.plusWeeks(2));
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.QUEUED);
    }

    @Test
    void pausedOrUnlinkedOrInactiveIsNotEligible() {
        schedule.setStatus(InvestmentSchedule.Status.PAUSED_BY_USER);
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.NOT_ELIGIBLE);
        schedule.setStatus(InvestmentSchedule.Status.ACTIVE);
        schedule.setAchRequestId(null);
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.NOT_ELIGIBLE);
        schedule.setAchRequestId("rel-1");
        user.setAccountStatus("ACTION_REQUIRED");
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.NOT_ELIGIBLE);
        verify(executionRepository, never()).save(any());
    }

    @Test
    void detectedModeIsNotEligibleInPhase0() {
        // Global constraint: every mode switch knows MANUAL and returns false/no-op for DETECTED.
        schedule.setMode(ScheduleMode.DETECTED);
        assertThat(processor.processOne(7L, TODAY)).isEqualTo(ProcessOutcome.NOT_ELIGIBLE);
        verify(executionRepository, never()).existsBySourceKey(anyString());
        verify(executionRepository, never()).save(any());
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void vanishedScheduleIsNotEligible() {
        when(scheduleRepository.findById(99L)).thenReturn(Optional.empty());
        assertThat(processor.processOne(99L, TODAY)).isEqualTo(ProcessOutcome.NOT_ELIGIBLE);
        verify(executionRepository, never()).save(any());
    }

    @Test
    void usesThePassedEasternDateNotTheClock() {
        LocalDate night = LocalDate.of(2031, 3, 3);
        schedule.setNextInvestmentDate(night);
        assertThat(processor.processOne(7L, night)).isEqualTo(ProcessOutcome.QUEUED);
        InvestmentExecution saved = savedExecution();
        assertThat(saved.getSourceKey()).isEqualTo("SCHED:7:2031-03-03");
        assertThat(saved.getNightKey()).isEqualTo("42:2031-03-03");
    }
}
