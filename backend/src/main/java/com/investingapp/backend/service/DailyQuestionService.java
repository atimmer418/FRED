package com.investingapp.backend.service;

import com.investingapp.backend.model.DailyQuestion;
import com.investingapp.backend.repository.DailyQuestionRepository;
import com.investingapp.backend.service.LLMService.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Daily Question Service
 * 
 * Phase 3: Smart question blending
 * - 1 LLM-generated question (variety)
 * - 1 popular question (from analytics)
 * - 1 personalized question (based on user's unexplored topics)
 * 
 * Fallback: If analytics empty, generate all 3 via LLM
 */
@Service
public class DailyQuestionService {

    private static final Logger logger = LoggerFactory.getLogger(DailyQuestionService.class);

    private final DailyQuestionRepository repository;
    private final LLMService llmService;
    private final ConversationAnalyticsService analyticsService;

    @Autowired
    public DailyQuestionService(DailyQuestionRepository repository, LLMService llmService,
            ConversationAnalyticsService analyticsService) {
        this.repository = repository;
        this.llmService = llmService;
        this.analyticsService = analyticsService;
    }

    /**
     * Retrieves today's questions. If not present, generates them synchronously
     * (lazy load fallback).
     * 
     * NOTE: For Phase 3, we need userId for personalized questions.
     * This method stays generic (no userId) for backward compatibility.
     */
    public List<String> getTodayQuestions() {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        Optional<DailyQuestion> existing = repository.findByDate(today);

        if (existing.isPresent()) {
            return existing.get().getQuestions();
        }

        // If not found (e.g., scheduler failed or first run), generate now
        logger.info("No daily questions found for {}. Generating now...", today);
        return generateAndSaveQuestions(today, null); // No userId fallback
    }

    /**
     * Get today's questions with personalization for a specific user.
     * This is the new Phase 3 method that enables personalized questions.
     */
    public List<String> getTodayQuestionsForUser(Long userId) {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));

        // Check if we have base questions for today
        Optional<DailyQuestion> existing = repository.findByDate(today);

        if (existing.isEmpty()) {
            // Generate base questions first (includes 1 popular + 1 LLM-generated)
            logger.info("No daily questions found for {}. Generating now...", today);
            generateAndSaveQuestions(today, null);
            existing = repository.findByDate(today);
        }

        // Get all 3 base questions
        List<String> baseQuestions = new ArrayList<>(existing.get().getQuestions());

        // Check if user has ever asked "What's your story FRED?"
        List<String> userTopics = analyticsService.getUserTopics(userId);
        boolean hasAskedAboutFred = userTopics != null &&
                (userTopics.contains("fred_story") || userTopics.contains("fred_features"));

        // If user hasn't asked about FRED, replace question 3 with FRED story
        if (!hasAskedAboutFred && baseQuestions.size() >= 3) {
            baseQuestions.set(2, "What's your story FRED?");
            logger.info("Showing FRED story as question 3 for user {}", userId);
        }

        return baseQuestions;
    }

    /**
     * Scheduled task to generate base questions (LLM + popular) at midnight EST.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "America/New_York")
    public void scheduleQuestionGeneration() {
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        logger.info("Executing scheduled question generation for {}", today);
        if (repository.findByDate(today).isEmpty()) {
            generateAndSaveQuestions(today, null);
        }
    }

    /**
     * Generate and save the day's 3 suggestions — each slot showcases a different
     * FRED capability:
     *   1. an intriguing educational question (classic ask-FRED)
     *   2. a chart request (exercises the inline ```chart rendering)
     *   3. a personalized check-in (answered from the user context FRED holds)
     */
    private List<String> generateAndSaveQuestions(LocalDate date, Long userId) {
        try {
            List<String> questions = new ArrayList<>(generateShowcaseQuestions(date));

            if (questions.isEmpty()) {
                questions.add("Why is compound interest called the 8th wonder of the world?");
            }
            if (questions.size() < 2) {
                questions.add("Show me a graph of how $200 a month grows over 25 years");
            }
            questions.add(personalQuestionFor(date));

            List<String> finalQuestions = questions.subList(0, 3);

            // Save to database
            DailyQuestion dq = new DailyQuestion(date, finalQuestions);
            repository.save(dq);
            logger.info("✅ Saved 3 daily questions for {}: {}", date, finalQuestions);

            return finalQuestions;

        } catch (Exception e) {
            logger.error("Failed to generate daily questions", e);
            // Return safe fallback (same slot mix)
            return Arrays.asList(
                    "Why is compound interest called the 8th wonder of the world?",
                    "Show me a graph of how $200 a month grows over 25 years",
                    "Look at my investing setup. What's one thing I could improve?");
        }
    }

    /**
     * One LLM call producing slots 1 and 2: an educational question plus a
     * chart-showcase request.
     */
    private List<String> generateShowcaseQuestions(LocalDate date) {
        try {
            List<ChatMessage> messages = new ArrayList<>();
            List<String> allConcepts = Arrays.asList(
                    "Compound Interest", "Risk Tolerance", "ETF vs Mutual Fund",
                    "Inflation", "Market Cycles", "Dividends", "Compound Growth", "Asset Allocation",
                    "Retirement Planning", "Tech Stocks", "Dollar Cost Averaging", "Emergency Funds");

            List<String> selectedConcepts = new ArrayList<>(allConcepts);
            java.util.Collections.shuffle(selectedConcepts);
            String concepts = String.join(", ", selectedConcepts.subList(0, 3));

            messages.add(new ChatMessage("system",
                    "You write suggestion chips for FRED, a financial education chat for novice investors. "
                            + "Generate exactly 2 suggestions, written from the user's perspective:\n"
                            + "1. A short, intriguing beginner question about one of these concepts: " + concepts + ".\n"
                            + "2. A request for a VISUAL, phrased like 'Show me a graph of ...' or 'Chart the difference between ...', "
                            + "with concrete numbers (monthly amounts, years) about growth, compounding, or a comparison. "
                            + "Do NOT specify a return rate in the question; FRED applies the app's standard 10% average annual return by default.\n"
                            + "Keep each under 90 characters. Never use an em dash in a suggestion. Output ONLY the 2 suggestions separated by a pipe (|). No numbering, no intro."));

            messages.add(new ChatMessage("user", "Generate the 2 suggestions for " + date));

            String response = llmService.generateChatResponse(messages);

            List<String> questions = Arrays.stream(response.split("\\|"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .limit(2)
                    .collect(Collectors.toList());

            logger.info("Generated {} showcase questions", questions.size());
            return questions;

        } catch (Exception e) {
            logger.error("Failed to generate showcase questions", e);
            return new ArrayList<>();
        }
    }

    /**
     * Slot 3: a personal check-in FRED can genuinely answer from the user
     * context it already holds (automation status, horizon, risk tolerance).
     * Rotates daily. When agentic tools land, this slot upgrades to real
     * take-an-action prompts.
     */
    private String personalQuestionFor(LocalDate date) {
        List<String> pool = Arrays.asList(
                "How is my portfolio actually doing?",
                "Chart my portfolio over the last 3 months",
                "Help me bump up my monthly investment a little",
                "Am I on track for my time horizon? Be honest.",
                "I need to pause my investing for a bit. Can you set that up?",
                "Review my investing schedule and suggest one change");
        return pool.get((int) (date.toEpochDay() % pool.size()));
    }

}
