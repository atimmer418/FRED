package com.investingapp.backend.controller;

import com.investingapp.backend.security.services.UserDetailsImpl;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A refused referral code must reach the user as a 400 with the reason. With the real service and
 * the real database, because the bug was in how the two transactions met: the refusal doomed a
 * transaction the route shared with the service, the commit failed, and the answer was a 500.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class ReferralRefusalTest {

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc;
    private String email;
    private Long userId;
    private String ownCode;

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        email = "referral-" + UUID.randomUUID() + "@fredvested.test";
        ownCode = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase();
        jdbc.update("INSERT INTO users (email, create_date, public_id, analytics_opt_out, referral_code) "
                + "VALUES (?, ?, ?, 0, ?)", email, LocalDateTime.now(), UUID.randomUUID().toString(), ownCode);
        userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM user_progress WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    private MockHttpServletResponse apply(String code) throws Exception {
        UserDetailsImpl principal = new UserDetailsImpl(userId, email, List.of());
        return mvc.perform(post("/api/user/referral/apply").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}")
                .with(authentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()))))
                .andReturn().getResponse();
    }

    @Test
    @DisplayName("a code nobody has is refused with the reason")
    void unknownCode() throws Exception {
        MockHttpServletResponse response = apply("NOSUCH" + UUID.randomUUID().toString().substring(0, 4));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("Invalid referral code.");
    }

    @Test
    @DisplayName("your own code is refused with the reason")
    void ownCode() throws Exception {
        MockHttpServletResponse response = apply(ownCode);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("You cannot apply your own referral code.");
    }
}
