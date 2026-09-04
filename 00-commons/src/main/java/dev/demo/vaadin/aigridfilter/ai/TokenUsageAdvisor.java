package dev.demo.vaadin.aigridfilter.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Counts tokens and latency per model call; passed to {@code ChatClient.prompt().advisors(...)}. */
@Component
public class TokenUsageAdvisor implements CallAdvisor {

    private static final Logger logger = LoggerFactory.getLogger(TokenUsageAdvisor.class);

    private long requests;
    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
    private long durationMillis;

    /** Bounded: a running app never calls {@link #reset()}, and this must not grow all day. */
    private static final int RECORDED_CALLS = 64;

    /** The most recent calls, oldest first; read per query by the benchmark. */
    private final List<Call> calls = new ArrayList<>();

    /**
     * One model call's numbers. {@code providerMetadata} carries the backend's own response metadata
     * verbatim (for Ollama e.g. {@code eval-count} / {@code eval-duration}), so this class stays
     * provider-agnostic and the reader decides what to make of it.
     */
    public record Call(String query, long promptTokens, long completionTokens, long totalTokens,
                       long durationMillis, Map<String, Object> providerMetadata) {
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.nanoTime();
        ChatClientResponse response = chain.nextCall(request);
        record(queryOf(request), usageOf(response), metadataOf(response),
                (System.nanoTime() - start) / 1_000_000);
        return response;
    }

    /** Zeroes the totals, so a fresh batch of requests starts from scratch. */
    public synchronized void reset() {
        requests = 0;
        promptTokens = 0;
        completionTokens = 0;
        totalTokens = 0;
        durationMillis = 0;
        calls.clear();
    }

    /** The individual calls since the last {@link #reset()}, at most the {@value #RECORDED_CALLS} newest. */
    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    /** Totals and per-request averages since the last {@link #reset()}. */
    public synchronized void logSummary(String label) {
        long averageTokens = requests == 0 ? 0 : Math.round((double) totalTokens / requests);
        long averageMillis = requests == 0 ? 0 : Math.round((double) durationMillis / requests);
        logger.info("Token summary [{}]: {} requests, {} total tokens (prompt={}, completion={}), "
                        + "avg {} tokens/request, {} ms total, avg {} ms/request",
                label, requests, totalTokens, promptTokens, completionTokens,
                averageTokens, durationMillis, averageMillis);
    }

    /** Total tokens (prompt + completion) recorded since the last {@link #reset()}. */
    public synchronized long totalTokens() {
        return totalTokens;
    }

    /** Number of chat requests completed since the last {@link #reset()}. */
    public synchronized long requestCount() {
        return requests;
    }

    private synchronized void record(String query, Usage usage, Map<String, Object> metadata, long millis) {
        if (usage == null) {
            return;
        }
        int prompt = orZero(usage.getPromptTokens());
        int completion = orZero(usage.getCompletionTokens());
        int total = usage.getTotalTokens() == null ? prompt + completion : usage.getTotalTokens();

        requests++;
        promptTokens += prompt;
        completionTokens += completion;
        totalTokens += total;
        durationMillis += millis;
        if (calls.size() == RECORDED_CALLS) {
            calls.removeFirst();
        }
        calls.add(new Call(query, prompt, completion, total, millis, metadata));

        logger.info("Token usage for '{}': prompt={}, completion={}, total={}, time={} ms",
                query, prompt, completion, total, millis);
    }

    /** The user's query, for the log line. */
    private static String queryOf(ChatClientRequest request) {
        var userMessage = request.prompt().getUserMessage();
        return userMessage == null ? "" : userMessage.getText();
    }

    /** {@code null} if the backend reported no usage at all, in which case there is nothing to record. */
    private static Usage usageOf(ChatClientResponse response) {
        var chatResponse = response.chatResponse();
        return chatResponse == null || chatResponse.getMetadata() == null
                ? null : chatResponse.getMetadata().getUsage();
    }

    /** The backend's response metadata as a plain map, so callers need no provider-specific types. */
    private static Map<String, Object> metadataOf(ChatClientResponse response) {
        var chatResponse = response.chatResponse();
        ChatResponseMetadata metadata = chatResponse == null ? null : chatResponse.getMetadata();
        if (metadata == null) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        metadata.keySet().forEach(key -> copy.put(key, metadata.get(key)));
        return copy;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    @Override
    public String getName() {
        return "tokenUsage";
    }

    /** Innermost, so the tool loop's follow-up calls are measured too — see the class Javadoc. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
