package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * monthly_amount is derived from the per-paycheck amount through InvestmentFrequency, so the
 * stored value is rounded to cents exactly like the client's InvestmentFrequencyUtils.
 */
@ExtendWith(MockitoExtension.class)
class InvestmentScheduleServiceMonthlyAmountTest {

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

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(78L);
        user.setEmail("monthly-amount@example.invalid");
        user.setCreateDate(LocalDateTime.now().minusDays(1));
        when(investmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());
        when(investmentScheduleRepository.save(any(InvestmentSchedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private BigDecimal monthlyAmountFor(String investmentAmount, String frequency) {
        InvestmentSchedule saved = service.createInvestmentSchedule(user, new BigDecimal(investmentAmount),
                frequency, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6));
        return saved.getMonthlyAmount();
    }

    @Test
    void weeklyMonthlyAmountIsRoundedToCentsHalfUp() {
        // 15.50 x 4.33 = 67.115, which the client rounds to 67.12; the row must hold the same number.
        assertEquals(new BigDecimal("67.12"), monthlyAmountFor("15.50", "WEEKLY"));
    }

    @Test
    void biweeklyAndSemiMonthlyUseTheEnumFactors() {
        assertEquals(new BigDecimal("542.50"), monthlyAmountFor("250.00", "BIWEEKLY"));
        assertEquals(new BigDecimal("500.00"), monthlyAmountFor("250.00", "SEMI_MONTHLY"));
        assertEquals(new BigDecimal("500.00"), monthlyAmountFor("250.00", "SEMIMONTHLY"));
    }

    @Test
    void unknownFrequencyKeepsTheLenientDefault() {
        // The column is an unvalidated varchar; an unknown value stores the per-paycheck amount as
        // the monthly amount, exactly as before, so the analytics null-slug path keeps working.
        assertEquals(new BigDecimal("25.00"), monthlyAmountFor("25.00", "FORTNIGHTLY-ISH"));
    }
}
