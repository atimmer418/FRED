package com.investingapp.backend.controller;

import com.investingapp.backend.dto.ErrorCodeResponse;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.InvestmentScheduleRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.AlpacaService;
import com.investingapp.backend.service.AnalyticsOutboxService;
import com.investingapp.backend.service.EncryptionService;
import com.investingapp.backend.service.UserDeletionService;
import com.investingapp.backend.util.ServerAnalyticsEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
 *
 * <p>Every row the user owns is deleted by UserDeletionService now, so the deletion assertions are
 * made against that one path rather than against the individual repositories. closeAccount is not
 * transactional (the broker calls must stay outside a database transaction) and the outbox refuses
 * to write without one, so the controller hands the cancellation to UserDeletionService.delete as a
 * hook to run inside the deletion transaction. The mock here runs that hook where the real service
 * runs it: after the deletion is allowed to start and before any row is removed.
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
    private EncryptionService encryptionService;
    private AnalyticsOutboxService analyticsOutboxService;
    private UserDeletionService userDeletionService;

    private TradingController controller;
    private Authentication authentication;
    private User user;

    @BeforeEach
    void setUp() {
        alpacaService = mock(AlpacaService.class);
        userRepository = mock(UserRepository.class);
        investmentScheduleRepository = mock(InvestmentScheduleRepository.class);
        encryptionService = mock(EncryptionService.class);
        analyticsOutboxService = mock(AnalyticsOutboxService.class);
        userDeletionService = mock(UserDeletionService.class);

        controller = new TradingController();
        ReflectionTestUtils.setField(controller, "alpacaService", alpacaService);
        ReflectionTestUtils.setField(controller, "userRepository", userRepository);
        ReflectionTestUtils.setField(controller, "investmentScheduleRepository",
                investmentScheduleRepository);
        ReflectionTestUtils.setField(controller, "encryptionService", encryptionService);
        ReflectionTestUtils.setField(controller, "analyticsOutboxService", analyticsOutboxService);
        ReflectionTestUtils.setField(controller, "userDeletionService", userDeletionService);

        user = new User(EMAIL);
        user.setId(USER_ID);
        user.setPublicId(PUBLIC_ID);
        user.setAlpacaAccountId(ENCRYPTED_ACCOUNT);

        UserDetails principal = mock(UserDetails.class);
        when(principal.getUsername()).thenReturn(EMAIL);
        authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());

        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(encryptionService.decrypt(ENCRYPTED_ACCOUNT)).thenReturn(ALPACA_ACCOUNT);
        // Nothing in flight, so the closure is allowed to proceed to the deletes.
        when(userDeletionService.deferralReason(user)).thenReturn(Optional.empty());
        // Stand in for the real deletion transaction: run the hook, then the deletes would follow.
        doAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return null;
        }).when(userDeletionService).delete(any(), any(), any());
    }

    private void givenTheUserHasASchedule() {
        when(investmentScheduleRepository.existsByUser(user)).thenReturn(true);
    }

    @Test
    @DisplayName("closing an account with a schedule records exactly one user cancellation, reason account_closed")
    void closingWithAScheduleRecordsOneCancellation() {
        givenTheUserHasASchedule();

        ResponseEntity<?> response = controller.closeAccount(authentication);

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
    @DisplayName("closing an account hands the whole deletion, person record included, to one service")
    void closingDelegatesTheDeletionIncludingThePersonRecord() {
        givenTheUserHasASchedule();

        controller.closeAccount(authentication);

        // The vendor's person record is deleted by UserDeletionService, inside the same transaction
        // as the row deletes, so EVERY deletion reason propagates it and not just this one. The
        // controller's job is only to hand over; UserDeletionServiceTest owns the
        // "it really is requested, in that transaction, with the user still intact" assertions.
        verify(userDeletionService).delete(eq(user),
                eq(UserDeletionService.DeletionReason.ACCOUNT_CLOSED), any(Runnable.class));
    }

    @Test
    @DisplayName("closing an account that never had a schedule cancels nothing, so nothing is emitted")
    void closingWithoutAScheduleEmitsNothing() {
        // existsByUser is left unstubbed: false, this user never set a schedule up.

        ResponseEntity<?> response = controller.closeAccount(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The closure itself is untouched: the one deletion path still runs.
        verify(userDeletionService).delete(eq(user),
                eq(UserDeletionService.DeletionReason.ACCOUNT_CLOSED), any(Runnable.class));
        verifyNoInteractions(analyticsOutboxService);
    }

    @Test
    @DisplayName("the existence read and the event happen inside the deletion transaction, before any row goes")
    void theEventIsRecordedInsideTheDeletionTransaction() {
        List<String> order = new ArrayList<>();
        // The deletion transaction, from the outside: it opens, runs the controller's hook, then
        // removes the rows. An event committed before this window could describe a cancellation
        // whose schedule and user are still there; one written after it could not read the
        // schedule, or the user's identity, at all.
        doAnswer(invocation -> {
            order.add("deletion transaction opens");
            invocation.getArgument(2, Runnable.class).run();
            order.add("rows removed");
            return null;
        }).when(userDeletionService).delete(any(), any(), any());
        when(investmentScheduleRepository.existsByUser(user)).thenAnswer(invocation -> {
            order.add("schedule existence read");
            return true;
        });
        when(analyticsOutboxService.record(anyString(), any(User.class), anyMap()))
                .thenAnswer(invocation -> {
                    order.add("event recorded");
                    return null;
                });

        controller.closeAccount(authentication);

        assertThat(order).containsExactly(
                "deletion transaction opens",
                "schedule existence read",
                "event recorded",
                "rows removed");
    }

    @Test
    @DisplayName("the cancellation is recorded while the user still has an identity to copy")
    void cancellationIsRecordedWhileTheUserStillHasAnIdentity() {
        givenTheUserHasASchedule();
        AtomicReference<String> identityAtRecordTime = new AtomicReference<>();
        when(analyticsOutboxService.record(anyString(), any(User.class), anyMap()))
                .thenAnswer(invocation -> {
                    User subject = invocation.getArgument(1);
                    identityAtRecordTime.set(subject.getId() + "|" + subject.getPublicId());
                    return null;
                });

        controller.closeAccount(authentication);

        verify(analyticsOutboxService)
                .record(eq(ServerAnalyticsEvent.SCHEDULE_CANCELLED), eq(user), anyMap());
        // The outbox copies both of these out of the user; neither can be recovered afterwards.
        assertThat(identityAtRecordTime.get()).isEqualTo(USER_ID + "|" + PUBLIC_ID);
    }

    @Test
    @DisplayName("the event goes with the deletes, not before the broker call: a failed close cancels nothing")
    void aFailedBrokerCloseEmitsNothing() {
        givenTheUserHasASchedule();
        doThrow(new RuntimeException("alpaca unreachable")).when(alpacaService).closeAccount(ALPACA_ACCOUNT);

        // The failure PROPAGATES rather than being swallowed into a 500 body, so a partial close
        // can never be reported as an orderly one. The rollback itself is UserDeletionService's
        // doing now, inside its own transaction; an @ExceptionHandler turns this throw into the
        // same 500 the caller used to get.
        assertThatThrownBy(() -> controller.closeAccount(authentication))
                .isInstanceOf(TradingController.AccountCloseFailedException.class);

        // Nothing was closed and nothing was deleted, so there is no cancellation to describe.
        verify(userDeletionService, never()).delete(any(), any(), any());
        verify(analyticsOutboxService, never()).record(anyString(), any(), anyMap());
    }

    @Test
    @DisplayName("a deferred deletion is refused before the broker call and cancels nothing")
    void deferredClosureEmitsNothing() {
        givenTheUserHasASchedule();
        when(userDeletionService.deferralReason(user)).thenReturn(Optional.of("SETTLEMENT_WINDOW"));

        ResponseEntity<?> response = controller.closeAccount(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody())
                .isEqualTo(new ErrorCodeResponse("DELETION_DEFERRED", "SETTLEMENT_WINDOW"));
        // The schedule is still running and the account is still open: nothing was cancelled.
        verify(alpacaService, never()).closeAccount(anyString());
        verify(userDeletionService, never()).delete(any(), any(), any());
        verifyNoInteractions(analyticsOutboxService);
    }

    @Test
    @DisplayName("a user with no brokerage account is refused and emits nothing")
    void userWithoutABrokerageAccountEmitsNothing() {
        user.setAlpacaAccountId(null);

        ResponseEntity<?> response = controller.closeAccount(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(analyticsOutboxService);
        verify(userDeletionService, never()).delete(any(), any(), any());
    }

    @Test
    @DisplayName("an unauthenticated call emits nothing")
    void unauthenticatedCallEmitsNothing() {
        ResponseEntity<?> response = controller.closeAccount(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(analyticsOutboxService);
    }
}
