package com.investingapp.backend.controller;

import com.investingapp.backend.security.ratelimit.RateLimiter;
import com.investingapp.backend.dto.ChatRequest;
import com.investingapp.backend.dto.ChatResponse;
import com.investingapp.backend.service.ChatService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.investingapp.backend.model.ChatMessage;
import com.investingapp.backend.security.services.UserDetailsImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestAttribute;
import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final com.investingapp.backend.service.DailyQuestionService dailyQuestionService;

    private final RateLimiter rateLimiter;

    /** Far longer than anyone types in a chat box, far shorter than a pasted book. */
    static final int MAX_MESSAGE_CHARACTERS = 4000;

    public ChatController(ChatService chatService,
            com.investingapp.backend.service.DailyQuestionService dailyQuestionService,
            RateLimiter rateLimiter) {
        this.chatService = chatService;
        this.dailyQuestionService = dailyQuestionService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Every chat message costs a model call, so each user gets a set number a minute and a day.
     * Both ways of sending (POST and the stream) draw on the same budget. Returns the user's id.
     */
    private Long countMessage(String message) {
        // One message can set off several model calls, and the model is paid by size.
        if (message != null && message.length() > MAX_MESSAGE_CHARACTERS) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
                    "Message is too long. Keep it under " + MAX_MESSAGE_CHARACTERS + " characters.");
        }
        Long userId = authenticatedUserId();
        if (userId == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        rateLimiter.require(RateLimiter.Rule.CHAT_MESSAGES_PER_MINUTE, String.valueOf(userId));
        rateLimiter.require(RateLimiter.Rule.CHAT_MESSAGES_PER_DAY, String.valueOf(userId));
        return userId;
    }

    // Preserved for fallback — prefer GET /stream for new clients
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        // SECURITY: never trust a client-supplied userId — personalization must
        // follow the authenticated principal.
        ChatRequest bound = new ChatRequest(request.message(), countMessage(request.message()),
                sessionOrDefault(request.sessionId()), request.generateTitle());
        ChatResponse response = chatService.processChat(bound);
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam String message,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "false") boolean generateTitle) {
        // SECURITY: the userId query param is ignored — the chat tools read the
        // real portfolio of whichever user the request binds, so it must always
        // be the authenticated principal, never client input.
        ChatRequest request = new ChatRequest(message, countMessage(message),
                sessionOrDefault(sessionId), generateTitle);
        return chatService.streamChat(request);
    }

    /**
     * A request with no session id goes to the caller's own default conversation. The name is the
     * same for everyone, and that is safe: every message lookup is by user AND session, so one
     * user's "default-session" is a different conversation from another's.
     */
    private static String sessionOrDefault(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? "default-session" : sessionId;
    }

    /**
     * Phase B agentic: execute a FRED-proposed action. Only fires on an explicit
     * Confirm tap in the UI; the model itself can never call this.
     */
    @PostMapping("/action")
    public ResponseEntity<java.util.Map<String, Object>> executeAction(
            @RequestBody com.investingapp.backend.dto.ChatActionRequest request) {
        Long authenticatedUserId = authenticatedUserId();
        if (authenticatedUserId == null) {
            return ResponseEntity.status(401).build();
        }
        var result = chatService.executeChatAction(authenticatedUserId, request.sessionId(),
                request.action(), request.params());
        return ResponseEntity.ok(java.util.Map.of(
                "success", result.success(),
                "message", result.message()));
    }

    private Long authenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserDetailsImpl userDetails) {
            return userDetails.getId();
        }
        return null;
    }

    @GetMapping("/history")
    public ResponseEntity<List<ChatMessage>> getHistory(
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) Long userId) {
        // SECURITY: both params used to be trusted verbatim — a guessed sessionId
        // or arbitrary userId returned someone else's conversation. History is
        // now always scoped to the authenticated principal (params kept for API
        // compatibility; userId is ignored).
        Long authenticatedUserId = authenticatedUserId();
        if (authenticatedUserId == null) {
            return ResponseEntity.status(401).build();
        }
        rateLimiter.require(RateLimiter.Rule.CHAT_READS_PER_MINUTE, String.valueOf(authenticatedUserId));
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            return ResponseEntity.ok(chatService.getChatHistoryForUser(sessionId, authenticatedUserId));
        }
        return ResponseEntity.ok(chatService.getRecentSessionHistory(authenticatedUserId));
    }

    /**
     * Phase 3: Get personalized daily suggestions for authenticated user.
     * Blends: 1 LLM + 1 popular + 1 personalized (or "What's your story FRED?" for
     * first-time users).
     */
    @GetMapping("/suggestions")
    public ResponseEntity<List<String>> getSuggestions() {
        // SECURITY: this takes no userId on purpose. Older clients still send one in the query;
        // it is not bound, so whatever they send is ignored. The suggestions are personalized,
        // so they always follow the authenticated principal, never client input.
        Long authenticatedUserId = authenticatedUserId();
        if (authenticatedUserId == null) {
            return ResponseEntity.status(401).build();
        }
        rateLimiter.require(RateLimiter.Rule.CHAT_READS_PER_MINUTE, String.valueOf(authenticatedUserId));
        return ResponseEntity.ok(dailyQuestionService.getTodayQuestionsForUser(authenticatedUserId));
    }
}
