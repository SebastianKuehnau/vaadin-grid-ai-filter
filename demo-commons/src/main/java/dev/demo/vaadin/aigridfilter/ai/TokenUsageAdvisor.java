package dev.demo.vaadin.aigridfilter.ai;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.core.Ordered;

/**
 * Measures every chat request and hands the numbers to a {@link TokenUsageRecorder}: how many tokens it
 * cost and how long it took, wall clock.
 * <p>
 * Passed to {@code ChatClient.prompt().advisors(...)}, this replaces the same measurement written out by
 * hand in each AI service — capture the {@code ChatResponse} instead of the content, read
 * {@code getMetadata().getUsage()}, and bracket the call with {@code System.nanoTime()}. Doing it in an
 * advisor is not just less code: it takes the measurement out of the four files the talk puts on a slide,
 * so each service is left with nothing but its prompt and its tool.
 * <p>
 * Implemented as a plain {@link CallAdvisor} rather than a {@code BaseAdvisor}, because
 * {@code BaseAdvisor} splits the work into {@code before} and {@code after} and the duration has to be
 * measured across the whole call. {@link #adviseCall} wraps {@code chain.nextCall(...)} and therefore
 * sees exactly the interval the services used to time.
 * <p>
 * <b>Tool calling makes several round trips</b>, and this advisor is invoked once around all of them —
 * the chain sits outside the model's tool loop. It reports the usage of the final {@code ChatResponse},
 * relying on both the OpenAI and the Ollama chat model accumulating usage across the round trips into it,
 * which is exactly what the hand-written version relied on. The aggregate figures
 * ({@link TokenUsageRecorder#totalTokens()}, {@link TokenUsageRecorder#logSummary}) never came from here
 * at all: they are read off the Micrometer meters Spring AI publishes, so they are unaffected by how this
 * per-request line is captured.
 * <p>
 * Not a Spring bean, and deliberately not annotated — see {@link TokenUsageRecorder} for why. Each
 * service builds one instance in its constructor.
 */
public class TokenUsageAdvisor implements CallAdvisor {

    private final TokenUsageRecorder tokenUsageRecorder;

    public TokenUsageAdvisor(TokenUsageRecorder tokenUsageRecorder) {
        this.tokenUsageRecorder = tokenUsageRecorder;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.nanoTime();
        ChatClientResponse response = chain.nextCall(request);
        long durationMillis = (System.nanoTime() - start) / 1_000_000;

        Usage usage = usageOf(response);
        if (usage != null) {
            tokenUsageRecorder.record(queryOf(request), usage, durationMillis);
        }
        return response;
    }

    /** The user's query, for the log line — the same string the services used to pass in. */
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

    @Override
    public String getName() {
        return "tokenUsage";
    }

    /**
     * Outermost: the measured interval should contain everything the other advisors do, so the duration
     * matches what a user waits for.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
