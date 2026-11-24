package com.investingapp.backend.service;

import com.investingapp.backend.dto.EmailMessage;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.PasskeyCredentialRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.repository.UserSessionRepository;
import com.investingapp.backend.security.TokenCutoffRegistry;
import com.investingapp.backend.service.AccountRecoveryService.SendStatus;
import com.investingapp.backend.service.AccountRecoveryService.VerifyOutcome;
import com.investingapp.backend.service.AccountRecoveryService.VerifyStatus;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialParameters;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.UserIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * The rules of AccountRecoveryService that depend on time, counting and configuration. Nothing here
 * needs a database, so these run everywhere. The attacks against the real endpoints and real SQL
 * are in AccountRecoveryAttackTest.
 */
class AccountRecoveryServiceTest {

    private static final long USER_ID = 7L;
    private static final String EMAIL = "owner@example.test";
    private static final String NOBODY = "nobody@example.test";
    private static final String ADDRESS = "203.0.113.9";
    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    /** A clock the test can move forward. */
    private static final class MovableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-23T12:00:00Z");

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
    private PasskeyCredentialRepository passkeys;
    private UserSessionRepository sessions;
    private TokenCutoffRegistry cutoffs;
    private EmailService emailService;
    private MovableClock clock;
    private AccountRecoveryService service;
    private int addressCounter;
    private final com.github.benmanes.caffeine.cache.Cache<String, PublicKeyCredentialCreationOptions> signUpChallenges =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().build();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passkeys = mock(PasskeyCredentialRepository.class);
        sessions = mock(UserSessionRepository.class);
        cutoffs = new TokenCutoffRegistry(3_600_000);
        emailService = mock(EmailService.class);
        clock = new MovableClock();
        service = newService("ci", false);

