package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.Condition;
import dev.demo.vaadin.aigridfilter.ai.filter.Condition.Operator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Plain JUnit (no Spring context, no LLM) guard for the one tool-calling failure mode this repository has
 * actually observed and mitigated: a {@code void} tool is answered with a bare "Done", which a model can
 * read as "nothing happened" and call the tool again with no arguments — wiping the filter it just
 * extracted. See {@code docs/capability-matrix.md}, "Same tool called twice".
 * <p>
 * Only that behaviour is covered. Tests that merely asserted arguments land in a record were plumbing and
 * were removed; what makes an AI call robust is the guard against the model repeating itself.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CustomerSearchServiceToolsTest {

    private final CustomerSearchService service =
            new CustomerSearchService(mock(ChatModel.class), mock(TokenUsageAdvisor.class));

    private static final Condition BERLIN_OR_HAMBURG =
            new Condition("city", Operator.CONTAINS, List.of("Berlin", "Hamburg"), false);
    private static final Condition REVENUE_AT_LEAST =
            new Condition("annualRevenue", Operator.GREATER_OR_EQUAL, List.of("100000"), false);
    private static final Condition REVENUE_AT_MOST =
            new Condition("annualRevenue", Operator.LESS_OR_EQUAL, List.of("500000"), false);



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

}
