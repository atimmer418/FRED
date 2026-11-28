package com.investingapp.backend.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.investingapp.backend.config.DevToolsConfig;
import com.investingapp.backend.dto.EmailMessage;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.PasskeyCredentialRepository;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.repository.UserSessionRepository;
import com.investingapp.backend.security.TokenCutoffRegistry;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Getting back into an account when the passkey is gone, in three steps.
 *
 *   1. sendCode: a six-digit code goes to the account's email.
 *   2. verifyCode: the right code removes the account's passkeys, ends every session and token the
 *      account had, and hands back a one-time recovery token. It does NOT sign the caller in.
 *   3. The recovery token, and nothing else, lets the caller register one new passkey
 *      (WebAuthnController). Registering it is what signs them in.
 *
 * What this guards against:
 *
 *   - Guessing the code. A code allows 5 wrong tries and is then dead. An account takes at most
 *     15 code checks a day, however many codes are asked for. Codes and checks are also limited
 *     per account and per network address.
 *   - A stolen session token. Before, any valid token could register a passkey on an account that
 *     had none, so a thief could beat the real owner to it right after a recovery. Now only the
 *     recovery token can, and the thief's token stops working the moment recovery succeeds.
 *
 * The answers do not say whether an email has an account: an unknown email gets the same replies
 * and the same limits as a known one. (Response time can still differ a little, because a real
 * account is sent an email inside the request. Sign-up also says when an email is taken.)
 *
 * The cost of these limits: anyone who knows an account's email can use up its tries and keep the
 * owner out of recovery for a day at a time. That is the lesser harm. They cannot get in, the owner
 * is not signed out, and help@fredvested.com is the way round it.
 *
 * Codes, recovery tokens and counters are kept in memory, the same way passkey challenges and
 * pending email changes are. They live 15 minutes to a day, so a restart only means asking again.
 * Like those, this assumes a single backend instance. The code is held only as a bcrypt hash and
 * the recovery token only as a SHA-256 hash.
 */
@Service
public class AccountRecoveryService {

    private static final Logger logger = LoggerFactory.getLogger(AccountRecoveryService.class);

    public static final int CODE_MINUTES = 15;
    public static final int MAX_WRONG_CODES = 5;
    /** Codes one account can be sent. Each code allows MAX_WRONG_CODES guesses. */
    public static final int CODES_PER_ACCOUNT_PER_HOUR = 5;
    public static final int CODES_PER_ACCOUNT_PER_DAY = 10;
    /** Code checks against one account, right or wrong. */
    public static final int CHECKS_PER_ACCOUNT_PER_HOUR = 10;
    /**
     * Code checks one account gets in a day, right or wrong, before recovery is closed for it until
     * the day is over. This is the number that bounds guessing: without it, 10 codes a day at 5
     * tries each would allow 50 guesses a day. With it, an account can be guessed at 15 times a day
     * at most. Each check is counted before it is looked at, so checks sent together cannot slip past.
     */
    public static final int CHECKS_PER_ACCOUNT_PER_DAY = 15;
    /** One network address, across all accounts. Looser, because offices and phone networks share one. */
    public static final int CODES_PER_ADDRESS_PER_HOUR = 20;
    public static final int CHECKS_PER_ADDRESS_PER_HOUR = 40;

    public static final int RECOVERY_TOKEN_MINUTES = 15;
    /**
     * Tokens issued up to this long after a recovery are ended with the older ones. A request that
     * was already in flight when recovery landed (a token refresh, say) could otherwise come out
     * the far side with a fresh token. The owner's own new token comes after a passkey prompt,
     * which takes longer than this.
     */
    public static final int TOKEN_CUTOFF_GRACE_SECONDS = 2;
    /** How many times one recovery token can ask for a passkey prompt (cancelled prompts happen). */
    public static final int MAX_PASSKEY_STARTS = 5;

