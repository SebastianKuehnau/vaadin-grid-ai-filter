package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerFilter;
import dev.demo.vaadin.aigridfilter.ai.filter.Operator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import static dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgentIT.hasCondition;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the AI layer against a real Ollama, same infrastructure as
 * {@link CustomerSearchAgentIT}, but for the cases that need a capability {@code 02-ai-agent-filter}'s
 * flat {@code CustomerSearchCriteria} model cannot express at all, so they have no counterpart to
 * compare against there:
 * <ul>
 *   <li>{@code negation} — {@code NOT} / {@code NOT_*} operators</li>
 *   <li>{@code operator-precision} — STARTS_WITH/ENDS_WITH/EQUALS distinctions its CONTAINS-only
 *       text matching can't make</li>
 *   <li>{@code relative-date} — arbitrary date bounds vs. its year-equality-only dates</li>
 *   <li>{@code cross-field-or}/{@code nested-tree} — its AND-across-fields/OR-within-field shape
 *       can't OR across different fields or nest arbitrarily</li>
 * </ul>
 * {@code cross-field-or} and {@code nested-tree} cases are additionally tagged
 * {@code medium-model-query}/{@code large-model-query} by the nesting complexity required, since
 * that's harder for smaller local models to produce reliably.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CustomerSearchAgentNestedIT extends LocalOllamaTests {

    @Autowired
    CustomerSearchStructuredOutputService service;

    @Test
    @Tag("negation")
    void singleFalseCity() {
        CustomerFilter filter = service.requestFilter("show me all customers except from Berlin");
        assertThat(hasCondition(filter, "city", Operator.NOT_EQUALS.toString(), "berlin")).isTrue();
    }

    @Test
    @Tag("operator-precision")
    void contactNameStartsWith() {
        CustomerFilter filter = service.requestFilter(
                "show me all customers with an \"m\" as the first character in the contact name");
        assertThat(hasCondition(filter, "contactName", Operator.STARTS_WITH.toString(), "m")).isTrue();
    }

    @Test
    @Tag("operator-precision")
    void contactNameEndsWith() {
        CustomerFilter filter = service.requestFilter(
                "show me all customers their contact name ends with \"schmidt\"");
        assertThat(hasCondition(filter, "contactName", Operator.ENDS_WITH.toString(), "schmidt")).isTrue();
    }

    @Test
    @Tag("operator-precision")
    void contactNameAndCity() {
        CustomerFilter filter = service.requestFilter(
                "customers whose contact name is Sofia and who are from Berlin");
        assertThat(hasCondition(filter, "contactName", Operator.EQUALS.toString(), "sofia")).isTrue();
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
    }

    @Test
    @Tag("relative-date")
    void orderedInTheLastWeek() {
        CustomerFilter filter = service.requestFilter(
                "Show me all customers who placed an order in the last week");
        assertThat(hasCondition(filter, "lastOrderDate", Operator.GREATER_OR_EQUAL.toString(), "")).isTrue();
    }

    @Test
    @Tag("relative-date")
    void orderedYesterday() {
        CustomerFilter filter = service.requestFilter("show me all customers who made an order yesterday");
        String yesterday = LocalDate.now().minusDays(1).toString();
        assertThat(hasCondition(filter, "lastOrderDate", Operator.EQUALS.toString(), yesterday)).isTrue();
    }

    @Test
    @Tag("relative-date")
    void customerSinceThisYear() {
        CustomerFilter filter = service.requestFilter("customers who became customers this year");
        assertThat(hasCondition(filter, "customerSince", Operator.GREATER_OR_EQUAL.toString(), "")).isTrue();
    }

    @Test
    @Tag("relative-date")
    void lastOrderBeforeDate() {
        // "before 2024-01-01" can be expressed as < 2024-01-01 or <= 2023-12-31; either is correct.
        CustomerFilter filter = service.requestFilter("customers whose last order was before 2024-01-01");
        assertThat(hasCondition(filter, "lastOrderDate", new String[]{Operator.LESS_OR_EQUAL.toString()},
                "2024-01-01", "2023-12-31")).isTrue();
    }

    @Test
    @Tag("negation")
    void notInCityWithRevenueRange_keepsEveryCondition() {
        CustomerFilter filter = service.requestFilter(
                "companies not in Munich with revenue between 100000 and 500000");
        assertThat(hasCondition(filter, "city",
                new String[]{Operator.NOT_EQUALS.toString(), Operator.NOT_CONTAINS.toString()}, "munich")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "100000")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.LESS_OR_EQUAL.toString(), "500000")).isTrue();
    }

    @Test
    @Tag("operator-precision")
    void emailEndsWith() {
        CustomerFilter filter = service.requestFilter("customers whose email ends with .com");
        assertThat(hasCondition(filter, "email", Operator.ENDS_WITH.toString(), ".com")).isTrue();
    }

    @Test
    @Tag("negation")
    void emailNotContains() {
        CustomerFilter filter = service.requestFilter("customers whose email does not contain gmail");
        assertThat(hasCondition(filter, "email",
                new String[]{Operator.NOT_CONTAINS.toString(), Operator.NOT_EQUALS.toString()}, "gmail")).isTrue();
    }

    @Test
    @Tag("operator-precision")
    void companyNameStartsWith() {
        CustomerFilter filter = service.requestFilter("customers whose company name starts with A");
        assertThat(hasCondition(filter, "companyName", Operator.STARTS_WITH.toString(), "a")).isTrue();
    }

    @Test
    @Tag("operator-precision")
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
    @Tag("negation")
    void notInCityWithRevenueRange_keepsEveryCondition_German() {
        CustomerFilter filter = service.requestFilter(
                "Alle Kunden ausser aus Hamburg mit einem Umsatz von 500000 bis 1000000");
        assertThat(hasCondition(filter, "city",
                new String[]{Operator.NOT_EQUALS.toString(), Operator.NOT_CONTAINS.toString()}, "hamburg")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "500000")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.LESS_OR_EQUAL.toString(), "1000000")).isTrue();
    }

    @Test
    @Tag("medium-model-query")
    @Tag("nested-tree")
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
    @Tag("nested-tree")
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
    @Tag("cross-field-or")
    void crossFieldOr_cityOrRevenue() {
        CustomerFilter filter = service.requestFilter(
                "customers in Berlin or with revenue above 1 million");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "1000000")).isTrue();
    }

    @Test
    @Tag("large-model-query")
    @Tag("cross-field-or")
    void crossFieldOr_cityOrCreditRating() {
        CustomerFilter filter = service.requestFilter("Hamburg or good credit rating");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "hamburg")).isTrue();
        assertThat(hasCondition(filter, "creditRating",
                new String[]{Operator.EQUALS.toString(), Operator.CONTAINS.toString()}, "good", "creditworthy")).isTrue();
    }

    @Test
    @Tag("large-model-query")
    @Tag("negation")
    void negatedGroup() {
        // NOT (city=Berlin AND revenue < 100000)
        CustomerFilter filter = service.requestFilter(
                "show me customers that are not both from Berlin and have a revenue under 100000");
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasCondition(filter, "annualRevenue", Operator.LESS_OR_EQUAL.toString(), "100000")).isTrue();
    }
}
