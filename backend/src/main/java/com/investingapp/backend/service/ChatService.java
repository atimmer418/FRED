package com.investingapp.backend.service;

import com.investingapp.backend.dto.ChatRequest;
import com.investingapp.backend.dto.ChatResponse;
import com.investingapp.backend.config.FredConstitution; // Restore Import
import com.investingapp.backend.dto.SafetyResult; // Import
// Import
import com.investingapp.backend.dto.SafetyVerdict; // Import
import com.investingapp.backend.config.FredRedirections; // Import
import com.investingapp.backend.model.ChatMessage; // Import
import com.investingapp.backend.model.User;
import com.investingapp.backend.repository.ChatMessageRepository; // Import
import com.investingapp.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Collections; // Import
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    private final UserRepository userRepository;
    private final ChatMessageRepository chatMessageRepository; // Inject
    private final RAGService ragService;
    private final LLMService llmService;
    private final SafetyService safetyService; // Inject
    private final ConversationAnalyticsService analyticsService; // NEW: Phase 2A
    private final FredChatToolsService fredChatToolsService; // Phase A agentic: read-only chat tools
    private final FredChatActionService fredChatActionService; // Phase B agentic: confirmed mutations

    /** Lenient trigger matcher — the model occasionally invents tag variants
     *  (e.g. {{TRIGGER_FRED_STORY}}), and a raw tag must never reach the user. */
    private static final java.util.regex.Pattern FRED_TRIGGER_PATTERN =
            java.util.regex.Pattern.compile("\\{\\{TRIGGER_FRED_[A-Z_]+\\}\\}");

    /**
     * Emits everything that cannot be part of a trigger tag; keeps a possibly
     * open tag in the buffer. Flips intercepted (and clears) on a full match.
     */
    private static String drainSendable(StringBuilder holdback, boolean[] intercepted) {
        StringBuilder out = new StringBuilder();
        while (holdback.length() > 0) {
            String pending = holdback.toString();
            int open = pending.indexOf("{{");
            if (open == -1) {
                int keep = pending.endsWith("{") ? 1 : 0; // '{{' may split across tokens
                out.append(pending, 0, pending.length() - keep);
                holdback.delete(0, pending.length() - keep);
                break;
            }
            if (open > 0) {
                out.append(pending, 0, open);
                holdback.delete(0, open);
                continue;
            }
            int close = pending.indexOf("}}", 2);
            if (close == -1) {
                if (pending.length() > 64) { // too long to be a trigger tag
                    out.append(pending);
                    holdback.setLength(0);
                }
                break; // keep holding
            }
            String tag = pending.substring(0, close + 2);
            if (FRED_TRIGGER_PATTERN.matcher(tag).matches()) {
                intercepted[0] = true;
                holdback.setLength(0);
                break;
            }
            out.append(tag);
            holdback.delete(0, close + 2);
        }
        return out.toString();
    }

    /**
     * The canned reply that replaces a story trigger token. The model never
     * writes this turn, so the follow-up question and quick-reply chips it
     * would normally append are attached here (see FredConstitution).
     */
    static String canonicalStoryReply(String tag) {
        return tag.contains("STRATEGY")
                ? FredConstitution.FRED_STORY_STRATEGY + FredConstitution.FRED_STORY_STRATEGY_FOLLOWUP
                : FredConstitution.FRED_STORY_ORIGIN + FredConstitution.FRED_STORY_ORIGIN_FOLLOWUP;
    }

    private static final String TOOLS_UNAVAILABLE_NOTE =
            "Note: your data-lookup tools are NOT available on this request. Do not claim you can "
            + "look up the user's portfolio or schedule. Guide them to the relevant app tab instead.";

    public ChatService(UserRepository userRepository, ChatMessageRepository chatMessageRepository,
            RAGService ragService, LLMService llmService, SafetyService safetyService,
            ConversationAnalyticsService analyticsService, FredChatToolsService fredChatToolsService,
            FredChatActionService fredChatActionService) {
        this.userRepository = userRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.ragService = ragService;
        this.llmService = llmService;
        this.safetyService = safetyService;
        this.analyticsService = analyticsService; // NEW: Phase 2A
        this.fredChatToolsService = fredChatToolsService;
        this.fredChatActionService = fredChatActionService;
    }

    /**
     * Phase B: execute a FRED-proposed action after the user's Confirm tap.
     * The outcome is also persisted as an assistant message so the next model
     * turn (and a reloaded chat) sees what actually happened.
     */
    public FredChatActionService.ActionResult executeChatAction(Long userId, String sessionId,
            String action, com.fasterxml.jackson.databind.JsonNode params) {
        User user = userId != null ? userRepository.findById(userId).orElse(null) : null;
        if (user == null) {
            return new FredChatActionService.ActionResult(false, "User not found");
        }
        FredChatActionService.ActionResult result = fredChatActionService.execute(user, action, params, sessionId);
        if (sessionId != null && !sessionId.isBlank()) {
            try {
                // ✅/⚠️ prefix marks this as a SYSTEM outcome record — the
                // constitution tells the model these are not its own words, so
                // history can't teach it to claim executions itself
                String prefix = result.success() ? "✅ " : "⚠️ ";
                chatMessageRepository.save(new ChatMessage(userId, sessionId, "assistant", prefix + result.message()));
            } catch (Exception e) {
                logger.error("Failed to persist action outcome for session {}", sessionId, e);
            }
        }
        return result;
    }

    public ChatResponse processChat(ChatRequest request) {
        // 0. Safety Check
        SafetyResult safety = safetyService.check(request.message());

        // Log Safety Decision
        logger.info("Safety Check - User: {}, Verdict: {}, Reason: {}, Query: \"{}\"",
                request.userId(), safety.verdict(), safety.reason(), request.message());

        if (safety.verdict() != com.investingapp.backend.dto.SafetyVerdict.SAFE) {
            String redirectionContent = FredRedirections.get(safety.reason());

            // Persist the user message and the redirection response so history stays in
            // sync
            chatMessageRepository
                    .save(new ChatMessage(request.userId(), request.sessionId(), "user", request.message()));
            chatMessageRepository
                    .save(new ChatMessage(request.userId(), request.sessionId(), "assistant", redirectionContent));

            return new ChatResponse(redirectionContent, null);
        }

        // 1. Persist user message
        chatMessageRepository.save(new ChatMessage(request.userId(), request.sessionId(), "user", request.message()));

        // 2. NEW Phase 2B: Track follow-up patterns (before logging question)
        if (request.userId() != null && request.sessionId() != null) {
            try {
                analyticsService.trackFollowUpPattern(request.sessionId(), request.message());
            } catch (Exception e) {
                logger.error("Failed to track follow-up pattern", e);
                // Continue even if follow-up tracking fails
            }
        }

        // 2. Load User (guard null userId for guest/anonymous, matching streamChat)
        User user = request.userId() != null ? userRepository.findById(request.userId()).orElse(null) : null;

        // 3. Load System Prompt
        String systemPrompt = FredConstitution.SYSTEM_PROMPT;

        // 4. Build User Context
        String userContext = buildUserContext(user);

        // 5. Retrieve RAG Chunks
        List<String> ragChunks = ragService.retrieveChunks(request.message());
        String ragContext = formatRagChunks(ragChunks);

        // 6. Load History (Top 10 most recent)
        List<ChatMessage> historyEntities = chatMessageRepository
                .findTop10BySessionIdOrderByCreatedAtDesc(request.sessionId());
        // Reverse to Chronological Order
        List<ChatMessage> chronologicalHistory = new ArrayList<>(historyEntities);
        Collections.reverse(chronologicalHistory);

        // 7. Assemble Prompt
        List<LLMService.ChatMessage> messages = new ArrayList<>();
        // System Layer
        messages.add(new LLMService.ChatMessage("system", systemPrompt));
        if (!userContext.isEmpty()) {
            messages.add(new LLMService.ChatMessage("system", userContext));
        }
        if (!ragContext.isEmpty()) {
            messages.add(new LLMService.ChatMessage("system", "Relevant FRED knowledge:\n" + ragContext));
        }

        // History + Current Message (Current message is likely in historyEntities if
        // saved first)
        // If save() was synchronous and fast, it might be in Top 10.
        // To be safe and deterministic, let's explicitly add the CURRENT message if
        // it's missing,
        // OR rely on history.
        // ISSUE: If user sends 11th message, history has 10. Does it have the current
        // one?
        // findTop10 Sort By Desc -> Current is #1.
        // So chronologicalHistory has [Oldest ... Current].
        // This is perfect.

        for (ChatMessage h : chronologicalHistory) {
            messages.add(new LLMService.ChatMessage(h.getRole(), h.getContent()));
        }

        // This non-streaming fallback path never binds tools — keep Claude honest
        messages.add(new LLMService.ChatMessage("system", TOOLS_UNAVAILABLE_NOTE));

        // 8. Call LLM
        String response = llmService.generateChatResponse(messages);

        // 8.5 SMART INTERCEPT FOR FRED STORY
        if (response != null) {
            java.util.regex.Matcher triggerMatch = FRED_TRIGGER_PATTERN.matcher(response);
            if (triggerMatch.find()) {
                String tag = triggerMatch.group();
                logger.info("Smart Trigger: matched {} — swapping with canonical text.", tag);
                response = canonicalStoryReply(tag);
            }
        }

        // NEW: Phase 2A - Log conversation analytics
        if (request.userId() != null && response != null) {
            try {
                analyticsService.logUserQuestion(
                        request.userId(),
                        request.sessionId(),
                        request.message(),
                        response.length());
            } catch (Exception e) {
                logger.error("Failed to log conversation analytics", e);
                // Continue with chat response even if analytics fails
            }
        }

        // 9. Persistence (Assistant Response)
        chatMessageRepository.save(new ChatMessage(request.userId(), request.sessionId(), "assistant", response));

        // 10. Log interaction (Basic logging for now)
        logger.info("Chat interaction - User: {}, Query: {}, RAG Chunks Used: {}",
                request.userId(), request.message(), ragChunks.size());

        // 11. Generate Title if requested
        String title = null;
        if (request.generateTitle()) {
            title = generateTitle(titleSource(request));
        }

        return new ChatResponse(response, title);
    }

    public SseEmitter streamChat(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);
        ObjectMapper mapper = new ObjectMapper();

        // The client closing the app must never abort the generation: once a
        // message is sent, the reply is completed and persisted server-side so
        // it's waiting in history when the app reopens. This flag only stops
        // further SSE writes.
        boolean[] clientGone = { false };
        emitter.onTimeout(() -> clientGone[0] = true);
        emitter.onError(t -> clientGone[0] = true);

        // 0. Safety Check
        SafetyResult safety = safetyService.check(request.message());
        logger.info("Safety Check (stream) - User: {}, Verdict: {}, Reason: {}, Query: \"{}\"",
                request.userId(), safety.verdict(), safety.reason(), request.message());

        if (safety.verdict() != SafetyVerdict.SAFE) {
            String redirectionContent = FredRedirections.get(safety.reason());
            chatMessageRepository.save(new ChatMessage(request.userId(), request.sessionId(), "user", request.message()));
            chatMessageRepository.save(new ChatMessage(request.userId(), request.sessionId(), "assistant", redirectionContent));
            try {
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(Map.of("token", redirectionContent))));
                emitter.send(SseEmitter.event().data(mapper.writeValueAsString(Map.of("done", true))));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        // 1. Persist user message
        chatMessageRepository.save(new ChatMessage(request.userId(), request.sessionId(), "user", request.message()));

        // 2. Track follow-up patterns
        if (request.userId() != null && request.sessionId() != null) {
            try {
                analyticsService.trackFollowUpPattern(request.sessionId(), request.message());
            } catch (Exception e) {
                logger.error("Failed to track follow-up pattern", e);
            }
        }

        // 3. Load User
        User user = request.userId() != null ? userRepository.findById(request.userId()).orElse(null) : null;

        // 4. Build prompt
        String systemPrompt = FredConstitution.SYSTEM_PROMPT;
        String userContext = buildUserContext(user);
        List<String> ragChunks = ragService.retrieveChunks(request.message());
        String ragContext = formatRagChunks(ragChunks);

        List<ChatMessage> historyEntities = chatMessageRepository
                .findTop10BySessionIdOrderByCreatedAtDesc(request.sessionId());
        List<ChatMessage> chronologicalHistory = new ArrayList<>(historyEntities);
        Collections.reverse(chronologicalHistory);

        List<LLMService.ChatMessage> messages = new ArrayList<>();
        messages.add(new LLMService.ChatMessage("system", systemPrompt));
        if (!userContext.isEmpty()) {
            messages.add(new LLMService.ChatMessage("system", userContext));
        }
        if (!ragContext.isEmpty()) {
            messages.add(new LLMService.ChatMessage("system", "Relevant FRED knowledge:\n" + ragContext));
        }
        for (ChatMessage h : chronologicalHistory) {
            messages.add(new LLMService.ChatMessage(h.getRole(), h.getContent()));
        }

        // 5. Stream on async thread — with read-only tools bound to this user
        FredChatToolsService.BoundTools boundTools = (user != null) ? fredChatToolsService.forUser(user) : null;
        if (boundTools == null) {
            // The constitution advertises tools; make sure Claude doesn't promise
            // lookups it can't perform on this request
            messages.add(new LLMService.ChatMessage("system", TOOLS_UNAVAILABLE_NOTE));
        }
        CompletableFuture.runAsync(() -> {
            StringBuilder fullResponse = new StringBuilder();
            // Trigger tokens must NEVER reach the client raw — hold back any text
            // that might be inside a {{...}} tag until it resolves. On a match the
            // rest of the stream is swallowed and the canonical story is sent whole.
            StringBuilder holdback = new StringBuilder();
            boolean[] intercepted = { false };
            try {
                llmService.streamChatResponse(messages, token -> {
                    fullResponse.append(token);
                    if (intercepted[0] || token.isEmpty() && holdback.length() == 0) {
                        if (token.isEmpty() && !intercepted[0]) {
                            // heartbeat — forward so the connection stays alive
                            sendSafe(emitter, mapper, Map.of("token", ""), clientGone);
                        }
                        return;
                    }
                    holdback.append(token);
                    String sendable = drainSendable(holdback, intercepted);
                    if (!sendable.isEmpty()) {
                        sendSafe(emitter, mapper, Map.of("token", sendable), clientGone);
                    }
                }, boundTools);

                // 6. Apply smart intercepts to the assembled response (lenient:
                // the model sometimes invents variants like {{TRIGGER_FRED_STORY}})
                String assembled = fullResponse.toString();
                java.util.regex.Matcher triggerMatch = FRED_TRIGGER_PATTERN.matcher(assembled);
                if (triggerMatch.find()) {
                    String tag = triggerMatch.group();
                    logger.info("Smart Trigger (stream): matched {} — swapping canonical text.", tag);
                    assembled = canonicalStoryReply(tag);
                    // The raw tag was suppressed client-side — deliver the story
                    sendSafe(emitter, mapper, Map.of("token", assembled), clientGone);
                } else if (holdback.length() > 0 && !intercepted[0]) {
                    // Flush any innocent tail still held back
                    sendSafe(emitter, mapper, Map.of("token", holdback.toString()), clientGone);
                    holdback.setLength(0);
                }

                // 7. Persist assistant response
                chatMessageRepository.save(new ChatMessage(request.userId(), request.sessionId(), "assistant", assembled));

                // 8. Analytics
                if (request.userId() != null) {
                    try {
                        analyticsService.logUserQuestion(request.userId(), request.sessionId(),
                                request.message(), assembled.length());
                    } catch (Exception e) {
                        logger.error("Failed to log conversation analytics", e);
                    }
                }

                logger.info("Stream chat interaction - User: {}, Query: {}, RAG Chunks Used: {}",
                        request.userId(), request.message(), ragChunks.size());

                // 9. Generate title if requested — a title failure must never
                // kill the stream (the done event still has to reach the client)
                String title = null;
                if (request.generateTitle() && !clientGone[0]) {
                    try {
                        title = generateTitle(titleSource(request));
                    } catch (Exception e) {
                        logger.error("Title generation failed — sending done without title", e);
                    }
                }

                // 10. Send done event
                Map<String, Object> donePayload = title != null
                        ? Map.of("done", true, "title", title)
                        : Map.of("done", true);
                sendSafe(emitter, mapper, donePayload, clientGone);
                if (!clientGone[0]) {
                    try {
                        emitter.complete();
                    } catch (Exception ignored) {
                        // client raced us to disconnect — reply is already persisted
                    }
                }

            } catch (Exception e) {
                logger.error("Stream chat error", e);
                if (!clientGone[0]) {
                    emitter.completeWithError(e);
                }
            }
        });

        return emitter;
    }

    /** Send an SSE event unless the client has disconnected. A failed send
     *  flips the flag and is swallowed: the generation and persistence behind
     *  it must run to completion so the reply is in history when the app
     *  reopens — only the writes stop. */
    private void sendSafe(SseEmitter emitter, ObjectMapper mapper, Map<String, ?> payload, boolean[] clientGone) {
        if (clientGone[0]) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(mapper.writeValueAsString(payload)));
        } catch (Exception e) {
            clientGone[0] = true;
            logger.info("SSE client disconnected mid-stream — finishing generation detached");
        }
    }

    /** Titles should describe the conversation, not whatever message happened
     *  to carry the generateTitle flag. The flag is re-sent until a session has
     *  a real title, so after an interrupted first exchange it can arrive on a
     *  throwaway follow-up ("↑") — prefer the session's opening question. */
    private String titleSource(ChatRequest request) {
        return chatMessageRepository
                .findTopBySessionIdAndRoleOrderByCreatedAtAsc(request.sessionId(), "user")
                .map(ChatMessage::getContent)
                .filter(content -> content != null && !content.isBlank())
                .orElse(request.message());
    }

    private String generateTitle(String userMessage) {
        List<LLMService.ChatMessage> messages = new ArrayList<>();
        messages.add(new LLMService.ChatMessage("system",
                "Generate a concise 3-5 word title for the following user question. "
                + "If the text is short, vague, or symbolic, still invent a brief descriptive title for it. "
                + "Never ask for more information and never explain yourself. "
                + "Respond with the title only, in plain text: no quotes, no markdown, no asterisks, no trailing punctuation."));
        messages.add(new LLMService.ChatMessage("user", userMessage));
        String title = llmService.generateChatResponse(messages);
        // Belt-and-suspenders: strip any markdown/quote characters the model adds anyway
        title = title.replaceAll("[*_`\"'#]", "").trim();
        // A real title is a few words; a full sentence means the model went meta
        // ("I need the question text to...") — use the question itself instead
        if (title.isBlank() || title.split("\\s+").length > 8) {
            title = userMessage.length() > 30 ? userMessage.substring(0, 30).trim() + "..." : userMessage;
        }
        if (title.length() > 60) {
            title = title.substring(0, 60).trim();
        }
        return title;
    }

    public List<ChatMessage> getChatHistory(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<ChatMessage> history = chatMessageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
        Collections.reverse(history); // Return in chronological order
        return history;
    }

    /**
     * SECURITY: session-scoped history restricted to the requesting user —
     * session IDs are client-generated and must not act as bearer tokens for
     * another user's conversation.
     */
    public List<ChatMessage> getChatHistoryForUser(String sessionId, Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        return getChatHistory(sessionId).stream()
                .filter(m -> userId.equals(m.getUserId()))
                .toList();
    }

    public List<ChatMessage> getRecentSessionHistory(Long userId) {
        if (userId == null)
            return Collections.emptyList();

        // 1. Find the MOST RECENT message for this user to get the latest sessionId
        Optional<ChatMessage> latestMsg = chatMessageRepository.findTopByUserIdOrderByCreatedAtDesc(userId);

        if (latestMsg.isPresent()) {
            String lastSessionId = latestMsg.get().getSessionId();
            return getChatHistory(lastSessionId);
        }

        return Collections.emptyList();
    }

    private String buildUserContext(User user) {
        if (user == null) {
            return "User context: Anonymous/Guest (No personalization)";
        }

        // Logic to construct safe context
        String age = (user.getAge() != null) ? user.getAge().toString() : "Unknown";
        String payFreq = (user.getPayFrequency() != null) ? user.getPayFrequency() : "Unknown";
        String automated = (user.getNextInvestmentDate() != null
                || (user.getMonthlyInvestment() != null && user.getMonthlyInvestment() > 0)) ? "yes" : "no";
        String contribution = (user.getMonthlyInvestment() != null && user.getMonthlyInvestment() > 0) ? "consistent"
                : "not started";
        String timeHorizon = (user.getTimeToFI() != null) ? user.getTimeToFI() : "long-term";
        String risk = user.getRiskTolerance();

        return String.format("""
                User context:
                - Age range: %s
                - Paycheck frequency: %s
                - Investing automated: %s
                - Contribution rate: %s
                - Time horizon: %s
                - Risk tolerance: %s

                Rules:
                • This profile contains no dollar figures. Never invent or infer them from it. Real figures come only from your tools (when available).
                • No advice requests embedded
                • No speculative inference
                """, age, payFreq, automated, contribution, timeHorizon, risk);
    }

    private String formatRagChunks(List<String> chunks) {
        if (chunks.isEmpty())
            return "";
        StringBuilder sb = new StringBuilder();
        for (String chunk : chunks) {
            sb.append("[").append(chunk).append("]\n\n");
        }
        return sb.toString();
    }
}
