package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerFilter;
import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode;
import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.And;
import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.Condition;
import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.Not;
import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.Or;
import dev.demo.vaadin.aigridfilter.ai.filter.Operator;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
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
 * Tests are tagged by the model capability they require to build the correct tree shape — see
 * {@code small-model-query}, {@code medium-model-query}, {@code large-model-query} — since deeper
 * nesting and cross-field OR are harder for smaller local models to produce reliably.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CustomerSearchAgentIT extends LocalOllamaTests {

    @Autowired
    CustomerSearchStructuredOutputService service;

    @Test
    @Tag("small-model-query")
    void singleCity() {
        CustomerFilter filter = service.requestFilter("show me all customers in Berlin");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void singleFalseCity() {
        CustomerFilter filter = service.requestFilter("show me all customers except from Berlin");
        assertThat(hasCondition(filter, "city", Operator.NOT_EQUALS.toString(), "berlin")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void multipleCities() {
        CustomerFilter filter = service.requestFilter("customers in Berlin or Hamburg");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "hamburg")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void citiesAndRevenue_keepsEveryCondition() {
        CustomerFilter filter = service.requestFilter(
                "show me all customers in Berlin or Hamburg with a minimal revenue of 100000");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(),"hamburg")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(),"100000")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void contactNameStartsWith() {
        CustomerFilter filter = service.requestFilter(
                "show me all customers with an \"m\" as the first character in the contact name");
        assertThat(hasCondition(filter, "contactName", Operator.STARTS_WITH.toString(), "m")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void contactNameContains() {
        CustomerFilter filter = service.requestFilter(
                "show me all customers with \"meyer\" in the contact name");
        assertThat(hasCondition(filter, "contactName", Operator.CONTAINS.toString(), "meyer")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void contactNameEndsWith() {
        CustomerFilter filter = service.requestFilter(
                "show me all customers their contact name ends with \"schmidt\"");
        assertThat(hasCondition(filter, "contactName", Operator.ENDS_WITH.toString(), "schmidt")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void contactNameAndCity() {
        CustomerFilter filter = service.requestFilter(
                "customers whose contact name is Sofia and who are from Berlin");
        assertThat(hasCondition(filter, "contactName", Operator.EQUALS.toString(), "sofia")).isTrue();
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void phoneNumberContains() {
        CustomerFilter filter = service.requestFilter(
                "show me the customer with the phone number 5020000001 or similar");
        assertThat(hasCondition(filter, "phone", Operator.CONTAINS.toString(), "5020000001")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void orderedInTheLastWeek() {
        CustomerFilter filter = service.requestFilter(
                "Show me all customers who placed an order in the last week");
        assertThat(hasCondition(filter, "lastOrderDate", Operator.GREATER_OR_EQUAL.toString(), "")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void orderedYesterday() {
        CustomerFilter filter = service.requestFilter("show me all customers who made an order yesterday");
        String yesterday = LocalDate.now().minusDays(1).toString();
        assertThat(hasCondition(filter, "lastOrderDate", Operator.EQUALS.toString(), yesterday)).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void customerSinceThisYear() {
        CustomerFilter filter = service.requestFilter("customers who became customers this year");
        assertThat(hasCondition(filter, "customerSince", Operator.GREATER_OR_EQUAL.toString(), "")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void customerSinceYear() {
        CustomerFilter filter = service.requestFilter("customers since 2020");
        assertThat(hasCondition(filter, "customerSince", Operator.GREATER_OR_EQUAL.toString(), "2020")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void lastOrderBeforeDate() {
        // "before 2024-01-01" can be expressed as < 2024-01-01 or <= 2023-12-31; either is correct.
        CustomerFilter filter = service.requestFilter("customers whose last order was before 2024-01-01");
        assertThat(hasCondition(filter, "lastOrderDate", new String[]{Operator.LESS_OR_EQUAL.toString()},
                "2024-01-01", "2023-12-31")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void revenueOverAMillion() {
        CustomerFilter filter = service.requestFilter("companies with annual revenue over 1 million");
        assertThat(hasCondition(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "1000000")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void notInCityWithRevenueRange_keepsEveryCondition() {
        CustomerFilter filter = service.requestFilter(
                "companies not in Munich with revenue between 100000 and 500000");
        assertThat(hasCondition(filter, "city",
                new String[]{Operator.NOT_EQUALS.toString(), Operator.NOT_CONTAINS.toString()}, "munich")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "100000")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.LESS_OR_EQUAL.toString(), "500000")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void country() {
        CustomerFilter filter = service.requestFilter("customers in Germany");
        assertThat(hasCondition(filter, "country",
                new String[]{Operator.CONTAINS.toString(), Operator.EQUALS.toString()}, "germany")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void emailEndsWith() {
        CustomerFilter filter = service.requestFilter("customers whose email ends with .com");
        assertThat(hasCondition(filter, "email", Operator.ENDS_WITH.toString(), ".com")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void emailNotContains() {
        CustomerFilter filter = service.requestFilter("customers whose email does not contain gmail");
        assertThat(hasCondition(filter, "email",
                new String[]{Operator.NOT_CONTAINS.toString(), Operator.NOT_EQUALS.toString()}, "gmail")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void companyNameStartsWith() {
        CustomerFilter filter = service.requestFilter("customers whose company name starts with A");
        assertThat(hasCondition(filter, "companyName", Operator.STARTS_WITH.toString(), "a")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void creditworthyInCity() {
        CustomerFilter filter = service.requestFilter("creditworthy customers in Berlin");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCondition(filter, "creditRating",
                new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "good", "creditworthy")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void creditRatingTwoValues_staySeparateCriteria() {
        // "good and at-risk" must become two OR'd conditions on creditRating, not one AND'd range.
        CustomerFilter filter = service.requestFilter(
                "show me all customers in Berlin with a good and an at-risk credit rating");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCondition(filter, "creditRating",
                new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "good", "creditworthy")).isTrue();
        assertThat(hasCondition(filter, "creditRating",
                new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "poor", "risk")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void showAllCustomers_noCriteria() {
        CustomerFilter filter = service.requestFilter("show all customers");
        assertThat(flatten(filter)).isEmpty();
    }

    @Test
    @Tag("small-model-query")
    void resetTheFilter_German() {
        CustomerFilter filter = service.requestFilter("setze den Filter zurück");
        assertThat(flatten(filter)).isEmpty();
    }

    @Test
    @Tag("small-model-query")
    void citiesAndCreditRating_German() {
        CustomerFilter filter = service.requestFilter("zeige mir Kunden aus Berlin oder Hamburg mit einer positiven Kreditwürdigkeit");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "hamburg")).isTrue();
        assertThat(hasCondition(filter, "creditRating",
                new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "good", "creditworthy")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void notInCityWithRevenueRange_keepsEveryCondition_German() {
        CustomerFilter filter = service.requestFilter(
                "Alle Kunden ausser aus Hamburg mit einem Umsatz von 500000 bis 1000000");
        assertThat(hasCondition(filter, "city",
                new String[]{Operator.NOT_EQUALS.toString(), Operator.NOT_CONTAINS.toString()}, "hamburg")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "500000")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.LESS_OR_EQUAL.toString(), "1000000")).isTrue();
    }

    @Test
    @Tag("small-model-query")
    void contactNameAndCity_German() {
        CustomerFilter filter = service.requestFilter(
                "Zeigen mir Kunden deren Kontaktname Julia ist und die in Berlin sind.");
        assertThat(hasCondition(filter, "contactName", new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "julia")).isTrue();
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
    }

    @Test
    @Tag("medium-model-query")
    void citiesWithRevenueRange_nestedCombination() {
        // "(city=Berlin OR city=Hamburg) AND (revenue>=100000 AND revenue<=500000)"
        CustomerFilter filter = service.requestFilter(
                "Berlin or Hamburg with revenue between 100000 and 500000");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "hamburg")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "100000")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.LESS_OR_EQUAL.toString(), "500000")).isTrue();
    }

    @Test
    @Tag("medium-model-query")
    void nestedOrOfOrs() {
        // (city=Berlin OR city=Hamburg) AND (revenue>=500000 OR creditRating=GOOD)
        CustomerFilter filter = service.requestFilter(
                "customers in Berlin or Hamburg who either have a revenue of at least 500000 or a good credit rating");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "hamburg")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "500000")).isTrue();
        assertThat(hasCondition(filter, "creditRating",
                new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "good", "creditworthy")).isTrue();
    }

    @Test
    @Tag("large-model-query")
    void crossFieldOr_cityOrRevenue() {
        CustomerFilter filter = service.requestFilter(
                "customers in Berlin or with revenue above 1 million");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "1000000")).isTrue();
    }

    @Test
    @Tag("large-model-query")
    void crossFieldOr_cityOrCreditRating() {
        CustomerFilter filter = service.requestFilter("Hamburg or good credit rating");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "hamburg")).isTrue();
        assertThat(hasCondition(filter, "creditRating",
                new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "good", "creditworthy")).isTrue();
    }

    @Test
    @Tag("large-model-query")
    void negatedGroup() {
        // NOT (city=Berlin AND revenue < 100000)
        CustomerFilter filter = service.requestFilter(
                "show me customers that are not both from Berlin and have a revenue under 100000");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.LESS_OR_EQUAL.toString(), "100000")).isTrue();
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