        User user = new User(EMAIL);
        user.setId(USER_ID);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(userRepository.findByEmail(NOBODY)).thenReturn(Optional.empty());
    }

    private AccountRecoveryService newService(String profile, boolean emailEnabled) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new AccountRecoveryService(userRepository, passkeys, sessions, cutoffs, emailService,
                new BCryptPasswordEncoder(4), new TransactionTemplate(mock(PlatformTransactionManager.class)),
                environment, signUpChallenges, emailEnabled, clock);
    }

    /** A fresh network address, for tests that are not about the per-address limits. */
    private String anyAddress() {
        return "198.51.100." + (++addressCounter);
    }

    private String lastCode() {
        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService, atLeastOnce())
                .sendEmail(sent.capture(), eq(USER_ID), eq(AccountRecoveryService.TYPE_CODE), any());
        EmailMessage last = sent.getAllValues().get(sent.getAllValues().size() - 1);
        assertThat(last.getTo()).isEqualTo(EMAIL);
        Matcher matcher = CODE.matcher(last.getTextBody());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String wrong(String real, int i) {
        return String.format("%06d", (Integer.parseInt(real) + 1 + i) % 1_000_000);
    }

    private String recover() {
        assertThat(service.sendCode(EMAIL, anyAddress())).isEqualTo(SendStatus.SENT);
        VerifyOutcome outcome = service.verifyCode(EMAIL, lastCode(), anyAddress());
        assertThat(outcome.status()).isEqualTo(VerifyStatus.OK);
        return outcome.recoveryToken();
    }

    private static PublicKeyCredentialCreationOptions challenge() {
        return PublicKeyCredentialCreationOptions.builder()
                .rp(RelyingPartyIdentity.builder().id("example.test").name("FRED").build())
                .user(UserIdentity.builder().name(EMAIL).displayName(EMAIL).id(new ByteArray(new byte[16])).build())
                .challenge(new ByteArray(new byte[32]))
                .pubKeyCredParams(List.of(PublicKeyCredentialParameters.ES256))
                .build();
    }

    // ------------------------------------------------------------------ the code

    @Test
    @DisplayName("the right code removes the passkeys, ends the sessions and every earlier token, and tells the owner")
    void rightCodeDoesTheRecovery() {
        Date tokenFromBefore = Date.from(clock.instant().minusSeconds(60));

        String recoveryToken = recover();

        assertThat(recoveryToken).isNotBlank();
        verify(passkeys).deleteAllForUser(USER_ID);
        verify(sessions).deactivateAllForUser(USER_ID);
        assertThat(cutoffs.isEnded(USER_ID, tokenFromBefore)).isTrue();
        // A token from a request that was in flight when recovery landed dies too; later ones do not.
        assertThat(cutoffs.isEnded(USER_ID, Date.from(clock.instant().plusSeconds(1)))).isTrue();
        assertThat(cutoffs.isEnded(USER_ID, Date.from(clock.instant().plusSeconds(5)))).isFalse();
        verify(emailService).sendEmail(any(EmailMessage.class), eq(USER_ID),
                eq(AccountRecoveryService.TYPE_DONE_NOTICE), any());
    }

    @Test
    @DisplayName("a wrong code changes nothing on the account")
    void wrongCodeChangesNothing() {
        service.sendCode(EMAIL, anyAddress());

        assertThat(service.verifyCode(EMAIL, wrong(lastCode(), 0), anyAddress()).status())
                .isEqualTo(VerifyStatus.INVALID);

        verify(passkeys, never()).deleteAllForUser(anyLong());
        verify(sessions, never()).deactivateAllForUser(anyLong());
        assertThat(cutoffs.isEnded(USER_ID, Date.from(clock.instant().minusSeconds(60)))).isFalse();
    }

    @Test
    @DisplayName("a code stops working after 15 minutes")
    void codeExpires() {
        service.sendCode(EMAIL, anyAddress());
        String code = lastCode();

        clock.advance(Duration.ofMinutes(AccountRecoveryService.CODE_MINUTES));

        assertThat(service.verifyCode(EMAIL, code, anyAddress()).status()).isEqualTo(VerifyStatus.INVALID);
        verify(passkeys, never()).deleteAllForUser(anyLong());
    }

    @Test
    @DisplayName("a new code replaces the old one")
    void newCodeReplacesOld() {
        service.sendCode(EMAIL, anyAddress());
        String first = lastCode();
        service.sendCode(EMAIL, anyAddress());
        String second = lastCode();
        if (first.equals(second)) {
            return; // one in a million: nothing to tell apart
        }

        assertThat(service.verifyCode(EMAIL, first, anyAddress()).status()).isEqualTo(VerifyStatus.INVALID);
        assertThat(service.verifyCode(EMAIL, second, anyAddress()).status()).isEqualTo(VerifyStatus.OK);
    }

    @Test
    @DisplayName("a missing or empty code, email or request counts as wrong and never throws")
    void emptyInputIsJustWrong() {
        service.sendCode(EMAIL, anyAddress());

        assertThat(service.verifyCode(EMAIL, null, anyAddress()).status()).isEqualTo(VerifyStatus.INVALID);
        assertThat(service.verifyCode(EMAIL, "  ", anyAddress()).status()).isEqualTo(VerifyStatus.INVALID);
        assertThat(service.verifyCode(null, "123456", anyAddress()).status()).isEqualTo(VerifyStatus.INVALID);
        assertThat(service.sendCode(null, null)).isEqualTo(SendStatus.SENT);
    }

    @Test
    @DisplayName("wrong guesses sent all at once still only get 5 tries between them")
    void parallelGuessesShareTheCap() throws Exception {
        service.sendCode(EMAIL, anyAddress());
        String real = lastCode();
        int guesses = 8; // under the 10 checks an hour, so only the per-code cap is in play

        ExecutorService pool = Executors.newFixedThreadPool(guesses);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<VerifyStatus>> results = new ArrayList<>();
        for (int i = 0; i < guesses; i++) {
            String guess = wrong(real, i);
            String address = anyAddress();
            results.add(pool.submit(() -> {
                go.await();
                return service.verifyCode(EMAIL, guess, address).status();
            }));
        }
        go.countDown();
        for (Future<VerifyStatus> result : results) {
            assertThat(result.get()).isEqualTo(VerifyStatus.INVALID);
        }
        pool.shutdown();

        assertThat(service.verifyCode(EMAIL, real, anyAddress()).status())
                .as("the right code after the burst").isEqualTo(VerifyStatus.INVALID);
        verify(passkeys, never()).deleteAllForUser(anyLong());
    }

    // ------------------------------------------------------------------ limits over time

    @Test
    @DisplayName("5 codes an hour per account, and the count starts again after the hour")
    void codesPerHour() {
        for (int i = 0; i < AccountRecoveryService.CODES_PER_ACCOUNT_PER_HOUR; i++) {
            assertThat(service.sendCode(EMAIL, anyAddress())).isEqualTo(SendStatus.SENT);
        }
        assertThat(service.sendCode(EMAIL, anyAddress())).isEqualTo(SendStatus.THROTTLED);

        clock.advance(Duration.ofMinutes(61));

        assertThat(service.sendCode(EMAIL, anyAddress())).isEqualTo(SendStatus.SENT);
    }

    @Test
    @DisplayName("10 codes a day per account, however they are spread")
    void codesPerDay() {
        int sent = 0;
        for (int hour = 0; hour < 4; hour++) {
            for (int i = 0; i < AccountRecoveryService.CODES_PER_ACCOUNT_PER_HOUR; i++) {
                if (service.sendCode(EMAIL, anyAddress()) == SendStatus.SENT) {
                    sent++;
                }
            }
            clock.advance(Duration.ofMinutes(61));
        }
        assertThat(sent).isEqualTo(AccountRecoveryService.CODES_PER_ACCOUNT_PER_DAY);

        clock.advance(Duration.ofHours(24));
        assertThat(service.sendCode(EMAIL, anyAddress())).isEqualTo(SendStatus.SENT);
    }

    @Test
    @DisplayName("a refused request sends no email and does not replace the live code")
    void throttledSendChangesNothing() {
        for (int i = 0; i < AccountRecoveryService.CODES_PER_ACCOUNT_PER_HOUR; i++) {
            service.sendCode(EMAIL, anyAddress());
        }
        String live = lastCode();
        org.mockito.Mockito.clearInvocations(emailService);

        assertThat(service.sendCode(EMAIL, anyAddress())).isEqualTo(SendStatus.THROTTLED);

        verifyNoInteractions(emailService);
        assertThat(service.verifyCode(EMAIL, live, anyAddress()).status()).isEqualTo(VerifyStatus.OK);
    }

    @Test
    @DisplayName("10 code checks an hour per account; the 11th is refused even with the right code")
    void checksPerHour() {
        service.sendCode(EMAIL, anyAddress());
        String first = lastCode();
        for (int i = 0; i < 4; i++) {
            service.verifyCode(EMAIL, wrong(first, i), anyAddress());
        }
        service.sendCode(EMAIL, anyAddress());
        String second = lastCode();
        for (int i = 0; i < 4; i++) {
            service.verifyCode(EMAIL, wrong(second, i), anyAddress());
        }
        service.verifyCode(EMAIL, "", anyAddress());
        service.verifyCode(EMAIL, "", anyAddress());

        assertThat(service.verifyCode(EMAIL, second, anyAddress()).status()).isEqualTo(VerifyStatus.THROTTLED);
        verify(passkeys, never()).deleteAllForUser(anyLong());
    }

    @Test
    @DisplayName("one address is limited across accounts, known or not, and another address is not affected")
    void perAddressLimits() {
        for (int i = 0; i < AccountRecoveryService.CODES_PER_ADDRESS_PER_HOUR; i++) {
            assertThat(service.sendCode("someone" + i + "@example.test", ADDRESS)).isEqualTo(SendStatus.SENT);
        }
        assertThat(service.sendCode(EMAIL, ADDRESS)).isEqualTo(SendStatus.THROTTLED);
        assertThat(service.sendCode(EMAIL, anyAddress())).isEqualTo(SendStatus.SENT);

        for (int i = 0; i < AccountRecoveryService.CHECKS_PER_ADDRESS_PER_HOUR; i++) {
            assertThat(service.verifyCode("someone" + i + "@example.test", "123456", ADDRESS).status())
                    .isEqualTo(VerifyStatus.INVALID);
        }
        assertThat(service.verifyCode(EMAIL, lastCode(), ADDRESS).status()).isEqualTo(VerifyStatus.THROTTLED);
    }

    @Test
    @DisplayName("an email with no account is limited exactly like a real one, and is never sent anything")
    void unknownEmailIsLimitedTheSame() {
        for (int i = 0; i < AccountRecoveryService.CODES_PER_ACCOUNT_PER_HOUR; i++) {
            assertThat(service.sendCode(NOBODY, anyAddress())).isEqualTo(SendStatus.SENT);
        }
        assertThat(service.sendCode(NOBODY, anyAddress())).isEqualTo(SendStatus.THROTTLED);

        for (int i = 0; i < AccountRecoveryService.CHECKS_PER_ACCOUNT_PER_HOUR; i++) {
            assertThat(service.verifyCode(NOBODY, "123456", anyAddress()).status()).isEqualTo(VerifyStatus.INVALID);
        }
        assertThat(service.verifyCode(NOBODY, "123456", anyAddress()).status()).isEqualTo(VerifyStatus.THROTTLED);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("the account limits ignore capitals and spaces in the email")
    void limitsIgnoreCase() {
        // The service looks the account up by the cleaned address, so the same user comes back
        // however the caller typed it.
        verify(userRepository, never()).findByEmail(anyString());
        for (int i = 0; i < AccountRecoveryService.CODES_PER_ACCOUNT_PER_HOUR; i++) {
            assertThat(service.sendCode(i % 2 == 0 ? " Owner@Example.Test " : EMAIL, anyAddress()))
                    .isEqualTo(SendStatus.SENT);
        }
        assertThat(service.sendCode("OWNER@EXAMPLE.TEST", anyAddress())).isEqualTo(SendStatus.THROTTLED);
        verify(userRepository, org.mockito.Mockito.times(6)).findByEmail(EMAIL);
    }

    @Test
    @DisplayName("15 code checks in a day close recovery for that account until the day is over")
    void wrongCodesPerDay() {
        int wrong = 0;
        while (wrong < AccountRecoveryService.CHECKS_PER_ACCOUNT_PER_DAY) {
            assertThat(service.sendCode(EMAIL, anyAddress())).isEqualTo(SendStatus.SENT);
            String real = lastCode();
            for (int i = 0; i < AccountRecoveryService.MAX_WRONG_CODES; i++, wrong++) {
                assertThat(service.verifyCode(EMAIL, wrong(real, i), anyAddress()).status())
                        .isEqualTo(VerifyStatus.INVALID);
            }
            clock.advance(Duration.ofMinutes(61));
        }

        // Three codes used, seven left in the day, and still nothing more is sent or checked.
        assertThat(service.sendCode(EMAIL, anyAddress())).isEqualTo(SendStatus.THROTTLED);
        assertThat(service.verifyCode(EMAIL, "123456", anyAddress()).status()).isEqualTo(VerifyStatus.THROTTLED);

        clock.advance(Duration.ofHours(24));
        assertThat(service.sendCode(EMAIL, anyAddress())).isEqualTo(SendStatus.SENT);
        assertThat(service.verifyCode(EMAIL, lastCode(), anyAddress()).status()).isEqualTo(VerifyStatus.OK);
    }

    @Test
    @DisplayName("an email with no account is closed for the day after the same 15 checks")
    void wrongCodesPerDayForUnknownEmail() {
        for (int i = 0; i < AccountRecoveryService.CHECKS_PER_ACCOUNT_PER_DAY; i++) {
            if (i > 0 && i % AccountRecoveryService.CHECKS_PER_ACCOUNT_PER_HOUR == 0) {
                clock.advance(Duration.ofMinutes(61));
            }
            assertThat(service.verifyCode(NOBODY, "123456", anyAddress()).status()).isEqualTo(VerifyStatus.INVALID);
        }
        clock.advance(Duration.ofMinutes(61));

        assertThat(service.sendCode(NOBODY, anyAddress())).isEqualTo(SendStatus.THROTTLED);
        assertThat(service.verifyCode(NOBODY, "123456", anyAddress()).status()).isEqualTo(VerifyStatus.THROTTLED);
    }

    @Test
    @DisplayName("a code sent to an address the account has since left is dead")
    void codeSentToAnOldAddressIsDead() {
        service.sendCode(EMAIL, anyAddress());
        String code = lastCode();

        // The owner changes their email while the code sits in the old mailbox.
        User moved = new User("moved@example.test");
        moved.setId(USER_ID);
        when(userRepository.findByEmail("moved@example.test")).thenReturn(Optional.of(moved));

        assertThat(service.verifyCode("moved@example.test", code, anyAddress()).status())
                .isEqualTo(VerifyStatus.INVALID);
        verify(passkeys, never()).deleteAllForUser(anyLong());
    }

    @Test
    @DisplayName("if the database fails after the right code, nothing is half done and the code still works")
    void databaseFailureGivesTheCodeBack() {
        service.sendCode(EMAIL, anyAddress());
        String code = lastCode();
        when(sessions.deactivateAllForUser(USER_ID))
                .thenThrow(new IllegalStateException("database blinked"))
                .thenReturn(1);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.verifyCode(EMAIL, code, anyAddress()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cutoffs.isEnded(USER_ID, Date.from(clock.instant().minusSeconds(60)))).isFalse();

        assertThat(service.verifyCode(EMAIL, code, anyAddress()).status()).isEqualTo(VerifyStatus.OK);
    }

    @Test
    @DisplayName("a sign-up for the same email that was started but never finished is cancelled by recovery")
    void recoveryCancelsAHalfDoneSignUp() {
        signUpChallenges.put(EMAIL, challenge());
        signUpChallenges.put("other@example.test", challenge());

        recover();

        assertThat(signUpChallenges.getIfPresent(EMAIL)).isNull();
        assertThat(signUpChallenges.getIfPresent("other@example.test")).isNotNull();
    }

    @Test
    @DisplayName("a finished recovery token is remembered as done, and only a finished one")
    void finishedTokenIsRememberedAsDone() {
        String token = recover();
        assertThat(service.alreadyFinished(token)).isFalse();
        assertThat(service.alreadyFinished("made-up")).isFalse();
        assertThat(service.alreadyFinished(null)).isFalse();

        service.recoveryFinished(token);
        service.recoveryFinished("made-up");

        assertThat(service.alreadyFinished(token)).isTrue();
        assertThat(service.alreadyFinished("made-up")).isFalse();
    }

    // ------------------------------------------------------------------ real email off

    @Test
    @DisplayName("outside development, with real email off, recovery says it is unavailable to everyone and sends nothing")
    void unavailableWithoutRealEmail() {
        service = newService("prod", false);

        assertThat(service.sendCode(EMAIL, anyAddress())).isEqualTo(SendStatus.UNAVAILABLE);
        assertThat(service.sendCode(NOBODY, anyAddress())).isEqualTo(SendStatus.UNAVAILABLE);
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("with real email on, recovery works in prod")
    void worksInProdWithRealEmail() {
        service = newService("prod", true);

        assertThat(service.sendCode(EMAIL, anyAddress())).isEqualTo(SendStatus.SENT);
        assertThat(service.verifyCode(EMAIL, lastCode(), anyAddress()).status()).isEqualTo(VerifyStatus.OK);
    }

    // ------------------------------------------------------------------ the recovery token

    @Test
    @DisplayName("a recovery token names its user, holds one challenge at a time, and hands it over once")
    void recoveryTokenHoldsOneChallenge() {
        String token = recover();
        PublicKeyCredentialCreationOptions challenge = challenge();

        assertThat(service.userForRecoveryToken(token)).contains(USER_ID);
        assertThat(service.takePasskeyChallenge(token)).as("nothing started yet").isEmpty();
        assertThat(service.rememberPasskeyChallenge(token, challenge)).isTrue();

        assertThat(service.takePasskeyChallenge(token).orElseThrow().options()).isSameAs(challenge);
        assertThat(service.takePasskeyChallenge(token)).as("a challenge is handed over once").isEmpty();
        assertThat(service.userForRecoveryToken(token)).as("the token outlives a failed attempt").contains(USER_ID);
    }

    @Test
    @DisplayName("a finished recovery uses the token up")
    void finishedRecoveryUsesTheTokenUp() {
        String token = recover();
        service.rememberPasskeyChallenge(token, challenge());

        service.recoveryFinished(token);

        assertThat(service.userForRecoveryToken(token)).isEmpty();
        assertThat(service.takePasskeyChallenge(token)).isEmpty();
        assertThat(service.rememberPasskeyChallenge(token, challenge())).isFalse();
    }

    @Test
    @DisplayName("a recovery token stops working after 15 minutes, challenge and all")
    void recoveryTokenExpires() {
        String token = recover();
        service.rememberPasskeyChallenge(token, challenge());

        clock.advance(Duration.ofMinutes(AccountRecoveryService.RECOVERY_TOKEN_MINUTES));

        assertThat(service.userForRecoveryToken(token)).isEmpty();
        assertThat(service.takePasskeyChallenge(token)).isEmpty();
        assertThat(service.rememberPasskeyChallenge(token, challenge())).isFalse();
    }

    @Test
    @DisplayName("asking for a passkey prompt keeps the token no longer than its 15 minutes")
    void startingDoesNotExtendTheToken() {
        String token = recover();
        clock.advance(Duration.ofMinutes(10));
        assertThat(service.rememberPasskeyChallenge(token, challenge())).isTrue();

        clock.advance(Duration.ofMinutes(6));

        assertThat(service.takePasskeyChallenge(token)).isEmpty();
    }

    @Test
    @DisplayName("a recovery token allows 5 passkey prompts and is cancelled by the 6th")
    void passkeyStartsAreCapped() {
        String token = recover();
        for (int i = 0; i < AccountRecoveryService.MAX_PASSKEY_STARTS; i++) {
            assertThat(service.rememberPasskeyChallenge(token, challenge())).isTrue();
        }

        assertThat(service.rememberPasskeyChallenge(token, challenge())).isFalse();
        assertThat(service.userForRecoveryToken(token)).isEmpty();
    }

    @Test
    @DisplayName("a made-up, empty or missing recovery token is nobody's")
    void unknownTokensAreNobodys() {
        recover();

        for (String token : new String[] { null, "", "   ", "made-up" }) {
            assertThat(service.userForRecoveryToken(token)).isEmpty();
            assertThat(service.takePasskeyChallenge(token)).isEmpty();
            assertThat(service.rememberPasskeyChallenge(token, challenge())).isFalse();
            service.recoveryFinished(token);
        }
    }

    @Test
    @DisplayName("a second recovery of the same account cancels the first recovery token")
    void newerRecoveryCancelsOlderToken() {
        String first = recover();
        String second = recover();

        assertThat(first).isNotEqualTo(second);
        assertThat(service.userForRecoveryToken(first)).isEmpty();
        assertThat(service.userForRecoveryToken(second)).contains(USER_ID);
    }
}
