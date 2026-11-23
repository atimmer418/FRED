package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.investingapp.backend.dto.EmailMessage;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.service.EmailChangeService.Outcome;
import com.investingapp.backend.service.EmailChangeService.Status;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The rules of EmailChangeService. Nothing here needs a database, so these run everywhere. The
 * cross-user attacks against real SQL are in EmailChangeTakeoverTest.
 */
class EmailChangeServiceTest {

    private static final long USER_ID = 7L;
    private static final long OTHER_USER_ID = 8L;
    private static final String OLD = "old@example.test";
    private static final String NEW = "new@example.test";
    private static final JsonNode ASSERTION = JsonNodeFactory.instance.objectNode().put("id", "x");
    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    /** A clock the test can move forward. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-22T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private UserRepository userRepository;
    private PasskeyReauthVerifier verifier;
    private EmailService emailService;
    private MovableClock clock;
    private EmailChangeService service;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        verifier = mock(PasskeyReauthVerifier.class);
        emailService = mock(EmailService.class);
        clock = new MovableClock();
        service = newService(new MockEnvironment().withProperty("spring.profiles.active", "ci"), false);

        user = new User(OLD);
        user.setId(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        User other = new User("other@example.test");
        other.setId(OTHER_USER_ID);
        when(userRepository.findById(OTHER_USER_ID)).thenReturn(Optional.of(other));
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.updateEmail(anyLong(), anyString())).thenReturn(1);
        when(verifier.verifiedUserId(anyString(), any())).thenReturn(Optional.of(USER_ID));
    }

    private EmailChangeService newService(MockEnvironment environment, boolean emailEnabled) {
        String profiles = environment.getProperty("spring.profiles.active");
        if (profiles != null) {
            environment.setActiveProfiles(profiles.split(","));
        }
        return new EmailChangeService(userRepository, verifier, emailService, new BCryptPasswordEncoder(4),
                VALIDATOR, environment, emailEnabled, clock);
    }

    private Outcome start(String address) {
        return service.start(USER_ID, address, "s", ASSERTION);
    }

    /** The code in the newest code message. */
    private String lastCodeSent() {
        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService, atLeastOnce()).sendEmail(sent.capture(), any(), eq(EmailChangeService.TYPE_CODE), any());
        Matcher matcher = CODE.matcher(sent.getAllValues().get(sent.getAllValues().size() - 1).getTextBody());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String wrongCodeFor(String realCode) {
        return "000000".equals(realCode) ? "111111" : "000000";
    }

    // ------------------------------------------------------------------ start

