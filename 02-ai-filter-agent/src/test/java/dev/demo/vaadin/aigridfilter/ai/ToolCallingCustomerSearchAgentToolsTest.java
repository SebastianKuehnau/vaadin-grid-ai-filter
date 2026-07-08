package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerSearchCriteria;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Plain JUnit test (no Spring context, no LLM) of the tools' own correctness, in isolation: calling
 * {@code searchCustomers} directly with fixed literal arguments must capture them, verbatim, into
 * {@link ToolCallingCustomerSearchAgent#criteria}, and {@code currentLocalDateTime} must return the
 * actual current time. The {@link ChatModel} is mocked purely to satisfy the constructor — it is
 * never invoked.
 */
class ToolCallingCustomerSearchAgentToolsTest {

    private final ToolCallingCustomerSearchAgent agent = new ToolCallingCustomerSearchAgent(mock(ChatModel.class));

    @Test
    void capturesAllArgumentsIntoResult() {
        agent.searchCustomers("Acme", "Jane Doe", "jane@acme.example", "+4916057123456",
                LocalDate.of(2020, 1, 1), LocalDate.of(2021, 6, 15),
                "Germany", "Berlin", "10115", "Main Street", "1", CreditRating.GOOD);

        assertThat(agent.criteria).isEqualTo(new CustomerSearchCriteria("Acme", "Jane Doe",
                "jane@acme.example", "+4916057123456", LocalDate.of(2020, 1, 1), LocalDate.of(2021, 6, 15),
                "Germany", "Berlin", "10115", "Main Street", "1", CreditRating.GOOD));
    }

    @Test
    void allNullArgumentsCaptureNullCriteria() {
        agent.searchCustomers(null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(agent.criteria).isEqualTo(new CustomerSearchCriteria(
                null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void returnsTheCurrentDateTime() {
        LocalDateTime result = agent.currentLocalDateTime();

        assertThat(Duration.between(result, LocalDateTime.now()).abs()).isLessThan(Duration.ofSeconds(5));
    }
}
