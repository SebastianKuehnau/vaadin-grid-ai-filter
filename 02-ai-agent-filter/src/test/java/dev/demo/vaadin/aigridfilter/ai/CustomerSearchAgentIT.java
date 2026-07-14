package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerSearchCriteria;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the AI layer (natural language -> {@link CustomerSearchCriteria}) against a
 * real Ollama. Extends {@link LocalOllamaTests}, which provides the connection to a native Ollama
 * instance (no Docker) and skips gracefully when it is unreachable. Run with
 * {@code -Pit-local-ollama} (see {@code 02-ai-agent-filter/pom.xml}), or directly with
 * {@code -Dit.test=CustomerSearchAgentIT}.
 * <p>
 * Assertions are tolerant: the LLM is non-deterministic, so values are checked case-insensitively
 * and by substring rather than exact equality. Unlike {@code 03-ai-structured-filter}'s
 * {@code CustomerSearchAgentIT}, no tree-walking helpers are needed — {@link CustomerSearchCriteria} is
 * flat, so each field is asserted directly; every field is now a list, so single-value queries are
 * asserted with {@code anySatisfy}/{@code contains}, and multi-value queries assert on all expected
 * entries.
 * <p>
 * Every case here uses the exact same wording/values as one of {@code 03-ai-structured-filter}'s
 * {@code CustomerSearchAgentIT} cases, so the two modules' results and timings are directly
 * comparable. That module has additional cases with no counterpart here (tagged {@code negation},
 * {@code operator-precision}, {@code relative-date}, {@code cross-field-or}, {@code nested-tree}),
 * because this module's flat {@link CustomerSearchCriteria} can't express NOT, STARTS_WITH/ENDS_WITH,
 * arbitrary date bounds, or OR/nesting across different fields — see that class's Javadoc.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.autoconfigure.exclude=com.vaadin.flow.spring.SpringBootAutoConfiguration"
})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CustomerSearchAgentIT {

    @Autowired
    CustomerSearchToolCallingService agent;

    @Test
    void singleCity() {
        CustomerSearchCriteria criteria = agent.requestCriteria("show me all customers in Berlin");
        assertThat(criteria.city()).anySatisfy(city -> assertThat(city).containsIgnoringCase("berlin"));
    }

    @Test
    void creditworthyCustomers() {
        CustomerSearchCriteria criteria = agent.requestCriteria("show me all creditworthy customers");
        assertThat(criteria.creditRating()).containsExactly(CreditRating.GOOD);
    }

    @Test
    void atRiskCustomers() {
        CustomerSearchCriteria criteria = agent.requestCriteria("show me all customers that are at risk");
        assertThat(criteria.creditRating()).containsExactly(CreditRating.POOR);
    }

    @Test
    void creditworthyInCity() {
        CustomerSearchCriteria criteria = agent.requestCriteria("creditworthy customers in Hamburg");
        assertThat(criteria.city()).anySatisfy(city -> assertThat(city).containsIgnoringCase("hamburg"));
        assertThat(criteria.creditRating()).containsExactly(CreditRating.GOOD);
    }

    @Test
    void contactNameContains() {
        CustomerSearchCriteria criteria = agent.requestCriteria(
                "show me all customers with \"meyer\" in the contact name");
        assertThat(criteria.contactName()).anySatisfy(name -> assertThat(name).containsIgnoringCase("meyer"));
    }

    @Test
    void companyNameContains() {
        CustomerSearchCriteria criteria = agent.requestCriteria("customers whose company name contains data");
        assertThat(criteria.companyName()).anySatisfy(name -> assertThat(name).containsIgnoringCase("data"));
    }

    @Test
    void customerSinceYear() {
        CustomerSearchCriteria criteria = agent.requestCriteria("customers since 2020");
        assertThat(criteria.customerSince()).anySatisfy(date -> assertThat(date.getYear()).isEqualTo(2020));
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
    void germanPhoneNumberNormalizedToE164() {
        CustomerSearchCriteria criteria = agent.requestCriteria(
                "show me the customer with phone number 030 10023757");
        assertThat(criteria.phone()).anySatisfy(phone -> assertThat(phone).contains("3010023757"));
    }

    @Test
    void multiValueCities() {
        CustomerSearchCriteria criteria = agent.requestCriteria("show me customers from Berlin or Hamburg");
        assertThat(criteria.city()).anySatisfy(city -> assertThat(city).containsIgnoringCase("berlin"));
        assertThat(criteria.city()).anySatisfy(city -> assertThat(city).containsIgnoringCase("hamburg"));
    }

    @Test
    void multiValueCreditRating() {
        CustomerSearchCriteria criteria = agent.requestCriteria(
                "show me customers with GOOD or MEDIUM credit rating");
        assertThat(criteria.creditRating()).contains(CreditRating.GOOD, CreditRating.MEDIUM);
    }

    @Test
    void multiValueCustomerSinceYears() {
        CustomerSearchCriteria criteria = agent.requestCriteria("customers since 2020 or 2021");
        assertThat(criteria.customerSince()).anySatisfy(date -> assertThat(date.getYear()).isEqualTo(2020));
        assertThat(criteria.customerSince()).anySatisfy(date -> assertThat(date.getYear()).isEqualTo(2021));
    }

    @Test
    void annualRevenueOverThreshold() {
        CustomerSearchCriteria criteria = agent.requestCriteria(
                "show me customers with annual revenue over 200000");
        assertThat(criteria.annualRevenue()).anySatisfy(range -> assertThat(range.atLeast())
                .isNotNull()
                .isGreaterThanOrEqualTo(BigDecimal.valueOf(150_000)));
    }

    @Test
    void citiesAndRevenue_keepsEveryCondition() {
        // Same wording as 03-ai-structured-filter's CustomerSearchAgentIT, for direct comparability.
        CustomerSearchCriteria criteria = agent.requestCriteria(
                "show me all customers in Berlin or Hamburg with a minimal revenue of 100000");
        assertThat(criteria.city()).anySatisfy(city -> assertThat(city).containsIgnoringCase("berlin"));
        assertThat(criteria.city()).anySatisfy(city -> assertThat(city).containsIgnoringCase("hamburg"));
        assertThat(criteria.annualRevenue()).anySatisfy(range -> assertThat(range.atLeast())
                .isNotNull()
                .isGreaterThanOrEqualTo(BigDecimal.valueOf(75_000)));
    }

    @Test
    void country() {
        // Same wording as 03-ai-structured-filter's CustomerSearchAgentIT, for direct comparability.
        CustomerSearchCriteria criteria = agent.requestCriteria("customers in Germany");
        assertThat(criteria.country()).anySatisfy(country -> assertThat(country).containsIgnoringCase("germany"));
    }

    @Test
    void resetTheFilter_German() {
        // Same wording as 03-ai-structured-filter's CustomerSearchAgentIT, for direct comparability.
        CustomerSearchCriteria criteria = agent.requestCriteria("setze den Filter zurück");
        assertThat(criteria).satisfiesAnyOf(
                c -> assertThat(c).isNull(),
                c -> assertThat(Arrays.asList(c.companyName(), c.contactName(), c.email(), c.phone(),
                        c.customerSince(), c.lastOrderDate(), c.country(), c.city(), c.postalCode(),
                        c.street(), c.houseNumber(), c.creditRating(), c.annualRevenue()))
                        .allSatisfy(field -> assertThat(field).isNullOrEmpty()));
    }

    @Test
    void citiesAndCreditRating_German() {
        // Same wording as 03-ai-structured-filter's CustomerSearchAgentIT, for direct comparability.
        CustomerSearchCriteria criteria = agent.requestCriteria(
                "zeige mir Kunden aus Berlin oder Hamburg mit einer positiven Kreditwürdigkeit");
        assertThat(criteria.city()).anySatisfy(city -> assertThat(city).containsIgnoringCase("berlin"));
        assertThat(criteria.city()).anySatisfy(city -> assertThat(city).containsIgnoringCase("hamburg"));
        assertThat(criteria.creditRating()).contains(CreditRating.GOOD);
    }

    @Test
    void contactNameAndCity_German() {
        // Same wording as 03-ai-structured-filter's CustomerSearchAgentIT, for direct comparability.
        CustomerSearchCriteria criteria = agent.requestCriteria(
                "Zeigen mir Kunden deren Kontaktname Julia ist und die in Berlin sind.");
        assertThat(criteria.contactName()).anySatisfy(name -> assertThat(name).containsIgnoringCase("julia"));
        assertThat(criteria.city()).anySatisfy(city -> assertThat(city).containsIgnoringCase("berlin"));
    }

    @Test
    void showAllCustomers_noCriteria() {
        // Every field is a list, and a small model may emit an empty list rather than omitting a
        // parameter entirely - CustomerSpecifications treats both as "no filter" (see its class
        // Javadoc), so this asserts on that same "null or empty" contract rather than requiring the
        // model to have produced literal nulls.
        CustomerSearchCriteria criteria = agent.requestCriteria("show all customers");
        assertThat(criteria).satisfiesAnyOf(
                c -> assertThat(c).isNull(),
                c -> assertThat(Arrays.asList(c.companyName(), c.contactName(), c.email(), c.phone(),
                        c.customerSince(), c.lastOrderDate(), c.country(), c.city(), c.postalCode(),
                        c.street(), c.houseNumber(), c.creditRating(), c.annualRevenue()))
                        .allSatisfy(field -> assertThat(field).isNullOrEmpty()));
    }
}
