package com.investingapp.backend.service;

import com.investingapp.backend.model.InvestmentSchedule;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * kyc_decided, driven the way it is really driven: through the status poller, which is the only
 * caller of the three decision handlers.
 *
 * Each test asserts the WHOLE property map, not a subset, because a stray property is how PII
 * reaches a third party. The no-op cases matter just as much: an intermediate broker status, an
 * unchanged status and a failed broker call must emit nothing at all, or the funnel counts
 * decisions that were never made.
 */
@ExtendWith(MockitoExtension.class)
class AccountStatusServiceKycDecidedTest {

    private static final String ENCRYPTED_ACCOUNT = "ENC:alpaca-account";
    private static final String ALPACA_ACCOUNT = "acct-1";
    private static final String PUBLIC_ID = "11111111-2222-3333-4444-555555555555";

    @Mock
    private UserRepository userRepository;
    @Mock
    private InvestmentScheduleRepository investmentScheduleRepository;
    @Mock
    private AlpacaApiService alpacaApiService;
    @Mock
    private EncryptionService encryptionService;
    @Mock
    private EmailService emailService;
    @Mock
    private PlaidToAlpacaService plaidToAlpacaService;
    @Mock
    private InvestmentScheduleService investmentScheduleService;
    @Mock
    private AnalyticsOutboxService analyticsOutboxService;
    @Mock
    private AchRelationshipService achRelationshipService;

    @Captor
    private ArgumentCaptor<String> eventNameCaptor;
    @Captor
    private ArgumentCaptor<User> subjectCaptor;
    @Captor
    private ArgumentCaptor<Map<String, Object>> propertiesCaptor;

    private AccountStatusService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AccountStatusService(
                userRepository,
                investmentScheduleRepository,
                alpacaApiService,
                encryptionService,
                emailService,
                plaidToAlpacaService,
                investmentScheduleService,
                analyticsOutboxService,
                achRelationshipService);

