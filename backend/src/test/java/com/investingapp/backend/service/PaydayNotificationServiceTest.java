package com.investingapp.backend.service;

import com.investingapp.backend.model.HoldReason;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Scope: the MANUAL D-1 notice only (spec section 10, PAYDAY_D1 MANUAL row).
 *
 * <p>Pins the copy, the Eastern date pass-through (the service never reads the clock), and the
 * blocking-hold skip. The JPQL mode filter in InvestmentScheduleRepository is not provable here;
 * BackendApplicationTests under the ci profile parses it at context start.
 */
class PaydayNotificationServiceTest {

    private static final LocalDate TODAY_ET = LocalDate.of(2026, 11, 5);
    private static final LocalDate TOMORROW = LocalDate.of(2026, 11, 6);

    private PaydayNotificationService service;
    private EmailService emailService;
    private InvestmentScheduleRepository scheduleRepository;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PaydayNotificationService();
        emailService = mock(EmailService.class);
        scheduleRepository = mock(InvestmentScheduleRepository.class);

        ReflectionTestUtils.setField(service, "emailService", emailService);
        ReflectionTestUtils.setField(service, "investmentScheduleRepository", scheduleRepository);
        ReflectionTestUtils.setField(service, "investmentExecutionRepository",
                mock(InvestmentExecutionRepository.class));

        user = new User("payday@fredvested.com");
        user.setId(42L);
    }

    // --- fixtures ----------------------------------------------------------------------------

    private InvestmentSchedule manualScheduleDueTomorrow(String amount) {
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setId(7L);
        schedule.setUser(user);
        schedule.setNextInvestmentDate(TOMORROW);
        schedule.setInvestmentAmount(new BigDecimal(amount));
        schedule.setIsPaused(false);
        return schedule;
    }

    // --- copy --------------------------------------------------------------------------------

    @Test
    void manualD1EmailSaysFredWillInvestTomorrow() {
        when(scheduleRepository.findActiveByNextInvestmentDate(TOMORROW))
                .thenReturn(List.of(manualScheduleDueTomorrow("300.00")));

        service.sendDayBeforeNotifications(TODAY_ET);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmail(eq("payday@fredvested.com"), subject.capture(), body.capture());
        assertThat(subject.getValue()).isEqualTo("Your investment hits tomorrow");
        assertThat(body.getValue())
                .isEqualTo("FRED will invest $300 tomorrow. Your Freedom Date moves closer.");
    }

    // --- Eastern date pass-through -----------------------------------------------------------

    @Test
    void queriesTheDayAfterTheEasternDateItWasGiven() {
        // A date far from the real today: if the service read the clock, this would not match.
        LocalDate givenTodayET = LocalDate.of(2031, 2, 27);
        when(scheduleRepository.findActiveByNextInvestmentDate(any())).thenReturn(List.of());

        service.sendDayBeforeNotifications(givenTodayET);

        verify(scheduleRepository).findActiveByNextInvestmentDate(LocalDate.of(2031, 2, 28));
        verifyNoInteractions(emailService);
    }

    // --- blocking holds (spec 5.2 MANUAL clause; section 10 "no blocking hold") ---------------

    @Test
    void manualReviewHoldSendsNoD1Notice() {
        InvestmentSchedule held = manualScheduleDueTomorrow("300.00");
        held.setHoldReason(HoldReason.MANUAL_REVIEW);
        when(scheduleRepository.findActiveByNextInvestmentDate(TOMORROW)).thenReturn(List.of(held));

        service.sendDayBeforeNotifications(TODAY_ET);

        verifyNoInteractions(emailService);
    }

    @Test
    void achReturnHoldsSendNoD1Notice() {
        InvestmentSchedule unauthorized = manualScheduleDueTomorrow("300.00");
        unauthorized.setHoldReason(HoldReason.ACH_RETURN_UNAUTHORIZED);
        InvestmentSchedule account = manualScheduleDueTomorrow("300.00");
        account.setHoldReason(HoldReason.ACH_RETURN_ACCOUNT);
        when(scheduleRepository.findActiveByNextInvestmentDate(TOMORROW))
                .thenReturn(List.of(unauthorized, account));

        service.sendDayBeforeNotifications(TODAY_ET);

        verifyNoInteractions(emailService);
    }

    @Test
    void bankChangeHoldStillGetsTheD1Notice() {
        // Informational hold: the 15-minute ACH poll clears it before the 11:55 PM pull.
        InvestmentSchedule held = manualScheduleDueTomorrow("300.00");
        held.setHoldReason(HoldReason.BANK_CHANGE);
        when(scheduleRepository.findActiveByNextInvestmentDate(TOMORROW)).thenReturn(List.of(held));

        service.sendDayBeforeNotifications(TODAY_ET);

        verify(emailService).sendEmail(eq("payday@fredvested.com"),
                eq("Your investment hits tomorrow"),
                eq("FRED will invest $300 tomorrow. Your Freedom Date moves closer."));
    }

    // --- constants: push copy and the no-em-dash rule (spec section 10) ----------------------

    @Test
    void pushCopyIsTheFirstSentenceAndNoD1StringCarriesAnEmDash() {
        assertThat(String.format(PaydayNotificationService.D1_MANUAL_PUSH_BODY, "$300"))
                .isEqualTo("FRED will invest $300 tomorrow.");
        assertThat(PaydayNotificationService.D1_MANUAL_PUSH_TITLE).isEqualTo("Investment tomorrow");
        assertThat(PaydayNotificationService.D1_MANUAL_SUBJECT).isEqualTo("Your investment hits tomorrow");
        assertThat(PaydayNotificationService.BLOCKING_HOLDS).containsExactlyInAnyOrder(
                HoldReason.ACH_RETURN_UNAUTHORIZED, HoldReason.ACH_RETURN_ACCOUNT, HoldReason.MANUAL_REVIEW);
        // U+2014 is the em dash; the escape keeps the character itself out of the source tree.
        for (String copy : List.of(
                PaydayNotificationService.D1_MANUAL_SUBJECT,
                PaydayNotificationService.D1_MANUAL_BODY,
                PaydayNotificationService.D1_MANUAL_PUSH_TITLE,
                PaydayNotificationService.D1_MANUAL_PUSH_BODY)) {
            assertThat(copy).doesNotContain("\u2014");
        }
    }
}
