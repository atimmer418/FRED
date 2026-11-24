package com.investingapp.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.dto.EmailMessage;
import com.investingapp.backend.security.jwt.JwtUtils;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.EmailChangeService;
import com.investingapp.backend.service.EmailService;
import com.investingapp.backend.service.PasskeyReauthVerifier;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The account takeover this closes: with nothing but a session token, a caller could point an
 * account's email (its login and its recovery channel) at an inbox of their own.
 *
 * Two users are seeded in a real database. Every attack is run through the real request mapping,
 * the real service and real SQL, and after each one both users' emails are read back from the
 * database. Only two things are faked: the passkey check (so the test can say "this caller did or
 * did not prove it is them") and the outbound email (so the test can read the code that was sent).
 * The real passkey check has its own test, WebAuthnPasskeyReauthVerifierTest.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class EmailChangeTakeoverTest {

    private static final String PROOF_SESSION = "challenge-1";
    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    @Autowired
    private EmailChangeController controller;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private EmailChangeService emailChangeService;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    private PasskeyReauthVerifier passkeyReauthVerifier;

    @MockitoBean
    private EmailService emailService;

    private final ObjectMapper json = new ObjectMapper();

    private String emailA;
    private String emailB;
    private String newEmail;
    private Long userAId;
    private Long userBId;

    @BeforeEach
    void seed() {
        emailA = "chg-a-" + UUID.randomUUID() + "@fredvested.test";
        emailB = "chg-b-" + UUID.randomUUID() + "@fredvested.test";
        newEmail = "chg-new-" + UUID.randomUUID() + "@fredvested.test";
        userAId = insertUser(emailA);
        userBId = insertUser(emailB);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM user_sessions WHERE user_id IN (?, ?)", userAId, userBId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", userAId, userBId);
    }

    // ------------------------------------------------------------------ helpers

    private Long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, create_date, public_id, analytics_opt_out) "
                + "VALUES (?, ?, ?, 0)", email, LocalDateTime.now(), UUID.randomUUID().toString());
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
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

    private String emailOf(Long userId) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    /** 1 while the user has a change waiting for its code, otherwise 0. */
    private long pendingRequestsFor(Long userId) {
        return emailChangeService.hasPendingChange(userId) ? 1 : 0;
    }

    private void signInAs(Long userId, String email) {
        UserDetailsImpl principal = new UserDetailsImpl(userId, email, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    /** The passkey check will say this assertion belongs to the given user (or to nobody). */
    private void passkeyProves(Long userId) {
        when(passkeyReauthVerifier.verifiedUserId(eq(PROOF_SESSION), any(JsonNode.class)))
                .thenReturn(Optional.ofNullable(userId));
    }

    private MockHttpServletResponse post(String path, Map<String, Object> body, String bearerToken) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post("/api/user/email-change" + path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
        if (bearerToken != null) {
            request = request.header("Authorization", "Bearer " + bearerToken);
        }
        return MockMvcBuilders.standaloneSetup(controller).build().perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse start(String wantedEmail) throws Exception {
        return post("/start", Map.of(
                "newEmail", wantedEmail,
                "passkeySessionId", PROOF_SESSION,
                "credential", Map.of("id", "fake-assertion")), null);
    }

    private MockHttpServletResponse confirm(String code) throws Exception {
        return post("/confirm", Map.of("code", code), null);
    }

    /** The six-digit code from the message that was sent to the given address. */
    private String codeSentTo(String address) {
        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService, atLeastOnce()).sendEmail(sent.capture(), any(), eq(EmailChangeService.TYPE_CODE), any());
        EmailMessage last = sent.getAllValues().get(sent.getAllValues().size() - 1);
        assertThat(last.getTo()).isEqualTo(address);
        Matcher matcher = CODE.matcher(last.getTextBody());
        assertThat(matcher.find()).as("a six-digit code in the message").isTrue();
        return matcher.group(1);
    }

    private String errorCode(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString()).path("code").asText();
    }

    private void assertNothingChanged() {
        assertThat(emailOf(userAId)).isEqualTo(emailA);
        assertThat(emailOf(userBId)).isEqualTo(emailB);
    }

    // ------------------------------------------------------------------ the attack

    @Test
    @DisplayName("a session token with no passkey proof cannot start a change, and no email goes out")
    void tokenAloneIsNotEnough() throws Exception {
        signInAs(userAId, emailA);
        passkeyProves(null);

        MockHttpServletResponse response = start(newEmail);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(errorCode(response)).isEqualTo("REAUTH_REQUIRED");
        assertNothingChanged();
        assertThat(pendingRequestsFor(userAId)).isZero();
        verify(emailService, never()).sendEmail(any(EmailMessage.class), any(), anyString(), any());
    }

    @Test
    @DisplayName("a request with no passkey fields at all is refused the same way")
    void missingProofFieldsAreRefused() throws Exception {
        signInAs(userAId, emailA);

        MockHttpServletResponse response = post("/start", Map.of("newEmail", newEmail), null);

        assertThat(response.getStatus()).isEqualTo(403);
        assertNothingChanged();
        assertThat(pendingRequestsFor(userAId)).isZero();
    }

    @Test
    @DisplayName("the old one-step endpoint is gone")
    void oldEndpointNoLongerExists() {
        boolean stillMapped = handlerMapping.getHandlerMethods().keySet().stream()
                .anyMatch(info -> info.toString().contains("/api/user/update-email"));
        assertThat(stillMapped).isFalse();
    }

    @Test
    @DisplayName("someone else's passkey proof does not unlock this account, in either direction")
    void anotherUsersPasskeyDoesNotCount() throws Exception {
        signInAs(userAId, emailA);
        passkeyProves(userBId);
        assertThat(start(newEmail).getStatus()).isEqualTo(403);

        signInAs(userBId, emailB);
        passkeyProves(userAId);
        assertThat(start(newEmail).getStatus()).isEqualTo(403);

        assertNothingChanged();
        assertThat(pendingRequestsFor(userAId)).isZero();
        assertThat(pendingRequestsFor(userBId)).isZero();
    }

    @Test
    @DisplayName("a user id or current email in the request cannot aim the change at another account")
    void requestCannotNameAnotherUser() throws Exception {
        signInAs(userAId, emailA);
        passkeyProves(userAId);

        MockHttpServletResponse response = post("/start", Map.of(
                "newEmail", newEmail,
                "passkeySessionId", PROOF_SESSION,
                "credential", Map.of("id", "fake-assertion"),
                "userId", userBId,
                "id", userBId,
                "currentEmail", emailB,
                "email", emailB), null);

        assertThat(response.getStatus()).isEqualTo(200);
        // The pending change is A's own. B has nothing pending and was not written to.
        assertThat(pendingRequestsFor(userAId)).isEqualTo(1);
        assertThat(pendingRequestsFor(userBId)).isZero();
        assertNothingChanged();
    }

    @Test
    @DisplayName("starting a change does not change the email; only the right code does")
    void nothingChangesUntilTheCodeIsConfirmed() throws Exception {
        signInAs(userAId, emailA);
        passkeyProves(userAId);

        assertThat(start(newEmail).getStatus()).isEqualTo(200);
        assertThat(emailOf(userAId)).isEqualTo(emailA);

        MockHttpServletResponse wrong = confirm("000000".equals(codeSentTo(newEmail)) ? "111111" : "000000");
        assertThat(wrong.getStatus()).isEqualTo(400);
        assertThat(errorCode(wrong)).isEqualTo("INVALID_CODE");
        assertThat(emailOf(userAId)).isEqualTo(emailA);
    }

    @Test
    @DisplayName("A cannot confirm B's pending change, even with B's real code, and B can still finish it")
    void cannotConfirmSomeoneElsesChange() throws Exception {
        signInAs(userBId, emailB);
        passkeyProves(userBId);
        assertThat(start(newEmail).getStatus()).isEqualTo(200);
        String bCode = codeSentTo(newEmail);

        signInAs(userAId, emailA);
        MockHttpServletResponse stolen = post("/confirm", Map.of("code", bCode, "userId", userBId), null);

        assertThat(stolen.getStatus()).isEqualTo(400);
        assertThat(errorCode(stolen)).isEqualTo("NO_PENDING_CHANGE");
        assertNothingChanged();
        assertThat(pendingRequestsFor(userBId)).isEqualTo(1);

        // And the reverse view: B's own confirm still works after A's attempt.
        signInAs(userBId, emailB);
        assertThat(confirm(bCode).getStatus()).isEqualTo(200);
        assertThat(emailOf(userBId)).isEqualTo(newEmail);
        assertThat(emailOf(userAId)).isEqualTo(emailA);
    }

    @Test
    @DisplayName("five wrong codes cancel the change, so the code cannot be guessed")
    void wrongCodesRunOut() throws Exception {
        signInAs(userAId, emailA);
        passkeyProves(userAId);
        assertThat(start(newEmail).getStatus()).isEqualTo(200);
        String realCode = codeSentTo(newEmail);
        String wrongCode = "000000".equals(realCode) ? "111111" : "000000";

        for (int attempt = 1; attempt < EmailChangeService.MAX_WRONG_CODES; attempt++) {
            assertThat(errorCode(confirm(wrongCode))).isEqualTo("INVALID_CODE");
        }
        MockHttpServletResponse last = confirm(wrongCode);
        assertThat(last.getStatus()).isEqualTo(429);
        assertThat(errorCode(last)).isEqualTo("TOO_MANY_ATTEMPTS");

        // The real code is useless now.
        assertThat(errorCode(confirm(realCode))).isEqualTo("NO_PENDING_CHANGE");
        assertThat(emailOf(userAId)).isEqualTo(emailA);
    }

    @Test
    @DisplayName("an address that belongs to another user is refused, at the start and at the finish")
    void cannotTakeAnotherUsersAddress() throws Exception {
        signInAs(userAId, emailA);
        passkeyProves(userAId);

        MockHttpServletResponse taken = start(emailB);
        assertThat(taken.getStatus()).isEqualTo(409);
        assertThat(errorCode(taken)).isEqualTo("EMAIL_IN_USE");

        // The address was free at the start, then B took it before A confirmed.
        assertThat(start(newEmail).getStatus()).isEqualTo(200);
        String code = codeSentTo(newEmail);
        jdbc.update("UPDATE users SET email = ? WHERE id = ?", newEmail, userBId);

        MockHttpServletResponse raced = confirm(code);
        assertThat(raced.getStatus()).isEqualTo(409);
        assertThat(emailOf(userAId)).isEqualTo(emailA);
        assertThat(emailOf(userBId)).isEqualTo(newEmail);
    }

    @Test
    @DisplayName("nobody signed in gets 401 from both steps")
    void signedOutIsRefused() throws Exception {
        SecurityContextHolder.clearContext();
        passkeyProves(userAId);

        assertThat(start(newEmail).getStatus()).isEqualTo(401);
        assertThat(confirm("123456").getStatus()).isEqualTo(401);
        assertNothingChanged();
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    @DisplayName("the real owner: passkey, code to the new address, notices to the old one, then the change")
    void ownerCanChangeTheirEmail() throws Exception {
        signInAs(userAId, emailA);
        passkeyProves(userAId);

        assertThat(start(newEmail).getStatus()).isEqualTo(200);
        String code = codeSentTo(newEmail);

        // The old address was told a change was asked for, without the code in it.
        ArgumentCaptor<EmailMessage> requested = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService).sendEmail(requested.capture(), eq(userAId),
                eq(EmailChangeService.TYPE_REQUESTED_NOTICE), any());
        assertThat(requested.getValue().getTo()).isEqualTo(emailA);
        assertThat(requested.getValue().getTextBody()).doesNotContain(code).doesNotContain(newEmail);

        // A is signed in on two devices. The change is confirmed from the first.
        Long thisDevice = insertSession(userAId, "this-device");
        Long otherDevice = insertSession(userAId, "other-device");
        Long bDevice = insertSession(userBId, "b-device");
        String currentToken = jwtUtils.generateTokenFromUsername(emailA, thisDevice, "tabs", "pid-a");
        MockHttpServletResponse response = post("/confirm", Map.of("code", code), currentToken);

        // This device stays signed in, A's other device is signed out, B is untouched.
        assertThat(sessionActive(thisDevice)).isTrue();
        assertThat(sessionActive(otherDevice)).isFalse();
        assertThat(sessionActive(bDevice)).isTrue();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(emailOf(userAId)).isEqualTo(newEmail);
        assertThat(emailOf(userBId)).isEqualTo(emailB);
        assertThat(pendingRequestsFor(userAId)).isZero();

        // The new token names the new email and stays on the same session.
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.path("newEmail").asText()).isEqualTo(newEmail);
        String newToken = body.path("jwtToken").asText();
        assertThat(jwtUtils.validateJwtToken(newToken)).isTrue();
        assertThat(jwtUtils.getUserNameFromJwtToken(newToken)).isEqualTo(newEmail);
        assertThat(jwtUtils.getSessionIdFromJwtToken(newToken)).isEqualTo(thisDevice);

        // The old address was told the change happened.
        ArgumentCaptor<EmailMessage> done = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService).sendEmail(done.capture(), eq(userAId), eq(EmailChangeService.TYPE_DONE_NOTICE), any());
        assertThat(done.getValue().getTo()).isEqualTo(emailA);

        // The code cannot be used twice.
        assertThat(errorCode(confirm(code))).isEqualTo("NO_PENDING_CHANGE");
    }

    @Test
    @DisplayName("asking again replaces the earlier request, and the earlier code stops working")
    void secondStartReplacesTheFirst() throws Exception {
        signInAs(userAId, emailA);
        passkeyProves(userAId);
        String otherNewEmail = "chg-other-" + UUID.randomUUID() + "@fredvested.test";

        assertThat(start(newEmail).getStatus()).isEqualTo(200);
        String firstCode = codeSentTo(newEmail);
        assertThat(start(otherNewEmail).getStatus()).isEqualTo(200);
        String secondCode = codeSentTo(otherNewEmail);
        assertThat(pendingRequestsFor(userAId)).isEqualTo(1);

        if (!firstCode.equals(secondCode)) {
            assertThat(errorCode(confirm(firstCode))).isEqualTo("INVALID_CODE");
        }
        assertThat(confirm(secondCode).getStatus()).isEqualTo(200);
        assertThat(emailOf(userAId)).isEqualTo(otherNewEmail);
    }
}
