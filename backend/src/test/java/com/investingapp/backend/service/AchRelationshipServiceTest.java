package com.investingapp.backend.service;

import com.investingapp.backend.model.HoldReason;
import com.investingapp.backend.model.InvestmentExecution;
import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.ScheduleMode;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentExecutionRepository;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.AchRelationshipService.AdoptResult;
import com.investingapp.backend.util.EasternTime;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AchRelationshipServiceTest {

    private static final String ACCOUNT = "acct-1";
    private final UserRepository users = mock(UserRepository.class);
    private final InvestmentScheduleRepository schedules = mock(InvestmentScheduleRepository.class);
    private final InvestmentExecutionRepository executions = mock(InvestmentExecutionRepository.class);
    private final AlpacaApiService alpaca = mock(AlpacaApiService.class);
    private final EncryptionService encryption = mock(EncryptionService.class);
    private final PlaidToAlpacaService plaidToAlpaca = mock(PlaidToAlpacaService.class);
    private final AnalyticsOutboxService analyticsOutboxService = mock(AnalyticsOutboxService.class);
    private AchRelationshipService service;
    private User user;
    private InvestmentSchedule schedule;

    @BeforeEach
    void setUp() {
        service = new AchRelationshipService(users, schedules, executions, alpaca, encryption,
                plaidToAlpaca, analyticsOutboxService);
        when(encryption.decrypt(anyString())).thenAnswer(i -> i.getArgument(0));
        when(encryption.encrypt(anyString())).thenAnswer(i -> "ENC:" + i.getArgument(0));
        user = new User("ach@fredvested.com");
        user.setId(7L);
        user.setAlpacaAccountId(ACCOUNT);
        user.setPlaidAccountId("plaid-acct");
        user.setFirstName("Ada");
        user.setLastName("Lovelace");
        user.setPlaidAccountMask("1234");
        user.setPlaidItemLinkedAt(EasternTime.now().minusHours(1));
        schedule = new InvestmentSchedule();
        schedule.setMode(ScheduleMode.MANUAL);
        // Never adopted: waiting for its bank, which is what is_paused = 1 with no ACH id meant.
        schedule.setStatus(InvestmentSchedule.Status.PENDING_BANK);
        when(schedules.findTopByUserOrderByCreatedAtDesc(user)).thenReturn(Optional.of(schedule));
    }

    private static AchRelationshipSummary rel(String id, String status, String number) {
        return new AchRelationshipSummary(id, status, OffsetDateTime.now(ZoneOffset.UTC).toString(), number);
    }

    private void alpacaHolds(AchRelationshipSummary... rels) {
        when(alpaca.listAchRelationships(eq(ACCOUNT), anyList())).thenReturn(List.of(rels));
    }

    @Test
    void firstAdoptionUnpausesNewSchedule() {
        assertThat(service.adopt(user, rel("rel-new", "QUEUED", "000000001234"))).isEqualTo(AdoptResult.ADOPTED);

        assertThat(user.getAlpacaAchRelationshipId()).isEqualTo("ENC:rel-new");
        assertThat(user.getAlpacaAchStatus()).isEqualTo("QUEUED");
        assertThat(schedule.getAchRequestId()).isEqualTo("rel-new");
        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.ACTIVE);
        verify(users).save(user);
        verify(schedules).save(schedule);
    }

    @Test
    void bankChangeHoldClearedOnAdoptManual() {
        user.setPreviousAchRelationshipId("rel-old");
        schedule.setHoldReason(HoldReason.BANK_CHANGE);

        assertThat(service.adopt(user, rel("rel-new", "QUEUED", "****1234"))).isEqualTo(AdoptResult.ADOPTED);

        assertThat(schedule.getHoldReason()).isNull();
        assertThat(schedule.getAchRequestId()).isEqualTo("rel-new");
    }

    @Test
    void adoptNeverUnpausesAfterBankChange() {
        user.setPreviousAchRelationshipId("rel-old");
        schedule.setHoldReason(HoldReason.BANK_CHANGE);

        service.adopt(user, rel("rel-new", "QUEUED", null));

        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.PENDING_BANK);
    }

    @Test
    void adoptNeverClearsUserPause() {
        schedule.setAchRequestId("rel-first"); // adopted once already, then the user paused
        schedule.setStatus(InvestmentSchedule.Status.PAUSED_BY_USER);

        assertThat(service.adopt(user, rel("rel-second", "APPROVED", null))).isEqualTo(AdoptResult.ADOPTED);

        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.PAUSED_BY_USER);
        assertThat(schedule.getAchRequestId()).isEqualTo("rel-second");
    }

    @Test
    void adoptNeverUnpausesLegacySplitRowAfterBankChange() {
        // Legacy split row: the deleted /update-ach-request-id endpoint wrote only the schedule's
        // achRequestId; alpacaAchRelationshipId was never populated, so the direct-copy capture had
        // nothing to copy.
        schedule.setAchRequestId("rel-legacy");
        schedule.setStatus(InvestmentSchedule.Status.PENDING_BANK);

        // The bank-change capture falls back to the schedule's achRequestId when
        // alpacaAchRelationshipId is null (PlaidService.exchangePublicTokenAndLinkUser /
        // AccountStatusService.handleBankAccountChange), then resetForBankChange nulls the schedule
        // id and holds it.
        user.setPreviousAchRelationshipId("rel-legacy");
        schedule.setAchRequestId(null);
        schedule.setHoldReason(HoldReason.BANK_CHANGE);

        assertThat(service.adopt(user, rel("rel-new", "QUEUED", null))).isEqualTo(AdoptResult.ADOPTED);

        assertThat(user.getPreviousAchRelationshipId()).isEqualTo("rel-legacy");
        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.PENDING_BANK);
        assertThat(schedule.getAchRequestId()).isEqualTo("rel-new");
    }

    // --- schedule_resumed: the system resume, on a real transition only -------------------------
    //
    // This is the event that used to live on InvestmentScheduleService.updateWithAchRequestId.
    // adopt is the single writer of the ACH fields now, so it is the only place that can see a
    // schedule go from stopped to running because the relationship landed. Stopped means either
    // stop: the system's hold_reason or a status that is not ACTIVE, and the two are orthogonal.
    // Both halves of the transition must hold, or the churn count this event feeds is inflated by
    // writes that started nothing.

    @Test
    @DisplayName("a bank-change hold cleared on an otherwise running schedule emits one system resume")
    void bankChangeHoldClearedEmitsOneSystemResume() {
        user.setPreviousAchRelationshipId("rel-old");
        schedule.setHoldReason(HoldReason.BANK_CHANGE);
        schedule.setStatus(InvestmentSchedule.Status.ACTIVE);

        assertThat(service.adopt(user, rel("rel-new", "QUEUED", null))).isEqualTo(AdoptResult.ADOPTED);

        assertThat(schedule.getHoldReason()).isNull();
        ArgumentCaptor<Map<String, Object>> props = ArgumentCaptor.captor();
        verify(analyticsOutboxService, times(1))
                .record(eq(ServerAnalyticsEvent.SCHEDULE_RESUMED), eq(user), props.capture());
        // The WHOLE map: two enum values, in this order, and nothing else. No bank, no id, no dates.
        assertThat(props.getValue()).containsExactly(
                entry("initiator", "system"),
                entry("reason", "ach_confirmed"));
    }

    @Test
    @DisplayName("the first-ever adoption unpauses the new schedule and emits one system resume")
    void firstAdoptionEmitsOneSystemResume() {
        assertThat(service.adopt(user, rel("rel-new", "QUEUED", null))).isEqualTo(AdoptResult.ADOPTED);

        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.ACTIVE);
        verify(analyticsOutboxService, times(1))
                .record(eq(ServerAnalyticsEvent.SCHEDULE_RESUMED), eq(user), anyMap());
    }

    /** The hold came off but the user's own pause stands: the schedule is still stopped. */
    @Test
    @DisplayName("a hold cleared on a schedule the user paused emits nothing")
    void holdClearedOnAPausedScheduleEmitsNothing() {
        user.setPreviousAchRelationshipId("rel-old");
        schedule.setHoldReason(HoldReason.BANK_CHANGE);
        schedule.setStatus(InvestmentSchedule.Status.PAUSED_BY_USER);

        assertThat(service.adopt(user, rel("rel-new", "QUEUED", null))).isEqualTo(AdoptResult.ADOPTED);

        assertThat(schedule.getHoldReason()).isNull();
        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.PAUSED_BY_USER);
        verifyNoInteractions(analyticsOutboxService);
    }

    /**
     * THE GUARD THAT MOVED HERE from the deleted updateWithAchRequestId, and the reason the old
     * boolean was a bug rather than an imprecision. The ACH retry path reaches adopt with no idea
     * why a schedule is stopped. Under the boolean it force-unpaused it, so a retry could silently
     * restart a schedule the user had deliberately switched off and start moving their money
     * again. The status makes it impossible: PENDING_BANK is the only status adopt may move, so a
     * PAUSED_BY_USER schedule is untouchable by it. The new id is still recorded, ready for
     * whenever the user does come back.
     */
    @Test
    @DisplayName("a first-ever adoption may NOT restart a schedule the user paused")
    void firstAdoptionNeverRestartsAScheduleTheUserPaused() {
        // Looks like a first adoption in every other respect: no previous id, no ACH id yet.
        schedule.setStatus(InvestmentSchedule.Status.PAUSED_BY_USER);

        assertThat(service.adopt(user, rel("rel-new", "QUEUED", null))).isEqualTo(AdoptResult.ADOPTED);

        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.PAUSED_BY_USER);
        assertThat(schedule.getAchRequestId()).isEqualTo("rel-new");
        verifyNoInteractions(analyticsOutboxService);
    }

    /** The ACH id rewritten on a schedule that was already live resumed nothing. */
    @Test
    @DisplayName("adopting onto an already running schedule emits nothing")
    void adoptOntoARunningScheduleEmitsNothing() {
        schedule.setAchRequestId("rel-first");
        schedule.setStatus(InvestmentSchedule.Status.ACTIVE);

        assertThat(service.adopt(user, rel("rel-second", "APPROVED", null))).isEqualTo(AdoptResult.ADOPTED);

        assertThat(schedule.getAchRequestId()).isEqualTo("rel-second");
        verifyNoInteractions(analyticsOutboxService);
    }

    /** A refused adoption holds the schedule for review; it started nothing. */
    @Test
    @DisplayName("a refused adoption emits nothing")
    void refusedAdoptionEmitsNothing() {
        assertThat(service.adopt(user, rel("rel-new", "QUEUED", "****9999")))
                .isEqualTo(AdoptResult.REFUSED_MASK_MISMATCH);

        verifyNoInteractions(analyticsOutboxService);
    }

    @Test
    void adoptRefusesMaskMismatch() {
        assertThat(service.adopt(user, rel("rel-new", "QUEUED", "****9999"))).isEqualTo(AdoptResult.REFUSED_MASK_MISMATCH);

        assertThat(user.getAlpacaAchRelationshipId()).isNull();
        assertThat(schedule.getAchRequestId()).isNull();
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.MANUAL_REVIEW);
        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.PENDING_BANK);
    }

    @Test
    void adoptRefusesPreviousIdAndAnythingCreatedBeforeTheLink() {
        user.setPreviousAchRelationshipId("rel-old");
        AchRelationshipSummary stale = new AchRelationshipSummary("rel-stale", "APPROVED",
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(2).toString(), null);

        assertThat(service.adopt(user, rel("rel-old", "APPROVED", "1234"))).isEqualTo(AdoptResult.REFUSED_PREVIOUS_ID);
        assertThat(service.adopt(user, stale)).isEqualTo(AdoptResult.REFUSED_PREDATES_LINK);

        assertThat(user.getAlpacaAchRelationshipId()).isNull();
        assertThat(schedule.getHoldReason()).isEqualTo(HoldReason.MANUAL_REVIEW);
    }

    @Test
    void cancelDeletesEveryActiveRelationshipUnlessATransferIsInFlight() {
        alpacaHolds(rel("rel-a", "QUEUED", null), rel("rel-b", "APPROVED", null));
        when(alpaca.deleteAchRelationship(eq(ACCOUNT), anyString())).thenReturn(true);

        assertThat(service.cancelAllActive(user)).isTrue();
        verify(alpaca).deleteAchRelationship(ACCOUNT, "rel-a");
        verify(alpaca).deleteAchRelationship(ACCOUNT, "rel-b");
        assertThat(user.getAlpacaAchStatus()).isEqualTo("CANCEL_REQUESTED");

        when(executions.existsByUserAndStatus(user, InvestmentExecution.ExecutionStatus.FUNDING_INITIATED)).thenReturn(true);
        assertThat(service.cancelAllActive(user)).isFalse();
        verify(alpaca, times(2)).deleteAchRelationship(anyString(), anyString());
    }

    @Test
    void cancelSurvivesAlpacaOutage() {
        when(alpaca.listAchRelationships(eq(ACCOUNT), anyList())).thenThrow(new ResourceAccessException("alpaca down"));

        assertThat(service.cancelAllActive(user)).isTrue();

        assertThat(user.getAlpacaAchStatus()).isNull();
        verify(alpaca, never()).deleteAchRelationship(anyString(), anyString());
    }

    @Test
    void emptyListCreatesAndAdopts() {
        alpacaHolds();
        when(plaidToAlpaca.createAchRelationshipFromPlaid(ACCOUNT, "plaid-acct", "Ada Lovelace", user))
                .thenReturn(Map.of("id", "rel-new", "status", "QUEUED", "created_at", OffsetDateTime.now(ZoneOffset.UTC).toString()));

        service.ensureRelationship(user);

        assertThat(user.getAlpacaAchRelationshipId()).isEqualTo("ENC:rel-new");
        assertThat(schedule.getAchRequestId()).isEqualTo("rel-new");
        assertThat(schedule.getStatus()).isEqualTo(InvestmentSchedule.Status.ACTIVE);
    }

    @Test
    void conflictFromCreateIsNotAdopted() {
        alpacaHolds();
        when(plaidToAlpaca.createAchRelationshipFromPlaid(ACCOUNT, "plaid-acct", "Ada Lovelace", user))
                .thenReturn(Map.of("error", "Alpaca returned 409: an ACH relationship already exists; waiting for cancellation", "conflict", true));

        service.ensureRelationship(user);

        assertThat(user.getAlpacaAchRelationshipId()).isNull();
        assertThat(schedule.getAchRequestId()).isNull();
    }

    @Test
    void retryWaitsForCancel() {
        user.setPreviousAchRelationshipId("rel-old");
        alpacaHolds(rel("rel-old", "CANCEL_REQUESTED", null));

        service.ensureRelationship(user);
        user.setPlaidItemLinkedAt(EasternTime.now().minusHours(25)); // older than 24 h: alert log only, still no create
        service.ensureRelationship(user);

        verify(plaidToAlpaca, never()).createAchRelationshipFromPlaid(any(), any(), any(), any());
        verify(alpaca, never()).deleteAchRelationship(anyString(), anyString());
        assertThat(user.getAlpacaAchRelationshipId()).isNull();
    }

    @Test
    void pollRetriesDeferredCancel() {
        user.setPreviousAchRelationshipId("rel-old");
        alpacaHolds(rel("rel-old", "APPROVED", null));
        when(alpaca.deleteAchRelationship(ACCOUNT, "rel-old")).thenReturn(true);

        service.ensureRelationship(user);

        verify(alpaca).deleteAchRelationship(ACCOUNT, "rel-old");
        verify(plaidToAlpaca, never()).createAchRelationshipFromPlaid(any(), any(), any(), any());
        assertThat(user.getAlpacaAchStatus()).isEqualTo("CANCEL_REQUESTED");
    }

    @Test
    void unknownRelationshipMakesThePollWait() {
        alpacaHolds(rel("rel-mystery", "APPROVED", null));

        service.ensureRelationship(user);

        verify(plaidToAlpaca, never()).createAchRelationshipFromPlaid(any(), any(), any(), any());
        verify(alpaca, never()).deleteAchRelationship(anyString(), anyString());
        assertThat(user.getAlpacaAchRelationshipId()).isNull();
    }
}