    public static final String TYPE_CODE = "RECOVERY_CODE";
    public static final String TYPE_DONE_NOTICE = "RECOVERY_DONE";

    public enum SendStatus { SENT, THROTTLED, UNAVAILABLE }

    public enum VerifyStatus { OK, INVALID, THROTTLED }

    /** recoveryToken is set only when the status is OK. */
    public record VerifyOutcome(VerifyStatus status, String recoveryToken) {
        static VerifyOutcome of(VerifyStatus status) {
            return new VerifyOutcome(status, null);
        }
    }

    /** A passkey registration that a recovery token started and has not finished yet. */
    public record PendingPasskey(Long userId, PublicKeyCredentialCreationOptions options) {
    }

    /** A code waiting to be entered, and the address it was sent to. */
    private record PendingCode(String codeHash, Instant expiresAt, int wrongCodes, String sentTo) {
        PendingCode withOneMoreWrongCode() {
            return new PendingCode(codeHash, expiresAt, wrongCodes + 1, sentTo);
        }
    }

    /** A verified recovery: the right to register one passkey for this user. */
    private record Grant(Long userId, Instant expiresAt, PublicKeyCredentialCreationOptions options, int starts) {
    }

    private final UserRepository userRepository;
    private final PasskeyCredentialRepository passkeyCredentialRepository;
    private final UserSessionRepository userSessionRepository;
    private final TokenCutoffRegistry tokenCutoffs;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transaction;
    private final Environment environment;
    private final boolean emailEnabled;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    /** Checked when there is no real code, so "no such account" takes as long as "wrong code". */
    private final String decoyHash;

    /** The sign-up passkey challenges (CacheConfig), keyed by email. */
    private final Cache<String, PublicKeyCredentialCreationOptions> signUpChallenges;
    private final Cache<Long, PendingCode> codeByUser;
    /** Recovery tokens that did their job, kept briefly so a repeat can be told "already done". */
    private final Cache<String, Boolean> finishedTokenHashes;
    private final Cache<String, Grant> grantByTokenHash;
    private final Cache<Long, String> tokenHashByUser;

    // Accounts that exist are counted by user id. Emails with no account are counted separately,
    // by address: anyone can invent those, and a flood of invented addresses must not be able to
    // push a real account's counters out of memory.
    private final AttemptLimiter<Long> codesPerAccountHour;
    private final AttemptLimiter<Long> codesPerAccountDay;
    private final AttemptLimiter<Long> checksPerAccountHour;
    private final AttemptLimiter<Long> checksPerAccountDay;
    private final AttemptLimiter<String> checksPerUnknownEmailDay;
    private final AttemptLimiter<String> codesPerUnknownEmailHour;
    private final AttemptLimiter<String> codesPerUnknownEmailDay;
    private final AttemptLimiter<String> checksPerUnknownEmailHour;
    private final AttemptLimiter<String> codesPerAddressHour;
    private final AttemptLimiter<String> checksPerAddressHour;

    @Autowired
    public AccountRecoveryService(UserRepository userRepository,
            PasskeyCredentialRepository passkeyCredentialRepository,
            UserSessionRepository userSessionRepository,
            TokenCutoffRegistry tokenCutoffs,
            EmailService emailService,
            PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager,
            Environment environment,
            Cache<String, PublicKeyCredentialCreationOptions> challengeCache,
            @Value("${app.email.enabled:false}") boolean emailEnabled) {
        this(userRepository, passkeyCredentialRepository, userSessionRepository, tokenCutoffs, emailService,
                passwordEncoder, new TransactionTemplate(transactionManager), environment, challengeCache,
                emailEnabled, Clock.systemUTC());
    }

