package com.investingapp.backend.controller;

import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.ChatService;
import com.investingapp.backend.service.DailyQuestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/chat/suggestions is personalized, so it must follow the signed-in user and never a
 * userId the client sends. These tests go through the real request mapping, so they prove a
 * userId in the query string is ignored whatever it holds.
 */
class ChatControllerSuggestionsOwnershipTest {

    private static final long USER_A = 101L;
    private static final long USER_B = 202L;

    private DailyQuestionService dailyQuestionService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        dailyQuestionService = mock(DailyQuestionService.class);
        when(dailyQuestionService.getTodayQuestionsForUser(USER_A)).thenReturn(List.of("a1", "a2", "a3"));
        when(dailyQuestionService.getTodayQuestionsForUser(USER_B)).thenReturn(List.of("b1", "b2", "b3"));
        mvc = MockMvcBuilders
                .standaloneSetup(new ChatController(mock(ChatService.class), dailyQuestionService,
                        new com.investingapp.backend.security.ratelimit.RateLimiter(
                                new org.springframework.mock.env.MockEnvironment())))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void signInAs(long userId) {
        UserDetailsImpl principal = new UserDetailsImpl(userId, "user-" + userId + "@fredvested.test", List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Test
    @DisplayName("user A asking with user B's id gets A's suggestions, and B is never looked up")
    void clientUserIdIsIgnored() throws Exception {
        signInAs(USER_A);

        mvc.perform(get("/api/chat/suggestions").param("userId", String.valueOf(USER_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("a1"))
                .andExpect(jsonPath("$[2]").value("a3"));

        verify(dailyQuestionService).getTodayQuestionsForUser(USER_A);
        verify(dailyQuestionService, never()).getTodayQuestionsForUser(USER_B);
    }

    @Test
    @DisplayName("the request works with no userId at all")
    void userIdIsOptional() throws Exception {
        signInAs(USER_A);

        mvc.perform(get("/api/chat/suggestions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("a1"));

        verify(dailyQuestionService).getTodayQuestionsForUser(USER_A);
    }

    @Test
    @DisplayName("a junk userId (what an old client with no stored id sends) is ignored too")
    void junkUserIdIsIgnored() throws Exception {
        signInAs(USER_A);

        mvc.perform(get("/api/chat/suggestions").param("userId", "undefined"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("a1"));

        verify(dailyQuestionService).getTodayQuestionsForUser(USER_A);
    }

    @Test
    @DisplayName("with nobody signed in the answer is 401, even when a real user's id is sent")
    void noPrincipalIs401() throws Exception {
        mvc.perform(get("/api/chat/suggestions").param("userId", String.valueOf(USER_B)))
                .andExpect(status().isUnauthorized());

        verify(dailyQuestionService, never()).getTodayQuestionsForUser(anyLong());
    }
}
