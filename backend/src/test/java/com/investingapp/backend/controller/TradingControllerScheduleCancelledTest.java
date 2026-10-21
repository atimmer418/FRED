package com.investingapp.backend.controller;

import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.PortfolioRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.repository.UserSessionRepository;
import com.investingapp.backend.service.AlpacaService;
import com.investingapp.backend.service.AnalyticsOutboxService;
import com.investingapp.backend.service.EncryptionService;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * schedule_cancelled on the account-closure path: the one cancellation the user asks for themselves.
 *
 * <p>Closing an account destroys the schedule along with everything else, which is churn and is
 * counted. Closing an account that never had a schedule cancels nothing, and must emit nothing: the
 * delete is a no-op for those users, and an event there would invent a cancellation for everyone who
 * closed an account without ever setting one up.
 *
 * <p>The controller is built by hand with its collaborators set directly, because it uses field
 * injection. Only the fields closeAccount actually touches are set; alpacaApiService is not one of
 * them. Mocks are plain (no MockitoExtension), as the repo's other controller tests are, so the
 * 404 and broker-failure cases can share a fixture without tripping strict-stub checks.
 */
class TradingControllerScheduleCancelledTest {

    private static final String EMAIL = "closing@fredvested.test";
    private static final String ENCRYPTED_ACCOUNT = "ENC:alpaca-account";
    private static final String ALPACA_ACCOUNT = "acct-close";
    private static final String PUBLIC_ID = "abcdabcd-1234-5678-9012-abcdabcdabcd";
    private static final Long USER_ID = 11L;

    private AlpacaService alpacaService;
    private UserRepository userRepository;
    private InvestmentScheduleRepository investmentScheduleRepository;
    private UserSessionRepository userSessionRepository;
    private PortfolioRepository portfolioRepository;
    private EncryptionService encryptionService;
    private AnalyticsOutboxService analyticsOutboxService;

    private TradingController controller;
    private Authentication authentication;
    private User user;

    @BeforeEach
    void setUp() {
        alpacaService = mock(AlpacaService.class);
        userRepository = mock(UserRepository.class);
        investmentScheduleRepository = mock(InvestmentScheduleRepository.class);
        userSessionRepository = mock(UserSessionRepository.class);
        portfolioRepository = mock(PortfolioRepository.class);
        encryptionService = mock(EncryptionService.class);
        analyticsOutboxService = mock(AnalyticsOutboxService.class);

        controller = new TradingController();
        ReflectionTestUtils.setField(controller, "alpacaService", alpacaService);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "investmentScheduleRepository",
                investmentScheduleRepository);
        ReflectionTestUtils.setField(controller, "userSessionRepository", userSessionRepository);
        ReflectionTestUtils.setField(controller, "portfolioRepository", portfolioRepository);
        ReflectionTestUtils.setField(controller, "encryptionService", encryptionService);
        ReflectionTestUtils.setField(controller, "analyticsOutboxService", analyticsOutboxService);

        user = new User(EMAIL);
        user.setId(USER_ID);
        user.setPublicId(PUBLIC_ID);
        user.setAlpacaAccountId(ENCRYPTED_ACCOUNT);

