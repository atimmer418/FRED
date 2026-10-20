package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The investment_schedule_created event, pinned at its emitting service.
 *
 * <p>Two things matter here and nothing else does. First, the CREATE branch emits exactly one event
 * with exactly these three properties, in this order, all of them banded: the whole map is asserted
 * rather than a subset, because a property that quietly appears is a privacy problem and a property
 * that quietly disappears is a broken funnel.
 *
 * <p>Second, nothing else emits. {@code POST /api/investment-schedule/create} doubles as the edit
 * endpoint and investment_schedules has a UNIQUE key on user_id, so the update branch is a user
 * changing the one schedule they already have. The taxonomy has no schedule_updated event, so an
 * edit must be silent, and so must a create whose save throws.
 *
 * <p>Atomicity is not testable here, because a Mockito test has no transaction. That is
 * {@link InvestmentScheduleCreatedOutboxRollbackTest}'s job.
 */
@ExtendWith(MockitoExtension.class)
class InvestmentScheduleServiceAnalyticsTest {

    /** The literal wire name. Spelled out so a rename of the constant cannot pass unnoticed. */
    private static final String EVENT = "investment_schedule_created";

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

    @Captor
    private ArgumentCaptor<Map<String, Object>> propsCaptor;

    private User user;

    @BeforeEach
    void setUpUser() {
        user = new User();
        user.setId(77L);
        user.setEmail("schedule-analytics@example.invalid");
        // Five days and an hour ago, so Duration.toDays() is unambiguously 5 -> the '4_7' band.
        user.setCreateDate(LocalDateTime.now().minusDays(5).minusHours(1));
    }

    /** save() returns what it was handed, which is what the real repository does for a new row. */
    private void stubSaveEchoesBack() {
        when(investmentScheduleRepository.save(any(InvestmentSchedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createBranchEmitsExactlyOneEventWithTheExactPropertyMap() {
        when(investmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(user))
                .thenReturn(Optional.empty());
        stubSaveEchoesBack();

        service.createInvestmentSchedule(user, new BigDecimal("250.00"), "BIWEEKLY",
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6));

        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(analyticsOutboxService, times(1))
                .record(nameCaptor.capture(), eq(user), propsCaptor.capture());

        assertEquals(EVENT, nameCaptor.getValue());

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("frequency", "biweekly");
        expected.put("amount_band", "250_499");
        expected.put("days_since_signup_band", "4_7");

        Map<String, Object> actual = propsCaptor.getValue();
        assertEquals(expected, actual, "the whole property map, not a subset");
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(actual.keySet()),
                "properties are sent in taxonomy order");
    }

    /**
     * The only user-visible edit path. It reaches the same endpoint and the same method, and it must
     * emit nothing at all.
     */
    @Test
    void updateBranchEmitsNothing() {
        InvestmentSchedule existing = new InvestmentSchedule();
        existing.setId(12L);
        existing.setUser(user);
        existing.setFrequency("MONTHLY");
        existing.setInvestmentAmount(new BigDecimal("100.00"));
        existing.setStartDate(LocalDate.of(2026, 1, 5));
        existing.setNextInvestmentDate(LocalDate.of(2026, 2, 5));

        when(investmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(user))
                .thenReturn(Optional.of(existing));
        stubSaveEchoesBack();

        service.createInvestmentSchedule(user, new BigDecimal("600.00"), "WEEKLY",
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6));

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /**
     * The failure path. If the schedule cannot be persisted there is no fact to describe, and the
     * emit sits after the save so it is never reached.
     */
    @Test
    void failedSaveEmitsNothing() {
        when(investmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(user))
                .thenReturn(Optional.empty());
        when(investmentScheduleRepository.save(any(InvestmentSchedule.class)))
                .thenThrow(new RuntimeException("constraint violation"));

        assertThrows(RuntimeException.class, () -> service.createInvestmentSchedule(
                user, new BigDecimal("250.00"), "BIWEEKLY",
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6)));

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /**
     * The frequency column is an unvalidated varchar fed from the request body, so the slug is a
     * closed vocabulary: the four values this app acts on, and null for anything else. Null is the
     * point. Passing the raw string through would put client-supplied free text in a dimension.
     */
    @Test
    void frequencySlugIsAClosedVocabulary() {
        assertEquals("weekly", InvestmentScheduleService.frequencySlug("WEEKLY"));
        assertEquals("biweekly", InvestmentScheduleService.frequencySlug("BIWEEKLY"));
        assertEquals("semi_monthly", InvestmentScheduleService.frequencySlug("SEMI_MONTHLY"));
        assertEquals("monthly", InvestmentScheduleService.frequencySlug("MONTHLY"));
        assertEquals("monthly", InvestmentScheduleService.frequencySlug(" monthly "));

        assertNull(InvestmentScheduleService.frequencySlug(null));
        assertNull(InvestmentScheduleService.frequencySlug(""));
        assertNull(InvestmentScheduleService.frequencySlug("QUARTERLY"));
        assertNull(InvestmentScheduleService.frequencySlug("every other tuesday"));
    }

    /** An unknown frequency still emits, with a null slug rather than the raw string. */
    @Test
    void unknownFrequencyIsSentAsNullNotAsFreeText() {
        when(investmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(user))
                .thenReturn(Optional.empty());
        stubSaveEchoesBack();

        service.createInvestmentSchedule(user, new BigDecimal("25.00"), "FORTNIGHTLY-ISH",
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6));

        verify(analyticsOutboxService, times(1)).record(eq(EVENT), eq(user), propsCaptor.capture());

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("frequency", null);
        expected.put("amount_band", "under_50");
        expected.put("days_since_signup_band", "4_7");
        assertEquals(expected, propsCaptor.getValue());
        assertEquals(List.of("frequency", "amount_band", "days_since_signup_band"),
                new ArrayList<>(propsCaptor.getValue().keySet()));
    }
}