    @Test
    @DisplayName("without passkey proof the caller learns nothing: no lookup of the address, no email, nothing pending")
    void proofIsCheckedBeforeAnythingElse() {
        when(verifier.verifiedUserId(anyString(), any())).thenReturn(Optional.empty());

        assertThat(start("taken@example.test").status()).isEqualTo(Status.REAUTH_REQUIRED);
        // Even a malformed address gets the same answer, so the refusal says nothing about it.
        assertThat(start("not-an-email").status()).isEqualTo(Status.REAUTH_REQUIRED);

        verify(userRepository, never()).existsByEmail(anyString());
        assertThat(service.hasPendingChange(USER_ID)).isFalse();
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("proof that belongs to a different user is refused")
    void someoneElsesProofIsRefused() {
        when(verifier.verifiedUserId(anyString(), any())).thenReturn(Optional.of(OTHER_USER_ID));

        assertThat(start(NEW).status()).isEqualTo(Status.REAUTH_REQUIRED);
        assertThat(service.hasPendingChange(USER_ID)).isFalse();
        assertThat(service.hasPendingChange(OTHER_USER_ID)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "plain", "no-at.example.test", "two@@example.test", "a@b",
            "has space@example.test", "a@b..com", "a@example.test\nbcc: x@example.test"})
    @DisplayName("an address that is not an email is refused")
    void badAddressesAreRefused(String address) {
        assertThat(start(address).status()).isEqualTo(Status.INVALID_EMAIL);
        assertThat(service.hasPendingChange(USER_ID)).isFalse();
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("an address longer than the account can hold is refused")
    void overlongAddressIsRefused() {
        assertThat(start("a".repeat(60) + "@" + "b".repeat(40) + ".test").status()).isEqualTo(Status.INVALID_EMAIL);
    }

    @Test
    @DisplayName("the current address, in any letter case, is refused")
    void sameAddressIsRefused() {
        assertThat(start(" OLD@example.test ").status()).isEqualTo(Status.SAME_EMAIL);
    }

    @Test
    @DisplayName("an address another account has is refused")
    void takenAddressIsRefused() {
        when(userRepository.existsByEmail(NEW)).thenReturn(true);
        assertThat(start(NEW).status()).isEqualTo(Status.EMAIL_IN_USE);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("a good request sends the code to the new address, warns the old one, and changes nothing")
    void startSendsCodeAndNotice() {
        Outcome outcome = start(" " + NEW + " ");

        assertThat(outcome.status()).isEqualTo(Status.OK);
        assertThat(outcome.newEmail()).isEqualTo(NEW);
        assertThat(service.hasPendingChange(USER_ID)).isTrue();
        verify(userRepository, never()).updateEmail(anyLong(), anyString());

        String code = lastCodeSent();
        ArgumentCaptor<EmailMessage> notice = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService).sendEmail(notice.capture(), eq(USER_ID), eq(EmailChangeService.TYPE_REQUESTED_NOTICE), any());
        assertThat(notice.getValue().getTo()).isEqualTo(OLD);
        assertThat(notice.getValue().getTextBody()).doesNotContain(code).doesNotContain(NEW).contains("n***@example.test");
    }

    @Test
    @DisplayName("where real email is off and this is not a development environment, the change is refused, not faked")
    void noEmailMeansNoChange() {
        for (String profiles : new String[] {"prod", "", "staging", "dev,prod"}) {
            MockEnvironment environment = new MockEnvironment();
            if (!profiles.isEmpty()) {
                environment.setActiveProfiles(profiles.split(","));
            }
            service = newService(environment, false);

            assertThat(start(NEW).status()).as("profiles [%s]", profiles).isEqualTo(Status.EMAIL_UNAVAILABLE);
            assertThat(service.hasPendingChange(USER_ID)).isFalse();
        }
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("with real email on, production works")
    void productionWorksOnceEmailIsOn() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        service = newService(environment, true);

        assertThat(start(NEW).status()).isEqualTo(Status.OK);
    }

    @Test
    @DisplayName("only so many codes can be asked for in an hour")
    void startsAreCapped() {
        for (int i = 0; i < EmailChangeService.MAX_STARTS_PER_HOUR; i++) {
            assertThat(start("n" + i + "@example.test").status()).isEqualTo(Status.OK);
        }
        assertThat(start("one-more@example.test").status()).isEqualTo(Status.TOO_MANY_REQUESTS);
    }

    // ------------------------------------------------------------------ confirm

    @Test
    @DisplayName("the right code changes the email, tells the old address, and cannot be used twice")
    void rightCodeAppliesTheChange() {
        start(NEW);
        String code = lastCodeSent();

        Outcome outcome = service.confirm(USER_ID, " " + code + " ");

        assertThat(outcome.status()).isEqualTo(Status.OK);
        assertThat(outcome.newEmail()).isEqualTo(NEW);
        verify(userRepository).updateEmail(USER_ID, NEW);
        ArgumentCaptor<EmailMessage> done = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService).sendEmail(done.capture(), eq(USER_ID), eq(EmailChangeService.TYPE_DONE_NOTICE), any());
        assertThat(done.getValue().getTo()).isEqualTo(OLD);

        assertThat(service.confirm(USER_ID, code).status()).isEqualTo(Status.NO_PENDING_CHANGE);
    }

    @Test
    @DisplayName("a code with nothing pending, an empty code and a missing code all change nothing")
    void nothingPendingOrNoCode() {
        assertThat(service.confirm(USER_ID, "123456").status()).isEqualTo(Status.NO_PENDING_CHANGE);

        start(NEW);
        assertThat(service.confirm(USER_ID, "").status()).isEqualTo(Status.INVALID_CODE);
        assertThat(service.confirm(USER_ID, null).status()).isEqualTo(Status.INVALID_CODE);
        verify(userRepository, never()).updateEmail(anyLong(), anyString());
    }

    @Test
    @DisplayName("another user cannot confirm this user's change, even with the real code")
    void pendingChangeBelongsToItsOwner() {
        start(NEW);
        String code = lastCodeSent();

        assertThat(service.confirm(OTHER_USER_ID, code).status()).isEqualTo(Status.NO_PENDING_CHANGE);
        verify(userRepository, never()).updateEmail(anyLong(), anyString());
        assertThat(service.hasPendingChange(USER_ID)).isTrue();
    }

    @Test
    @DisplayName("five wrong codes cancel the change")
    void wrongCodesRunOut() {
        start(NEW);
        String code = lastCodeSent();
        String wrong = wrongCodeFor(code);

        for (int i = 1; i < EmailChangeService.MAX_WRONG_CODES; i++) {
            assertThat(service.confirm(USER_ID, wrong).status()).isEqualTo(Status.INVALID_CODE);
        }
        assertThat(service.confirm(USER_ID, wrong).status()).isEqualTo(Status.TOO_MANY_ATTEMPTS);
        assertThat(service.confirm(USER_ID, code).status()).isEqualTo(Status.NO_PENDING_CHANGE);
        verify(userRepository, never()).updateEmail(anyLong(), anyString());
    }

    @Test
    @DisplayName("sending many guesses at the same moment does not buy extra tries")
    void parallelGuessesAreStillCounted() throws Exception {
        start(NEW);
        String wrong = wrongCodeFor(lastCodeSent());
        int guesses = 24;
        ExecutorService pool = Executors.newFixedThreadPool(guesses);
        CountDownLatch go = new CountDownLatch(1);
        List<java.util.concurrent.Future<Status>> results = new ArrayList<>();
        for (int i = 0; i < guesses; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return service.confirm(USER_ID, wrong).status();
            }));
        }
        go.countDown();

        int checked = 0;
        for (var result : results) {
            Status status = result.get(30, TimeUnit.SECONDS);
            if (status == Status.INVALID_CODE || status == Status.TOO_MANY_ATTEMPTS) {
                checked++;
            } else {
                assertThat(status).isEqualTo(Status.NO_PENDING_CHANGE);
            }
        }
        pool.shutdownNow();

        // Exactly five guesses were looked at. The other nineteen found nothing to guess against.
        assertThat(checked).isEqualTo(EmailChangeService.MAX_WRONG_CODES);
    }

    @Test
    @DisplayName("a code stops working after fifteen minutes")
    void codeExpires() {
        start(NEW);
        String code = lastCodeSent();

        clock.advance(Duration.ofMinutes(EmailChangeService.CODE_MINUTES).minusSeconds(1));
        assertThat(service.hasPendingChange(USER_ID)).isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(service.hasPendingChange(USER_ID)).isFalse();

        assertThat(service.confirm(USER_ID, code).status()).isEqualTo(Status.CODE_EXPIRED);
        verify(userRepository, never()).updateEmail(anyLong(), anyString());
    }

    @Test
    @DisplayName("asking again replaces the earlier code")
    void newestCodeWins() {
        start(NEW);
        String first = lastCodeSent();
        start("second@example.test");
        String second = lastCodeSent();

        if (!first.equals(second)) {
            assertThat(service.confirm(USER_ID, first).status()).isEqualTo(Status.INVALID_CODE);
        }
        assertThat(service.confirm(USER_ID, second).newEmail()).isEqualTo("second@example.test");
    }

    @Test
    @DisplayName("an address taken between the start and the finish is refused, both ways it can show up")
    void addressTakenInTheMeantime() {
        start(NEW);
        when(userRepository.existsByEmail(NEW)).thenReturn(true);
        assertThat(service.confirm(USER_ID, lastCodeSent()).status()).isEqualTo(Status.EMAIL_IN_USE);

        // And when the two confirms are so close that only the database's unique key catches it.
        when(userRepository.existsByEmail(NEW)).thenReturn(false);
        start(NEW);
        when(userRepository.updateEmail(USER_ID, NEW)).thenThrow(new DataIntegrityViolationException("duplicate"));
        assertThat(service.confirm(USER_ID, lastCodeSent()).status()).isEqualTo(Status.EMAIL_IN_USE);

        verify(emailService, never()).sendEmail(any(EmailMessage.class), any(), eq(EmailChangeService.TYPE_DONE_NOTICE), any());
    }

    @Test
    @DisplayName("the notice shows only a masked form of the new address")
    void maskHidesTheAddress() {
        assertThat(EmailChangeService.mask("jane.doe@example.test")).isEqualTo("j***@example.test");
        assertThat(EmailChangeService.mask("@example.test")).isEqualTo("***");
        assertThat(EmailChangeService.mask("nonsense")).isEqualTo("***");
    }
}
