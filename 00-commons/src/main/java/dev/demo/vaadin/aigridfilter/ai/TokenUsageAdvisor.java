package dev.demo.vaadin.aigridfilter.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/** Counts tokens and latency per model call; passed to {@code ChatClient.prompt().advisors(...)}. */
@Component
public class TokenUsageAdvisor implements CallAdvisor {

    private static final Logger logger = LoggerFactory.getLogger(TokenUsageAdvisor.class);

    private long requests;
    private long promptTokens;
    private long completionTokens;
    private long totalTokens;
    private long durationMillis;

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.nanoTime();
        ChatClientResponse response = chain.nextCall(request);
        record(queryOf(request), usageOf(response), (System.nanoTime() - start) / 1_000_000);
        return response;
    }

    /** Zeroes the totals, so a fresh batch of requests starts from scratch. */
    public synchronized void reset() {
        requests = 0;
        promptTokens = 0;
        completionTokens = 0;
        totalTokens = 0;
        durationMillis = 0;
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

    private synchronized void record(String query, Usage usage, long millis) {
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
