package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerSearchCriteria;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the AI layer (natural language -> {@link CustomerSearchCriteria}) against a
 * real Ollama. Extends {@link LocalOllamaTests}, which provides the connection to a native Ollama
 * instance (no Docker) and skips gracefully when it is unreachable. Run with
 * {@code -Pit-local-ollama} (see {@code 02-ai-filter-agent/pom.xml}), or directly with
 * {@code -Dit.test=CustomerSearchAgentIT}.
 * <p>
 * Assertions are tolerant: the LLM is non-deterministic, so values are checked case-insensitively
 * and by substring rather than exact equality. Unlike {@code 04-local-ai-filter}'s
 * {@code CustomerSearchIT}, no tree-walking helpers are needed — {@link CustomerSearchCriteria} is
 * flat, so each field is asserted directly.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CustomerSearchAgentIT extends LocalOllamaTests {

    @Autowired
    CustomerSearchToolCallingService agent;

    @Test
    @Tag("small-model-query")
    void singleCity() {
        CustomerSearchCriteria criteria = agent.requestCriteria("show me all customers in Berlin");
        assertThat(criteria.city()).containsIgnoringCase("berlin");
    }

    @Test
    @Tag("small-model-query")
    void creditworthyCustomers() {
        CustomerSearchCriteria criteria = agent.requestCriteria("show me all creditworthy customers");
        assertThat(criteria.creditRating()).isEqualTo(CreditRating.GOOD);
    }

    @Test
    @Tag("small-model-query")
    void atRiskCustomers() {
        CustomerSearchCriteria criteria = agent.requestCriteria("show me all customers that are at risk");
        assertThat(criteria.creditRating()).isEqualTo(CreditRating.POOR);
    }

    @Test
    @Tag("small-model-query")
    void creditworthyInCity() {
        CustomerSearchCriteria criteria = agent.requestCriteria("creditworthy customers in Hamburg");
        assertThat(criteria.city()).containsIgnoringCase("hamburg");
        assertThat(criteria.creditRating()).isEqualTo(CreditRating.GOOD);
    }

    @Test
    @Tag("small-model-query")
    void contactNameContains() {
        CustomerSearchCriteria criteria = agent.requestCriteria(
                "show me all customers with \"meyer\" in the contact name");
        assertThat(criteria.contactName()).containsIgnoringCase("meyer");
    }

    @Test
    @Tag("small-model-query")
    void companyNameContains() {
        CustomerSearchCriteria criteria = agent.requestCriteria("customers whose company name contains data");
        assertThat(criteria.companyName()).containsIgnoringCase("data");
    }

    @Test
    @Tag("small-model-query")
    void customerSinceYear() {
        CustomerSearchCriteria criteria = agent.requestCriteria("customers since 2020");
        assertThat(criteria.customerSince()).isEqualTo(LocalDate.of(2020, 1, 1));
    }

    // No relative-date case ("yesterday", "last week") here: it requires the model to chain two
    // tool calls — read currentLocalDateTime(), then compute an offset date from it — and
    // llama3.1:8b (this module's configured default) reliably fails that chain, either passing a
    // literal placeholder string instead of a computed date, or skipping the tool call and
    // hallucinating a stale one. qwen3:8b handles it correctly, so this is a model capability gap,
    // not a bug in the tool wiring (see the module README's "Known limitations" note). Module 04
    // avoids the issue entirely by putting "today" directly into its prompt text instead of
    // requiring a live tool call.

    @Test
    @Tag("small-model-query")
    void germanPhoneNumberNormalizedToE164() {
        CustomerSearchCriteria criteria = agent.requestCriteria(
                "show me the customer with phone number 030 10023757");
        assertThat(criteria.phone()).contains("3010023757");
    }

    @Test
    @Tag("small-model-query")
    void showAllCustomers_noCriteria() {
        CustomerSearchCriteria criteria = agent.requestCriteria("show all customers");
        assertThat(criteria).satisfiesAnyOf(
                c -> assertThat(c).isNull(),
                c -> assertThat(c).usingRecursiveComparison().isEqualTo(
                        new CustomerSearchCriteria(null, null, null, null, null, null, null, null, null, null, null, null)));
    }
}
