package dev.demo.vaadin.aigridfilter.benchmark.run;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The chat model, with a budget: a query that keeps calling it is aborted instead of running forever.
 *
 * <p>Spring AI's tool loop is an unbounded {@code do/while} — it ends when the model stops asking for
 * a tool, and a tool error is handed back to the model rather than thrown. A model that repeats a
 * malformed call therefore loops until something outside stops it, and nothing does. Counting model
 * calls rather than errors catches all three shapes of that: an argument the converter rejects, an
 * exception from the tool method itself, and a model that simply never stops calling.
 *
 * <p>Deliberately the benchmark's own class, not the modules': it exists so an unattended run
 * terminates, which is a benchmark concern. The measured code stays exactly as the talk shows it.
 */
public class CallBudgetChatModel implements ChatModel {

    private final ChatModel delegate;
    private final int maxCallsPerQuery;
    private final AtomicInteger calls = new AtomicInteger();

    public CallBudgetChatModel(ChatModel delegate, int maxCallsPerQuery) {
        this.delegate = delegate;
        this.maxCallsPerQuery = maxCallsPerQuery;
    }

    /** Zeroes the counter, so the next query starts with the full budget. */
    public void reset() {
        calls.set(0);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (calls.incrementAndGet() > maxCallsPerQuery) {
            throw new BudgetExceededException(maxCallsPerQuery);
        }
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt);
    }

    /** Must be {@code getOptions()}, not the deprecated {@code getDefaultOptions()}: the model name
     * the backend needs travels in here, and the interface default would hand out empty options. */
    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    /** Ends a runaway tool loop; each service's own {@code catch} turns it into "no filter". */
    public static class BudgetExceededException extends RuntimeException {

        BudgetExceededException(int maxCallsPerQuery) {
            super("The model made more than " + maxCallsPerQuery + " calls for one query - almost "
                    + "certainly a tool loop that cannot end. Raise benchmark.max-model-calls-per-query "
                    + "if a query legitimately needs more round trips.");
        }
    }
}
