package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerFilter;
import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode;
import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.And;
import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.Condition;
import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.Not;
import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.Or;
import dev.demo.vaadin.aigridfilter.ai.filter.Operator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the AI layer (natural language -> {@link CustomerFilter}) against a real Ollama.
 * Extends {@link LocalOllamaTests}, which provides the connection to a native Ollama instance (no
 * Docker) and skips gracefully when it is unreachable. Run with {@code -Pit-local-ollama} (see
 * {@code 03-ai-structured-filter/pom.xml}), or directly with {@code -Dit.test=CustomerSearchAgentIT}.
 * <p>
 * Assertions are tolerant: the LLM is non-deterministic, so we only check that the expected condition is
 * present <em>somewhere</em> in the filter tree (field + value substring), ignoring operator, extras, and
 * where exactly it sits in the AND/OR/NOT structure.
 * <p>
 * Every case here uses the exact same wording/values as one of {@code 02-ai-agent-filter}'s
 * {@code CustomerSearchAgentIT} cases, so the two modules' results and timings are directly
 * comparable. Cases that need a capability {@code 02}'s flat {@code CustomerSearchCriteria}
 * model cannot express at all (negation, STARTS_WITH/ENDS_WITH/EQUALS precision, arbitrary date
 * bounds, cross-field OR, deeper nesting) have no counterpart there and live separately in
 * {@link CustomerSearchAgentNestedIT}.
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

    /** True if any condition in the tree is on {@code fieldString} (ignoring case) with a value containing {@code valueSubstring}. */
    static boolean hasCondition(CustomerFilter filter, String fieldString, String operatorString, String valueSubstring) {
        return hasCondition(filter, fieldString,
                operatorString == null ? new String[0] : new String[]{operatorString}, valueSubstring);
    }

    /**
     * True if any condition in the tree is on {@code fieldString} (ignoring case), whose operator matches one
     * of {@code acceptableOperators} (any operator accepted if the array is empty), and whose value contains at
     * least one of {@code acceptableValueSubstrings} (case-insensitive). Mirrors the tolerant matching in
     * {@code BenchmarkLocalModels}'s comparable helpers.
     */
    static boolean hasCondition(CustomerFilter filter, String fieldString, String[] acceptableOperators,
            String... acceptableValueSubstrings) {
        return flatten(filter).stream().anyMatch(c ->
                c.field() != null && c.field().equalsIgnoreCase(fieldString)
                        && (acceptableOperators.length == 0 || (c.operator() != null
                                && Arrays.stream(acceptableOperators).anyMatch(op -> c.operator().name().equalsIgnoreCase(op))))
                        && c.value() != null
                        && Arrays.stream(acceptableValueSubstrings).anyMatch(v -> c.value().toLowerCase().contains(v.toLowerCase())));
    }

    /**
     * Collects every {@link Condition} leaf anywhere in the filter's tree, in encounter order. A leaf
     * under an odd number of {@link Not} ancestors is negated (EQUALS/CONTAINS flipped to their NOT_*
     * counterpart) so a query like "not in Munich" matches the expected NOT_EQUALS/NOT_CONTAINS
     * assertion whether the model expressed it as {@code city NOT_EQUALS Munich} or
     * {@code NOT(city EQUALS Munich)} — both are valid, equivalent trees.
     */
    static List<Condition> flatten(CustomerFilter filter) {
        List<Condition> conditions = new ArrayList<>();
        if (filter != null) {
            collect(filter.root(), false, conditions);
        }
        return conditions;
    }

    private static void collect(FilterNode node, boolean negated, List<Condition> into) {
        switch (node) {
            case null -> {
            }
            case Condition c -> into.add(negated ? negate(c) : c);
            case And a -> a.children().forEach(child -> collect(child, negated, into));
            case Or o -> o.children().forEach(child -> collect(child, negated, into));
            case Not n -> collect(n.child(), !negated, into);
        }
    }

    private static Condition negate(Condition c) {
        Operator negated = switch (c.operator()) {
            case EQUALS -> Operator.NOT_EQUALS;
            case NOT_EQUALS -> Operator.EQUALS;
            case CONTAINS -> Operator.NOT_CONTAINS;
            case NOT_CONTAINS -> Operator.CONTAINS;
            case null, default -> c.operator(); // no exact negation (e.g. ranges) — leave as-is, best effort
        };
        return new Condition(c.field(), negated, c.value());
    }
}