    AccountRecoveryService(UserRepository userRepository,
            PasskeyCredentialRepository passkeyCredentialRepository,
            UserSessionRepository userSessionRepository,
            TokenCutoffRegistry tokenCutoffs,
            EmailService emailService,
            PasswordEncoder passwordEncoder,
            TransactionTemplate transaction,
            Environment environment,
            Cache<String, PublicKeyCredentialCreationOptions> signUpChallenges,
            boolean emailEnabled,
            Clock clock) {
        this.signUpChallenges = signUpChallenges;
        this.userRepository = userRepository;
        this.passkeyCredentialRepository = passkeyCredentialRepository;
        this.userSessionRepository = userSessionRepository;
        this.tokenCutoffs = tokenCutoffs;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
        this.transaction = transaction;
        this.environment = environment;
        this.emailEnabled = emailEnabled;
        this.clock = clock;
        this.decoyHash = passwordEncoder.encode("not-a-code");

        Ticker ticker = () -> TimeUnit.MILLISECONDS.toNanos(clock.millis());
        Duration hour = Duration.ofHours(1);
        Duration day = Duration.ofDays(1);
        this.codeByUser = Caffeine.newBuilder().ticker(ticker)
                .expireAfterWrite(Duration.ofMinutes(CODE_MINUTES + 5)).build();
        this.grantByTokenHash = Caffeine.newBuilder().ticker(ticker)
                .expireAfterWrite(Duration.ofMinutes(RECOVERY_TOKEN_MINUTES + 5)).build();
        this.finishedTokenHashes = Caffeine.newBuilder().ticker(ticker)
                .expireAfterWrite(Duration.ofMinutes(RECOVERY_TOKEN_MINUTES)).maximumSize(100_000).build();
        this.tokenHashByUser = Caffeine.newBuilder().ticker(ticker)
                .expireAfterWrite(Duration.ofMinutes(RECOVERY_TOKEN_MINUTES + 5)).build();
        this.codesPerAccountHour = new AttemptLimiter<>(CODES_PER_ACCOUNT_PER_HOUR, hour, 5_000_000, ticker);
        this.codesPerAccountDay = new AttemptLimiter<>(CODES_PER_ACCOUNT_PER_DAY, day, 5_000_000, ticker);
        this.checksPerAccountHour = new AttemptLimiter<>(CHECKS_PER_ACCOUNT_PER_HOUR, hour, 5_000_000, ticker);
        this.checksPerAccountDay = new AttemptLimiter<>(CHECKS_PER_ACCOUNT_PER_DAY, day, 5_000_000, ticker);
        this.checksPerUnknownEmailDay = new AttemptLimiter<>(CHECKS_PER_ACCOUNT_PER_DAY, day, 50_000, ticker);
        this.codesPerUnknownEmailHour = new AttemptLimiter<>(CODES_PER_ACCOUNT_PER_HOUR, hour, 50_000, ticker);
        this.codesPerUnknownEmailDay = new AttemptLimiter<>(CODES_PER_ACCOUNT_PER_DAY, day, 50_000, ticker);
        this.checksPerUnknownEmailHour = new AttemptLimiter<>(CHECKS_PER_ACCOUNT_PER_HOUR, hour, 50_000, ticker);
        this.codesPerAddressHour = new AttemptLimiter<>(CODES_PER_ADDRESS_PER_HOUR, hour, 100_000, ticker);
        this.checksPerAddressHour = new AttemptLimiter<>(CHECKS_PER_ADDRESS_PER_HOUR, hour, 100_000, ticker);
    }

    // ------------------------------------------------------------------ step 1: send a code

