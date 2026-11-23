package com.investingapp.backend.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.investingapp.backend.model.ConversationInsight;
import com.investingapp.backend.model.DailyQuestion;
import com.investingapp.backend.repository.ConversationInsightRepository;
import com.investingapp.backend.repository.DailyQuestionRepository;
import com.investingapp.backend.security.services.UserDetailsImpl;
import com.investingapp.backend.support.RequiresCiDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one thing chat suggestions reveal about a person is whether they have already asked FRED for
 * its story: someone who has not gets "What's your story FRED?" as the third suggestion, someone
 * who has gets the day's normal third question.
 *
 * Two users are seeded in a real database. B has asked for the story, A has not. Whoever is signed
 * in must see their own answer no matter whose id they put in the request.
 */
@SpringBootTest
@ActiveProfiles("ci")
@RequiresCiDatabase
class CrossUserChatSuggestionsIsolationTest {

    private static final String FRED_STORY = "What's your story FRED?";
    private static final List<String> BASE = List.of("base one", "base two", "base three");

    @Autowired
    private ChatController controller;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DailyQuestionRepository dailyQuestionRepository;

    @Autowired
    private ConversationInsightRepository insightRepository;

    private Long userAId;
    private Long userBId;
    private Long seededInsightId;
    private Long seededDailyQuestionId;
    private List<String> todaysBase;

    @BeforeEach
    void seed() {
        userAId = insertUser("sugg-a-" + UUID.randomUUID() + "@fredvested.test");
        userBId = insertUser("sugg-b-" + UUID.randomUUID() + "@fredvested.test");

        ConversationInsight insight = new ConversationInsight(
                userBId, "sugg-test-session", "What's your story FRED?", "fred_story", 10, false);
        seededInsightId = insightRepository.save(insight).getId();

        // Today's base questions must exist so the test never asks the model to write them. If
        // another test already made today's row, use it and leave it alone afterwards.
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        todaysBase = dailyQuestionRepository.findByDate(today)
                .map(DailyQuestion::getQuestions)
                .orElseGet(() -> {
                    DailyQuestion saved = dailyQuestionRepository.save(new DailyQuestion(today, BASE));
                    seededDailyQuestionId = saved.getId();
                    return BASE;
                });
        assertThat(todaysBase).hasSize(3);
        assertThat(todaysBase.get(2)).isNotEqualTo(FRED_STORY);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (seededInsightId != null) {
            insightRepository.deleteById(seededInsightId);
        }
        if (seededDailyQuestionId != null) {
            dailyQuestionRepository.deleteById(seededDailyQuestionId);
        }
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", userAId, userBId);
    }

    private Long insertUser(String email) {
        jdbc.update("INSERT INTO users (email, create_date, public_id, analytics_opt_out) "
                + "VALUES (?, ?, ?, 0)", email, LocalDateTime.now(), UUID.randomUUID().toString());
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private static void signInAs(Long userId) {
        UserDetailsImpl principal = new UserDetailsImpl(userId, "user-" + userId + "@fredvested.test", List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    /** Sends the real request, with the given id in the query string when there is one. */
    private MockHttpServletResponse request(Long userIdInQuery) throws Exception {
        MockHttpServletRequestBuilder get = MockMvcRequestBuilders.get("/api/chat/suggestions");
        if (userIdInQuery != null) {
            get = get.param("userId", String.valueOf(userIdInQuery));
        }
        return MockMvcBuilders.standaloneSetup(controller).build().perform(get).andReturn().getResponse();
    }

    private List<String> suggestions(Long userIdInQuery) throws Exception {
        MockHttpServletResponse response = request(userIdInQuery);
        assertThat(response.getStatus()).isEqualTo(200);
        return new ObjectMapper().readValue(response.getContentAsString(), new TypeReference<List<String>>() {
        });
    }

    @Test
    @DisplayName("A sending B's id still sees A's own suggestions, so A learns nothing about B")
    void aCannotReadBsSuggestions() throws Exception {
        signInAs(userAId);

        List<String> withBsId = suggestions(userBId);
        List<String> withNoId = suggestions(null);
        List<String> withOwnId = suggestions(userAId);

        // A has never asked for the story, so A is offered it. B's answer would be the base list.
        assertThat(withBsId).hasSize(3);
        assertThat(withBsId.get(2)).isEqualTo(FRED_STORY);
        assertThat(withBsId).isEqualTo(withNoId).isEqualTo(withOwnId);
    }

    @Test
    @DisplayName("B sending A's id still sees B's own suggestions (proves the two users really differ)")
    void bSeesBsOwnSuggestionsWhateverIdIsSent() throws Exception {
        signInAs(userBId);

        List<String> withAsId = suggestions(userAId);
        List<String> withNoId = suggestions(null);

        // B has already asked for the story, so B gets the day's normal list.
        assertThat(withAsId).isEqualTo(todaysBase);
        assertThat(withAsId).isEqualTo(withNoId);
    }

    @Test
    @DisplayName("with nobody signed in, sending a real user's id gets 401 and no suggestions")
    void signedOutGetsNothing() throws Exception {
        SecurityContextHolder.clearContext();

        MockHttpServletResponse response = request(userBId);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEmpty();
    }
}