        UserDetails principal = mock(UserDetails.class);
        when(principal.getUsername()).thenReturn(EMAIL);
        authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(encryptionService.decrypt(ENCRYPTED_ACCOUNT)).thenReturn(ALPACA_ACCOUNT);
        when(portfolioRepository.findByUser(user)).thenReturn(Optional.empty());
    }

    private void givenTheUserHasASchedule() {
        when(investmentScheduleRepository.existsByUser(user)).thenReturn(true);
    }

    @Test
    @DisplayName("closing an account with a schedule records exactly one user cancellation, reason account_closed")
    void closingWithAScheduleRecordsOneCancellation() {
        givenTheUserHasASchedule();

        ResponseEntity<Map<String, Object>> response = controller.closeAccount(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<String> eventName = ArgumentCaptor.captor();
        ArgumentCaptor<User> subject = ArgumentCaptor.captor();
        ArgumentCaptor<Map<String, Object>> properties = ArgumentCaptor.captor();
        verify(analyticsOutboxService, times(1))
                .record(eventName.capture(), subject.capture(), properties.capture());

        assertThat(eventName.getValue()).isEqualTo("schedule_cancelled");
        assertThat(eventName.getValue()).isEqualTo(ServerAnalyticsEvent.SCHEDULE_CANCELLED);
        assertThat(subject.getValue()).isSameAs(user);
        // The WHOLE map: two enum values, in this order, and nothing else. No amount, no frequency,
        // no bank, no id.
        assertThat(properties.getValue()).containsExactly(
                entry("initiator", "user"),
                entry("reason", "account_closed"));
    }

    @Test
    @DisplayName("closing an account that never had a schedule cancels nothing, so nothing is emitted")
    void closingWithoutAScheduleEmitsNothing() {
        // existsByUser is left unstubbed: false, this user never set a schedule up.

        ResponseEntity<Map<String, Object>> response = controller.closeAccount(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The closure itself is untouched: the delete still runs, it is simply a no-op.
        verify(investmentScheduleRepository).deleteAllByUser(user);
        verify(userRepository).delete(user);
        verifyNoInteractions(analyticsOutboxService);
    }

    @Test
    @DisplayName("the schedule's existence is read before the schedule is deleted")
    void existenceIsReadBeforeTheDelete() {
        givenTheUserHasASchedule();

        controller.closeAccount(authentication);

        // Reading it afterwards would always answer 'no' and the event would never fire.
        InOrder order = inOrder(investmentScheduleRepository, analyticsOutboxService);
        order.verify(investmentScheduleRepository).existsByUser(user);
        order.verify(analyticsOutboxService)
                .record(eq(ServerAnalyticsEvent.SCHEDULE_CANCELLED), eq(user), anyMap());
        order.verify(investmentScheduleRepository).deleteAllByUser(user);
    }

    @Test
    @DisplayName("the cancellation is recorded before the user is deleted, while it still has an identity")
    void cancellationIsRecordedBeforeTheHardDelete() {
        givenTheUserHasASchedule();
        AtomicReference<String> identityAtRecordTime = new AtomicReference<>();
        when(analyticsOutboxService.record(anyString(), any(User.class), anyMap()))
                .thenAnswer(invocation -> {
                    User subject = invocation.getArgument(1);
                    identityAtRecordTime.set(subject.getId() + "|" + subject.getPublicId());
                    return null;
                });

        controller.closeAccount(authentication);

        InOrder order = inOrder(analyticsOutboxService, userRepository);
        order.verify(analyticsOutboxService)
                .record(eq(ServerAnalyticsEvent.SCHEDULE_CANCELLED), eq(user), anyMap());
        order.verify(userRepository).delete(user);
        // The outbox copies both of these out of the user; neither can be recovered afterwards.
        assertThat(identityAtRecordTime.get()).isEqualTo(USER_ID + "|" + PUBLIC_ID);
    }

    @Test
    @DisplayName("the event goes with the deletes, not before the broker call: a failed close cancels nothing")
    void aFailedBrokerCloseEmitsNothing() {
        givenTheUserHasASchedule();
        doThrow(new RuntimeException("alpaca unreachable")).when(alpacaService).closeAccount(ALPACA_ACCOUNT);

        ResponseEntity<Map<String, Object>> response = controller.closeAccount(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        // Nothing was closed and nothing was deleted, so there is no cancellation to describe.
        verify(investmentScheduleRepository, never()).deleteAllByUser(any());
        verify(userRepository, never()).delete(any(User.class));
        verify(analyticsOutboxService, never()).record(anyString(), any(), anyMap());
    }

    @Test
    @DisplayName("a user with no brokerage account is refused and emits nothing")
    void userWithoutABrokerageAccountEmitsNothing() {
        user.setAlpacaAccountId(null);

        ResponseEntity<Map<String, Object>> response = controller.closeAccount(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(analyticsOutboxService);
        verify(userSessionRepository, never()).deleteAllByUserId(anyLong());
    }

    @Test
    @DisplayName("an unauthenticated call emits nothing")
    void unauthenticatedCallEmitsNothing() {
        ResponseEntity<Map<String, Object>> response = controller.closeAccount(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(analyticsOutboxService);
    }
}
