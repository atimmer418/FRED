package com.investingapp.backend.service;

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
import static org.mockito.Mockito.when;

/**
 * schedule_cancelled on the KYC rejection path, driven the way it is really driven: through the
 * status poller, the only caller of the rejection handler.
 *
 * <p>A rejected user loses their schedule along with their account, and that is a real cancellation
 * worth counting. A rejected user who never set a schedule up has nothing cancelled, and
 * UserDeletionService removes no schedule for them. Both cases are pinned here, because the event
 * exists to measure churn and a phantom cancellation for every schedule-less rejection would
 * inflate exactly the number it is for.
 *
 * <p>The property map is asserted WHOLE, never as a subset: a stray property is how PII reaches a
 * third party, and this event is only ever allowed to carry two enum values.
 */
@ExtendWith(MockitoExtension.class)
class AccountStatusServiceScheduleCancelledTest {

    private static final String ENCRYPTED_ACCOUNT = "ENC:alpaca-account";
    private static final String ALPACA_ACCOUNT = "acct-1";
    private static final String PUBLIC_ID = "99999999-8888-7777-6666-555555555555";

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

        user = new User("rejected@fredvested.com");
        user.setId(7L);
        user.setPublicId(PUBLIC_ID);
        user.setAccountStatus("APPROVAL_PENDING");
        user.setAlpacaAccountId(ENCRYPTED_ACCOUNT);
        user.setKycSubmittedAt(LocalDateTime.now().minusDays(2));
    }

    /** The one external input the poller has: what the broker says this account's status is. */
    private void brokerSays(String status) {
        when(userRepository.findUsersWithPendingAccountStatus()).thenReturn(List.of(user));
        when(encryptionService.decrypt(ENCRYPTED_ACCOUNT)).thenReturn(ALPACA_ACCOUNT);
        when(alpacaApiService.getAccountStatus(ALPACA_ACCOUNT))
                .thenReturn("{\"status\":\"" + status + "\"}");
    }

    private void givenTheUserHasASchedule() {
        when(investmentScheduleRepository.existsByUser(user)).thenReturn(true);
    }

    @Test
    @DisplayName("a rejected user with a schedule gets exactly one system cancellation, reason kyc_rejected")
    void rejectionCancelsTheSchedule() {
        givenTheUserHasASchedule();
        brokerSays("REJECTED");

        service.checkAndUpdateAccountStatuses();

        // Two events on this path, in this order: the decision, then the cancellation it caused.
        verify(analyticsOutboxService, times(2))
                .record(eventNameCaptor.capture(), subjectCaptor.capture(), propertiesCaptor.capture());
        assertThat(eventNameCaptor.getAllValues()).containsExactly(
                ServerAnalyticsEvent.KYC_DECIDED,
                ServerAnalyticsEvent.SCHEDULE_CANCELLED);
        assertThat(eventNameCaptor.getAllValues().get(1)).isEqualTo("schedule_cancelled");
        assertThat(subjectCaptor.getAllValues().get(1)).isSameAs(user);
        // The WHOLE map: two enum values, in this order, and nothing else.
        assertThat(propertiesCaptor.getAllValues().get(1)).containsExactly(
                entry("initiator", "system"),
                entry("reason", "kyc_rejected"));
    }

    @Test
    @DisplayName("a rejected user with no schedule has nothing cancelled, so nothing is emitted")
    void rejectionWithoutAScheduleEmitsNoCancellation() {
        // existsByUser is left unstubbed: false, the user never set a schedule up.
        brokerSays("REJECTED");

        service.checkAndUpdateAccountStatuses();

        // Nothing cancelled is not churn. The row is marked REJECTED either way.
        assertThat(user.getAccountStatus()).isEqualTo("REJECTED");
        verify(analyticsOutboxService, never())
                .record(eq(ServerAnalyticsEvent.SCHEDULE_CANCELLED), any(), anyMap());
        // The decision itself is unaffected.
        verify(analyticsOutboxService, times(1))
                .record(eq(ServerAnalyticsEvent.KYC_DECIDED), eq(user), anyMap());
    }

    @Test
    @DisplayName("the schedule's existence is read before the status is written")
    void existenceIsReadBeforeTheStatusWrite() {
        givenTheUserHasASchedule();
        brokerSays("REJECTED");

        service.checkAndUpdateAccountStatuses();

        InOrder order = inOrder(investmentScheduleRepository, analyticsOutboxService, userRepository);
        order.verify(investmentScheduleRepository).existsByUser(user);
        order.verify(analyticsOutboxService)
                .record(eq(ServerAnalyticsEvent.SCHEDULE_CANCELLED), eq(user), anyMap());
        order.verify(userRepository).save(user);
    }

    @Test
    @DisplayName("the cancellation is recorded with the user's real identity, in one transaction")
    void cancellationIsRecordedWithTheRealIdentity() {
        givenTheUserHasASchedule();
        brokerSays("REJECTED");
        AtomicReference<String> identityAtRecordTime = new AtomicReference<>();
        when(analyticsOutboxService.record(anyString(), any(User.class), anyMap()))
                .thenAnswer(invocation -> {
                    if (ServerAnalyticsEvent.SCHEDULE_CANCELLED.equals(invocation.getArgument(0))) {
                        User subject = invocation.getArgument(1);
                        identityAtRecordTime.set(subject.getId() + "|" + subject.getPublicId());
                    }
                    return null;
                });

        service.checkAndUpdateAccountStatuses();

        InOrder order = inOrder(analyticsOutboxService, userRepository);
        order.verify(analyticsOutboxService)
                .record(eq(ServerAnalyticsEvent.SCHEDULE_CANCELLED), eq(user), anyMap());
        order.verify(userRepository).save(user);
        assertThat(identityAtRecordTime.get()).isEqualTo("7|" + PUBLIC_ID);
        verify(userRepository, never()).delete(any());
    }

    @Test
    @DisplayName("the cancellation is recorded exactly once, with system and kyc_rejected")
    void theCancellationIsRecordedOnce() {
        givenTheUserHasASchedule();
        brokerSays("REJECTED");

        service.checkAndUpdateAccountStatuses();

        // The broker has rejected the application, so the schedule is cancelled. It is the system
        // doing it, not the user, which is what keeps this out of the churn number.
        verify(analyticsOutboxService, times(1))
                .record(eq(ServerAnalyticsEvent.SCHEDULE_CANCELLED), eq(user), propertiesCaptor.capture());
        assertThat(propertiesCaptor.getValue()).containsExactly(
                entry("initiator", "system"),
                entry("reason", "kyc_rejected"));
        assertThat(user.getAccountStatus()).isEqualTo("REJECTED");
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("an approval cancels nothing: the schedule survives an approved account")
    void approvalEmitsNoCancellation() {
        // existsByUser is deliberately not stubbed. On this path the schedule is never asked
        // about, because nothing is being deleted.
        brokerSays("ACTIVE");

        service.checkAndUpdateAccountStatuses();

        verify(analyticsOutboxService, never())
                .record(eq(ServerAnalyticsEvent.SCHEDULE_CANCELLED), any(), anyMap());
    }
}
