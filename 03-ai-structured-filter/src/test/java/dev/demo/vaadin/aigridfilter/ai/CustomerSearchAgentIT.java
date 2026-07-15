package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.Condition;
import dev.demo.vaadin.aigridfilter.ai.filter.CustomerFilter;
import dev.demo.vaadin.aigridfilter.ai.filter.Operator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the AI layer (natural language -> {@link CustomerFilter}) against a real Ollama.
 * Run with {@code -Pit-local-ollama} (see {@code 03-ai-structured-filter/pom.xml}), or directly with
 * {@code -Dit.test=CustomerSearchAgentIT}.
 * <p>
 * Assertions are tolerant: the LLM is non-deterministic, so we only check that the expected condition
 * is present <em>somewhere</em> in the flat conditions list (field + value substring), ignoring
 * operator and extras.
 * <p>
 * Every case here uses the exact same wording/values as one of {@code 02-ai-agent-filter}'s
 * {@code CustomerSearchAgentIT} cases, so the two modules' results and timings are directly
 * comparable. Cases that need a capability {@code 02}'s flat {@code CustomerSearchCriteria} model
 * cannot express at all (negation, STARTS_WITH/ENDS_WITH/EQUALS precision, arbitrary date bounds)
 * have no counterpart there and live separately in {@link CustomerSearchAgentExtraIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.autoconfigure.exclude=com.vaadin.flow.spring.SpringBootAutoConfiguration"
})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CustomerSearchAgentIT {

    @Autowired
    CustomerSearchStructuredOutputService service;

    @Test
    void singleCity() {
        CustomerFilter filter = service.requestFilter("show me all customers in Berlin");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
    }

    @Test
    void multiValueCities() {
        // Same wording as 02-ai-agent-filter's CustomerSearchAgentIT.multiValueCities, for direct comparability.
        CustomerFilter filter = service.requestFilter("show me customers from Berlin or Hamburg");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "hamburg")).isTrue();
    }

    @Test
    void citiesAndRevenue_keepsEveryCondition() {
        CustomerFilter filter = service.requestFilter(
                "show me all customers in Berlin or Hamburg with a minimal revenue of 100000");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(),"hamburg")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(),"100000")).isTrue();
    }

    @Test
    void citiesWithRevenueRange() {
        // Same wording as 02-ai-agent-filter's CustomerSearchAgentIT.citiesWithRevenueRange, for direct comparability.
        CustomerFilter filter = service.requestFilter("Berlin or Hamburg with revenue between 100000 and 500000");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "hamburg")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "100000")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.LESS_OR_EQUAL.toString(), "500000")).isTrue();
    }

    @Test
    void contactNameContains() {
        CustomerFilter filter = service.requestFilter(
                "show me all customers with \"meyer\" in the contact name");
        assertThat(hasCondition(filter, "contactName", Operator.CONTAINS.toString(), "meyer")).isTrue();
    }

    @Test
    void phoneNumberContains() {
        CustomerFilter filter = service.requestFilter(
                "show me the customer with the phone number 5020000001 or similar");
        assertThat(hasCondition(filter, "phone", Operator.CONTAINS.toString(), "5020000001")).isTrue();
    }

    @Test
    void germanPhoneNumberNormalizedToE164() {
        // Same wording as 02-ai-agent-filter's CustomerSearchAgentIT, for direct comparability.
        CustomerFilter filter = service.requestFilter(
                "show me the customer with phone number 030 10023757");
        assertThat(hasCondition(filter, "phone", new String[0], "3010023757")).isTrue();
    }

    @Test
    void customerSinceYear() {
        CustomerFilter filter = service.requestFilter("customers since 2020");
        assertThat(hasCondition(filter, "customerSince", Operator.GREATER_OR_EQUAL.toString(), "2020")).isTrue();
    }

    @Test
    void multiValueCustomerSinceYears() {
        // Same wording as 02-ai-agent-filter's CustomerSearchAgentIT, for direct comparability.
        CustomerFilter filter = service.requestFilter("customers since 2020 or 2021");
        assertThat(hasCondition(filter, "customerSince", Operator.GREATER_OR_EQUAL.toString(), "2020")).isTrue();
        assertThat(hasCondition(filter, "customerSince", Operator.GREATER_OR_EQUAL.toString(), "2021")).isTrue();
    }

    @Test
    void annualRevenueOverThreshold() {
        // Same wording/threshold as 02-ai-agent-filter's CustomerSearchAgentIT, for direct comparability.
        CustomerFilter filter = service.requestFilter("show me customers with annual revenue over 200000");
        assertThat(hasCondition(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "200000")).isTrue();
    }

    @Test
    void country() {
        CustomerFilter filter = service.requestFilter("customers in Germany");
        assertThat(hasCondition(filter, "country",
                new String[]{Operator.CONTAINS.toString(), Operator.EQUALS.toString()}, "germany")).isTrue();
    }

    @Test
    void companyNameContains() {
        // Same wording as 02-ai-agent-filter's CustomerSearchAgentIT, for direct comparability.
        CustomerFilter filter = service.requestFilter("customers whose company name contains data");
        assertThat(hasCondition(filter, "companyName", Operator.CONTAINS.toString(), "data")).isTrue();
    }

    @Test
    void creditworthyCustomers() {
        // Same wording as 02-ai-agent-filter's CustomerSearchAgentIT, for direct comparability.
        CustomerFilter filter = service.requestFilter("show me all creditworthy customers");
        assertThat(hasCondition(filter, "creditRating",
                new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "good", "creditworthy")).isTrue();
    }

    @Test
    void atRiskCustomers() {
        // Same wording as 02-ai-agent-filter's CustomerSearchAgentIT, for direct comparability.
        CustomerFilter filter = service.requestFilter("show me all customers that are at risk");
        assertThat(hasCondition(filter, "creditRating",
                new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "poor", "risk")).isTrue();
    }

    @Test
    void multiValueCreditRating() {
        // Same wording as 02-ai-agent-filter's CustomerSearchAgentIT, for direct comparability.
        CustomerFilter filter = service.requestFilter("show me customers with GOOD or MEDIUM credit rating");
        assertThat(hasCondition(filter, "creditRating",
                new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "good", "creditworthy")).isTrue();
        assertThat(hasCondition(filter, "creditRating",
                new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "medium", "moderate")).isTrue();
    }

    @Test
    void creditworthyInCity() {
        // Same wording as 02-ai-agent-filter's CustomerSearchAgentIT.creditworthyInCity, for direct comparability.
        CustomerFilter filter = service.requestFilter("creditworthy customers in Hamburg");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "hamburg")).isTrue();
        assertThat(hasCondition(filter, "creditRating",
                new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "good", "creditworthy")).isTrue();
    }

    @Test
    void showAllCustomers_noCriteria() {
        CustomerFilter filter = service.requestFilter("show all customers");
        assertThat(flatten(filter)).isEmpty();
    }

    @Test
    void resetTheFilter_German() {
        CustomerFilter filter = service.requestFilter("setze den Filter zurück");
        assertThat(flatten(filter)).isEmpty();
    }

    @Test
    void citiesAndCreditRating_German() {
        CustomerFilter filter = service.requestFilter("zeige mir Kunden aus Berlin oder Hamburg mit einer positiven Kreditwürdigkeit");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "hamburg")).isTrue();
        assertThat(hasCondition(filter, "creditRating",
                new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "good", "creditworthy")).isTrue();
    }

    @Test
    void contactNameAndCity_German() {
        CustomerFilter filter = service.requestFilter(
                "Zeigen mir Kunden deren Kontaktname Julia ist und die in Berlin sind.");
        assertThat(hasCondition(filter, "contactName", new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "julia")).isTrue();
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
    }

    /** One expanded (field, operator, value) leaf; {@code operator} gets a synthetic {@code NOT_} prefix when negated. */
    record Leaf(String field, String operator, String value) {
    }

    /** True if any leaf is on {@code fieldString} (ignoring case) with a value containing {@code valueSubstring}. */
    static boolean hasCondition(CustomerFilter filter, String fieldString, String operatorString, String valueSubstring) {
        return hasCondition(filter, fieldString,
                operatorString == null ? new String[0] : new String[]{operatorString}, valueSubstring);
    }

    /**
     * True if any leaf is on {@code fieldString} (ignoring case), whose operator matches one of
     * {@code acceptableOperators} (any operator accepted if the array is empty), and whose value
     * contains at least one of {@code acceptableValueSubstrings} (case-insensitive). Mirrors the
     * tolerant matching in {@code BenchmarkLocalModels}'s comparable helpers.
     */
    static boolean hasCondition(CustomerFilter filter, String fieldString, String[] acceptableOperators,
            String... acceptableValueSubstrings) {
        return flatten(filter).stream().anyMatch(leaf ->
                leaf.field() != null && leaf.field().equalsIgnoreCase(fieldString)
                        && (acceptableOperators.length == 0
                                || Arrays.stream(acceptableOperators).anyMatch(op -> leaf.operator().equalsIgnoreCase(op)))
                        && leaf.value() != null
                        && Arrays.stream(acceptableValueSubstrings).anyMatch(v -> leaf.value().toLowerCase().contains(v.toLowerCase())));
    }

    /**
     * Expands every {@link Condition} into one {@link Leaf} per value. A negated condition's operator
     * gets a synthetic {@code NOT_} prefix (e.g. {@code NOT_EQUALS}), so a query like "not in Munich"
     * matches the expected assertion regardless of which operator the model chose for the positive
     * comparison.
     */
    static List<Leaf> flatten(CustomerFilter filter) {
        if (filter == null || filter.conditions() == null) {
            return List.of();
        }
        return filter.conditions().stream()
                .filter(c -> c != null && c.field() != null && c.operator() != null && c.values() != null)
                .flatMap(c -> c.values().stream().map(value ->
                        new Leaf(c.field(), (c.negate() ? "NOT_" : "") + c.operator().name(), value)))
                .toList();
    }
}
