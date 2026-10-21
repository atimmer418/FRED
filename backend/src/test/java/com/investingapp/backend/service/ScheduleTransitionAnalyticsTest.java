package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.InvestmentSchedule.Status;
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
 * The four status transitions of an investment schedule, and the two events that may come out of
 * them.
 *
 * <p>THE MODEL THIS PINS. {@link Status} has three values, and only four moves between them:
 *
 * <pre>
 *   PENDING_BANK   -&gt; ACTIVE          updateWithAchRequestId   emits nothing
 *   ACTIVE         -&gt; PENDING_BANK    resetForBankChange       emits nothing
 *   ACTIVE         -&gt; PAUSED_BY_USER  pauseSchedule            schedule_paused  {user, user_request}
 *   PAUSED_BY_USER -&gt; ACTIVE          resumeSchedule           schedule_resumed {user, user_request}
 * </pre>
 *
 * <p>WHAT THE STATUS BOUGHT, and what the old boolean could not express. Under the boolean, a bank
 * change emitted a system {@code schedule_paused} and the ACH relationship landing emitted a system
 * {@code schedule_resumed}, because to the code "waiting for a bank" and "the user switched it off"
 * were the same single flag. They are nothing alike, and neither is a pause: a schedule going back
 * to waiting for its bank is lifecycle, not churn. So the two system paths now emit NOTHING AT ALL,
 * and {@code schedule_paused} / {@code schedule_resumed} are user-only BY CONSTRUCTION rather than
 * by a {@code reason} property a reader has to remember to filter on. The tests over those two
 * paths therefore assert the transition and the ABSENCE of any event.
 *
 * <p>THE GUARD IS STILL THE SUBJECT, more than the event. These events exist so v_schedule_churn
 * can count how many schedules a user actually stopped and started, so only a real transition out
 * of or into ACTIVE may emit. Every call site is tested both for its one legal transition and for
 * each state it must leave alone, including the two the boolean made impossible to protect:
 * {@link #achConfirmationDoesNotRestartAScheduleTheUserPaused} and
 * {@link #bankChangeDoesNotClearAUserPause}.
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
 * the one case from that path asserted here is the trap, that being BORN PENDING_BANK is not a
 * pause.
 */
@ExtendWith(MockitoExtension.class)
class ScheduleTransitionAnalyticsTest {

    /** The literal wire names. Spelled out so a rename of a constant cannot pass unnoticed. */
    private static final String PAUSED = "schedule_paused";
    private static final String RESUMED = "schedule_resumed";
    private static final String CREATED = "investment_schedule_created";

    private static final Long SCHEDULE_ID = 42L;

    /** The relationship id a schedule that has had a bank already carries. */
    private static final String EXISTING_ACH_ID = "ach-relationship-existing";
    /** The id the ACH path writes. Distinct from the above so an overwrite is observable. */
    private static final String NEW_ACH_ID = "ach-relationship-id";

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

    /**
     * A persisted schedule in a known status.
     *
     * <p>The ACH id follows from the status rather than being a separate knob, because in the real
     * table it is not independent: a PENDING_BANK schedule has never had a relationship, and the
     * other two have. This is the same evidence V6's backfill reads to turn the old boolean into a
     * status, so a fixture that disagreed with it would be testing a row that cannot exist.
     */
    private InvestmentSchedule schedule(Status status) {
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setId(SCHEDULE_ID);
        schedule.setUser(user);
        schedule.setFrequency("BIWEEKLY");
        schedule.setInvestmentAmount(new BigDecimal("250.00"));
        schedule.setMonthlyAmount(new BigDecimal("542.50"));
        schedule.setStartDate(LocalDate.of(2026, 1, 5));
        schedule.setNextInvestmentDate(LocalDate.of(2026, 2, 5));
        schedule.setStatus(status);
        schedule.setAchRequestId(status == Status.PENDING_BANK ? null : EXISTING_ACH_ID);
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

    // --- pauseSchedule: ACTIVE -> PAUSED_BY_USER, the one event that means churn ----------------

    @Test
    void userPauseOfARunningScheduleEmitsExactlyOneEventWithTheExactPropertyMap() {
        stubFoundById(schedule(Status.ACTIVE));
        stubSaveEchoesBack();

        InvestmentSchedule result = service.pauseSchedule(user, SCHEDULE_ID);

        assertEquals(Status.PAUSED_BY_USER, result.getStatus(), "the user's pause is recorded as theirs");
        assertRecordedExactlyOnce(PAUSED, "user", "user_request");
    }

    /**
     * The no-op. The REST endpoint has no guard of its own and FredChatActionService's guard reads
     * a separately loaded copy of the schedule, so a double pause reaches this method. It changes
     * nothing, so it must count as nothing.
     */
    @Test
    void pausingAnAlreadyPausedScheduleEmitsNothing() {
        stubFoundById(schedule(Status.PAUSED_BY_USER));
        stubSaveEchoesBack();

        InvestmentSchedule result = service.pauseSchedule(user, SCHEDULE_ID);

        assertEquals(Status.PAUSED_BY_USER, result.getStatus(), "already off, still off");
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /**
     * A user cannot pause a schedule that has never run. The status is what makes this expressible
     * at all: under the boolean this schedule and a user-paused one were the same value, so the
     * call looked like a no-op for the wrong reason and nothing distinguished it from real churn.
     * It must not move to PAUSED_BY_USER, because PAUSED_BY_USER is counted.
     */
    @Test
    void pausingAScheduleStillWaitingForItsBankEmitsNothing() {
        stubFoundById(schedule(Status.PENDING_BANK));
        stubSaveEchoesBack();

        InvestmentSchedule result = service.pauseSchedule(user, SCHEDULE_ID);

        assertEquals(Status.PENDING_BANK, result.getStatus(),
                "never ran, so there is nothing to stop and nothing to count as churn");
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

    // --- resumeSchedule: PAUSED_BY_USER -> ACTIVE, the undo of churn ----------------------------

    @Test
    void userResumeOfAPausedScheduleEmitsExactlyOneEventWithTheExactPropertyMap() {
        stubFoundById(schedule(Status.PAUSED_BY_USER));
        stubSaveEchoesBack();

        InvestmentSchedule result = service.resumeSchedule(user, SCHEDULE_ID);

        assertEquals(Status.ACTIVE, result.getStatus(), "the user turned their own schedule back on");
        assertRecordedExactlyOnce(RESUMED, "user", "user_request");
    }

    /** The no-op: nothing resumed, so nothing is counted. */
    @Test
    void resumingAnAlreadyRunningScheduleEmitsNothing() {
        stubFoundById(schedule(Status.ACTIVE));
        stubSaveEchoesBack();

        InvestmentSchedule result = service.resumeSchedule(user, SCHEDULE_ID);

        assertEquals(Status.ACTIVE, result.getStatus(), "already running, still running");
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /**
     * The mirror of the pause case: a schedule waiting for its bank is started by the ACH path, not
     * by the user, so resume must leave it alone. If this moved it to ACTIVE it would start a
     * schedule with no ACH relationship, and it would book a resume the user never earned.
     */
    @Test
    void resumingAScheduleStillWaitingForItsBankEmitsNothing() {
        stubFoundById(schedule(Status.PENDING_BANK));
        stubSaveEchoesBack();

        InvestmentSchedule result = service.resumeSchedule(user, SCHEDULE_ID);

        assertEquals(Status.PENDING_BANK, result.getStatus(),
                "only the ACH relationship landing may start a schedule that has never run");
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

    // --- resetForBankChange: ACTIVE -> PENDING_BANK, lifecycle and no longer an event -----------

    /**
     * This used to emit a system schedule_paused. It is not a pause: the schedule has gone back to
     * waiting for a bank, which it can only do because that is now its own state. What is asserted
     * is the transition itself plus silence, which is what keeps Plaid outages out of the churn
     * number.
     */
    @Test
    void bankChangeMovesARunningScheduleToPendingBankAndEmitsNothing() {
        InvestmentSchedule schedule = schedule(Status.ACTIVE);
        stubLatestSchedule(schedule);
        stubSaveEchoesBack();

        service.resetForBankChange(user);

        assertEquals(Status.PENDING_BANK, schedule.getStatus(),
                "it cannot run until the new ACH relationship lands");
        assertNull(schedule.getAchRequestId(), "the old relationship is gone");
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /**
     * THE SECOND THING THE STATUS EXISTS FOR. A user who paused their schedule and then changed
     * bank has not asked for it to start again. Their intent outlives the bank change, so the
     * status is left alone while the stale relationship id is still cleared. Under the boolean
     * this was indistinguishable from the case above, which is how the ACH path came to be able to
     * restart a deliberate pause.
     */
    @Test
    void bankChangeDoesNotClearAUserPause() {
        InvestmentSchedule schedule = schedule(Status.PAUSED_BY_USER);
        stubLatestSchedule(schedule);
        stubSaveEchoesBack();

        service.resetForBankChange(user);

        assertEquals(Status.PAUSED_BY_USER, schedule.getStatus(),
                "a bank change does not undo the user's decision to stop");
        assertNull(schedule.getAchRequestId(), "the stale relationship is still cleared");
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /**
     * A second bank change in a row, or one during onboarding before ACH ever landed. It is
     * already where this path would put it.
     */
    @Test
    void bankChangeOfAScheduleAlreadyWaitingForItsBankEmitsNothing() {
        InvestmentSchedule schedule = schedule(Status.PENDING_BANK);
        stubLatestSchedule(schedule);
        stubSaveEchoesBack();

        service.resetForBankChange(user);

        assertEquals(Status.PENDING_BANK, schedule.getStatus(), "already waiting, still waiting");
        assertNull(schedule.getAchRequestId());
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /** No schedule at all: the method returns early, well before any emit. */
    @Test
    void bankChangeWithNoScheduleEmitsNothing() {
        stubLatestSchedule(null);

        service.resetForBankChange(user);

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    // --- updateWithAchRequestId: PENDING_BANK -> ACTIVE, lifecycle and no longer an event -------

    /**
     * This used to emit a system schedule_resumed. It is a schedule running for the first time, not
     * resuming: investment_schedule_created already recorded that it exists, and schedule_resumed
     * now means a user turning their own schedule back on. The transition is the assertion.
     */
    @Test
    void achConfirmationStartsAScheduleWaitingForItsBankAndEmitsNothing() {
        stubLatestSchedule(schedule(Status.PENDING_BANK));
        stubSaveEchoesBack();

        InvestmentSchedule result = service.updateWithAchRequestId(user, NEW_ACH_ID);

        assertEquals(Status.ACTIVE, result.getStatus(), "the bank landed, so it may run");
        assertEquals(NEW_ACH_ID, result.getAchRequestId());
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /**
     * THE FIRST THING THE STATUS EXISTS FOR, and the reason the old boolean was a bug and not just
     * an imprecision. AccountStatusService.attemptAchCreation and the ACH retry scheduler both
     * reach this method on their own schedule, with no idea why the schedule is stopped. Under the
     * boolean they force-unpaused it, so a retry could silently restart a schedule the user had
     * deliberately switched off and start moving their money again. The ACH path now does exactly
     * one thing, PENDING_BANK to ACTIVE, so a PAUSED_BY_USER schedule is untouchable by it: the id
     * is recorded for when the user does come back, and nothing else changes.
     */
    @Test
    void achConfirmationDoesNotRestartAScheduleTheUserPaused() {
        stubLatestSchedule(schedule(Status.PAUSED_BY_USER));
        stubSaveEchoesBack();

        InvestmentSchedule result = service.updateWithAchRequestId(user, NEW_ACH_ID);

        assertEquals(Status.PAUSED_BY_USER, result.getStatus(),
                "the ACH machinery may not override a deliberate pause");
        assertEquals(NEW_ACH_ID, result.getAchRequestId(),
                "the new relationship is still recorded, ready for whenever the user resumes");
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /**
     * The ACH id being rewritten on a schedule that is already live. Both
     * AccountStatusService.attemptAchCreation (the "ACH already exists" branch) and the ACH retry
     * scheduler can do this. It started nothing.
     */
    @Test
    void achUpdateOfAnAlreadyRunningScheduleEmitsNothing() {
        stubLatestSchedule(schedule(Status.ACTIVE));
        stubSaveEchoesBack();

        InvestmentSchedule result = service.updateWithAchRequestId(user, NEW_ACH_ID);

        assertEquals(Status.ACTIVE, result.getStatus(), "already running, still running");
        assertEquals(NEW_ACH_ID, result.getAchRequestId());
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /** No schedule yet: the ACH id is kept on the user and this returns null without emitting. */
    @Test
    void achUpdateWithNoScheduleEmitsNothing() {
        stubLatestSchedule(null);

        service.updateWithAchRequestId(user, NEW_ACH_ID);

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    // --- createInvestmentSchedule: the trap. Being BORN stopped is not a pause. -----------------

    /**
     * createInvestmentSchedule starts a schedule in PENDING_BANK, because a schedule waits for ACH
     * confirmation before it may run. That is not a transition out of a running state, and
     * investment_schedule_created already records the schedule coming into existence. If a
     * schedule_paused ever appears here, every new user shows up as immediate churn.
     *
     * <p>The status is asserted too, not just the silence: PENDING_BANK rather than PAUSED_BY_USER
     * is what makes the silence structural instead of a guard someone could delete.
     */
    @Test
    void aNewlyCreatedScheduleStartsPendingBankAndEmitsOnlyCreated() {
        stubLatestSchedule(null);
        stubSaveEchoesBack();

        InvestmentSchedule created = service.createInvestmentSchedule(user, new BigDecimal("250.00"),
                "BIWEEKLY", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6));

        assertEquals(Status.PENDING_BANK, created.getStatus(),
                "born waiting for a bank, which is not the same state as a user pause");

        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(analyticsOutboxService, times(1)).record(nameCaptor.capture(), eq(user), any());

        assertEquals(List.of(CREATED), nameCaptor.getAllValues(),
                "being born stopped is not a pause: exactly one event, and it is the creation");
        verify(analyticsOutboxService, never()).record(eq(PAUSED), any(), any());
    }
}
