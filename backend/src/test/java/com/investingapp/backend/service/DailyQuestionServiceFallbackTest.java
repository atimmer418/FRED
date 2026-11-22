package com.investingapp.backend.service;

import com.investingapp.backend.model.DailyQuestion;
import com.investingapp.backend.repository.DailyQuestionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * If today's questions cannot be saved, the suggestions call used to throw (it read the row back
 * and assumed it was there). It must hand back the safe list instead.
 */
class DailyQuestionServiceFallbackTest {

    @Test
    @DisplayName("no row for today and the save fails: the user still gets three suggestions")
    void saveFailureFallsBackInsteadOfThrowing() {
        DailyQuestionRepository repository = mock(DailyQuestionRepository.class);
        ConversationAnalyticsService analytics = mock(ConversationAnalyticsService.class);
        when(repository.findByDate(any())).thenReturn(Optional.empty());
        when(repository.save(any(DailyQuestion.class))).thenThrow(new RuntimeException("database is down"));
        when(analytics.getUserTopics(7L)).thenReturn(List.of("fred_story"));

        DailyQuestionService service = new DailyQuestionService(repository, mock(LLMService.class), analytics);

        List<String> questions = service.getTodayQuestionsForUser(7L);

        assertThat(questions).hasSize(3).doesNotContainNull();
    }
}
