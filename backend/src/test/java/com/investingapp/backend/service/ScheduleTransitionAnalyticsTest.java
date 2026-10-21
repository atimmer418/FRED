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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * schedule_paused and schedule_resumed, pinned at their four emitting call sites.
 *
 * <p>THE GUARD IS THE SUBJECT OF THIS TEST, more than the event. These events exist so
 * v_schedule_churn can count how many schedules actually stopped and started, so only a real state
 * transition may emit. Pausing an already-paused schedule and resuming an already-running one are
 * no-ops and must be silent, because a no-op counted as a transition inflates the churn number the
 * view reports. Every one of the four sites therefore has both a transition test and a no-op test.
 *
 * <p>Each event carries exactly two properties, in this order: {@code initiator} and {@code reason}.
 * The whole map is asserted rather than a subset, in both content and key order: a property that
 * quietly appears is a privacy problem (no amount, no frequency, no bank, no id may ever be sent)
 * and a property that quietly disappears breaks the view, which reads $.initiator and $.reason.
 *
 * <p>Atomicity is not testable here, because a Mockito test has no transaction and
 * {@link AnalyticsOutboxService} is a mock. That is
 * {@link ScheduleTransitionOutboxRollbackTest}'s job.
 *
 * <p>investment_schedule_created has its own test, {@link InvestmentScheduleServiceAnalyticsTest};
 * the one case from that path asserted here is the trap, that being BORN paused emits no pause.
 */
@ExtendWith(MockitoExtension.class)
class ScheduleTransitionAnalyticsTest {

    /** The literal wire names. Spelled out so a rename of a constant cannot pass unnoticed. */
    private static final String PAUSED = "schedule_paused";
    private static final String RESUMED = "schedule_resumed";
    private static final String CREATED = "investment_schedule_created";