        user = new User("kyc@fredvested.com");
        user.setId(42L);
        user.setPublicId(PUBLIC_ID);
        user.setAccountStatus("APPROVAL_PENDING");
        user.setAlpacaAccountId(ENCRYPTED_ACCOUNT);
        // Two days ago, so days_to_decision_band is the deterministic "1_3".
        user.setKycSubmittedAt(LocalDateTime.now().minusDays(2));
    }

    /** The one external input the poller has: what the broker says this account's status is. */
    private void brokerSays(String status) {
        when(userRepository.findUsersWithPendingAccountStatus()).thenReturn(List.of(user));
        when(encryptionService.decrypt(ENCRYPTED_ACCOUNT)).thenReturn(ALPACA_ACCOUNT);
        when(alpacaApiService.getAccountStatus(ALPACA_ACCOUNT))
                .thenReturn("{\"status\":\"" + status + "\"}");
    }

    /**
     * Asserts exactly one event, with the exact name, for the exact user, carrying exactly these
     * two properties and nothing else.
     */
    private void assertOneDecisionEvent(String outcome, String daysBand) {
        verify(analyticsOutboxService, times(1))
                .record(eventNameCaptor.capture(), subjectCaptor.capture(), propertiesCaptor.capture());
        verifyNoMoreInteractions(analyticsOutboxService);

        assertThat(eventNameCaptor.getValue()).isEqualTo("kyc_decided");
        assertThat(eventNameCaptor.getValue()).isEqualTo(ServerAnalyticsEvent.KYC_DECIDED);
        assertThat(subjectCaptor.getValue()).isSameAs(user);
        assertThat(propertiesCaptor.getValue()).containsExactly(
                entry("outcome", outcome),
                entry("days_to_decision_band", daysBand));
    }

    @Test
    @DisplayName("ACTIVE from the broker is one approved decision")
    void activeIsApproved() {
        brokerSays("ACTIVE");

        service.checkAndUpdateAccountStatuses();

        assertOneDecisionEvent("approved", "1_3");
    }

    @Test
    @DisplayName("ACTION_REQUIRED from the broker is one needs_more_info decision")
    void actionRequiredNeedsMoreInfo() {
        brokerSays("ACTION_REQUIRED");

        service.checkAndUpdateAccountStatuses();

        assertOneDecisionEvent("needs_more_info", "1_3");
    }

    @Test
    @DisplayName("REJECTED from the broker is one rejected decision")
    void rejectedIsRejected() {
        brokerSays("REJECTED");

        service.checkAndUpdateAccountStatuses();

        assertOneDecisionEvent("rejected", "1_3");
    }

    @Test
    @DisplayName("a rejected applicant keeps their row, marked REJECTED, and is never deleted")
    void rejectionKeepsTheRow() {
        brokerSays("REJECTED");
        AtomicReference<String> identityAtRecordTime = new AtomicReference<>();
        when(analyticsOutboxService.record(anyString(), any(User.class), anyMap()))
                .thenAnswer(invocation -> {
                    User subject = invocation.getArgument(1);
                    identityAtRecordTime.set(subject.getId() + "|" + subject.getPublicId());
                    return null;
                });

        service.checkAndUpdateAccountStatuses();

        // Decision 8, and the standing rule that no client record is hard-deleted without asking.
        // This used to call UserDeletionService; now nothing deletes a rejected applicant, and the
        // row is the recordkeeping an RIA is expected to be able to produce.
        assertThat(user.getAccountStatus()).isEqualTo("REJECTED");
        verify(userRepository).save(user);
        verify(userRepository, never()).delete(any());
        // Still recorded with a real identity, which is now simply the identity it keeps.
        assertThat(identityAtRecordTime.get()).isEqualTo("42|" + PUBLIC_ID);
    }

    @Test
    @DisplayName("the decision is recorded and the status written in one transaction, in that order")
    void theDecisionIsRecordedBeforeTheStatusIsWritten() {
        brokerSays("REJECTED");

        service.checkAndUpdateAccountStatuses();

        assertOneDecisionEvent("rejected", "1_3");
        InOrder order = inOrder(analyticsOutboxService, userRepository);
        order.verify(analyticsOutboxService)
                .record(eq(ServerAnalyticsEvent.KYC_DECIDED), eq(user), anyMap());
        order.verify(userRepository).save(user);
        // The poller excludes REJECTED, so the event cannot fire a second time for this user.
        assertThat(user.getAccountStatus()).isEqualTo("REJECTED");
        verify(userRepository, never()).delete(any());
    }

    @Test
    @DisplayName("a user with no submission stamp reports an unknown decision time, not zero")
    void missingSubmissionStampBandsToUnknown() {
        user.setKycSubmittedAt(null);
        brokerSays("ACTIVE");

        service.checkAndUpdateAccountStatuses();

        assertOneDecisionEvent("approved", "unknown");
    }

    @Test
    @DisplayName("a same-day decision bands to same_day")
    void sameDayDecision() {
        user.setKycSubmittedAt(LocalDateTime.now().minusHours(3));
        brokerSays("ACTIVE");

        service.checkAndUpdateAccountStatuses();

        assertOneDecisionEvent("approved", "same_day");
    }

    @Test
    @DisplayName("an intermediate broker status is not a decision and emits nothing")
    void intermediateStatusEmitsNothing() {
        user.setAccountStatus("SUBMITTED");
        brokerSays("APPROVAL_PENDING");

        service.checkAndUpdateAccountStatuses();

        verify(userRepository).save(user); // the status is still recorded
        verify(analyticsOutboxService, never()).record(anyString(), any(), anyMap());
    }

    @Test
    @DisplayName("an unchanged status emits nothing, so a re-poll of the same decision cannot double count")
    void unchangedStatusEmitsNothing() {
        user.setAccountStatus("ACTION_REQUIRED");
        brokerSays("ACTION_REQUIRED");

        service.checkAndUpdateAccountStatuses();

        verify(userRepository, never()).save(any());
        verify(analyticsOutboxService, never()).record(anyString(), any(), anyMap());
    }

    @Test
    @DisplayName("a failed broker call emits nothing")
    void failedBrokerCallEmitsNothing() {
        when(userRepository.findUsersWithPendingAccountStatus()).thenReturn(List.of(user));
        when(encryptionService.decrypt(ENCRYPTED_ACCOUNT)).thenReturn(ALPACA_ACCOUNT);
        when(alpacaApiService.getAccountStatus(ALPACA_ACCOUNT))
                .thenThrow(new RuntimeException("alpaca unreachable"));

        service.checkAndUpdateAccountStatuses();

        verify(analyticsOutboxService, never()).record(anyString(), any(), anyMap());
    }

    @Test
    @DisplayName("a user with no Alpaca account is skipped and emits nothing")
    void userWithoutAlpacaAccountEmitsNothing() {
        user.setAlpacaAccountId(null);
        when(userRepository.findUsersWithPendingAccountStatus()).thenReturn(List.of(user));

        service.checkAndUpdateAccountStatuses();

        verify(analyticsOutboxService, never()).record(anyString(), any(), anyMap());
    }

    @Test
    @DisplayName("the outcome vocabulary is closed: three values, and null for anything that is not a decision")
    void outcomeVocabularyIsClosed() {
        assertThat(AccountStatusService.kycOutcome("ACTIVE")).isEqualTo("approved");
        assertThat(AccountStatusService.kycOutcome("APPROVED")).isEqualTo("approved");
        assertThat(AccountStatusService.kycOutcome("approved")).isEqualTo("approved");
        assertThat(AccountStatusService.kycOutcome("REJECTED")).isEqualTo("rejected");
        assertThat(AccountStatusService.kycOutcome("ACTION_REQUIRED")).isEqualTo("needs_more_info");
        assertThat(AccountStatusService.kycOutcome("APPROVAL_PENDING")).isNull();
        assertThat(AccountStatusService.kycOutcome("SUBMITTED")).isNull();
        assertThat(AccountStatusService.kycOutcome("")).isNull();
        assertThat(AccountStatusService.kycOutcome(null)).isNull();
    }

    @Test
    @DisplayName("a bank change records the previous relationship, resets, cancels, and never creates synchronously")
    void bankChangeResetsThenCancelsAndNeverCreatesSynchronously() {
        user.setAlpacaAchRelationshipId("ENC:rel-old");
        user.setPlaidAccessToken("ENC:token");
        user.setPlaidAccountId("plaid-acct");
        user.setFirstName("Ada");
        user.setLastName("Lovelace");

        service.handleBankAccountChange(user);

        assertThat(user.getPreviousAchRelationshipId()).isEqualTo("ENC:rel-old");
        InOrder order = inOrder(investmentScheduleService, achRelationshipService);
        order.verify(investmentScheduleService).resetForBankChange(user);
        order.verify(achRelationshipService).cancelAllActive(user);
        verify(achRelationshipService, never()).ensureRelationship(any());

        service.attemptAchCreation(user);

        verify(achRelationshipService).ensureRelationship(user);
    }

    @Test
    @DisplayName("a bank change falls back to the schedule's achRequestId when the user field was never populated (legacy split row)")
    void bankChangeFallsBackToTheScheduleWhenTheUserFieldIsNull() {
        // Legacy split row: the deleted /update-ach-request-id endpoint wrote only the schedule's
        // achRequestId; alpacaAchRelationshipId was never populated, so the direct copy has nothing
        // to capture.
        InvestmentSchedule schedule = new InvestmentSchedule();
        schedule.setAchRequestId("rel-legacy");
        when(investmentScheduleService.getCurrentSchedule(user)).thenReturn(Optional.of(schedule));
        when(encryptionService.encrypt("rel-legacy")).thenReturn("ENC:rel-legacy");

        service.handleBankAccountChange(user);

        assertThat(user.getPreviousAchRelationshipId()).isEqualTo("ENC:rel-legacy");
        verify(investmentScheduleService).resetForBankChange(user);
    }
}
