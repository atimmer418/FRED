package com.investingapp.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.dto.EmailMessage;
import com.investingapp.backend.security.jwt.JwtUtils;
import com.investingapp.backend.service.AccountRecoveryService;
import com.investingapp.backend.service.EmailService;
import com.investingapp.backend.support.RequiresCiDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * The two ways into someone else's account that account recovery used to leave open.
 *
 *   1. Guessing. The recovery code is six digits, the check needed no sign-in, and nothing counted
 *      wrong tries. Enough guesses got in, removed the owner's passkeys and returned a session token.
 *   2. The recovery window. Any valid session token could register a passkey on an account that had
 *      none. Recovery leaves an account with none, so a thief holding an old token could register
 *      their own passkey before the real owner did. Recovery also left every old token working.
 *
 * Two users are seeded in a real database. Every request goes through the real security filter
 * chain, the real controllers and services and real SQL. Only outbound email is faked, so the test
 * can read the code that was sent.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class AccountRecoveryAttackTest {

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtUtils jwtUtils;

    @MockitoBean
    private EmailService emailService;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    /** Each test calls from its own network address, so the per-address limits never leak between tests. */
    private String address;
    private String emailA;
    private String emailB;
    private Long userAId;
    private Long userBId;

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        address = randomAddress();
        emailA = "rec-a-" + UUID.randomUUID() + "@fredvested.test";
        emailB = "rec-b-" + UUID.randomUUID() + "@fredvested.test";
        userAId = insertUser(emailA);
        userBId = insertUser(emailB);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM user_sessions WHERE user_id IN (?, ?)", userAId, userBId);
        jdbc.update("DELETE FROM passkey_credentials WHERE user_id IN (?, ?)", userAId, userBId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", userAId, userBId);
    }

    // ------------------------------------------------------------------ helpers

    private static String randomAddress() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
    }

    private Long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, create_date, public_id, analytics_opt_out, user_handle) "
                + "VALUES (?, ?, ?, 0, ?)", email, LocalDateTime.now(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", "").substring(0, 22));
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private void insertPasskey(Long userId) {
        jdbc.update("INSERT INTO passkey_credentials (external_id, public_key_cose, signature_count, user_id) "
                + "VALUES (?, 'not-a-real-key', 0, ?)", "cred-" + UUID.randomUUID(), userId);
    }

    private long passkeyCount(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM passkey_credentials WHERE user_id = ?", Long.class, userId);
    }

    private Long insertSession(Long userId, String deviceId) {
        jdbc.update("INSERT INTO user_sessions (active, device_id, device_info, user_id, created_at) "
                + "VALUES (1, ?, 'test device', ?, NOW())", deviceId, userId);
        return jdbc.queryForObject("SELECT id FROM user_sessions WHERE user_id = ? AND device_id = ?",
                Long.class, userId, deviceId);
    }

    private boolean sessionActive(Long sessionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT active FROM user_sessions WHERE id = ?", Boolean.class, sessionId));
    }

    private MockHttpServletResponse post(String path, Map<String, Object> body, String bearer, String fromAddress)
            throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", fromAddress)
                .content(json.writeValueAsString(body));
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse post(String path, Map<String, Object> body, String bearer) throws Exception {
        return post(path, body, bearer, address);
    }

    private MockHttpServletResponse askForCode(String email) throws Exception {
        return post("/api/auth/recovery/initiate", Map.of("email", email), null);
    }

    private MockHttpServletResponse check(String email, String code) throws Exception {
        return post("/api/auth/recovery/verify", Map.of("email", email, "otp", code), null);
    }

    /** The six-digit code from the newest recovery message, which must have gone to this address. */
    private String codeSentTo(String address) {
        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService, atLeastOnce())
                .sendEmail(sent.capture(), any(), eq(AccountRecoveryService.TYPE_CODE), any());
        EmailMessage last = sent.getAllValues().get(sent.getAllValues().size() - 1);
        assertThat(last.getTo()).isEqualTo(address);
        Matcher matcher = CODE.matcher(last.getTextBody());
        assertThat(matcher.find()).as("a six-digit code in the message").isTrue();
        return matcher.group(1);
    }

    private static String wrongCode(String real, int i) {
        return String.format("%06d", (Integer.parseInt(real) + 1 + i) % 1_000_000);
    }

    /** Runs a whole recovery for the email and returns the recovery token. */
    private String recover(String email) throws Exception {
        assertThat(askForCode(email).getStatus()).isEqualTo(200);
        MockHttpServletResponse response = check(email, codeSentTo(email));
        assertThat(response.getStatus()).isEqualTo(200);
        String token = json.readTree(response.getContentAsString()).path("recoveryToken").asText(null);
        assertThat(token).as("recovery token").isNotBlank();
        return token;
    }

    /** 200 when the token is honoured, 401 when it is not. */
    private int useToken(String bearer) throws Exception {
        return post("/api/auth/refresh", Map.of(), bearer).getStatus();
    }

    private int startPasskey(String recoveryToken, String bearer) throws Exception {
        Map<String, Object> body = new HashMap<>();
        if (recoveryToken != null) {
            body.put("recoveryToken", recoveryToken);
        }
        return post("/api/passkey/register/recovery/start", body, bearer).getStatus();
    }

    private MockHttpServletResponse finishPasskey(String email, String recoveryToken) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("credential", Map.of("id", "forged", "response", Map.of("attestationObject", "forged")));
        if (recoveryToken != null) {
            body.put("recoveryToken", recoveryToken);
        }
        return post("/api/passkey/register/finish", body, null);
    }

    // ------------------------------------------------------------------ attack 1: guessing the code

    @Test
    @DisplayName("after 5 wrong guesses the code is dead: even the right code no longer works")
    void guessingIsCappedPerCode() throws Exception {
        insertPasskey(userAId);
        askForCode(emailA);
        String real = codeSentTo(emailA);

        for (int i = 0; i < AccountRecoveryService.MAX_WRONG_CODES; i++) {
            assertThat(check(emailA, wrongCode(real, i)).getStatus()).isEqualTo(401);
        }

        MockHttpServletResponse response = check(emailA, real);
        assertThat(response.getStatus()).as("the right code after 5 wrong ones").isEqualTo(401);
        assertThat(json.readTree(response.getContentAsString()).path("recoveryToken").isNull()).isTrue();
        assertThat(passkeyCount(userAId)).as("the owner's passkey is untouched").isEqualTo(1);
    }

    @Test
    @DisplayName("4 wrong guesses then the right code still works: an honest typo does not lock the owner out")
    void aFewTyposAreForgiven() throws Exception {
        askForCode(emailA);
        String real = codeSentTo(emailA);
        for (int i = 0; i < AccountRecoveryService.MAX_WRONG_CODES - 1; i++) {
            assertThat(check(emailA, wrongCode(real, i)).getStatus()).isEqualTo(401);
        }
        assertThat(check(emailA, real).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a code works once")
    void aCodeWorksOnce() throws Exception {
        askForCode(emailA);
        String real = codeSentTo(emailA);
        assertThat(check(emailA, real).getStatus()).isEqualTo(200);
        assertThat(check(emailA, real).getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("asking for new codes does not buy unlimited guesses: the 6th code in an hour is refused")
    void codesPerAccountAreLimited() throws Exception {
        for (int i = 0; i < AccountRecoveryService.CODES_PER_ACCOUNT_PER_HOUR; i++) {
            assertThat(askForCode(emailA).getStatus()).isEqualTo(200);
        }
        // From a different network address each time: the limit follows the account.
        assertThat(post("/api/auth/recovery/initiate", Map.of("email", emailA), null, randomAddress()).getStatus())
                .isEqualTo(429);
        assertThat(askForCode(emailB).getStatus()).as("another account is not affected").isEqualTo(200);
    }

    @Test
    @DisplayName("code checks against one account are limited too, whatever address they come from")
    void checksPerAccountAreLimited() throws Exception {
        for (int i = 0; i < AccountRecoveryService.CHECKS_PER_ACCOUNT_PER_HOUR; i++) {
            assertThat(post("/api/auth/recovery/verify", Map.of("email", emailA, "otp", "000000"), null,
                    randomAddress()).getStatus()).isEqualTo(401);
        }
        assertThat(post("/api/auth/recovery/verify", Map.of("email", emailA, "otp", "000000"), null,
                randomAddress()).getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("one network address cannot work through many accounts")
    void requestsPerAddressAreLimited() throws Exception {
        for (int i = 0; i < AccountRecoveryService.CODES_PER_ADDRESS_PER_HOUR; i++) {
            assertThat(askForCode("nobody-" + UUID.randomUUID() + "@fredvested.test").getStatus()).isEqualTo(200);
        }
        assertThat(askForCode(emailA).getStatus()).isEqualTo(429);
        assertThat(post("/api/auth/recovery/initiate", Map.of("email", emailA), null, randomAddress()).getStatus())
                .as("a different address is not affected").isEqualTo(200);
    }

    @Test
    @DisplayName("an email with no account gets the same answers and the same limits as a real one")
    void unknownEmailLooksTheSame() throws Exception {
        String nobody = "nobody-" + UUID.randomUUID() + "@fredvested.test";
        askForCode(emailA);

        MockHttpServletResponse known = check(emailA, wrongCode(codeSentTo(emailA), 0));
        MockHttpServletResponse unknown = check(nobody, "123456");
        assertThat(unknown.getStatus()).isEqualTo(known.getStatus()).isEqualTo(401);
        assertThat(unknown.getContentAsString()).isEqualTo(known.getContentAsString());

        for (int i = 0; i < AccountRecoveryService.CODES_PER_ACCOUNT_PER_HOUR; i++) {
            assertThat(askForCode(nobody).getStatus()).isEqualTo(200);
        }
        assertThat(askForCode(nobody).getStatus()).isEqualTo(429);
        // Only the real account was ever sent anything.
        verify(emailService, times(1)).sendEmail(any(EmailMessage.class), any(), anyString(), any());
    }

    // ------------------------------------------------------------------ attack 2: the recovery window

    @Test
    @DisplayName("recovery hands back a recovery token, not a session token")
    void recoveryDoesNotSignAnyoneIn() throws Exception {
        askForCode(emailA);
        JsonNode body = json.readTree(check(emailA, codeSentTo(emailA)).getContentAsString());

        assertThat(body.has("token")).isFalse();
        String recoveryToken = body.path("recoveryToken").asText();
        assertThat(jwtUtils.validateJwtToken(recoveryToken)).isFalse();
        assertThat(useToken(recoveryToken)).as("the recovery token used as a session token").isEqualTo(401);
    }

    @Test
    @DisplayName("a stolen session token cannot register a passkey on an account that was just recovered")
    void stolenTokenCannotRegisterAfterRecovery() throws Exception {
        String stolen = jwtUtils.generateJwtTokenFromUsername(emailA);
        recover(emailA);

        assertThat(startPasskey(null, stolen)).isEqualTo(401);
        assertThat(finishPasskey(emailA, null).getStatus()).isEqualTo(400);
        assertThat(passkeyCount(userAId)).isZero();
    }

    @Test
    @DisplayName("a session token cannot register a passkey on any account with none, recovered or not")
    void sessionTokenNeverRegistersAPasskey() throws Exception {
        String token = jwtUtils.generateJwtTokenFromUsername(emailA);
        assertThat(useToken(token)).as("the token itself is good").isEqualTo(200);

        assertThat(startPasskey(null, token)).isEqualTo(401);
        assertThat(startPasskey(token, null)).as("a session token passed off as a recovery token").isEqualTo(401);
    }

    @Test
    @DisplayName("every token the account held stops working once it is recovered, with or without a session id")
    void recoveryEndsEverySession() throws Exception {
        Long sessionId = insertSession(userAId, "phone");
        String sessionToken = jwtUtils.generateTokenFromUsername(emailA, sessionId, null, null);
        String looseToken = jwtUtils.generateJwtTokenFromUsername(emailA);
        assertThat(useToken(sessionToken)).isEqualTo(200);
        assertThat(useToken(looseToken)).isEqualTo(200);

        recover(emailA);

        assertThat(sessionActive(sessionId)).isFalse();
        assertThat(useToken(sessionToken)).isEqualTo(401);
        assertThat(useToken(looseToken)).isEqualTo(401);
    }

    @Test
    @DisplayName("a device still sending its dead token can go through recovery again")
    void aDeadTokenDoesNotBlockRecovery() throws Exception {
        Long sessionId = insertSession(userAId, "phone");
        String dead = jwtUtils.generateTokenFromUsername(emailA, sessionId, null, null);
        String recoveryToken = recover(emailA);
        assertThat(useToken(dead)).isEqualTo(401);

        // All four recovery steps answer for themselves; none is stopped at the door by the dead token.
        assertThat(post("/api/auth/recovery/initiate", Map.of("email", emailA), dead).getStatus()).isEqualTo(200);
        MockHttpServletResponse wrong = post("/api/auth/recovery/verify",
                Map.of("email", emailA, "otp", wrongCode(codeSentTo(emailA), 0)), dead);
        assertThat(wrong.getStatus()).isEqualTo(401);
        assertThat(wrong.getContentAsString()).as("the controller's own answer, not the filter's").contains("\"success\":false");
        assertThat(startPasskey(recoveryToken, dead)).isEqualTo(200);
        Map<String, Object> finish = new HashMap<>();
        finish.put("email", emailA);
        finish.put("credential", Map.of("id", "forged", "response", Map.of("attestationObject", "forged")));
        finish.put("recoveryToken", recoveryToken);
        assertThat(post("/api/passkey/register/finish", finish, dead).getStatus()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    @DisplayName("the owner recovers: passkeys removed, a notice sent, and the recovery token opens passkey registration")
    void theOwnerRecovers() throws Exception {
        insertPasskey(userAId);
        insertPasskey(userAId);

        String recoveryToken = recover(emailA);

        assertThat(passkeyCount(userAId)).isZero();
        verify(emailService).sendEmail(any(EmailMessage.class), eq(userAId),
                eq(AccountRecoveryService.TYPE_DONE_NOTICE), any());

        MockHttpServletResponse start = post("/api/passkey/register/recovery/start",
                Map.of("recoveryToken", recoveryToken), null);
        assertThat(start.getStatus()).isEqualTo(200);
        JsonNode options = json.readTree(json.readTree(start.getContentAsString()).path("options").asText());
        assertThat(options.path("user").path("name").asText()).isEqualTo(emailA);
        assertThat(options.path("challenge").asText()).isNotBlank();
    }

    @Test
    @DisplayName("a failed passkey attempt can be tried again with the same recovery token, but only so many times")
    void aFailedPasskeyAttemptCanBeRetried() throws Exception {
        String recoveryToken = recover(emailA);

        for (int i = 0; i < AccountRecoveryService.MAX_PASSKEY_STARTS; i++) {
            assertThat(startPasskey(recoveryToken, null)).isEqualTo(200);
            // A forged passkey response: refused, and the challenge it was aimed at is gone.
            assertThat(finishPasskey(emailA, recoveryToken).getStatus()).isEqualTo(400);
            assertThat(finishPasskey(emailA, recoveryToken).getStatus()).isEqualTo(400);
        }
        assertThat(startPasskey(recoveryToken, null)).as("one start too many").isEqualTo(401);
        assertThat(passkeyCount(userAId)).isZero();
    }

    @Test
    @DisplayName("a newer recovery cancels the older recovery token")
    void aNewerRecoveryCancelsTheOlderToken() throws Exception {
        String first = recover(emailA);
        String second = recover(emailA);

        assertThat(startPasskey(first, null)).isEqualTo(401);
        assertThat(startPasskey(second, null)).isEqualTo(200);
    }

    // ------------------------------------------------------------------ two users, both directions

    @Test
    @DisplayName("recovering one account touches nothing on the other, in both directions")
    void recoveryStaysInsideItsOwnAccount() throws Exception {
        insertPasskey(userAId);
        insertPasskey(userBId);
        Long sessionA = insertSession(userAId, "a-phone");
        Long sessionB = insertSession(userBId, "b-phone");
        String tokenA = jwtUtils.generateTokenFromUsername(emailA, sessionA, null, null);
        String tokenB = jwtUtils.generateTokenFromUsername(emailB, sessionB, null, null);

        String recoveryA = recover(emailA);

        assertThat(passkeyCount(userAId)).isZero();
        assertThat(passkeyCount(userBId)).isEqualTo(1);
        assertThat(sessionActive(sessionB)).isTrue();
        assertThat(useToken(tokenA)).isEqualTo(401);
        assertThat(useToken(tokenB)).isEqualTo(200);

        // A's recovery token names A whatever email the request claims, so it cannot be aimed at B.
        MockHttpServletResponse start = post("/api/passkey/register/recovery/start",
                Map.of("recoveryToken", recoveryA, "email", emailB), null);
        JsonNode options = json.readTree(json.readTree(start.getContentAsString()).path("options").asText());
        assertThat(options.path("user").path("name").asText()).isEqualTo(emailA);
        assertThat(finishPasskey(emailB, recoveryA).getStatus()).isEqualTo(400);
        assertThat(passkeyCount(userBId)).isEqualTo(1);

        String recoveryB = recover(emailB);

        assertThat(passkeyCount(userBId)).isZero();
        assertThat(useToken(tokenB)).isEqualTo(401);
        assertThat(startPasskey(recoveryA, null)).as("A's recovery token still works for A").isEqualTo(200);
        assertThat(startPasskey(recoveryB, null)).isEqualTo(200);
    }

    @Test
    @DisplayName("A's code does not work for B's email")
    void aCodeOnlyWorksForItsOwnAccount() throws Exception {
        insertPasskey(userBId);
        askForCode(emailA);
        String codeForA = codeSentTo(emailA);

        assertThat(check(emailB, codeForA).getStatus()).isEqualTo(401);
        assertThat(passkeyCount(userBId)).isEqualTo(1);
        assertThat(check(emailA, codeForA).getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ refresh keeps the session

    @Test
    @DisplayName("a refreshed token keeps its session id, so signing the device out still reaches it")
    void refreshKeepsTheSession() throws Exception {
        Long sessionId = insertSession(userAId, "phone");
        String token = jwtUtils.generateTokenFromUsername(emailA, sessionId, null, null);

        MockHttpServletResponse refreshed = post("/api/auth/refresh", Map.of(), token);
        assertThat(refreshed.getStatus()).isEqualTo(200);
        String newToken = json.readTree(refreshed.getContentAsString()).path("jwtToken").asText();
        assertThat(jwtUtils.getSessionIdFromJwtToken(newToken)).isEqualTo(sessionId);

        jdbc.update("UPDATE user_sessions SET active = 0 WHERE id = ?", sessionId);
        assertThat(useToken(newToken)).isEqualTo(401);
    }
}
