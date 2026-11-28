package com.investingapp.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.UserRepository;
import com.investingapp.backend.security.DevToolsKeyValidator;
import com.investingapp.backend.service.AccountRecoveryService;
import com.investingapp.backend.service.DeletionDeferredException;
import com.investingapp.backend.service.UserDeletionService;
import com.investingapp.backend.service.UserSessionService;
import com.investingapp.backend.support.RequiresCiDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * A closed account must not be able to get back into the app, and a device that still holds its
 * token must be TOLD the account is closed, so it can show a clear screen.
 *
 * Before this, two things were wrong:
 *
 *   1. The answer to a closed account's token was the same plain 401 as an expired session. A second
 *      device could not tell the two apart, asked for the passkey, and was refused for ever.
 *   2. When the brokerage account closed but the FRED row could not be deleted (a transfer landed
 *      in between, or the delete failed), nothing was marked. The user stayed signed in, could
 *      sign in again, and could recover the account, with no brokerage account behind it.
 *
 * Everything runs through the real security filter chain, real controllers and real SQL. Only the
 * broker and the cipher are mocked.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class ClosedAccountTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private UserSessionService userSessionService;
    @Autowired private AccountRecoveryService accountRecoveryService;
    @Autowired private com.investingapp.backend.repository.AnalyticsOutboxRepository outboxRepository;
    @Autowired private com.investingapp.backend.service.PaydayNotificationService paydayNotificationService;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Value("${app.dev-tools.key}") private String devToolsKey;

    /** The broker call is mocked so the test never leaves the machine. */
    @MockitoBean private com.investingapp.backend.service.AlpacaService alpacaService;
    /** The stored account id is a fabricated 'ENC:acct'; mocked so the cipher is not under test. */
    @MockitoBean private com.investingapp.backend.service.EncryptionService encryptionService;
    @MockitoBean private com.investingapp.backend.service.EmailService emailService;
    @MockitoSpyBean private UserDeletionService userDeletionService;

    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    private String email;
    private Long userId;

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        org.mockito.Mockito.when(encryptionService.decrypt(anyString())).thenReturn("acct-1");
        email = "closed-" + UUID.randomUUID() + "@fredvested.test";
        jdbc.update("INSERT INTO users (email, create_date, public_id, analytics_opt_out, user_handle, "
                + "alpaca_account_id, account_status) VALUES (?, ?, ?, 0, ?, 'ENC:acct', 'ACTIVE')",
                email, LocalDateTime.now(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", "").substring(0, 22));
        userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        jdbc.update("INSERT INTO passkey_credentials (user_id, external_id, public_key_cose, signature_count) "
                + "VALUES (?, ?, 'cose', 0)", userId, "cred-" + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM analytics_outbox WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM investment_schedules WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ops_alerts WHERE subject_id = ?", String.valueOf(userId));
        jdbc.update("DELETE FROM passkey_credentials WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM user_sessions WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM user_progress WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request, String bearer) throws Exception {
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse useToken(String bearer) throws Exception {
        return call(MockMvcRequestBuilders.get("/api/user/sessions"), bearer);
    }

    private MockHttpServletResponse refresh(String bearer) throws Exception {
        return call(MockMvcRequestBuilders.post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), bearer);
    }

    private MockHttpServletResponse close(String bearer) throws Exception {
        return call(MockMvcRequestBuilders.post("/api/trading/account/close")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), bearer);
    }

    private String code(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString();
        if (body.isBlank()) {
            return "";
        }
        return json.readTree(body).path("code").asText("");
    }

    /** A real sign-in through the simulator endpoint: a real session and a real token. */
    private String signIn() throws Exception {
        MockHttpServletResponse response = call(MockMvcRequestBuilders.post("/api/dev/authenticate-as-user")
                .contentType(MediaType.APPLICATION_JSON)
                .header(DevToolsKeyValidator.HEADER, devToolsKey)
                .content(json.writeValueAsString(Map.of("email", email))), null);
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.path("success").asBoolean()).as("sign-in").isTrue();
        return body.path("jwtToken").asText();
    }

    private String status() {
        return jdbc.queryForObject("SELECT account_status FROM users WHERE id = ?", String.class, userId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ------------------------------------------------------------------ a clean close

    @Test
    @DisplayName("after a clean close, the old token is refused and the answer says the account is closed")
    void tokenOfADeletedAccountIsToldItIsClosed() throws Exception {
        String token = signIn();
        String secondDevice = signIn();
        assertThat(useToken(token).getStatus()).isEqualTo(200);

        MockHttpServletResponse closed = close(token);
        assertThat(closed.getStatus()).isEqualTo(200);
        assertThat(userRepository.findById(userId)).isEmpty();

        for (String old : List.of(token, secondDevice)) {
            MockHttpServletResponse protectedCall = useToken(old);
            assertThat(protectedCall.getStatus()).isEqualTo(401);
            assertThat(code(protectedCall)).isEqualTo("ACCOUNT_CLOSED");

            MockHttpServletResponse refreshed = refresh(old);
            assertThat(refreshed.getStatus()).isEqualTo(401);
            assertThat(code(refreshed)).isEqualTo("ACCOUNT_CLOSED");
        }
    }

    @Test
    @DisplayName("an ordinary dead session is NOT called a closed account")
    void anEndedSessionIsNotCalledClosed() throws Exception {
        String token = signIn();
        jdbc.update("UPDATE user_sessions SET active = 0 WHERE user_id = ?", userId);

        MockHttpServletResponse protectedCall = useToken(token);
        assertThat(protectedCall.getStatus()).isEqualTo(401);
        assertThat(code(protectedCall)).isEmpty();
        MockHttpServletResponse refreshed = refresh(token);
        assertThat(refreshed.getStatus()).isEqualTo(401);
        assertThat(code(refreshed)).isEmpty();

        // No token, and a token nobody signed, are not closed accounts either.
        assertThat(code(useToken(null))).isEmpty();
        assertThat(code(useToken("not.a.token"))).isEmpty();
    }

    // ------------------------------------------------------------------ brokerage closed, row kept

    @Test
    @DisplayName("brokerage closed but the row cannot be deleted yet: the account is still shut")
    void aDeferredDeletionStillShutsTheAccount() throws Exception {
        String token = signIn();
        String secondDevice = signIn();
        // The transfer lands between the pre-check and the delete: the pre-check passes, the broker
        // closes the account, and only then does the deletion refuse.
        doThrow(new DeletionDeferredException("SETTLEMENT_WINDOW"))
                .when(userDeletionService).delete(any(User.class), any(), any());

        MockHttpServletResponse closed = close(token);

        // To the user the account IS closed, so the app is told so.
        assertThat(closed.getStatus()).isEqualTo(200);
        assertThat(json.readTree(closed.getContentAsString()).path("success").asBoolean()).isTrue();
        verify(alpacaService).closeAccount("acct-1");

        assertThat(status()).isEqualTo("CLOSED");
        assertThat(count("user_sessions")).as("sessions").isZero();
        assertThat(count("passkey_credentials")).as("passkeys").isZero();

        for (String old : List.of(token, secondDevice)) {
            MockHttpServletResponse protectedCall = useToken(old);
            assertThat(protectedCall.getStatus()).isEqualTo(401);
            assertThat(code(protectedCall)).isEqualTo("ACCOUNT_CLOSED");
            MockHttpServletResponse refreshed = refresh(old);
            assertThat(refreshed.getStatus()).isEqualTo(401);
            assertThat(code(refreshed)).isEqualTo("ACCOUNT_CLOSED");
        }
    }

    private void seedSchedule(java.time.LocalDate nextDate) {
        jdbc.update("INSERT INTO investment_schedules (user_id, created_at, frequency, "
                + "investment_amount, monthly_amount, monthly_streak, next_investment_date, "
                + "start_date, status) VALUES (?, NOW(), 'BIWEEKLY', 100.00, 200.00, 0, ?, ?, 'ACTIVE')",
                userId, nextDate, nextDate);
    }

    @Test
    @DisplayName("a close that keeps the row still records the schedule as cancelled, once")
    void aDeferredCloseRecordsTheCancellation() throws Exception {
        seedSchedule(java.time.LocalDate.now().plusDays(5));
        String token = signIn();
        doThrow(new DeletionDeferredException("SETTLEMENT_WINDOW"))
                .when(userDeletionService).delete(any(User.class), any(), any());

        assertThat(close(token).getStatus()).isEqualTo(200);

        assertThat(outboxRepository.countByUserIdAndEventName(userId,
                com.investingapp.backend.util.ServerAnalyticsEvent.SCHEDULE_CANCELLED)).isEqualTo(1);
    }

    @Test
    @DisplayName("a closed account is never told that FRED will invest for it tomorrow")
    void aClosedAccountGetsNoPaydayNotice() {
        java.time.LocalDate today = java.time.LocalDate.of(2031, 3, 3);
        seedSchedule(today.plusDays(1));
        // The scheduler runs this job inside a transaction; so does the test.
        Runnable dayBefore = () -> new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(tx -> paydayNotificationService.sendDayBeforeNotifications(today));

        dayBefore.run();
        verify(emailService).sendEmail(org.mockito.ArgumentMatchers.eq(email), anyString(), anyString());

        org.mockito.Mockito.clearInvocations(emailService);
        jdbc.update("UPDATE users SET account_status = 'CLOSED' WHERE id = ?", userId);
        dayBefore.run();
        verify(emailService, never()).sendEmail(org.mockito.ArgumentMatchers.eq(email), anyString(), anyString());
    }

    @Test
    @DisplayName("a session that somehow outlives the closing is not honoured")
    void aSessionThatSurvivedTheClosingIsRefused() throws Exception {
        // What a sign-in that overlapped the closing would leave behind: a live session row for
        // an account that is now closed.
        String token = signIn();
        jdbc.update("UPDATE users SET account_status = 'CLOSED' WHERE id = ?", userId);
        assertThat(count("user_sessions")).isEqualTo(1);

        MockHttpServletResponse protectedCall = useToken(token);
        assertThat(protectedCall.getStatus()).isEqualTo(401);
        assertThat(code(protectedCall)).isEqualTo("ACCOUNT_CLOSED");
        MockHttpServletResponse refreshed = refresh(token);
        assertThat(refreshed.getStatus()).isEqualTo(401);
        assertThat(code(refreshed)).isEqualTo("ACCOUNT_CLOSED");
    }

    @Test
    @DisplayName("brokerage closed and the delete then fails: the account is still shut")
    void aFailedDeletionAfterTheBrokerClosedStillShutsTheAccount() throws Exception {
        String token = signIn();
        doThrow(new IllegalStateException("database went away"))
                .when(userDeletionService).delete(any(User.class), any(), any());

        MockHttpServletResponse closed = close(token);

        assertThat(closed.getStatus()).isEqualTo(200);
        assertThat(status()).isEqualTo("CLOSED");
        assertThat(count("user_sessions")).isZero();
        assertThat(code(useToken(token))).isEqualTo("ACCOUNT_CLOSED");
    }

    @Test
    @DisplayName("the broker refusing to close leaves the account open and signed in")
    void aRefusedBrokerCloseChangesNothing() throws Exception {
        String token = signIn();
        doThrow(new RuntimeException("broker said no")).when(alpacaService).closeAccount(anyString());

        MockHttpServletResponse closed = close(token);

        assertThat(closed.getStatus()).isEqualTo(500);
        assertThat(status()).isEqualTo("ACTIVE");
        assertThat(count("passkey_credentials")).isEqualTo(1);
        assertThat(useToken(token).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a closed account cannot start a session, by any sign-in")
    void aClosedAccountCannotSignIn() throws Exception {
        jdbc.update("UPDATE users SET account_status = 'CLOSED' WHERE id = ?", userId);
        User user = userRepository.findById(userId).orElseThrow();

        assertThatThrownBy(() -> userSessionService.start(user)).isInstanceOf(IllegalStateException.class);

        MockHttpServletResponse response = call(MockMvcRequestBuilders.post("/api/dev/authenticate-as-user")
                .contentType(MediaType.APPLICATION_JSON)
                .header(DevToolsKeyValidator.HEADER, devToolsKey)
                .content(json.writeValueAsString(Map.of("email", email))), null);
        assertThat(json.readTree(response.getContentAsString()).path("jwtToken").asText("")).isEmpty();
        assertThat(count("user_sessions")).isZero();
    }

    @Test
    @DisplayName("a closed account gets no recovery code")
    void aClosedAccountCannotBeRecovered() {
        jdbc.update("UPDATE users SET account_status = 'CLOSED' WHERE id = ?", userId);

        AccountRecoveryService.SendStatus status = accountRecoveryService.sendCode(email, "203.0.113.77");

        // The same flat answer as an unknown address, and no email.
        assertThat(status).isEqualTo(AccountRecoveryService.SendStatus.SENT);
        verify(emailService, never()).sendEmail(any(), any(), any(), any());
    }

    @Test
    @DisplayName("the status poller leaves a closed account alone")
    void thePollerSkipsClosedAccounts() {
        jdbc.update("UPDATE users SET account_status = 'CLOSED' WHERE id = ?", userId);

        assertThat(userRepository.findUsersWithPendingAccountStatus())
                .extracting(User::getId).doesNotContain(userId);
    }

    // ------------------------------------------------------------------ a passkey the server no longer has

    @Test
    @DisplayName("signing in with a passkey the server no longer has says so")
    void anUnknownPasskeyIsNamed() throws Exception {
        MockHttpServletResponse start = call(MockMvcRequestBuilders.post("/api/passkey/authenticate/start")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), null);
        assertThat(start.getStatus()).isEqualTo(200);
        String sessionId = json.readTree(start.getContentAsString()).path("sessionId").asText();

        String credentialId = b64(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        String clientData = b64("{\"type\":\"webauthn.get\",\"challenge\":\"AAAA\",\"origin\":\"https://x.test\"}"
                .getBytes(StandardCharsets.UTF_8));
        Map<String, Object> credential = Map.of(
                "id", credentialId,
                "rawId", credentialId,
                "type", "public-key",
                "clientExtensionResults", Map.of(),
                "response", Map.of(
                        "authenticatorData", b64(new byte[37]),
                        "clientDataJSON", clientData,
                        "signature", b64(new byte[64]),
                        "userHandle", b64("gone-user".getBytes(StandardCharsets.UTF_8))));

        MockHttpServletResponse finish = call(MockMvcRequestBuilders.post("/api/passkey/authenticate/finish")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("sessionId", sessionId, "credential", credential))), null);

        assertThat(finish.getStatus()).isEqualTo(401);
        assertThat(code(finish)).isEqualTo("PASSKEY_NOT_REGISTERED");
    }
}
