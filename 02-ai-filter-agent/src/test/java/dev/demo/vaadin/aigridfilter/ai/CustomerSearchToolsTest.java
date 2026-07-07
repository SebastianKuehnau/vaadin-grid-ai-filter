package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerSearchCriteria;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain JUnit test (no Spring context, no LLM) of the extraction plumbing: calling
 * {@code searchCustomers} directly with fixed literal arguments must capture them, verbatim, into
 * the per-call {@link CustomerSearchResult} holder.
 */
class CustomerSearchToolsTest {

    @Test
    void capturesAllArgumentsIntoResult() {
        CustomerSearchResult result = new CustomerSearchResult();
        CustomerSearchTools tools = new CustomerSearchTools(result);

        tools.searchCustomers("Acme", "Jane Doe", "jane@acme.example", "+4916057123456",
                LocalDate.of(2020, 1, 1), LocalDate.of(2021, 6, 15),
                "Germany", "Berlin", "10115", "Main Street", "1", CreditRating.GOOD);

        assertThat(result.criteria).isEqualTo(new CustomerSearchCriteria("Acme", "Jane Doe",
                "jane@acme.example", "+4916057123456", LocalDate.of(2020, 1, 1), LocalDate.of(2021, 6, 15),
                "Germany", "Berlin", "10115", "Main Street", "1", CreditRating.GOOD));
    }

    @Test
    void allNullArgumentsCaptureNullCriteria() {
        CustomerSearchResult result = new CustomerSearchResult();
        CustomerSearchTools tools = new CustomerSearchTools(result);

        tools.searchCustomers(null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(result.criteria).isEqualTo(new CustomerSearchCriteria(
                null, null, null, null, null, null, null, null, null, null, null, null));
    }
}
