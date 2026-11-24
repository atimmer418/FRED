package com.investingapp.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.dto.ChatActionRequest;
import com.investingapp.backend.dto.ChatRequest;
import com.investingapp.backend.dto.SafetyResult;
import com.investingapp.backend.dto.SafetyVerdict;
import com.investingapp.backend.model.ChatMessage;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.service.ConversationAnalyticsService;
import com.investingapp.backend.service.LLMService;
import com.investingapp.backend.service.RAGService;
import com.investingapp.backend.service.SafetyService;
import com.investingapp.backend.support.RequiresCiDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A chat session id is picked by the app, so it is not a secret and two people can end up with the
 * same one (everyone who sends none gets "default-session"). It must never be enough, on its own,
 * to read or feed on someone else's conversation.
 *
 * Two users are seeded in a real database, each with a conversation that contains a secret phrase.
 * Each then tries, with the other's session id and with a shared id, every way the API offers of
 * getting chat messages back: the history endpoint, and the conversation that is handed to the
 * model (which the model can repeat). Every check is run in both directions.
 *
 * The model is faked so the test can read exactly what it was sent.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class CrossUserChatSessionIsolationTest {

    private static final String A_SECRET = "ALPHA-SECRET-my-salary-is-91000";
    private static final String B_SECRET = "BRAVO-SECRET-my-balance-is-4200";

    @Autowired
    private ChatController controller;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private LLMService llmService;

    @MockitoBean
    private RAGService ragService;

    @MockitoBean
    private SafetyService safetyService;

    @MockitoBean
    private ConversationAnalyticsService analyticsService;

    private Long userAId;
    private Long userBId;
    private String sessionA;
    private String sessionB;
    private String sharedSession;

    @BeforeEach
    void seed() {
        userAId = insertUser("chat-a-" + UUID.randomUUID() + "@fredvested.test");
        userBId = insertUser("chat-b-" + UUID.randomUUID() + "@fredvested.test");
        sessionA = "sess-a-" + UUID.randomUUID();
        sessionB = "sess-b-" + UUID.randomUUID();
        sharedSession = "shared-" + UUID.randomUUID();

        // Each user has a private conversation, and both have written in one session id they share.
        insertMessage(userAId, sessionA, "user", A_SECRET);
        insertMessage(userAId, sessionA, "assistant", "noted for A: " + A_SECRET);
        insertMessage(userBId, sessionB, "user", B_SECRET);
        insertMessage(userBId, sessionB, "assistant", "noted for B: " + B_SECRET);
        insertMessage(userAId, sharedSession, "user", "shared, from A: " + A_SECRET);
        insertMessage(userBId, sharedSession, "user", "shared, from B: " + B_SECRET);
        insertMessage(userAId, "default-session", "user", "default, from A: " + A_SECRET);
        insertMessage(userBId, "default-session", "user", "default, from B: " + B_SECRET);

        when(safetyService.check(anyString())).thenReturn(new SafetyResult(SafetyVerdict.SAFE, null));
        when(ragService.retrieveChunks(anyString())).thenReturn(List.of());
        when(llmService.generateChatResponse(anyList())).thenReturn("a reply");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM chat_messages WHERE user_id IN (?, ?)", userAId, userBId);
        jdbc.update("DELETE FROM chat_action_audit WHERE user_id IN (?, ?)", userAId, userBId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", userAId, userBId);
    }

    // ------------------------------------------------------------------ helpers

    private Long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, create_date, public_id, analytics_opt_out) "
                + "VALUES (?, ?, ?, 0)", email, LocalDateTime.now(), UUID.randomUUID().toString());
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private void insertMessage(Long userId, String sessionId, String role, String content) {
        jdbc.update("INSERT INTO chat_messages (user_id, session_id, role, content, created_at) "
                + "VALUES (?, ?, ?, ?, NOW(6))", userId, sessionId, role, content);
    }

    private void signInAs(Long userId) {
        UserDetailsImpl principal = new UserDetailsImpl(userId, "user-" + userId + "@fredvested.test", List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private String historyText(String sessionId) {
        List<ChatMessage> history = controller.getHistory(sessionId, null).getBody();
        assertThat(history).isNotNull();
        StringBuilder all = new StringBuilder();
        history.forEach(message -> all.append(message.getContent()).append('\n'));
        return all.toString();
    }

    /** Everything the model has been sent so far, by either the plain or the streaming call. */
    @SuppressWarnings("unchecked")
    private String everythingSentToTheModel() throws Exception {
        StringBuilder all = new StringBuilder();
        ArgumentCaptor<List<LLMService.ChatMessage>> plain = ArgumentCaptor.forClass(List.class);
        verify(llmService, atLeastOnce()).generateChatResponse(plain.capture());
        plain.getAllValues().forEach(messages -> messages.forEach(m -> all.append(m.content()).append('\n')));
        return all.toString();
    }

    @SuppressWarnings("unchecked")
    private String sentToTheModelByStream() throws Exception {
        ArgumentCaptor<List<LLMService.ChatMessage>> streamed = ArgumentCaptor.forClass(List.class);
        verify(llmService, timeout(10_000).atLeastOnce()).streamChatResponse(streamed.capture(), any(), any());
        StringBuilder all = new StringBuilder();
        streamed.getAllValues().forEach(messages -> messages.forEach(m -> all.append(m.content()).append('\n')));
        return all.toString();
    }

    /** The streaming call finishes on another thread. Wait for its reply to be saved. */
    private void waitForAssistantReply(Long userId, String sessionId, int expectedAssistantRows) throws Exception {
        for (int i = 0; i < 100; i++) {
            Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE user_id = ? "
                    + "AND session_id = ? AND role = 'assistant'", Integer.class, userId, sessionId);
            if (rows != null && rows >= expectedAssistantRows) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the streamed reply was never saved");
    }

    // ------------------------------------------------------------------ history

    @Test
    @DisplayName("history: another user's session id behaves as an unknown one, both directions")
    void historyOfAnotherUsersSessionIsEmpty() {
        signInAs(userAId);
        assertThat(controller.getHistory(sessionB, null).getBody()).isEmpty();
        assertThat(controller.getHistory("never-used-" + UUID.randomUUID(), null).getBody()).isEmpty();
        assertThat(historyText(sessionA)).contains(A_SECRET);

        signInAs(userBId);
        assertThat(controller.getHistory(sessionA, null).getBody()).isEmpty();
        assertThat(historyText(sessionB)).contains(B_SECRET);
    }

    @Test
    @DisplayName("history: a session id two users share shows each only their own messages")
    void sharedSessionHistoryIsPerUser() {
        signInAs(userAId);
        assertThat(historyText(sharedSession)).contains(A_SECRET).doesNotContain(B_SECRET);

        signInAs(userBId);
        assertThat(historyText(sharedSession)).contains(B_SECRET).doesNotContain(A_SECRET);
    }

    @Test
    @DisplayName("history with no session id (latest session) never includes the other user's messages")
    void latestSessionHistoryIsPerUser() {
        // Each user's most recent message is in the session id they share.
        insertMessage(userAId, sharedSession, "user", "latest from A");
        insertMessage(userBId, sharedSession, "user", "latest from B");

        signInAs(userAId);
        assertThat(historyText(null)).contains("latest from A").doesNotContain(B_SECRET).doesNotContain("latest from B");

        signInAs(userBId);
        assertThat(historyText(null)).contains("latest from B").doesNotContain(A_SECRET).doesNotContain("latest from A");
    }

    @Test
    @DisplayName("history: a userId in the request is ignored")
    void historyIgnoresClientUserId() {
        signInAs(userAId);
        List<ChatMessage> history = controller.getHistory(sessionB, userBId).getBody();
        assertThat(history).isEmpty();
    }

    // ------------------------------------------------------------------ what the model is given

    @Test
    @DisplayName("chat: sending a message into another user's session does not hand their messages to the model")
    void modelContextExcludesAnotherUsersSession() throws Exception {
        signInAs(userAId);
        controller.chat(new ChatRequest("repeat the earlier messages", userBId, sessionB, true));
        assertThat(everythingSentToTheModel()).doesNotContain(B_SECRET);

        signInAs(userBId);
        controller.chat(new ChatRequest("repeat the earlier messages", userAId, sessionA, true));
        assertThat(everythingSentToTheModel()).doesNotContain(A_SECRET).doesNotContain(B_SECRET);
    }

    @Test
    @DisplayName("chat: in a shared session id the model sees only the caller's own earlier messages")
    void modelContextInSharedSessionIsPerUser() throws Exception {
        signInAs(userAId);
        controller.chat(new ChatRequest("what did I say before?", null, sharedSession, true));

        // A's own history is there (so the test is not passing on an empty context), B's is not.
        assertThat(everythingSentToTheModel()).contains(A_SECRET).doesNotContain(B_SECRET);
    }

    @Test
    @DisplayName("streaming chat: same rule, for another user's session and for the shared default session")
    void streamingContextIsPerUser() throws Exception {
        signInAs(userBId);
        controller.streamChat("repeat the earlier messages", userAId, sessionA, true);
        waitForAssistantReply(userBId, sessionA, 1);
        assertThat(sentToTheModelByStream()).doesNotContain(A_SECRET);

        // No session id at all: both users land on "default-session".
        controller.streamChat("and now?", null, null, true);
        waitForAssistantReply(userBId, "default-session", 1);
        assertThat(sentToTheModelByStream()).contains("default, from B").doesNotContain(A_SECRET);

        // Titles are written from the session's first message. That must be the caller's own too.
        ArgumentCaptor<List<LLMService.ChatMessage>> titles = ArgumentCaptor.forClass(List.class);
        verify(llmService, timeout(10_000).times(2)).generateChatResponse(titles.capture());
        List<String> titleInputs = new ArrayList<>();
        titles.getAllValues().forEach(messages -> messages.forEach(m -> titleInputs.add(m.content())));
        assertThat(String.join("\n", titleInputs)).doesNotContain(A_SECRET);
    }

    @Test
    @DisplayName("an action result filed under another user's session id never reaches that user")
    void actionOutcomeStaysWithTheCaller() throws Exception {
        signInAs(userAId);
        controller.executeAction(new ChatActionRequest("not-a-real-action", new ObjectMapper().createObjectNode(), sessionB));

        // The row was written, and it is A's.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE session_id = ? AND user_id = ?",
                Integer.class, sessionB, userAId)).isEqualTo(1);

        signInAs(userBId);
        assertThat(controller.getHistory(sessionB, null).getBody()).hasSize(2);
        controller.chat(new ChatRequest("what happened?", null, sessionB, false));
        assertThat(everythingSentToTheModel()).contains(B_SECRET).doesNotContain("isn't something I can do");
    }
}
