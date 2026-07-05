package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerFilter;
import dev.demo.vaadin.aigridfilter.ai.filter.Operator;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared integration tests of the AI layer (natural language -> {@link CustomerFilter}) against a real
 * Ollama. Included as a {@code @Nested} suite by the infrastructure classes, which only provide the
 * connection:
 * <ul>
 *   <li>{@link LocalOllamaTests} — a native Ollama instance (no Docker), and</li>
 *   <li>{@link TestContainerOllamaTests} — an Ollama Testcontainer (Docker).</li>
 * </ul>
 * Both run the {@link #MODEL} model. Assertions are tolerant: the LLM is non-deterministic, so we only
 * check that the expected criteria are present (field + value substring), ignoring operator and extras.
 */
class CustomerSearchIT {

    /** Model used by both variants. The container variant needs a matching pre-built image. */
    static final String MODEL = "llama3.1:8b";
//    static final String MODEL = "qwen3.5:4b-mlx";

    @Autowired
    CustomerSearchService service;

    @Test
    void singleCity() {
        CustomerFilter filter = service.requestFilter("show me all customers in Berlin");
        assertThat(hasCriterion(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
    }

    @Test
    void singleFalseCity() {
        CustomerFilter filter = service.requestFilter("show me all customers except from Berlin");
        assertThat(hasCriterion(filter, "city", Operator.NOT_EQUALS.toString(), "berlin")).isTrue();
    }

    @Test
    void multipleCities() {
        CustomerFilter filter = service.requestFilter("customers in Berlin or Hamburg");
        assertThat(hasCriterion(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCriterion(filter, "city", Operator.CONTAINS.toString(), "hamburg")).isTrue();
    }

    @Test
    void citiesAndRevenue_keepsEveryCondition() {
        CustomerFilter filter = service.requestFilter(
                "show me all customers in Berlin or Hamburg with a minimal revenue of 100000");
        assertThat(hasCriterion(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCriterion(filter, "city", Operator.CONTAINS.toString(),"hamburg")).isTrue();
        assertThat(hasCriterion(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(),"100000")).isTrue();
    }

    @Test
    void contactNameStartsWith() {
        CustomerFilter filter = service.requestFilter(
                "show me all customers with an \"m\" as the first character in the contact name");
        assertThat(hasCriterion(filter, "contactName", Operator.STARTS_WITH.toString(), "m")).isTrue();
    }

    @Test
    void contactNameContains() {
        CustomerFilter filter = service.requestFilter(
                "show me all customers with \"meyer\" in the contact name");
        assertThat(hasCriterion(filter, "contactName", Operator.CONTAINS.toString(), "meyer")).isTrue();
    }

    @Test
    void contactNameEndsWith() {
        CustomerFilter filter = service.requestFilter(
                "show me all customers their contact name ends with \"schmidt\"");
        assertThat(hasCriterion(filter, "contactName", Operator.ENDS_WITH.toString(), "schmidt")).isTrue();
    }

    @Test
    void contactNameAndCity() {
        CustomerFilter filter = service.requestFilter(
                "customers whose contact name is Sofia and who are from Berlin");
        assertThat(hasCriterion(filter, "contactName", Operator.EQUALS.toString(), "sofia")).isTrue();
        assertThat(hasCriterion(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
    }

    @Test
    void phoneNumberContains() {
        CustomerFilter filter = service.requestFilter(
                "show me the customer with the phone number 5020000001 or similar");
        assertThat(hasCriterion(filter, "phone", Operator.CONTAINS.toString(), "5020000001")).isTrue();
    }

    @Test
    void orderedInTheLastWeek() {
        CustomerFilter filter = service.requestFilter(
                "Show me all customers who placed an order in the last week");
        assertThat(hasCriterion(filter, "lastOrderDate", Operator.GREATER_OR_EQUAL.toString(), "")).isTrue();
    }

    @Test
    void orderedYesterday() {
        CustomerFilter filter = service.requestFilter("show me all customers who made an order yesterday");
        String yesterday = LocalDate.now().minusDays(1).toString();
        assertThat(hasCriterion(filter, "lastOrderDate", Operator.EQUALS.toString(), yesterday)).isTrue();
    }

    @Test
    void customerSinceThisYear() {
        CustomerFilter filter = service.requestFilter("customers who became customers this year");
        assertThat(hasCriterion(filter, "customerSince", Operator.GREATER_OR_EQUAL.toString(), "")).isTrue();
    }

    @Test
    void customerSinceYear() {
        CustomerFilter filter = service.requestFilter("customers since 2020");
        assertThat(hasCriterion(filter, "customerSince", Operator.GREATER_OR_EQUAL.toString(), "2020")).isTrue();
    }

    @Test
    void lastOrderBeforeDate() {
        // "before 2024-01-01" can be expressed as < 2024-01-01 or <= 2023-12-31; either is correct.
        CustomerFilter filter = service.requestFilter("customers whose last order was before 2024-01-01");
        assertThat(hasCriterion(filter, "lastOrderDate", new String[]{Operator.LESS_OR_EQUAL.toString()},
                "2024-01-01", "2023-12-31")).isTrue();
    }

    @Test
    void revenueOverAMillion() {
        CustomerFilter filter = service.requestFilter("companies with annual revenue over 1 million");
        assertThat(hasCriterion(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "1000000")).isTrue();
    }

    @Test
    void notInCityWithRevenueRange_keepsEveryCondition() {
        CustomerFilter filter = service.requestFilter(
                "companies not in Munich with revenue between 100000 and 500000");
        assertThat(hasCriterion(filter, "city",
                new String[]{Operator.NOT_EQUALS.toString(), Operator.NOT_CONTAINS.toString()}, "munich")).isTrue();
        assertThat(hasCriterion(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "100000")).isTrue();
        assertThat(hasCriterion(filter, "annualRevenue", Operator.LESS_OR_EQUAL.toString(), "500000")).isTrue();
    }

    @Test
    void country() {
        CustomerFilter filter = service.requestFilter("customers in Germany");
        assertThat(hasCriterion(filter, "country",
                new String[]{Operator.CONTAINS.toString(), Operator.EQUALS.toString()}, "germany")).isTrue();
    }

    @Test
    void emailEndsWith() {
        CustomerFilter filter = service.requestFilter("customers whose email ends with .com");
        assertThat(hasCriterion(filter, "email", Operator.ENDS_WITH.toString(), ".com")).isTrue();
    }

    @Test
    void emailNotContains() {
        CustomerFilter filter = service.requestFilter("customers whose email does not contain gmail");
        assertThat(hasCriterion(filter, "email",
                new String[]{Operator.NOT_CONTAINS.toString(), Operator.NOT_EQUALS.toString()}, "gmail")).isTrue();
    }

    @Test
    void companyNameStartsWith() {
        CustomerFilter filter = service.requestFilter("customers whose company name starts with A");
        assertThat(hasCriterion(filter, "companyName", Operator.STARTS_WITH.toString(), "a")).isTrue();
    }

    @Test
    void creditworthyInCity() {
        CustomerFilter filter = service.requestFilter("creditworthy customers in Berlin");
        assertThat(hasCriterion(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCriterion(filter, "creditRating",
                new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "good", "creditworthy")).isTrue();
    }

    @Test
    void creditRatingTwoValues_staySeparateCriteria() {
        // "good and at-risk" must become two OR'd criteria on creditRating, not one AND'd range.
        CustomerFilter filter = service.requestFilter(
                "show me all customers in Berlin with a good and an at-risk credit rating");
        assertThat(hasCriterion(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCriterion(filter, "creditRating",
                new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "good", "creditworthy")).isTrue();
        assertThat(hasCriterion(filter, "creditRating",
                new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "poor", "risk")).isTrue();
    }

    @Test
    void showAllCustomers_noCriteria() {
        CustomerFilter filter = service.requestFilter("show all customers");
        assertThat(filter.criteria()).isNullOrEmpty();
    }

    @Test
    void resetTheFilter_German() {
        CustomerFilter filter = service.requestFilter("setze den Filter zurück");
        assertThat(filter.criteria()).isNullOrEmpty();
    }

    @Test
    void citiesAndCreditRating_German() {
        CustomerFilter filter = service.requestFilter("zeige mir Kunden aus Berlin oder Hamburg mit einer positiven Kreditwürdigkeit");
        assertThat(hasCriterion(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCriterion(filter, "city", Operator.CONTAINS.toString(), "hamburg")).isTrue();
        assertThat(hasCriterion(filter, "creditRating",
                new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "good", "creditworthy")).isTrue();
    }

    @Test
    void notInCityWithRevenueRange_keepsEveryCondition_German() {
        CustomerFilter filter = service.requestFilter(
                "Alle Kunden ausser aus Hamburg mit einem Umsatz von 500000 bis 1000000");
        assertThat(hasCriterion(filter, "city",
                new String[]{Operator.NOT_EQUALS.toString(), Operator.NOT_CONTAINS.toString()}, "hamburg")).isTrue();
        assertThat(hasCriterion(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "500000")).isTrue();
        assertThat(hasCriterion(filter, "annualRevenue", Operator.LESS_OR_EQUAL.toString(), "1000000")).isTrue();
    }

    @Test
    void contactNameAndCity_German() {
        CustomerFilter filter = service.requestFilter(
                "Zeigen mir Kunden deren Kontaktname Julia ist und die in Berlin sind.");
        assertThat(hasCriterion(filter, "contactName", Operator.EQUALS.toString(), "julia")).isTrue();
        assertThat(hasCriterion(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
    }

    /** True if any criterion is on {@code fieldString} (ignoring case) with a value containing {@code valueSubstring}. */
    static boolean hasCriterion(CustomerFilter filter, String fieldString, String operatorString, String valueSubstring) {
        return hasCriterion(filter, fieldString,
                operatorString == null ? new String[0] : new String[]{operatorString}, valueSubstring);
    }

    /**
     * True if any criterion is on {@code fieldString} (ignoring case), whose operator matches one of
     * {@code acceptableOperators} (any operator accepted if the array is empty), and whose value contains at
     * least one of {@code acceptableValueSubstrings} (case-insensitive). Mirrors the tolerant matching in
     * {@code benchmark_models.py}'s {@code op_matches}/{@code value_matches}.
     */
    static boolean hasCriterion(CustomerFilter filter, String fieldString, String[] acceptableOperators,
            String... acceptableValueSubstrings) {
        if (filter == null || filter.criteria() == null) {
            return false;
        }
        return filter.criteria().stream().anyMatch(c ->
                c.field() != null && c.field().equalsIgnoreCase(fieldString)
                        && (acceptableOperators.length == 0 || (c.operator() != null
                                && Arrays.stream(acceptableOperators).anyMatch(op -> c.operator().name().equalsIgnoreCase(op))))
                        && c.value() != null
                        && Arrays.stream(acceptableValueSubstrings).anyMatch(v -> c.value().toLowerCase().contains(v.toLowerCase())));
    }
}
