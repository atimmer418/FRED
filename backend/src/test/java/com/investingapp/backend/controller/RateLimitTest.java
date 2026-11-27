package com.investingapp.backend.controller;

import com.investingapp.backend.dto.ChatResponse;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.ChatService;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Every door that can be knocked on without signing in, and the one door that costs money per
 * knock (chat), stops answering after a set number of tries.
 *
 * The limits are set small here so the test is quick. The numbers the app ships with are in
 * application.properties under app.rate-limit.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
@TestPropertySource(properties = {
        "app.rate-limit.sign-up.per-address=4",
        "app.rate-limit.sign-up.per-email-per-hour=3",
        "app.rate-limit.sign-in.per-address=5",
        "app.rate-limit.login.per-address=3",
        "app.rate-limit.login.per-email-per-hour=2",
        "app.rate-limit.refresh.per-address=6",
        "app.rate-limit.reauth-check.per-address=4",
        "app.rate-limit.chat.messages-per-minute=3",
        "app.rate-limit.chat.messages-per-day=5",
        "app.rate-limit.chat.reads-per-minute=4",
})
class RateLimitTest {

    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;

    /** Chat is mocked: the test is about who gets through, not about what the model says. */
    @MockitoBean private ChatService chatService;

    /** Passkey work is mocked so sign-up tries leave no half-made accounts behind. */
    @MockitoBean private com.investingapp.backend.service.WebAuthnService webAuthnService;

