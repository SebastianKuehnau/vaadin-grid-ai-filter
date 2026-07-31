package dev.demo.vaadin.aigridfilter.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.core.Ordered;

/**
 * Measures every chat request — tokens and wall-clock time — and keeps the running totals a test run
 * needs. Passed to {@code ChatClient.prompt().advisors(...)}, it is the whole measurement apparatus of
 * this repository.
 * <p>
 * Doing it in an advisor keeps it out of the four files the talk puts on a slide: each
 * {@code CustomerSearchService} is left with its prompt and its tool, and nothing about counting.
 * <p>
 * Implemented as a plain {@link CallAdvisor} rather than a {@code BaseAdvisor}, because that one splits
 * the work into {@code before} and {@code after} while the duration has to be measured around the call.
 * <p>
 * <b>{@link #getOrder()} decides what gets counted, and it is not a formality.</b> Spring AI's
 * tool-execution loop re-enters the advisor chain for the follow-up call, so a tool-calling query passes
 * through it twice: once for the response that carries the {@code searchCustomers} call, and once for the
 * response after the tool result. This advisor sits <em>innermost</em>
 * ({@link Ordered#LOWEST_PRECEDENCE}) so that it sees both, and its totals are the real cost of a query.
 * Placed outermost it would see only the final round trip — for 04 that is a nine-token epilogue, while
 * the round trip that actually produced the filter would go unmeasured, making tool calling look about
 * half as expensive as it is. Each provider bills both prompts; the second contains the first as history,
 * which does not make it free.
 * <p>
 * So {@code requests} counts model calls, not user queries: for the tool-calling variants it is a multiple
 * of the number of queries. That is the same thing Micrometer's {@code gen_ai.client.token.usage} counter
 * used to report — this class replaces it, and reproduces its figures exactly, which is why
 * {@code demo-commons} needs no Micrometer and the apps no Actuator.
 * <p>
 * Access is synchronized because the views run searches off the UI thread, so several sessions may record
 * concurrently. A single shared bean, used both by the services and by the ITs, which reset it before a
 * class and log the summary after all cases.
 * <p>
 * Deliberately <b>not</b> annotated with {@code @Component}: this class lives in {@code demo-commons},
 * which {@code 01-non-ai-filter} also depends on, and it needs Spring AI — which that module must not
 * have. A component scan reads annotations from ASM metadata without loading the class, so Spring would
 * find a {@code @Component} here in {@code 01} too and then fail to create the bean with a
 * {@code NoClassDefFoundError}. Each of {@code 02}/{@code 03}/{@code 04} declares the bean in its own
 * {@code TokenUsageConfiguration} instead.
 */
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

    /** Snapshots away everything recorded so far, so a fresh batch of requests starts from zero. */
    public synchronized void reset() {
        requests = 0;
        promptTokens = 0;
        completionTokens = 0;
        totalTokens = 0;
        durationMillis = 0;
    }

    /**
     * Logs a summary of all requests recorded since the last {@link #reset()}: total tokens and time,
     * request count, and averages per request.
     */
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

    /**
     * Innermost, so that the tool-execution loop's follow-up calls pass through this advisor too — see the
     * class Javadoc for why that is what makes the totals correct. The measured duration is then per model
     * call; since the calls are sequential, their sum is still what a user waits for.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