    /**
     * Sends a recovery code to the email, if it has an account. SENT is returned either way, so the
     * answer does not say whether the account exists.
     */
    public SendStatus sendCode(String requestedEmail, String clientAddress) {
        // With real email switched off the code cannot reach anyone. Say so to every caller, known
        // account or not, instead of promising a code. (In development the code is logged below.)
        boolean dev = DevToolsConfig.isDevEnvironment(environment);
        if (!emailEnabled && !dev) {
            logger.error("Account recovery refused: outbound email is not enabled here");
            return SendStatus.UNAVAILABLE;
        }

        String email = normalise(requestedEmail);
        User user = email.isEmpty() ? null : userRepository.findByEmail(email).orElse(null);
        // A closed account that is still waiting to be deleted is treated exactly like an address
        // nobody has: the same answer, and no code.
        if (user != null && user.isClosed()) {
            user = null;
        }

        boolean allowed = codesPerAddressHour.allow(addressKey(clientAddress));
        if (user != null) {
            // Both are counted even if the first says no; the short-circuit would not matter.
            allowed &= codesPerAccountHour.allow(user.getId());
            allowed &= codesPerAccountDay.allow(user.getId());
        } else {
            allowed &= codesPerUnknownEmailHour.allow(email);
            allowed &= codesPerUnknownEmailDay.allow(email);
        }
        allowed &= !closedForTheDay(user, email);
        if (!allowed) {
            logger.warn("Recovery code refused: too many requests (known account: {})", user != null);
            return SendStatus.THROTTLED;
        }

        // Made and hashed for every caller, so an unknown email costs the same time as a known one.
        String code = String.format("%06d", random.nextInt(1_000_000));
        String codeHash = passwordEncoder.encode(code);
        if (user == null) {
            return SendStatus.SENT;
        }

        Long userId = user.getId();
        // Replaces any earlier code, so only the newest one works.
        codeByUser.put(userId, new PendingCode(codeHash,
                clock.instant().plus(Duration.ofMinutes(CODE_MINUTES)), 0, user.getEmail()));

        emailService.sendEmail(new EmailMessage(user.getEmail(), null,
                "Your FRED recovery code", null,
                "Your FRED recovery code is " + code + ".\n\n"
                        + "Enter it in the app to get back into your account. It works for "
                        + CODE_MINUTES + " minutes.\n\n"
                        + "If you did not ask for this, you can ignore this message. Your account "
                        + "has not changed."),
                userId, TYPE_CODE, null);

        if (dev && !emailEnabled) {
            // Email is mocked here, so the log is the only way to get the code. Never logged once
            // real email is on, in any environment.
            logger.info("DEV ONLY: recovery code for user {} is {}", userId, code);
        }
        logger.info("Recovery code sent for user {}", userId);
        return SendStatus.SENT;
    }

    // ------------------------------------------------------------------ step 2: check the code

