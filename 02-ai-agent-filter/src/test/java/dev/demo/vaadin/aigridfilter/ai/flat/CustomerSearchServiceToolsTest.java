package dev.demo.vaadin.aigridfilter.ai.flat;

import dev.demo.vaadin.aigridfilter.ai.TokenUsageAdvisor;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Plain JUnit (no Spring context, no LLM) guard for the one tool-calling failure mode this repository has
 * actually observed and mitigated: a {@code void} tool is answered with a bare "Done", which a model can
 * read as "nothing happened" and call the tool again with no arguments — wiping the criteria it just
 * extracted. See {@code docs/capability-matrix.md}, "Same tool called twice".
 * <p>
 * Only that behaviour is covered. Tests that merely asserted arguments land in a record were plumbing and
 * were removed; what makes an AI call robust is the guard against the model repeating itself.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CustomerSearchServiceToolsTest {

    private final CustomerSearchService service =
            new CustomerSearchService(mock(ChatModel.class), mock(TokenUsageAdvisor.class));



    @Test
    void aRepeatedEmptyCallDoesNotWipeTheExtractedCriteria() {
        // A void tool is answered with a bare "Done", and a model can read that as "nothing happened"
        // and call the tool again with no arguments. That must not clear what it just extracted.
        service.searchCustomers(null, null, null, null, null, null, null, "Berlin", null, null, null, null, null);
        service.searchCustomers(null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(service.criteria.city()).isEqualTo("Berlin");
    }

    @Test
    void aRepeatedNonEmptyCallStillReplacesTheCriteria() {
        // Only empty repeat calls are ignored: a model correcting itself with real values must win.
        service.searchCustomers(null, null, null, null, null, null, null, "Berlin", null, null, null, null, null);
        service.searchCustomers(null, null, null, null, null, null, null, "Hamburg", null, null, null, null, null);

        assertThat(service.criteria.city()).isEqualTo("Hamburg");
    }

}