    private MockMvc mvc;
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(chatService.processChat(any())).thenReturn(new ChatResponse("hello", null));
        when(chatService.streamChat(any())).thenAnswer(call -> {
            SseEmitter emitter = new SseEmitter();
            emitter.complete();
            return emitter;
        });
    }

    @AfterEach
    void tearDown() {
        for (Long id : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A network address no other test uses, so counts never mix. */
    private static String freshAddress() {
        int n = NEXT_ADDRESS.getAndIncrement();
        return "198.51." + (n / 250) + "." + (n % 250 + 1);
    }

    private int post(String path, String address, String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path).header("CF-Connecting-IP", address)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn().getResponse().getStatus();
    }

    private int get(String path, String address) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).header("CF-Connecting-IP", address))
                .andReturn().getResponse().getStatus();
    }

    private String newUser() {
        String email = "rl-" + UUID.randomUUID() + "@fredvested.test";
        jdbc.update("INSERT INTO users (email, create_date, public_id, analytics_opt_out) VALUES (?, ?, ?, 0)",
                email, LocalDateTime.now(), UUID.randomUUID().toString());
        userIds.add(jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email));
        return email;
    }

    private MockHttpServletResponse as(String email, MockHttpServletRequestBuilder request) throws Exception {
        // The principal the token filter builds for a real request: chat reads the user id from it.
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        UserDetailsImpl principal = new UserDetailsImpl(id, email, List.of());
        return mvc.perform(request.with(authentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of())))).andReturn().getResponse();
    }

    /** A complete sign-up request, so it gets past validation and reaches the per-email count. */
    private static String signUpBody(String email) {
        return "{\"email\":\"" + email + "\",\"planId\":\"p\",\"timeToFI\":\"10\","
                + "\"targetPortfolio\":1000000,\"retirementIncome\":40000,\"monthlyInvestment\":500}";
    }

    // ------------------------------------------------------------------ public doors, per address

    @Test
    @DisplayName("passkey sign-up: one address gets 4 tries, then 429; another address is not affected")
    void signUpPerAddress() throws Exception {
        String address = freshAddress();
        for (int i = 0; i < 4; i++) {
            assertThat(post("/api/passkey/register/start", address, signUpBody(UUID.randomUUID() + "@fredvested.test")))
                    .as("try %d", i + 1).isNotEqualTo(429);
        }
        assertThat(post("/api/passkey/register/start", address, signUpBody("x@fredvested.test"))).isEqualTo(429);
        // Start and finish share the budget: finishing is no way round it.
        assertThat(post("/api/passkey/register/finish", address, "{}")).isEqualTo(429);
        assertThat(post("/api/passkey/register/recovery/start", address, "{}")).isEqualTo(429);

        assertThat(post("/api/passkey/register/finish", freshAddress(), "{}")).isNotEqualTo(429);
    }

    @Test
    @DisplayName("writing a letter of the path as %xx is not a way round the count")
    void encodedPathIsCounted() throws Exception {
        String address = freshAddress();
        for (int i = 0; i < 5; i++) {
            mvc.perform(MockMvcRequestBuilders.post(java.net.URI.create("/api/%70asskey/authenticate/start"))
                    .header("CF-Connecting-IP", address).contentType(MediaType.APPLICATION_JSON).content("{}"));
        }
        assertThat(mvc.perform(MockMvcRequestBuilders.post(java.net.URI.create("/api/%70asskey/authenticate/start"))
                .header("CF-Connecting-IP", address).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse().getStatus()).isEqualTo(429);
        assertThat(post("/api/passkey/authenticate/start", address, "{}")).isEqualTo(429);
    }

    @Test
    @DisplayName("an IPv6 caller is counted by its /64 block, so changing the last half of the address does not help")
    void ipv6CountedByBlock() throws Exception {
        String block = "2001:db8:" + Integer.toHexString(NEXT_ADDRESS.getAndIncrement()) + ":77:";
        for (int i = 0; i < 5; i++) {
            assertThat(post("/api/passkey/authenticate/start", block + ":" + (i + 1), "{}")).isNotEqualTo(429);
        }
        assertThat(post("/api/passkey/authenticate/start", block + "aaaa:bbbb:cccc:dddd", "{}")).isEqualTo(429);
    }

    @Test
    @DisplayName("a chat message over 4000 characters is refused before any model work")
    void longChatMessageRefused() throws Exception {
        String email = newUser();
        String body = "{\"message\":\"" + "x".repeat(4001) + "\",\"sessionId\":\"s1\"}";

        assertThat(as(email, MockMvcRequestBuilders.post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(body)).getStatus()).isEqualTo(413);
        assertThat(as(email, MockMvcRequestBuilders.get("/api/chat/stream").param("message", "x".repeat(4001)))
                .getStatus()).isEqualTo(413);
        org.mockito.Mockito.verifyNoInteractions(chatService);
    }

    @Test
    @DisplayName("passkey sign-in: one address gets 5 tries across start and finish, then 429")
    void signInPerAddress() throws Exception {
        String address = freshAddress();
        for (int i = 0; i < 5; i++) {
            assertThat(post("/api/passkey/authenticate/start", address, "{}")).as("try %d", i + 1).isNotEqualTo(429);
        }
        assertThat(post("/api/passkey/authenticate/start", address, "{}")).isEqualTo(429);
        assertThat(post("/api/passkey/authenticate/finish", address, "{}")).isEqualTo(429);

        assertThat(post("/api/passkey/authenticate/start", freshAddress(), "{}")).isNotEqualTo(429);
    }

    @Test
    @DisplayName("a refused request says when to come back")
    void refusalCarriesRetryAfter() throws Exception {
        String address = freshAddress();
        for (int i = 0; i < 5; i++) {
            post("/api/passkey/authenticate/start", address, "{}");
        }
        MockHttpServletResponse refused = mvc.perform(MockMvcRequestBuilders.post("/api/passkey/authenticate/start")
                .header("CF-Connecting-IP", address).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andReturn().getResponse();

        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(refused.getHeader("Retry-After"))).isBetween(1, 600);
        assertThat(refused.getContentAsString()).contains("Too many");
    }

    @Test
    @DisplayName("the old password login: 3 tries per address, then 429")
    void loginPerAddress() throws Exception {
        String address = freshAddress();
        for (int i = 0; i < 3; i++) {
            String body = "{\"email\":\"" + UUID.randomUUID() + "@fredvested.test\",\"password\":\"guess-" + i + "\"}";
            assertThat(post("/api/auth/login", address, body)).as("try %d", i + 1).isNotEqualTo(429);
        }
        assertThat(post("/api/auth/login", address, "{\"email\":\"a@fredvested.test\",\"password\":\"x\"}"))
                .isEqualTo(429);
    }

    @Test
    @DisplayName("token refresh: 6 tries per address, then 429")
    void refreshPerAddress() throws Exception {
        String address = freshAddress();
        for (int i = 0; i < 6; i++) {
            assertThat(post("/api/auth/refresh", address, "{}")).as("try %d", i + 1).isNotEqualTo(429);
        }
        assertThat(post("/api/auth/refresh", address, "{}")).isEqualTo(429);
    }

    @Test
    @DisplayName("the re-auth check: 4 tries per address, then 429")
    void reauthCheckPerAddress() throws Exception {
        String address = freshAddress();
        for (int i = 0; i < 4; i++) {
            assertThat(get("/api/user/should-prompt-reauth", address)).as("try %d", i + 1).isEqualTo(200);
        }
        assertThat(get("/api/user/should-prompt-reauth", address)).isEqualTo(429);
    }

    // ------------------------------------------------------------------ per target, whatever the address

    @Test
    @DisplayName("sign-up for one email: 3 tries an hour, however many addresses they come from")
    void signUpPerEmail() throws Exception {
        String email = "rl-target-" + UUID.randomUUID() + "@fredvested.test";
        for (int i = 0; i < 3; i++) {
            assertThat(post("/api/passkey/register/start", freshAddress(), signUpBody(email)))
                    .as("try %d", i + 1).isNotEqualTo(429);
        }
        // Same email, new address every time, and with the letters changed to capitals.
        assertThat(post("/api/passkey/register/start", freshAddress(), signUpBody(email))).isEqualTo(429);
        assertThat(post("/api/passkey/register/start", freshAddress(), signUpBody(email.toUpperCase()))).isEqualTo(429);

        assertThat(post("/api/passkey/register/start", freshAddress(),
                signUpBody("rl-other-" + UUID.randomUUID() + "@fredvested.test"))).isNotEqualTo(429);
    }

    @Test
    @DisplayName("password guesses at one email: 2 an hour, however many addresses they come from")
    void loginPerEmail() throws Exception {
        String body = "{\"email\":\"rl-victim-" + UUID.randomUUID() + "@fredvested.test\",\"password\":\"guess\"}";
        assertThat(post("/api/auth/login", freshAddress(), body)).isNotEqualTo(429);
        assertThat(post("/api/auth/login", freshAddress(), body)).isNotEqualTo(429);

        assertThat(post("/api/auth/login", freshAddress(), body)).isEqualTo(429);
    }

    // ------------------------------------------------------------------ chat, per user

    @Test
    @DisplayName("chat: one user gets 3 messages a minute across both ways of sending; another user is not affected")
    void chatPerUserPerMinute() throws Exception {
        String email = newUser();
        String other = newUser();
        String body = "{\"message\":\"hi\",\"sessionId\":\"s1\"}";

        assertThat(as(email, MockMvcRequestBuilders.post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(body)).getStatus()).isEqualTo(200);
        assertThat(as(email, MockMvcRequestBuilders.get("/api/chat/stream").param("message", "hi")).getStatus())
                .isEqualTo(200);
        assertThat(as(email, MockMvcRequestBuilders.post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(body)).getStatus()).isEqualTo(200);

        assertThat(as(email, MockMvcRequestBuilders.post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(body)).getStatus()).isEqualTo(429);
        assertThat(as(email, MockMvcRequestBuilders.get("/api/chat/stream").param("message", "hi")).getStatus())
                .isEqualTo(429);

        assertThat(as(other, MockMvcRequestBuilders.post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(body)).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("chat history and suggestions: 4 reads a minute per user, then 429")
    void chatReadsPerUser() throws Exception {
        String email = newUser();
        for (int i = 0; i < 4; i++) {
            assertThat(as(email, MockMvcRequestBuilders.get("/api/chat/history")).getStatus())
                    .as("read %d", i + 1).isNotEqualTo(429);
        }
        assertThat(as(email, MockMvcRequestBuilders.get("/api/chat/history")).getStatus()).isEqualTo(429);
        assertThat(as(email, MockMvcRequestBuilders.get("/api/chat/suggestions")).getStatus()).isEqualTo(429);
    }

    // ------------------------------------------------------------------ what is left alone

    @Test
    @DisplayName("signed-in routes outside chat are not counted")
    void otherRoutesAreNotLimited() throws Exception {
        String email = newUser();
        for (int i = 0; i < 12; i++) {
            assertThat(as(email, MockMvcRequestBuilders.get("/api/beneficiaries")).getStatus()).isEqualTo(200);
        }
    }
}