    /**
     * Checks a code. On the right code the account's passkeys are removed, every session and token
     * it had is ended, and a one-time recovery token is returned.
     */
    public VerifyOutcome verifyCode(String requestedEmail, String submittedCode, String clientAddress) {
        String email = normalise(requestedEmail);
        String code = submittedCode == null ? "" : submittedCode.trim();
        User user = email.isEmpty() ? null : userRepository.findByEmail(email).orElse(null);
        if (user != null && user.isClosed()) {
            user = null;
        }

        boolean allowed = checksPerAddressHour.allow(addressKey(clientAddress));
        allowed &= (user != null)
                ? checksPerAccountHour.allow(user.getId())
                : checksPerUnknownEmailHour.allow(email);
        allowed &= (user != null)
                ? checksPerAccountDay.allow(user.getId())
                : checksPerUnknownEmailDay.allow(email);
        if (!allowed) {
            logger.warn("Recovery code check refused: too many tries (known account: {})", user != null);
            return VerifyOutcome.of(VerifyStatus.THROTTLED);
        }

        if (user == null) {
            passwordEncoder.matches(code, decoyHash);
            return VerifyOutcome.of(VerifyStatus.INVALID);
        }
        Long userId = user.getId();
        String currentEmail = user.getEmail();

        // The check and the count of wrong tries happen as one step per user, so sending many
        // guesses at once cannot buy more than MAX_WRONG_CODES tries.
        boolean[] accepted = { false };
        boolean[] compared = { false };
        PendingCode[] taken = { null };
        codeByUser.asMap().compute(userId, (id, pending) -> {
            if (pending == null) {
                return null;
            }
            if (!clock.instant().isBefore(pending.expiresAt())) {
                return null;
            }
            // A code sent to an address the account has since moved away from is dead: whoever
            // reads the old mailbox must not be able to use it.
            if (pending.sentTo() == null || !pending.sentTo().equalsIgnoreCase(currentEmail)) {
                return null;
            }
            taken[0] = pending;
            compared[0] = true;
            if (!passwordEncoder.matches(code, pending.codeHash()) || code.isEmpty()) {
                PendingCode counted = pending.withOneMoreWrongCode();
                if (counted.wrongCodes() >= MAX_WRONG_CODES) {
                    logger.warn("Recovery code for user {} cancelled after too many wrong tries", userId);
                    return null;
                }
                return counted;
            }
            // Right code. It is used up here, whatever happens next.
            accepted[0] = true;
            return null;
        });

        if (!accepted[0]) {
            if (!compared[0]) {
                // No live code to compare with. Spend the same time anyway, so the answer's speed
                // does not say whether there was one.
                passwordEncoder.matches(code, decoyHash);
            }
            return VerifyOutcome.of(VerifyStatus.INVALID);
        }

        // Passkeys gone and sessions ended together, or neither.
        try {
            transaction.executeWithoutResult(status -> {
                passkeyCredentialRepository.deleteAllForUser(userId);
                userSessionRepository.deactivateAllForUser(userId);
            });
        } catch (RuntimeException e) {
            // Nothing changed on the account, so the owner's code is still good: give it back
            // rather than making them ask for another because the database blinked.
            codeByUser.asMap().putIfAbsent(userId, taken[0]);
            logger.error("Account recovery for user {} could not be completed", userId, e);
            throw e;
        }
        // A sign-up for this email that was started but never finished must not be finishable
        // now: with the passkeys gone, the account would look like a brand-new one to it.
        signUpChallenges.invalidate(currentEmail);
        // Sessions only reach tokens that carry a session id. This reaches the rest.
        tokenCutoffs.endTokensIssuedUpTo(userId, clock.instant().plusSeconds(TOKEN_CUTOFF_GRACE_SECONDS));

        String recoveryToken = newToken();
        String tokenHash = hash(recoveryToken);
        // One live recovery token per account: a newer recovery cancels the older token.
        String earlier = tokenHashByUser.asMap().put(userId, tokenHash);
        if (earlier != null) {
            grantByTokenHash.invalidate(earlier);
        }
        grantByTokenHash.put(tokenHash, new Grant(userId,
                clock.instant().plus(Duration.ofMinutes(RECOVERY_TOKEN_MINUTES)), null, 0));

        emailService.sendEmail(new EmailMessage(user.getEmail(), null,
                "Your FRED account was recovered", null,
                "A recovery code was used to get back into your FRED account. Its passkeys were "
                        + "removed and it was signed out everywhere, so a new passkey can be set up.\n\n"
                        + "If this was not you, contact help@fredvested.com right away."),
                userId, TYPE_DONE_NOTICE, null);

        logger.info("Account recovery verified for user {}: passkeys removed, sessions ended", userId);
        return new VerifyOutcome(VerifyStatus.OK, recoveryToken);
    }

    // ------------------------------------------------------------------ step 3: one new passkey

    /** The user a live recovery token belongs to. */
    public Optional<Long> userForRecoveryToken(String recoveryToken) {
        String tokenHash = hash(recoveryToken);
        if (tokenHash == null) {
            return Optional.empty();
        }
        Grant grant = grantByTokenHash.getIfPresent(tokenHash);
        return (grant == null || expired(grant)) ? Optional.empty() : Optional.of(grant.userId());
    }

