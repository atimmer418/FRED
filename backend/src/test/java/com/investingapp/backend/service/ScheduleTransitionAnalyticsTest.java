package com.investingapp.backend.service;

import com.investingapp.backend.model.HoldReason;
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
 * schedule_paused and schedule_resumed, pinned at the emitting call sites in this class.
 *
 * <p>THE GUARD IS THE SUBJECT OF THIS TEST, more than the event. These events exist so
 * v_schedule_churn can count how many schedules actually stopped and started, so only a real state
 * transition may emit. Pausing an already-paused schedule and resuming an already-running one are
 * no-ops and must be silent, because a no-op counted as a transition inflates the churn number the
 * view reports. Every site here therefore has both a transition test and a no-op test.
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
 * <p>THE MODEL THIS PINS. A schedule has two independent stops, and the whole point of this file
 * is that neither one is allowed to move the other:
 *
 * <pre>
 *   status       the user and bank lifecycle: PENDING_BANK, ACTIVE, PAUSED_BY_USER.
 *                Moved by the user's own pause and resume, and by the ACH relationship landing.
 *   hold_reason  a system stop with a reason (BANK_CHANGE, MANUAL_REVIEW, an ACH return, ...).
 *                Set and cleared by the system only. A user resume may NOT clear one.
 * </pre>
 *
 * A schedule runs only when status is ACTIVE and no blocking hold is set, so the two have to be
 * read together to answer "is it running" but written entirely apart. That is why a bank change
 * sets a hold and leaves the status untouched, and why a user pausing a held schedule still
 * records a user pause: their decision is real whether or not the system was already stopping it.
 *
 * <p>The system events are initiator=system and the user events initiator=user, and
 * v_schedule_churn filters on that, so one real restart producing both a system resume (the hold
 * clearing) and a user resume (the status moving) does not double-count as churn.
 *
 * <p>The fourth site, the system resume on ACH confirmation, moved to
 * AchRelationshipService.adopt with the rest of the ACH writes, and is pinned in
 * {@link AchRelationshipServiceTest}. updateWithAchRequestId no longer exists; the guard that the
 * ACH path may never restart a schedule the user paused is pinned there too.
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
    private static final String EXISTING_ACH_ID = "ach-existing";

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
     * A persisted schedule in a known status, with no hold.
     *
     * <p>The ACH id follows from the status rather than being a separate knob, because in the real
     * table it is not independent: a PENDING_BANK schedule has never had a relationship, and the
     * other two have. This is the same evidence V7's backfill reads to turn the old boolean into a
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

    /**
     * A RUNNING schedule under a system hold, which is the other, orthogonal way to be stopped.
     * The status still says ACTIVE: the user never asked for this one to stop.
     */
    private InvestmentSchedule heldSchedule(HoldReason reason) {
        InvestmentSchedule held = schedule(Status.ACTIVE);
        held.setHoldReason(reason);
        return held;
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
        stubFoundById(schedule(Status.ACTIVE));
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
        stubFoundById(schedule(Status.PAUSED_BY_USER));
        stubSaveEchoesBack();

        service.pauseSchedule(user, SCHEDULE_ID);

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /**
     * A user pausing a schedule the system happens to be holding. This DOES emit, and that is the
     * orthogonality rule doing its work: the status moved ACTIVE to PAUSED_BY_USER, which is a
     * real decision by a real person, and it stays true after the hold clears. Suppressing it
     * because a bank change got there first would lose a genuine churn signal and leave the
     * schedule in PAUSED_BY_USER with no event explaining how it got there.
     *
     * <p>The pause does not touch the hold, and the hold did not stop the pause.
     */
    @Test
    void pausingAHeldButRunningScheduleStillEmitsTheUserPause() {
        InvestmentSchedule held = heldSchedule(HoldReason.BANK_CHANGE);
        stubFoundById(held);
        stubSaveEchoesBack();

        service.pauseSchedule(user, SCHEDULE_ID);

        assertRecordedExactlyOnce(PAUSED, "user", "user_request");
        assertEquals(Status.PAUSED_BY_USER, held.getStatus(), "the user's stop went on");
        assertEquals(HoldReason.BANK_CHANGE, held.getHoldReason(),
                "and the system's hold was left exactly as it was");
    }

    /** The no-op that is still a no-op: a schedule waiting for its bank was never running. */
    @Test
    void pausingAScheduleWaitingForItsBankEmitsNothing() {
        stubFoundById(schedule(Status.PENDING_BANK));
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
        stubFoundById(schedule(Status.PAUSED_BY_USER));
        stubSaveEchoesBack();

        service.resumeSchedule(user, SCHEDULE_ID);

        assertRecordedExactlyOnce(RESUMED, "user", "user_request");
    }

    /** The no-op: nothing resumed, so nothing is counted. */
    @Test
    void resumingAnAlreadyRunningScheduleEmitsNothing() {
        stubFoundById(schedule(Status.ACTIVE));
        stubSaveEchoesBack();

        service.resumeSchedule(user, SCHEDULE_ID);

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /**
     * A resume of a schedule the system still holds. The status moves back to ACTIVE and the event
     * is emitted, because the user really did turn their schedule back on; the hold is NOT cleared,
     * so the schedule still does not invest. A user cannot wave away an ACH return by pressing
     * resume, and the app has to keep telling them why it is still stopped.
     *
     * <p>This does not double-count against AchRelationshipService.adopt. That one emits when the
     * SYSTEM clears its own hold (initiator=system); this one records the USER's status change
     * (initiator=user). They are two different transitions by two different actors, and
     * v_schedule_churn counts only the user's.
     */
    @Test
    void resumingAScheduleTheSystemStillHoldsEmitsTheUserResumeAndLeavesTheHold() {
        InvestmentSchedule heldAndPaused = heldSchedule(HoldReason.BANK_CHANGE);
        heldAndPaused.setStatus(Status.PAUSED_BY_USER);
        stubFoundById(heldAndPaused);
        stubSaveEchoesBack();

        service.resumeSchedule(user, SCHEDULE_ID);

        assertRecordedExactlyOnce(RESUMED, "user", "user_request");
        assertEquals(Status.ACTIVE, heldAndPaused.getStatus(), "the user's own stop is lifted");
        assertEquals(HoldReason.BANK_CHANGE, heldAndPaused.getHoldReason(),
                "but the system's hold still stops it, and a resume may never clear one");
    }

    /** A schedule waiting for its bank is started by the ACH path, not by the user's resume. */
    @Test
    void resumingAScheduleWaitingForItsBankEmitsNothing() {
        InvestmentSchedule pending = schedule(Status.PENDING_BANK);
        stubFoundById(pending);
        stubSaveEchoesBack();

        service.resumeSchedule(user, SCHEDULE_ID);

        assertEquals(Status.PENDING_BANK, pending.getStatus(), "still waiting for its bank");
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
    void bankChangeHoldOfARunningScheduleEmitsASystemPause() {
        InvestmentSchedule running = schedule(Status.ACTIVE);
        stubLatestSchedule(running);
        stubSaveEchoesBack();

        service.resetForBankChange(user);

        assertRecordedExactlyOnce(PAUSED, "system", "bank_change");
        // The hold is what stops it. The status belongs to the user and the ACH path, and a bank
        // change writes neither: leaving it ACTIVE is what keeps a Plaid outage out of churn.
        assertEquals(HoldReason.BANK_CHANGE, running.getHoldReason(), "the system hold went on");
        assertEquals(Status.ACTIVE, running.getStatus(), "and the status was left alone");
        assertNull(running.getAchRequestId(), "the old relationship is gone");
    }

    /**
     * A user who paused their schedule and then changed bank has not asked for it to start again.
     * The hold goes on, the stale relationship id is cleared, and PAUSED_BY_USER survives
     * untouched: their intent outlives the bank change. This is the guard that stops the ACH path
     * from quietly restarting a deliberate pause once the new relationship lands.
     */
    @Test
    void bankChangeDoesNotClearAUserPause() {
        InvestmentSchedule paused = schedule(Status.PAUSED_BY_USER);
        stubLatestSchedule(paused);
        stubSaveEchoesBack();

        service.resetForBankChange(user);

        assertEquals(Status.PAUSED_BY_USER, paused.getStatus(), "the user's pause stands");
        assertEquals(HoldReason.BANK_CHANGE, paused.getHoldReason(), "and the hold went on as well");
        assertNull(paused.getAchRequestId(), "the old relationship is gone");
        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /**
     * A second bank change in a row, or a bank change while an earlier hold was still on. The
     * schedule was already stopped, so nothing stopped here and nothing is counted.
     */
    @Test
    void bankChangeHoldOfAnAlreadyHeldScheduleEmitsNothing() {
        stubLatestSchedule(heldSchedule(HoldReason.BANK_CHANGE));
        stubSaveEchoesBack();

        service.resetForBankChange(user);

        verify(analyticsOutboxService, never()).record(any(), any(), any());
    }

    /**
     * A bank changed while the schedule was paused by its owner: during onboarding before ACH ever
     * landed, or after the user stopped it themselves. Nothing stopped, so nothing is counted.
     */
    @Test
    void bankChangeHoldOfAnAlreadyPausedScheduleEmitsNothing() {
        stubLatestSchedule(schedule(Status.PAUSED_BY_USER));
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

    // --- Site 5: the system resume, which now lives in AchRelationshipService.adopt -------------
    // updateWithAchRequestId is gone (Phase 0 made AchRelationshipService the single writer of the
    // ACH fields), and its resume cases are AchRelationshipServiceTest's.

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