    private static final Long SCHEDULE_ID = 42L;

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
        user.setId(91L);
        user.setEmail("schedule-transitions@example.invalid");
        user.setCreateDate(LocalDateTime.now().minusDays(30));
        // resumeSchedule gates on this; the one test that exercises the gate overrides it.
        user.setAccountStatus("ACTIVE");
    }

    /** A persisted schedule in a known pause state. */
    private InvestmentSchedule schedule(boolean paused) {
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setId(SCHEDULE_ID);
        schedule.setUser(user);
        schedule.setFrequency("BIWEEKLY");
        schedule.setInvestmentAmount(new BigDecimal("250.00"));
        schedule.setMonthlyAmount(new BigDecimal("542.50"));
        schedule.setStartDate(LocalDate.of(2026, 1, 5));
        schedule.setNextInvestmentDate(LocalDate.of(2026, 2, 5));
        schedule.setIsPaused(paused);
        return schedule;
    }

    /** save() returns what it was handed, which is what the real repository does. */
    private void stubSaveEchoesBack() {
        when(investmentScheduleRepository.save(any(InvestmentSchedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubFoundById(InvestmentSchedule schedule) {
        when(investmentScheduleRepository.findByIdAndUser(SCHEDULE_ID, user))
                .thenReturn(Optional.of(schedule));
    }

    private void stubLatestSchedule(InvestmentSchedule schedule) {
        when(investmentScheduleRepository.findTopByUserOrderByCreatedAtDesc(user))
                .thenReturn(Optional.ofNullable(schedule));
    }

    /**
     * Asserts exactly one event was recorded, for this user, with exactly these two properties in
     * this order and nothing else.
     */
    private void assertRecordedExactlyOnce(String expectedEvent, String initiator, String reason) {
        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(analyticsOutboxService, times(1))
                .record(nameCaptor.capture(), eq(user), propsCaptor.capture());

        assertEquals(expectedEvent, nameCaptor.getValue(), "the exact wire event name");

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("initiator", initiator);
        expected.put("reason", reason);

        Map<String, Object> actual = propsCaptor.getValue();
        assertEquals(expected, actual, "the whole property map, not a subset: two enums and nothing else");
        assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(actual.keySet()),
                "initiator then reason, the order the taxonomy declares");
        assertEquals(2, actual.size(), "exactly two properties");
    }

    // --- Site 2: pauseSchedule, the genuine user pause ------------------------------------------

    @Test
    void userPauseOfARunningScheduleEmitsExactlyOneEventWithTheExactPropertyMap() {
        stubFoundById(schedule(false));
        stubSaveEchoesBack();

        service.pauseSchedule(user, SCHEDULE_ID);

        assertRecordedExactlyOnce(PAUSED, "user", "user_request");
    }

    /**
     * The no-op. The REST endpoint has no guard of its own and FredChatActionService's guard reads
     * a separately loaded copy of the schedule, so a double pause reaches this method. It changes
     * nothing, so it must count as nothing.
     */
    @Test
    void pausingAnAlreadyPausedScheduleEmitsNothing() {
        stubFoundById(schedule(true));
        stubSaveEchoesBack();

        service.pauseSchedule(user, SCHEDULE_ID);

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /** A pause of a schedule that does not belong to this user throws before any state changes. */
    @Test
    void pauseOfAMissingScheduleEmitsNothing() {
        when(investmentScheduleRepository.findByIdAndUser(SCHEDULE_ID, user))
                .thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> service.pauseSchedule(user, SCHEDULE_ID));

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    // --- Site 3: resumeSchedule, the genuine user resume ----------------------------------------

    @Test
    void userResumeOfAPausedScheduleEmitsExactlyOneEventWithTheExactPropertyMap() {
        stubFoundById(schedule(true));
        stubSaveEchoesBack();

        service.resumeSchedule(user, SCHEDULE_ID);

        assertRecordedExactlyOnce(RESUMED, "user", "user_request");
    }

    /** The no-op: nothing resumed, so nothing is counted. */
    @Test
    void resumingAnAlreadyRunningScheduleEmitsNothing() {
        stubFoundById(schedule(false));
        stubSaveEchoesBack();

        service.resumeSchedule(user, SCHEDULE_ID);

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /**
     * The ACCOUNT_NOT_ACTIVE gate. It throws before the schedule is even loaded, so there is no
     * transition and nothing to emit. Nothing is stubbed here on purpose: under strict stubs, an
     * unused stub would itself prove the method never got that far, but asserting no interaction
     * with the repository says it directly.
     */
    @Test
    void resumeBlockedByAnInactiveAccountEmitsNothing() {
        user.setAccountStatus("APPROVAL_PENDING");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.resumeSchedule(user, SCHEDULE_ID));
        assertEquals("ACCOUNT_NOT_ACTIVE", thrown.getMessage());

        verify(analyticsOutboxService, never()).record(any(), any(), any());
        verify(investmentScheduleRepository, never()).findByIdAndUser(any(), any());
        verify(investmentScheduleRepository, never()).save(any(InvestmentSchedule.class));
    }

    @Test
    void resumeOfAMissingScheduleEmitsNothing() {
        when(investmentScheduleRepository.findByIdAndUser(SCHEDULE_ID, user))
                .thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> service.resumeSchedule(user, SCHEDULE_ID));

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    // --- Site 4: resetForBankChange, the system pause -------------------------------------------

    @Test
    void bankChangePauseOfARunningScheduleEmitsASystemPause() {
        stubLatestSchedule(schedule(false));
        stubSaveEchoesBack();

        service.resetForBankChange(user);

        assertRecordedExactlyOnce(PAUSED, "system", "bank_change");
    }

    /**
     * A bank changed while the schedule was already paused: during onboarding before ACH ever
     * landed, or a second bank change in a row. Nothing stopped, so nothing is counted.
     */
    @Test
    void bankChangePauseOfAnAlreadyPausedScheduleEmitsNothing() {
        stubLatestSchedule(schedule(true));
        stubSaveEchoesBack();

        service.resetForBankChange(user);

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /** No schedule at all: the method returns early, well before any emit. */
    @Test
    void bankChangeWithNoScheduleEmitsNothing() {
        stubLatestSchedule(null);

        service.resetForBankChange(user);

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    // --- Site 5: updateWithAchRequestId, the system resume --------------------------------------

    @Test
    void achConfirmationOfAPausedScheduleEmitsASystemResume() {
        stubLatestSchedule(schedule(true));
        stubSaveEchoesBack();

        service.updateWithAchRequestId(user, "ach-relationship-id");

        assertRecordedExactlyOnce(RESUMED, "system", "ach_confirmed");
    }

    /**
     * The ACH id being rewritten on a schedule that is already live. Both
     * AccountStatusService.attemptAchCreation (the "ACH already exists" branch) and the ACH retry
     * scheduler can do this. It resumed nothing.
     */
    @Test
    void achUpdateOfAnAlreadyRunningScheduleEmitsNothing() {
        stubLatestSchedule(schedule(false));
        stubSaveEchoesBack();

        service.updateWithAchRequestId(user, "ach-relationship-id");

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /** No schedule yet: the ACH id is kept on the user and this returns null without emitting. */
    @Test
    void achUpdateWithNoScheduleEmitsNothing() {
        stubLatestSchedule(null);

        service.updateWithAchRequestId(user, "ach-relationship-id");

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    // --- Site 1: the trap. A schedule is BORN paused, which is not a pause. ---------------------

    /**
     * createInvestmentSchedule sets isPaused(true) on a brand new schedule, because a schedule
     * waits for ACH confirmation before it may run. That is not a transition out of a running
     * state, and investment_schedule_created already records the schedule coming into existence.
     * If a schedule_paused ever appears here, every new user shows up as immediate churn.
     */
    @Test
    void aNewlyCreatedScheduleEmitsOnlyCreatedNeverAPause() {
        stubLatestSchedule(null);
        stubSaveEchoesBack();

        service.createInvestmentSchedule(user, new BigDecimal("250.00"), "BIWEEKLY",
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6));

        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(analyticsOutboxService, times(1)).record(nameCaptor.capture(), eq(user), any());

        assertEquals(List.of(CREATED), nameCaptor.getAllValues(),
                "being born paused is not a pause: exactly one event, and it is the creation");
        verify(analyticsOutboxService, never()).record(eq(PAUSED), any(), any());
    }
}