    /**
     * Stores the passkey registration challenge made for this recovery token, replacing any earlier
     * one. False when the token is unknown, expired, or has asked too many times (which cancels it).
     */
    public boolean rememberPasskeyChallenge(String recoveryToken, PublicKeyCredentialCreationOptions options) {
        String tokenHash = hash(recoveryToken);
        if (tokenHash == null || options == null) {
            return false;
        }
        boolean[] stored = { false };
        grantByTokenHash.asMap().computeIfPresent(tokenHash, (hash, grant) -> {
            if (expired(grant) || grant.starts() >= MAX_PASSKEY_STARTS) {
                return null;
            }
            stored[0] = true;
            return new Grant(grant.userId(), grant.expiresAt(), options, grant.starts() + 1);
        });
        return stored[0];
    }

    /**
     * Hands over the waiting challenge for this recovery token, once. The token itself stays good
     * until {@link #recoveryFinished}, so a failed passkey prompt can be tried again.
     */
    public Optional<PendingPasskey> takePasskeyChallenge(String recoveryToken) {
        String tokenHash = hash(recoveryToken);
        if (tokenHash == null) {
            return Optional.empty();
        }
        PendingPasskey[] taken = { null };
        grantByTokenHash.asMap().computeIfPresent(tokenHash, (hash, grant) -> {
            if (expired(grant)) {
                return null;
            }
            if (grant.options() == null) {
                return grant;
            }
            taken[0] = new PendingPasskey(grant.userId(), grant.options());
            return new Grant(grant.userId(), grant.expiresAt(), null, grant.starts());
        });
        return Optional.ofNullable(taken[0]);
    }

    /** The new passkey is registered: the recovery token is used up. */
    public void recoveryFinished(String recoveryToken) {
        String tokenHash = hash(recoveryToken);
        if (tokenHash == null) {
            return;
        }
        Grant grant = grantByTokenHash.asMap().remove(tokenHash);
        if (grant != null) {
            tokenHashByUser.asMap().remove(grant.userId(), tokenHash);
            finishedTokenHashes.put(tokenHash, Boolean.TRUE);
        }
    }

    /**
     * True for a recovery token that already registered its passkey. Lets a device that never got
     * the "done" answer be told to sign in, instead of being sent round recovery again, which would
     * remove the passkey it just made.
     */
    public boolean alreadyFinished(String recoveryToken) {
        String tokenHash = hash(recoveryToken);
        return tokenHash != null && finishedTokenHashes.getIfPresent(tokenHash) != null;
    }

    /**
     * Waits out the short stretch after a recovery in which new tokens are still treated as ended
     * (see TOKEN_CUTOFF_GRACE_SECONDS), so the token the owner is about to be given is a live one.
     * A person takes longer than this at the passkey prompt; this is for a very fast device.
     */
    public void waitUntilNewTokensAreGood(Long userId) {
        long giveUpAt = System.currentTimeMillis() + (TOKEN_CUTOFF_GRACE_SECONDS + 2) * 1000L;
        while (tokenCutoffs.isEnded(userId, new java.util.Date()) && System.currentTimeMillis() < giveUpAt) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** True once an account (or an email with none) has had its day's worth of code checks. */
    private boolean closedForTheDay(User user, String email) {
        return (user != null)
                ? checksPerAccountDay.usedUp(user.getId())
                : checksPerUnknownEmailDay.usedUp(email);
    }

    private boolean expired(Grant grant) {
        return !clock.instant().isBefore(grant.expiresAt());
    }

    /** Trimmed and lower-cased. Cut at the longest a real address can be, so it is a safe counter key. */
    private static String normalise(String email) {
        String cleaned = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return cleaned.length() > 320 ? cleaned.substring(0, 320) : cleaned;
    }

    private static String addressKey(String clientAddress) {
        return (clientAddress == null || clientAddress.isBlank()) ? "unknown" : clientAddress.trim();
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 of the token, or null for a missing token. Only the hash is ever kept. */
    private static String hash(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
