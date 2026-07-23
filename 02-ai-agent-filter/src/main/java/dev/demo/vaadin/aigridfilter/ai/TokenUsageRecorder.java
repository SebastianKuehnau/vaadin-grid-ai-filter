package dev.demo.vaadin.aigridfilter.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

/**
 * Records the token usage of each AI request: it logs a per-request line
 * (prompt / completion / total) and keeps running totals so a batch of requests (e.g. an
 * integration-test run) can print a summary of total, request count, and average tokens per request
 * at the end.
 * <p>
 * A single shared bean used both by the AI service (which records every request) and by the
 * integration tests (which reset it before a class and log the summary after all cases). Access is
 * synchronized because {@code CustomerListView} runs searches off the UI thread, so several sessions
 * may record concurrently.
 */
@Component
public class TokenUsageRecorder {

    private static final Logger logger = LoggerFactory.getLogger(TokenUsageRecorder.class);

    private long totalPromptTokens;
    private long totalCompletionTokens;
    private long totalTokens;
    private long requestCount;

    /** Logs the token usage of a single request and adds it to the running totals. */
    public synchronized void record(String query, Usage usage) {
        int prompt = orZero(usage.getPromptTokens());
        int completion = orZero(usage.getCompletionTokens());
        int total = orZero(usage.getTotalTokens());

        totalPromptTokens += prompt;
        totalCompletionTokens += completion;
        totalTokens += total;
        requestCount++;

        logger.info("Token usage for '{}': prompt={}, completion={}, total={}",
                query, prompt, completion, total);
    }

    /** Clears the running totals; call before a fresh batch of requests. */
    public synchronized void reset() {
        totalPromptTokens = 0;
        totalCompletionTokens = 0;
        totalTokens = 0;
        requestCount = 0;
    }

    /** Logs a summary of all recorded requests: total tokens, request count, and average per request. */
    public synchronized void logSummary(String label) {
        long average = requestCount == 0 ? 0 : Math.round((double) totalTokens / requestCount);
        logger.info("Token summary [{}]: {} requests, {} total tokens (prompt={}, completion={}), "
                        + "avg {} tokens/request",
                label, requestCount, totalTokens, totalPromptTokens, totalCompletionTokens, average);
    }

    public synchronized long totalTokens() {
        return totalTokens;
    }

    public synchronized long requestCount() {
        return requestCount;
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
