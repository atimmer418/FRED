package com.investingapp.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonMissing;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.WebSearchTool20250305;
import com.anthropic.models.messages.WebSearchResultBlock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class LLMService {

    private static final Logger logger = LoggerFactory.getLogger(LLMService.class);
    // Shared budget for thinking + visible text on Claude — keep generous so
    // adaptive thinking can't starve the answer
    private static final long CHAT_MAX_TOKENS = 16000L;
    private static final long GROUNDED_SUMMARY_MAX_TOKENS = 4096L;
    private static final long GROUNDED_SUMMARY_MAX_SEARCHES = 5L;
    private static final long HEARTBEAT_INTERVAL_MS = 10_000L;
    // Read-tools loop bound: enough for lookup -> follow-up lookup -> answer
    private static final int MAX_TOOL_ROUNDS = 4;

    private final RestClient restClient;

    @Value("${openai.api-key}")
    private String apiKey;

    @Value("${anthropic.api-key}")
    private String anthropicApiKey;

    @Value("${anthropic.chat-model:claude-sonnet-5}")
    private String anthropicChatModel;

    @Value("${openai.embedding-model:text-embedding-3-large}")
    private String embeddingModel;

    private volatile AnthropicClient anthropicClient;

    /** Timer-driven SSE keepalive — stream events alone can go silent for 10-60s
     *  (adaptive thinking with omitted display, tool execution between rounds). */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chat-sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public LLMService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .baseUrl("https://api.openai.com/v1")
                .build();
    }

    /** Lazy so the app can boot without ANTHROPIC_API_KEY; chat calls fail with a clear message. */
    private AnthropicClient anthropic() {
        if (anthropicClient == null) {
            synchronized (this) {
                if (anthropicClient == null) {
                    if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
                        throw new IllegalStateException(
                                "ANTHROPIC_API_KEY is not set; it is required for FRED chat (Claude)");
                    }
                    anthropicClient = AnthropicOkHttpClient.builder()
                            .apiKey(anthropicApiKey)
                            .build();
                }
            }
        }
        return anthropicClient;
    }

    // Embeddings stay on OpenAI — Anthropic has no embeddings API (used by RAGService)
    public List<Float> getEmbedding(String text) {
        // Flatten newlines as recommended for embeddings
        String cleanText = text.replace("\n", " ");

        var request = Map.of(
                "model", embeddingModel,
                "input", cleanText);

        logger.debug("Generating embedding for text: {}",
                cleanText.substring(0, Math.min(cleanText.length(), 50)) + "...");

        EmbeddingResponse response = restClient.post()
                .uri("/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(EmbeddingResponse.class);

        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new RuntimeException("Failed to generate embedding");
        }

        return response.data().get(0).embedding();
    }

    /**
     * Non-streaming completion. Runs WITHOUT thinking: this path serves quick
     * utility calls (session titles, daily questions, analytics) where latency
     * matters more than reasoning depth. The user-facing chat streams.
     */
    public String generateChatResponse(List<ChatMessage> messages) {
        Message message = anthropic().messages().create(toAnthropicParams(messages, false));

        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());

        String stopReason = message.stopReason().map(Object::toString).orElse("none");
        if (text.isEmpty()) {
            throw new RuntimeException("Failed to generate chat response (stop_reason: " + stopReason + ")");
        }
        if (message.stopReason().filter(StopReason.MAX_TOKENS::equals).isPresent()) {
            logger.warn("Chat response truncated at max_tokens ({}): {}...", CHAT_MAX_TOKENS,
                    text.substring(0, Math.min(text.length(), 80)));
        }
        return text;
    }

    /**
     * One-shot, non-streaming completion grounded with the Anthropic server-side
     * web search tool. Used by the Monthly Market Breakdown — the model searches
     * for real events (Claude alone cannot know last month's news) and the
     * citations are returned for the audit trail. No thinking: utility path.
     */
    public GroundedSummary generateGroundedMarketSummary(String systemPrompt, String userPrompt) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(anthropicChatModel)
                .maxTokens(GROUNDED_SUMMARY_MAX_TOKENS)
                .system(systemPrompt)
                .addUserMessage(userPrompt)
                .addTool(ToolUnion.ofWebSearchTool20250305(WebSearchTool20250305.builder()
                        .maxUses(GROUNDED_SUMMARY_MAX_SEARCHES)
                        .build()))
                .build();

        Message message = anthropic().messages().create(params);

        String text = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(Collectors.joining());

        List<GroundedSummary.Source> sources = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        for (ContentBlock block : message.content()) {
            block.webSearchToolResult().ifPresent(result ->
                    result.content().resultBlocks().ifPresent(list -> {
                        for (WebSearchResultBlock res : list) {
                            if (seenUrls.add(res.url())) {
                                sources.add(new GroundedSummary.Source(res.url(), res.title()));
                            }
                        }
                    }));
        }

        String stopReason = message.stopReason().map(Object::toString).orElse("none");
        if (text.isBlank()) {
            throw new RuntimeException("Grounded summary came back empty (stop_reason: " + stopReason + ")");
        }
        logger.info("Grounded market summary generated: {} chars, {} sources", text.length(), sources.size());
        return new GroundedSummary(text, anthropicChatModel, sources);
    }

    /** Result of a grounded generation: narrative text + the searched sources. */
    public static class GroundedSummary {
        public final String text;
        public final String model;
        public final List<Source> sources;

        public GroundedSummary(String text, String model, List<Source> sources) {
            this.text = text;
            this.model = model;
            this.sources = sources;
        }

        public static class Source {
            public final String url;
            public final String title;

            public Source(String url, String title) {
                this.url = url;
                this.title = title;
            }
        }
    }

    public void streamChatResponse(List<ChatMessage> messages, Consumer<String> tokenCallback) throws Exception {
        streamChatResponse(messages, tokenCallback, null);
    }

    /**
     * Streams a chat response, optionally with read-only tools (Phase A agentic).
     * When Claude requests a tool, the loop executes it and continues streaming —
     * the client just sees one continuous answer.
     */
    public void streamChatResponse(List<ChatMessage> messages, Consumer<String> tokenCallback,
            FredChatToolsService.BoundTools tools) throws Exception {
        MessageCreateParams.Builder paramsBuilder = toAnthropicParamsBuilder(messages, true);
        if (tools != null) {
            for (Tool tool : tools.definitions()) {
                paramsBuilder.addTool(tool);
            }
        }
        MessageCreateParams params = paramsBuilder.build();

        // All emits (text, notes, heartbeat) synchronize on this lock — the
        // heartbeat runs on its own thread and SseEmitter is not thread-safe.
        Object emitLock = new Object();
        AtomicLong lastEmit = new AtomicLong(System.currentTimeMillis());
        boolean[] textStreamed = { false };

        Consumer<String> emit = text -> {
            synchronized (emitLock) {
                tokenCallback.accept(text);
            }
            lastEmit.set(System.currentTimeMillis());
        };

        // Timer-driven keepalive: fires during TRUE silence (adaptive thinking
        // with omitted display, tool execution between rounds) when no stream
        // events arrive at all. The frontend ignores empty tokens; failures here
        // mean the emitter is already dead — the stream thread will notice.
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (System.currentTimeMillis() - lastEmit.get() > HEARTBEAT_INTERVAL_MS) {
                try {
                    emit.accept("");
                } catch (Exception ignored) {
                }
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS / 2, TimeUnit.MILLISECONDS);

        StopReason finalStop = null;
        try {
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                MessageAccumulator accumulator = MessageAccumulator.create();
                AtomicReference<StopReason> stopRef = new AtomicReference<>();

                try (StreamResponse<RawMessageStreamEvent> stream =
                        anthropic().messages().createStreaming(params)) {
                    stream.stream().forEach(event -> {
                        accumulator.accumulate(event);
                        event.contentBlockDelta().flatMap(deltaEvent -> deltaEvent.delta().text())
                                .ifPresent(textDelta -> {
                                    if (!textDelta.text().isEmpty()) {
                                        textStreamed[0] = true;
                                    }
                                    emit.accept(textDelta.text());
                                });
                        event.messageDelta().ifPresent(md -> md.delta().stopReason().ifPresent(stopRef::set));
                    });
                } catch (Exception e) {
                    // A later round failing (429/529/timeout) after text already
                    // reached the client should degrade gracefully, not blow away
                    // the visible answer with an SSE error.
                    if (round > 0 && textStreamed[0]) {
                        logger.error("Tool-loop round {} failed after text streamed — closing gracefully", round, e);
                        emit.accept("\n\n*…I hit a snag finishing that thought. Ask again and I'll pick it up.*");
                        return;
                    }
                    throw e;
                }

                finalStop = stopRef.get();
                if (tools == null || finalStop == null || !StopReason.TOOL_USE.equals(finalStop)) {
                    break;
                }
                if (round == MAX_TOOL_ROUNDS - 1) {
                    // Out of rounds with data requests still pending — never end
                    // silently mid-thought (and don't waste the tool executions).
                    logger.warn("Chat tool loop exhausted {} rounds still requesting tools", MAX_TOOL_ROUNDS);
                    emit.accept("\n\n*…that took more digging than I have room for. Ask me to continue and I'll finish the thought.*");
                    break;
                }

                // Claude asked for data — execute the requested tools and continue.
                // Sanitize FIRST: the echo and the execution must see identical blocks.
                Message assistantMessage = sanitizeForEcho(accumulator.message());
                List<ContentBlockParam> toolResults = new ArrayList<>();
                for (ContentBlock block : assistantMessage.content()) {
                    block.toolUse().ifPresent(toolUse -> {
                        String result;
                        try {
                            result = tools.execute(toolUse.name(), toolUse._input());
                            logger.info("Chat tool executed: {}", toolUse.name());
                        } catch (Exception e) {
                            logger.error("Chat tool {} threw", toolUse.name(), e);
                            result = "{\"error\": \"tool execution failed\"}";
                        }
                        toolResults.add(ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                        .toolUseId(toolUse.id())
                                        .content(result)
                                        .build()));
                    });
                }
                if (toolResults.isEmpty()) {
                    break;
                }

                // Rounds are separate API messages — the model won't add joining
                // whitespace between its pre-tool narration and the continuation.
                if (textStreamed[0]) {
                    emit.accept("\n\n");
                }

                MessageCreateParams.Builder next = params.toBuilder()
                        .addMessage(assistantMessage)
                        .addMessage(MessageParam.builder()
                                .role(MessageParam.Role.USER)
                                .contentOfBlockParams(toolResults)
                                .build());
                if (round == MAX_TOOL_ROUNDS - 2) {
                    // Next round is the last: force a text answer so the loop
                    // can't end on another tool request.
                    next.toolChoice(ToolChoiceNone.builder().build());
                }
                params = next.build();
            }
        } finally {
            heartbeat.cancel(false);
        }

        if (finalStop != null && StopReason.REFUSAL.equals(finalStop)) {
            // A refusal is a content outcome, not an infrastructure failure —
            // finish the stream in-character so the turn persists and completes.
            logger.warn("Claude declined to answer (stop_reason: refusal)");
            emit.accept((textStreamed[0] ? "\n\n" : "")
                    + "*That one's outside what I can help with. Let's keep it to your investing journey.*");
            return;
        }
        if (finalStop != null && StopReason.MAX_TOKENS.equals(finalStop)) {
            logger.warn("Streamed chat response truncated at max_tokens ({})", CHAT_MAX_TOKENS);
            emit.accept("\n\n*…I ran out of room. Ask me to continue and I'll pick up from there.*");
        }
    }

    /**
     * Streaming accumulation leaves tool_use.input MISSING when the call has no
     * arguments — the API sends no input_json_delta events for `{}`, so the
     * accumulator never sets the field, and echoing the message back fails
     * validation ("tool_use.input: Field required"). Fill in an explicit {}.
     */
    private static Message sanitizeForEcho(Message message) {
        List<ContentBlock> fixed = message.content().stream()
                .map(block -> block.toolUse()
                        .filter(toolUse -> toolUse._input() instanceof JsonMissing)
                        .map(toolUse -> ContentBlock.ofToolUse(
                                toolUse.toBuilder().input(JsonValue.from(Map.of())).build()))
                        .orElse(block))
                .collect(Collectors.toList());
        return message.toBuilder().content(fixed).build();
    }

    /**
     * Bridge from the OpenAI-style role list ChatService builds to the Anthropic
     * Messages shape:
     *  - "system" entries fold into the top-level system prompt (in order)
     *  - leading assistant turns are dropped (first message must be user)
     *  - consecutive same-role turns are coalesced (orphaned user rows from a
     *    failed stream would otherwise 400 the whole session)
     *  - a trailing assistant turn is dropped (treated as prefill — rejected
     *    on claude-opus-4-8)
     */
    private MessageCreateParams toAnthropicParams(List<ChatMessage> messages, boolean withThinking) {
        return toAnthropicParamsBuilder(messages, withThinking).build();
    }

    private MessageCreateParams.Builder toAnthropicParamsBuilder(List<ChatMessage> messages, boolean withThinking) {
        StringBuilder system = new StringBuilder();
        List<ChatMessage> turns = new ArrayList<>();

        for (ChatMessage m : messages) {
            String content = m.content() == null ? "" : m.content();
            if (content.isBlank()) continue;

            if ("system".equals(m.role())) {
                if (system.length() > 0) system.append("\n\n");
                system.append(content);
                continue;
            }

            String role = "assistant".equals(m.role()) ? "assistant" : "user";
            if (turns.isEmpty() && "assistant".equals(role)) continue;
            ChatMessage prev = turns.isEmpty() ? null : turns.get(turns.size() - 1);
            if (prev != null && prev.role().equals(role)) {
                turns.set(turns.size() - 1, new ChatMessage(role, prev.content() + "\n\n" + content));
            } else {
                turns.add(new ChatMessage(role, content));
            }
        }

        if (!turns.isEmpty() && "assistant".equals(turns.get(turns.size() - 1).role())) {
            turns.remove(turns.size() - 1);
        }
        if (turns.isEmpty()) {
            throw new IllegalArgumentException("No non-empty user message to send to Claude");
        }

        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(anthropicChatModel)
                .maxTokens(CHAT_MAX_TOKENS);
        if (withThinking) {
            builder.thinking(ThinkingConfigAdaptive.builder().build());
        } else {
            // Must be EXPLICIT: on claude-sonnet-5, omitting `thinking` runs
            // adaptive thinking by default — the opposite of what the fast
            // utility paths (titles, daily questions, analytics) want.
            // (Note: an explicit disabled would 400 on claude-fable-5.)
            builder.thinking(ThinkingConfigDisabled.builder().build());
        }
        for (ChatMessage t : turns) {
            if ("assistant".equals(t.role())) {
                builder.addAssistantMessage(t.content());
            } else {
                builder.addUserMessage(t.content());
            }
        }
        if (system.length() > 0) builder.system(system.toString());
        return builder;
    }

    // DTOs
    public record ChatMessage(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(List<EmbeddingData> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingData(List<Float> embedding) {
    }
}
