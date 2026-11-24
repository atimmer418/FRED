package com.investingapp.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.investingapp.backend.config.DevToolsConfig;
import com.investingapp.backend.dto.EmailMessage;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Changing the email on an account, in two steps.
 *
 * Email is the login identity and the place recovery codes go, so whoever controls it controls the
 * account. A session token is not enough to change it:
 *
 *   1. start: the caller proves they hold the account's passkey right now (checked here on the
 *      server, not just in the app), and a six-digit code goes to the NEW address. The old address
 *      is told that a change was asked for. Nothing on the account changes yet.
 *   2. confirm: the caller sends the code back. Only then does the email change, and the old
 *      address is told it happened.
 *
 * Both steps act on the user id passed in by the controller, which is always the signed-in user.
 * Nothing in the request can point either step at another account.
 *
 * A change that is waiting for its code is kept in memory, keyed by user id, the same way passkey
 * challenges are (see CacheConfig). It lives for 15 minutes at most, so there is nothing worth
 * keeping across a restart: the user just asks again. Like the passkey challenges, this assumes a
 * single backend instance. The code itself is held only as a bcrypt hash.
 */
@Service
public class EmailChangeService {

    private static final Logger logger = LoggerFactory.getLogger(EmailChangeService.class);

    public static final int CODE_MINUTES = 15;
    public static final int MAX_WRONG_CODES = 5;
    /** How many codes one user can ask for in an hour. Each one sends mail to an address they chose. */
    public static final int MAX_STARTS_PER_HOUR = 5;

    public static final String TYPE_CODE = "EMAIL_CHANGE_CODE";
    public static final String TYPE_REQUESTED_NOTICE = "EMAIL_CHANGE_REQUESTED";
    public static final String TYPE_DONE_NOTICE = "EMAIL_CHANGE_DONE";

    public enum Status {
        OK, INVALID_EMAIL, SAME_EMAIL, REAUTH_REQUIRED, EMAIL_IN_USE, EMAIL_UNAVAILABLE, TOO_MANY_REQUESTS,
        NO_PENDING_CHANGE, CODE_EXPIRED, TOO_MANY_ATTEMPTS, INVALID_CODE
    }

    /** What happened. newEmail is set only when the status is OK. */
    public record Outcome(Status status, String newEmail) {
        static Outcome of(Status status) {
            return new Outcome(status, null);
        }

        public boolean ok() {
            return status == Status.OK;
        }
    }

    /** A change waiting for its code. */
    private record Pending(String newEmail, String codeHash, Instant expiresAt, int wrongCodes) {
        Pending withOneMoreWrongCode() {
            return new Pending(newEmail, codeHash, expiresAt, wrongCodes + 1);
        }
    }

