package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Eligibility is judged against the day the cron passes in, never against a clock the entity reads
 * itself. The dates sit in 2031 so no wall clock can satisfy them by accident.
 */
@ExtendWith(MockitoExtension.class)
class InvestmentScheduleServiceReadyDateTest {

    private static final LocalDate TODAY_ET = LocalDate.of(2031, 3, 4);

    @Mock
    private InvestmentScheduleRepository investmentScheduleRepository;
    @Mock
    private PortfolioService portfolioService;
    @Mock
    private StreakService streakService;
    @Mock
    private AnalyticsOutboxService analyticsOutboxService;

    @InjectMocks
    private InvestmentScheduleService service;

    private static InvestmentSchedule schedule(long id, LocalDate startDate, LocalDate nextInvestmentDate,
            boolean paused, String achRequestId) {
        User user = new User("ready-" + id + "@fredvested.com");
        user.setId(id);
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setId(id);
        schedule.setUser(user);
        schedule.setFrequency("WEEKLY");
        schedule.setInvestmentAmount(new BigDecimal("50.00"));
        schedule.setMonthlyAmount(new BigDecimal("216.50"));
        schedule.setStartDate(startDate);
        schedule.setNextInvestmentDate(nextInvestmentDate);
        schedule.setIsPaused(paused);
        schedule.setAchRequestId(achRequestId);
        return schedule;
    }

    @Test
    void onlySchedulesDueOnThePassedDayAreReady() {
        InvestmentSchedule dueToday = schedule(1, TODAY_ET.minusWeeks(4), TODAY_ET, false, "ach-1");
        InvestmentSchedule pastDue = schedule(2, TODAY_ET.minusWeeks(4), TODAY_ET.minusDays(3), false, "ach-2");
        InvestmentSchedule startsToday = schedule(3, TODAY_ET, TODAY_ET.plusWeeks(1), false, "ach-3");
        InvestmentSchedule dueTomorrow = schedule(4, TODAY_ET.minusWeeks(4), TODAY_ET.plusDays(1), false, "ach-4");
        InvestmentSchedule paused = schedule(5, TODAY_ET.minusWeeks(4), TODAY_ET, true, "ach-5");
        InvestmentSchedule noAch = schedule(6, TODAY_ET.minusWeeks(4), TODAY_ET, false, null);
        when(investmentScheduleRepository.findByUser_AccountStatus("ACTIVE"))
                .thenReturn(List.of(dueToday, pastDue, startsToday, dueTomorrow, paused, noAch));

        List<InvestmentSchedule> ready = service.getSchedulesReadyForInvestment(TODAY_ET);

        assertEquals(List.of(1L, 2L, 3L), ready.stream().map(InvestmentSchedule::getId).toList());
    }

    @Test
    void theSameScheduleIsNotReadyTheDayBefore() {
        InvestmentSchedule dueToday = schedule(1, TODAY_ET.minusWeeks(4), TODAY_ET, false, "ach-1");
        when(investmentScheduleRepository.findByUser_AccountStatus("ACTIVE")).thenReturn(List.of(dueToday));

        assertEquals(List.of(), service.getSchedulesReadyForInvestment(TODAY_ET.minusDays(1)));
    }
}
