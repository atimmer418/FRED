package com.investingapp.backend.web;

import com.investingapp.backend.model.User;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.AlpacaApiService;
import com.investingapp.backend.service.BeneficiaryService;
import com.investingapp.backend.service.EncryptionService;
import com.investingapp.backend.service.PlaidService;
import com.investingapp.backend.service.TlhSummaryService;
import com.investingapp.backend.service.UserService;
import com.investingapp.backend.support.RequiresCiDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * What the app is told when something goes wrong, through the real filter chain and controllers.
 *
 * <p>Each service is made to fail with text that stands in for what a real failure carries: the
 * broker's or the bank's raw answer, a web address with an account id, SQL. None of it may appear
 * in the reply. A message written for the user must still get through.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class ErrorTextStaysOnTheServerTest {

    /** Stands in for third-party or internal text. It must never be in a reply. */
    private static final String INTERNAL =
            "LEAKMARK 422 Unprocessable Entity: {\"code\":40010001} https://broker-api/v1/accounts/acct-9 SQL [insert into users]";

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private TlhSummaryService tlhSummaryService;
    @MockitoBean private PlaidService plaidService;
    @MockitoBean private AlpacaApiService alpacaApiService;
    @MockitoBean private EncryptionService encryptionService;
    @MockitoBean private BeneficiaryService beneficiaryService;
    @MockitoBean private UserService userService;

    private MockMvc mvc;
    private String email;
    private Long userId;

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        email = "errtext-" + UUID.randomUUID() + "@fredvested.test";
        jdbc.update("INSERT INTO users (email, create_date, public_id, analytics_opt_out, alpaca_account_id, "
                + "selected_tier) VALUES (?, ?, ?, 0, 'ENC:acct', 'pro')",
                email, LocalDateTime.now(), UUID.randomUUID().toString());
        userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        when(encryptionService.decrypt(anyString())).thenReturn("acct-9");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM user_progress WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
        UserDetailsImpl principal = new UserDetailsImpl(userId, email, List.of());
        return mvc.perform(request.with(authentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of())))).andReturn().getResponse();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static void assertClean(MockHttpServletResponse response, int status) throws Exception {
        String body = response.getContentAsString();
        assertThat(response.getStatus()).as(body).isEqualTo(status);
        assertThat(body).doesNotContain("LEAKMARK").doesNotContain("acct-9").doesNotContain("SQL")
                .doesNotContain("Exception").doesNotContain("java.");
        assertThat(body).as("a sentence for the user").isNotBlank();
    }

    // ------------------------------------------------------------------ failures: fixed sentence only

    @Test
    @DisplayName("a failed tax-loss-harvest status answers with a fixed sentence")
    void tlhStatus() throws Exception {
        when(tlhSummaryService.hasTlhHistory(any(User.class))).thenReturn(true);
        when(tlhSummaryService.getStatus(any(User.class), anyBoolean())).thenThrow(new IllegalStateException(INTERNAL));

        assertClean(call(get("/api/tlh/status")), 500);
    }

    @Test
    @DisplayName("a failed bank link never shows the bank service's own error")
    void plaidExchange() throws Exception {
        when(plaidService.exchangePublicTokenAndLinkUser(anyString(), any(User.class)))
                .thenThrow(new IOException("Plaid Public Token exchange failed: " + INTERNAL));

        MockHttpServletResponse response = call(json(post("/api/plaid/exchange_public_token"),
                "{\"public_token\":\"public-sandbox-1\"}"));

        assertClean(response, 500);
        // The Change Bank screen decides success or failure by these words in the message.
        assertThat(response.getContentAsString()).contains("Could not");
    }

    @Test
    @DisplayName("a refused identity update never shows the broker's own answer")
    void kycPatch() throws Exception {
        when(alpacaApiService.patchAccountKyc(anyString(), any())).thenThrow(new RuntimeException(INTERNAL));
        when(alpacaApiService.getAccountKyc(anyString())).thenThrow(new RuntimeException(INTERNAL));

        assertClean(call(json(patch("/api/alpaca/account/kyc"), "{\"givenName\":\"Sam\"}")), 500);
        assertClean(call(get("/api/alpaca/account/kyc")), 500);
    }

    @Test
    @DisplayName("an unexpected failure saving a beneficiary answers with a fixed sentence")
    void beneficiaryUnexpectedFailure() throws Exception {
        when(beneficiaryService.createBeneficiary(any(User.class), any())).thenThrow(new RuntimeException(INTERNAL));

        assertClean(call(json(post("/api/beneficiaries"), beneficiaryJson())), 500);
    }

    @Test
    @DisplayName("an unexpected failure applying a referral code shows nothing internal")
    void referralUnexpectedFailure() throws Exception {
        when(userService.applyReferralCode(anyLong(), anyString())).thenThrow(new RuntimeException(INTERNAL));

        MockHttpServletResponse response = call(json(post("/api/user/referral/apply"), "{\"code\":\"ABC123\"}"));

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).doesNotContain("LEAKMARK").doesNotContain("acct-9");
    }

    // ------------------------------------------------------------------ messages written for the user still arrive

    @Test
    @DisplayName("a beneficiary rule the user broke is still explained, word for word")
    void beneficiaryRuleIsStillExplained() throws Exception {
        // The app reads "Current total: N%" out of this exact sentence.
        String rule = "Total allocation cannot exceed 100% for primary beneficiaries. "
                + "Current total: 80.00%, attempting to add: 30.00%";
        when(beneficiaryService.createBeneficiary(any(User.class), any())).thenThrow(new UserFacingException(rule));

        MockHttpServletResponse response = call(json(post("/api/beneficiaries"), beneficiaryJson()));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains(rule);
    }

    @Test
    @DisplayName("a referral code refusal is still explained")
    void referralRefusalIsStillExplained() throws Exception {
        when(userService.applyReferralCode(anyLong(), anyString()))
                .thenThrow(new UserFacingException("Invalid referral code."));

        MockHttpServletResponse response = call(json(post("/api/user/referral/apply"), "{\"code\":\"NOPE\"}"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("Invalid referral code.");
    }

    // ------------------------------------------------------------------ bad input is a bad request, not a server error

    @Test
    @DisplayName("a value of the wrong type is a 400 with a sentence, not a 500")
    void wrongTypeIsABadRequest() throws Exception {
        MockHttpServletResponse progress = call(json(put("/api/user/progress"), "{\"getStartedCompleted\":\"yes\"}"));
        assertThat(progress.getStatus()).as(progress.getContentAsString()).isEqualTo(400);
        assertThat(progress.getContentAsString()).contains("getStartedCompleted").doesNotContain("ClassCast");

        MockHttpServletResponse nullFlag = call(json(put("/api/user/progress"), "{\"authFinalizeCompleted\":null}"));
        assertThat(nullFlag.getStatus()).isEqualTo(400);

        MockHttpServletResponse execute = call(json(post("/api/investments/execute"),
                "{\"amount\":50,\"type\":5}"));
        assertThat(execute.getStatus()).as(execute.getContentAsString()).isEqualTo(400);

        MockHttpServletResponse schedule = call(json(post("/api/investments/schedule"), "{\"payFrequency\":12}"));
        assertThat(schedule.getStatus()).as(schedule.getContentAsString()).isEqualTo(400);
    }

    @Test
    @DisplayName("answers Spring already gets right are left exactly as they were")
    void springsOwnAnswersAreUntouched() throws Exception {
        // A wrongly typed id or number in the address: 400, never the last-resort 500.
        assertThat(call(delete("/api/goals/abc")).getStatus()).isEqualTo(400);
        assertThat(call(get("/api/chat/history").param("userId", "abc")).getStatus()).isEqualTo(400);
        assertThat(call(get("/api/tlh/events").param("limit", "ten")).getStatus()).isEqualTo(400);
        // A missing required parameter: 400.
        assertThat(call(get("/api/chat/stream")).getStatus()).isEqualTo(400);
        // A status a route chose itself.
        assertThat(call(json(post("/api/chat"), "{\"message\":\"hi\",\"sessionId\":\"" + "s".repeat(101) + "\"}"))
                .getStatus()).isEqualTo(400);
        assertThat(call(json(post("/api/chat"), "{\"message\":\"" + "m".repeat(4001) + "\"}")).getStatus())
                .isEqualTo(413);
        // An unknown route and an unsupported method.
        assertThat(call(get("/api/no-such-route")).getStatus()).isEqualTo(404);
        assertThat(call(delete("/api/chat/history")).getStatus()).isEqualTo(405);
    }

    @Test
    @DisplayName("JSON that cannot be read is a 400 with a sentence that quotes nothing back")
    void unreadableJson() throws Exception {
        MockHttpServletResponse response = call(json(put("/api/user/progress"), "{\"getStartedCompleted\": LEAKMARK"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("message").doesNotContain("LEAKMARK")
                .doesNotContain("JsonParseException");
    }

    // ------------------------------------------------------------------ the size limit, through the real chain

    @Test
    @DisplayName("a body over the limit is refused with 413 on an ordinary route")
    void oversizeBodyIsRefused() throws Exception {
        String big = "{\"firstName\":\"" + "x".repeat(300_000) + "\"}";

        MockHttpServletResponse response = call(json(put("/api/user/profile"), big));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("message");
    }

    @Test
    @DisplayName("the document upload may be larger than that, up to its own limit")
    void uploadHasRoom() throws Exception {
        jdbc.update("UPDATE users SET account_status = 'ACTION_REQUIRED' WHERE id = ?", userId);
        // 400 KB of valid JPEG-looking content: over the ordinary limit, under the upload limit.
        String content = "/9j/4AAQ" + "A".repeat(400_000);
        when(alpacaApiService.uploadDocument(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new java.util.HashMap<>(java.util.Map.of("success", true)));

        MockHttpServletResponse ok = call(json(post("/api/alpaca/upload-document"),
                "{\"documentType\":\"identity_verification\",\"mimeType\":\"image/jpeg\",\"content\":\"" + content + "\"}"));
        assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(200);
        assertThat(ok.getContentAsString()).doesNotContain("raw_response");

        String tooBig = "/9j/4AAQ" + "A".repeat(10_600_000);
        MockHttpServletResponse refused = call(json(post("/api/alpaca/upload-document"),
                "{\"documentType\":\"identity_verification\",\"mimeType\":\"image/jpeg\",\"content\":\"" + tooBig + "\"}"));
        assertThat(refused.getStatus()).isEqualTo(413);
    }

    private static String beneficiaryJson() {
        return "{\"firstName\":\"Pat\",\"lastName\":\"Lee\",\"relationship\":\"SPOUSE\",\"beneficiaryType\":\"PRIMARY\","
                + "\"percentageAllocation\":50,\"dateOfBirth\":\"1990-01-01\",\"socialSecurityNumber\":\"123-45-6789\"}";
    }
}