    private final UserRepository userRepository;
    private final PasskeyReauthVerifier passkeyReauthVerifier;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;
    private final Environment environment;
    private final boolean emailEnabled;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** user id -> the change waiting for its code. Entries are dropped a little after they expire. */
    private final Cache<Long, Pending> pendingByUser = Caffeine.newBuilder()
            .expireAfterWrite(CODE_MINUTES + 5, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    /** user id -> codes asked for in the last hour. */
    private final Cache<Long, AtomicInteger> startsByUser = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(10_000)
            .build();

    @Autowired
    public EmailChangeService(UserRepository userRepository,
            PasskeyReauthVerifier passkeyReauthVerifier,
            EmailService emailService,
            PasswordEncoder passwordEncoder,
            Validator validator,
            Environment environment,
            @Value("${app.email.enabled:false}") boolean emailEnabled) {
        this(userRepository, passkeyReauthVerifier, emailService, passwordEncoder, validator, environment,
                emailEnabled, Clock.systemUTC());
    }

    EmailChangeService(UserRepository userRepository,
            PasskeyReauthVerifier passkeyReauthVerifier,
            EmailService emailService,
            PasswordEncoder passwordEncoder,
            Validator validator,
            Environment environment,
            boolean emailEnabled,
            Clock clock) {
        this.userRepository = userRepository;
        this.passkeyReauthVerifier = passkeyReauthVerifier;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.validator = validator;
        this.environment = environment;
        this.emailEnabled = emailEnabled;
        this.clock = clock;
    }

    /**
     * Step 1. Checks the passkey proof, then sends a code to the new address.
     * The account's email is not touched.
     */
    public Outcome start(Long userId, String requestedEmail, String passkeySessionId, JsonNode passkeyCredential) {
        User user = userRepository.findById(userId).orElseThrow();
        String currentEmail = user.getEmail();
        String newEmail = requestedEmail == null ? "" : requestedEmail.trim();

        // The proof comes first. Until it passes, the caller learns nothing, not even whether the
        // address they asked for is already taken.
        Optional<Long> proven = passkeyReauthVerifier.verifiedUserId(passkeySessionId, passkeyCredential);
        if (proven.isEmpty() || !proven.get().equals(userId)) {
            logger.warn("Email change refused for user {}: no valid passkey proof for this account", userId);
            return Outcome.of(Status.REAUTH_REQUIRED);
        }

        if (!isValidEmail(newEmail)) {
            return Outcome.of(Status.INVALID_EMAIL);
        }
        if (newEmail.equalsIgnoreCase(currentEmail)) {
            return Outcome.of(Status.SAME_EMAIL);
        }
        if (Boolean.TRUE.equals(userRepository.existsByEmail(newEmail))) {
            return Outcome.of(Status.EMAIL_IN_USE);
        }

        // With real email switched off the code cannot reach anyone. Say so, instead of telling the
        // user a code is on its way. (In development the code is written to the log below.)
        boolean dev = isDevEnvironment();
        if (!emailEnabled && !dev) {
            logger.error("Email change refused for user {}: outbound email is not enabled here", userId);
            return Outcome.of(Status.EMAIL_UNAVAILABLE);
        }

        AtomicInteger starts = startsByUser.get(userId, id -> new AtomicInteger());
        if (starts.incrementAndGet() > MAX_STARTS_PER_HOUR) {
            logger.warn("Email change refused for user {}: too many codes asked for", userId);
            return Outcome.of(Status.TOO_MANY_REQUESTS);
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        // Replaces any earlier request, so only the newest code works.
        pendingByUser.put(userId, new Pending(newEmail, passwordEncoder.encode(code),
                clock.instant().plus(Duration.ofMinutes(CODE_MINUTES)), 0));

        emailService.sendEmail(new EmailMessage(newEmail, null,
                "Your FRED confirmation code", null,
                "Your FRED confirmation code is " + code + ".\n\n"
                        + "Enter it in the app to finish changing your email. It works for "
                        + CODE_MINUTES + " minutes.\n\n"
                        + "If you did not ask for this, you can ignore this message."),
                userId, TYPE_CODE, null);

        emailService.sendEmail(new EmailMessage(currentEmail, null,
                "A change to your FRED email was requested", null,
                "Someone asked to change the email on your FRED account to " + mask(newEmail) + ".\n\n"
                        + "Nothing has changed yet. The change only happens if a code sent to the new "
                        + "address is entered in the app.\n\n"
                        + "If this was not you, contact help@fredvested.com right away."),
                userId, TYPE_REQUESTED_NOTICE, null);

        if (dev && !emailEnabled) {
            // Email is mocked here, so the log is the only way to get the code. Never logged once
            // real email is on, in any environment.
            logger.info("DEV ONLY: email change code for user {} is {}", userId, code);
        }

        logger.info("Email change started for user {}", userId);
        return new Outcome(Status.OK, newEmail);
    }

    /** Step 2. Applies the pending change if the code is right. */
    public Outcome confirm(Long userId, String submittedCode) {
        String code = submittedCode == null ? "" : submittedCode.trim();

        // The check and the count of wrong tries happen as one step per user, so sending many
        // guesses at once cannot buy more than MAX_WRONG_CODES tries.
        Status[] verdict = { Status.NO_PENDING_CHANGE };
        String[] acceptedEmail = { null };
        pendingByUser.asMap().compute(userId, (id, pending) -> {
            if (pending == null) {
                return null;
            }
            if (!clock.instant().isBefore(pending.expiresAt())) {
                verdict[0] = Status.CODE_EXPIRED;
                return null;
            }
            if (code.isEmpty() || !passwordEncoder.matches(code, pending.codeHash())) {
                Pending counted = pending.withOneMoreWrongCode();
                if (counted.wrongCodes() >= MAX_WRONG_CODES) {
                    verdict[0] = Status.TOO_MANY_ATTEMPTS;
                    return null;
                }
                verdict[0] = Status.INVALID_CODE;
                return counted;
            }
            // Right code. It is used up here, whatever happens next.
            verdict[0] = Status.OK;
            acceptedEmail[0] = pending.newEmail();
            return null;
        });

        if (verdict[0] != Status.OK) {
            if (verdict[0] == Status.TOO_MANY_ATTEMPTS) {
                logger.warn("Email change for user {} cancelled after too many wrong codes", userId);
            }
            return Outcome.of(verdict[0]);
        }

        String newEmail = acceptedEmail[0];
        String oldEmail = userRepository.findById(userId).map(User::getEmail).orElseThrow();

        // Someone else may have taken the address while the code was in the inbox. The unique key
        // on users.email is the real guard; the check before it just gives the common case a clean
        // answer.
        try {
            if (Boolean.TRUE.equals(userRepository.existsByEmail(newEmail))
                    || userRepository.updateEmail(userId, newEmail) != 1) {
                return Outcome.of(Status.EMAIL_IN_USE);
            }
        } catch (DataIntegrityViolationException e) {
            return Outcome.of(Status.EMAIL_IN_USE);
        }

        emailService.sendEmail(new EmailMessage(oldEmail, null,
                "Your FRED email was changed", null,
                "The email on your FRED account was changed to " + mask(newEmail) + ".\n\n"
                        + "From now on you sign in and recover your account with the new address.\n\n"
                        + "If this was not you, contact help@fredvested.com right away."),
                userId, TYPE_DONE_NOTICE, null);

        logger.info("Email changed for user {}", userId);
        return new Outcome(Status.OK, newEmail);
    }

    /** True while a change is waiting for its code. */
    public boolean hasPendingChange(Long userId) {
        Pending pending = pendingByUser.getIfPresent(userId);
        return pending != null && clock.instant().isBefore(pending.expiresAt());
    }

    /** The same rules the User entity puts on its email, so an address accepted here can be saved. */
    private boolean isValidEmail(String email) {
        if (email.isEmpty() || email.chars().anyMatch(Character::isWhitespace) || !email.contains(".")) {
            return false;
        }
        return validator.validateValue(User.class, "email", email).isEmpty();
    }

    /** "jane.doe@example.com" becomes "j***@example.com": enough to recognise, not enough to leak. */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    private boolean isDevEnvironment() {
        return DevToolsConfig.isDevEnvironment(environment);
    }
}
