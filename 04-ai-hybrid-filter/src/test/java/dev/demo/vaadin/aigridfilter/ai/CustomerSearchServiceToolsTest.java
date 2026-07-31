package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.Condition;
import dev.demo.vaadin.aigridfilter.ai.filter.Operator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Plain JUnit test (no Spring context, no LLM) of the one tool of this module in isolation: the
 * condition list must land in {@link CustomerSearchService#filter} verbatim, a
 * repeated empty call must not wipe it, and the prompt must carry the resolved "today".
 * The {@link ChatModel} and {@link TokenUsageRecorder} are mocked purely to satisfy the constructor —
 * neither is invoked (the tool never calls the model or records usage).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CustomerSearchServiceToolsTest {

    private final CustomerSearchService service =
            new CustomerSearchService(mock(ChatModel.class), mock(TokenUsageRecorder.class));

    private static final Condition BERLIN_OR_HAMBURG =
            new Condition("city", Operator.CONTAINS, List.of("Berlin", "Hamburg"), false);
    private static final Condition REVENUE_AT_LEAST =
            new Condition("annualRevenue", Operator.GREATER_OR_EQUAL, List.of("100000"), false);
    private static final Condition REVENUE_AT_MOST =
            new Condition("annualRevenue", Operator.LESS_OR_EQUAL, List.of("500000"), false);

    @Test
    void capturesTheConditionListVerbatim() {
        // The two capabilities the per-field variants 02(a)/02(b) cannot express, in one call: OR
        // within a field (two values) and a real range (two sibling conditions on one field).
        service.searchCustomers(List.of(BERLIN_OR_HAMBURG, REVENUE_AT_LEAST, REVENUE_AT_MOST));

        assertThat(service.filter.conditions())
                .containsExactly(BERLIN_OR_HAMBURG, REVENUE_AT_LEAST, REVENUE_AT_MOST);
    }

    @Test
    void nullConditionsBecomeAnEmptyFilter() {
        service.searchCustomers(null);

        assertThat(service.filter.conditions()).isEmpty();
    }

    @Test
    void aRepeatedEmptyCallDoesNotWipeTheFilter() {
        // A void tool is answered with a bare "Done", and a model can read that as "nothing happened"
        // and call the tool again with an empty list (observed with qwen3:8b). That must not clear the
        // filter it just built.
        service.searchCustomers(List.of(BERLIN_OR_HAMBURG));
        service.searchCustomers(List.of());

        assertThat(service.filter.conditions()).containsExactly(BERLIN_OR_HAMBURG);
    }

    @Test
    void aRepeatedNonEmptyCallStillReplacesTheFilter() {
        // Only empty repeat calls are ignored: a model correcting itself with real conditions must win.
        service.searchCustomers(List.of(BERLIN_OR_HAMBURG));
        service.searchCustomers(List.of(REVENUE_AT_LEAST));

        assertThat(service.filter.conditions()).containsExactly(REVENUE_AT_LEAST);
    }

    @Test
    void systemPromptResolvesRelativeDatesAgainstTheGivenToday() {
        String prompt = CustomerSearchService.systemPrompt(LocalDate.of(2026, 3, 17));

        assertThat(prompt).contains("Today is 2026-03-17");
        // "yesterday" must be pre-resolved in the examples, so no live-clock tool call is needed.
        assertThat(prompt).contains("2026-03-16");
        assertThat(prompt).contains("Call searchCustomers exactly ONCE");
    }
}
