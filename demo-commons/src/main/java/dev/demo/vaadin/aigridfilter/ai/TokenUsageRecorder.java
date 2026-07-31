package dev.demo.vaadin.aigridfilter.ai;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;

/**
 * Records the token usage and processing time of each AI request: it logs a per-request line
 * (prompt / completion / total tokens and the wall-clock time the request took) and reports
 * aggregate totals for a batch of requests (e.g. an integration-test run), sourced from the
 * Micrometer meters that Spring AI's chat-client observability already publishes once
 * spring-boot-starter-actuator is on the classpath: the {@code gen_ai.client.token.usage} counter
 * (tagged {@code gen_ai.token.type=input/output/total}) and the {@code gen_ai.client.operation} timer
 * (one call per completed chat request, tagged by model/system/error).
 * <p>
 * A tag combination (e.g. {@code error}) can vary between requests, so more than one Counter/Timer
 * can be registered under the same name; totals here sum across all of them rather than assuming a
 * single meter.
 * <p>
 * Those meters are cumulative for the JVM's lifetime, so {@link #reset()} snapshots their current
 * values as a baseline; {@link #totalTokens()}, {@link #requestCount()} and {@link #logSummary}
 * report the delta since that baseline rather than resetting the meters themselves.
 * <p>
 * A single shared bean used both by {@link TokenUsageAdvisor} (which records every request) and by the
 * integration tests (which reset it before a class and log the summary after all cases). Access is
 * synchronized because the views run searches off the UI thread, so several sessions may record
 * concurrently.
 * <p>
 * Deliberately <b>not</b> annotated with {@code @Component}: this class lives in {@code demo-commons},
 * which {@code 01-non-ai-filter} also depends on, and it needs Micrometer — a dependency that module
 * must not have. A component scan reads annotations from ASM metadata without loading the class, so
 * Spring would find a {@code @Component} here in {@code 01} too and then fail to create the bean with a
 * {@code NoClassDefFoundError}. Each of {@code 02}/{@code 03}/{@code 04} declares this bean in its own
 * {@code TokenUsageConfiguration} instead, which is also the more honest place to look for it.
 */
public class TokenUsageRecorder {

    private static final Logger logger = LoggerFactory.getLogger(TokenUsageRecorder.class);

    private static final String TOKEN_USAGE_METER = "gen_ai.client.token.usage";
    private static final String OPERATION_METER = "gen_ai.client.operation";
    private static final String TOKEN_TYPE_TAG = "gen_ai.token.type";

    private final MeterRegistry meterRegistry;

    private double promptTokensBaseline;
    private double completionTokensBaseline;
    private double totalTokensBaseline;
    private double durationMillisBaseline;
    private long requestCountBaseline;

    public TokenUsageRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Logs the token usage and processing time of a single request. {@code durationMillis} is the
     * wall-clock time the LLM call took, including any tool round trips. The aggregate totals
     * reported by {@link #totalTokens()}, {@link #requestCount()} and {@link #logSummary} come from
     * the Micrometer meters Spring AI updates as part of the same call, not from this method.
     */
    public synchronized void record(String query, Usage usage, long durationMillis) {
        int prompt = orZero(usage.getPromptTokens());
        int completion = orZero(usage.getCompletionTokens());
        int total = orZero(usage.getTotalTokens());

        logger.info("Token usage for '{}': prompt={}, completion={}, total={}, time={} ms",
                query, prompt, completion, total, durationMillis);
    }

    /** Snapshots the current meter values as the baseline for a fresh batch of requests. */
    public synchronized void reset() {
        promptTokensBaseline = tokenCount("input");
        completionTokensBaseline = tokenCount("output");
        totalTokensBaseline = tokenCount("total");
        durationMillisBaseline = operationDurationMillis();
        requestCountBaseline = operationCount();
    }

    /**
     * Logs a summary of all requests recorded since the last {@link #reset()}: total tokens and
     * time, request count, and averages per request.
     */
    public synchronized void logSummary(String label) {
        long requests = requestCount();
        long tokens = totalTokens();
        long promptTokens = Math.round(tokenCount("input") - promptTokensBaseline);
        long completionTokens = Math.round(tokenCount("output") - completionTokensBaseline);
        long durationMillis = Math.round(operationDurationMillis() - durationMillisBaseline);
        long averageTokens = requests == 0 ? 0 : Math.round((double) tokens / requests);
        long averageMillis = requests == 0 ? 0 : Math.round((double) durationMillis / requests);
        logger.info("Token summary [{}]: {} requests, {} total tokens (prompt={}, completion={}), "
                        + "avg {} tokens/request, {} ms total, avg {} ms/request",
                label, requests, tokens, promptTokens, completionTokens,
                averageTokens, durationMillis, averageMillis);
    }

    /** Total tokens (prompt + completion) recorded since the last {@link #reset()}. */
    public synchronized long totalTokens() {
        return Math.round(tokenCount("total") - totalTokensBaseline);
    }

    /** Number of chat-client requests completed since the last {@link #reset()}. */
    public synchronized long requestCount() {
        return operationCount() - requestCountBaseline;
    }

    private long operationCount() {
        return meterRegistry.find(OPERATION_METER).timers().stream().mapToLong(Timer::count).sum();
    }

    private double operationDurationMillis() {
        return meterRegistry.find(OPERATION_METER).timers().stream()
                .mapToDouble(timer -> timer.totalTime(TimeUnit.MILLISECONDS))
                .sum();
    }

    private double tokenCount(String tokenType) {
        return meterRegistry.find(TOKEN_USAGE_METER).tag(TOKEN_TYPE_TAG, tokenType).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
