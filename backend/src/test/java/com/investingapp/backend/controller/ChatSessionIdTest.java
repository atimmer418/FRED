package com.investingapp.backend.controller;

import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.ChatService;
import com.investingapp.backend.service.DailyQuestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A chat session id is stored in a column 100 characters wide. A longer one used to reach the
 * database and come back as a 500, after the message had already been counted against the user's
 * limit. It is now turned away as a bad request before anything else happens.
 */
class ChatSessionIdTest {

    private static final String TOO_LONG = "s".repeat(101);
    private static final String LONGEST_ALLOWED = "s".repeat(100);

    private ChatService chatService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new ChatController(chatService, mock(DailyQuestionService.class),
                        new com.investingapp.backend.security.ratelimit.RateLimiter(
                                new org.springframework.mock.env.MockEnvironment())))
                .build();
        UserDetailsImpl principal = new UserDetailsImpl(7L, "chat@fredvested.test", List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static String body(String sessionId) {
        return "{\"message\":\"hi\",\"sessionId\":\"" + sessionId + "\"}";
    }

    @Test
    @DisplayName("a session id over 100 characters is a bad request on every chat route")
    void longSessionIdIsRefusedEverywhere() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body(TOO_LONG)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/chat/stream").param("message", "hi").param("sessionId", TOO_LONG))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/chat/action").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"pause_schedule\",\"sessionId\":\"" + TOO_LONG + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/chat/history").param("sessionId", TOO_LONG))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatService);
    }

    @Test
    @DisplayName("a session id of exactly 100 characters is still accepted")
    void longestSessionIdIsAccepted() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content(body(LONGEST_ALLOWED)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/chat/history").param("sessionId", LONGEST_ALLOWED))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a chat message with no text is a bad request, not a database error")
    void missingMessageIsRefused() throws Exception {
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"s1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(chatService);
    }
}
