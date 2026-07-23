package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerFilter;
import dev.demo.vaadin.aigridfilter.ai.filter.Operator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import static dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgentIT.hasCondition;
import static dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgentIT.hasConditionExactNumeric;
import static dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgentIT.hasNoConditionsOutside;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests of the AI layer against a real Ollama, same infrastructure as
 * {@link CustomerSearchAgentIT}, but for the cases that need a capability {@code 02-ai-agent-filter}'s
 * flat {@code CustomerSearchCriteria} model cannot express at all, so they have no counterpart to
 * compare against there:
 * <ul>
 *   <li>{@code negation} — {@link dev.demo.vaadin.aigridfilter.ai.filter.Condition#negate()}</li>
 *   <li>{@code operator-precision} — STARTS_WITH/ENDS_WITH/EQUALS distinctions its CONTAINS-only
 *       text matching can't make</li>
 *   <li>{@code relative-date} — arbitrary date bounds vs. its year-equality-only dates</li>
 *   <li>{@code anti-hallucination} — combines an exact-numeric value check with the "no conditions
 *       outside an allow-list" guard (see {@link CustomerSearchAgentIT}'s Javadoc); {@code 02}'s model
 *       has no exact-value/no-extras contract to hold to this precision</li>
 *   <li>{@code fuzzy-match} — {@code phoneNumberContains} is expressible via 02's flat
 *       {@code CustomerSearchCriteria} too, but doesn't reliably pass there on the weaker
 *       {@code llama3.1:8b} (observed hallucinating an unrelated phone number during alignment
 *       testing), so per the "shared set = reliably-passing intersection" rule it stays here
 *       instead of {@code CustomerSearchAgentIT}</li>
 * </ul>
 * Cross-field OR and arbitrary nesting are no longer part of {@link CustomerFilter} either (a
 * deliberate trade-off for faster/more reliable structured output from small/local models), so the
 * cases that used to need them were removed rather than moved here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.autoconfigure.exclude=com.vaadin.flow.spring.SpringBootAutoConfiguration"
})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerSearchAgentExtraIT {

    @Autowired
    CustomerSearchStructuredOutputService service;

    @Autowired
    TokenUsageRecorder tokenUsageRecorder;

    @BeforeAll
    void resetTokenUsage() {
        tokenUsageRecorder.reset();
    }

    @AfterAll
    void logTokenSummary() {
        tokenUsageRecorder.logSummary("CustomerSearchAgentExtraIT");
    }

    @Test
    @Tag("fuzzy-match")
    void phoneNumberContains() {
        CustomerFilter filter = service.requestFilter(
                "show me the customer with the phone number 5020000001 or similar");
        assertThat(hasCondition(filter, "phone", Operator.CONTAINS.toString(), "5020000001")).isTrue();
        assertThat(hasNoConditionsOutside(filter, "phone")).isTrue();
    }

    @Test
    @Tag("negation")
    void singleFalseCity() {
        CustomerFilter filter = service.requestFilter("show me all customers except from Berlin");
        assertThat(hasCondition(filter, "city", new String[]{"NOT_EQUALS", "NOT_CONTAINS"}, "berlin")).isTrue();
    }

    @Test
    @Tag("operator-precision")
    void contactNameStartsWith() {
        CustomerFilter filter = service.requestFilter(
                "show me all customers with an \"m\" as the first character in the contact name");
        assertThat(hasCondition(filter, "contactName", Operator.STARTS_WITH.toString(), "m")).isTrue();
        assertThat(hasNoConditionsOutside(filter, "contactName")).isTrue();
    }

    @Test
    @Tag("operator-precision")
    void contactNameEndsWith() {
        CustomerFilter filter = service.requestFilter(
                "show me all customers their contact name ends with \"schmidt\"");
        assertThat(hasCondition(filter, "contactName", Operator.ENDS_WITH.toString(), "schmidt")).isTrue();
        assertThat(hasNoConditionsOutside(filter, "contactName")).isTrue();
    }

    @Test
    @Tag("operator-precision")
    void contactNameAndCity() {
        CustomerFilter filter = service.requestFilter(
                "customers whose contact name is Sofia and who are from Berlin");
        assertThat(hasCondition(filter, "contactName", Operator.EQUALS.toString(), "sofia")).isTrue();
        assertThat(hasCondition(filter, "city", Operator.CONTAINS.toString(), "berlin")).isTrue();
        assertThat(hasNoConditionsOutside(filter, "contactName", "city")).isTrue();
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
        assertThat(hasCondition(filter, "city", new String[]{"NOT_EQUALS", "NOT_CONTAINS"}, "munich")).isTrue();
        assertThat(hasConditionExactNumeric(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "100000")).isTrue();
        assertThat(hasConditionExactNumeric(filter, "annualRevenue", Operator.LESS_OR_EQUAL.toString(), "500000")).isTrue();
        assertThat(hasNoConditionsOutside(filter, "city", "annualRevenue")).isTrue();
    }

    @Test
    @Tag("operator-precision")
    void emailEndsWith() {
        CustomerFilter filter = service.requestFilter("customers whose email ends with .com");
        assertThat(hasCondition(filter, "email", Operator.ENDS_WITH.toString(), ".com")).isTrue();
        assertThat(hasNoConditionsOutside(filter, "email")).isTrue();
    }

    @Test
    @Tag("negation")
    void emailNotContains() {
        CustomerFilter filter = service.requestFilter("customers whose email does not contain gmail");
        assertThat(hasCondition(filter, "email", new String[]{"NOT_CONTAINS", "NOT_EQUALS"}, "gmail")).isTrue();
        assertThat(hasNoConditionsOutside(filter, "email")).isTrue();
    }

    @Test
    @Tag("operator-precision")
    void companyNameStartsWith() {
        CustomerFilter filter = service.requestFilter("customers whose company name starts with A");
        assertThat(hasCondition(filter, "companyName", Operator.STARTS_WITH.toString(), "a")).isTrue();
        assertThat(hasNoConditionsOutside(filter, "companyName")).isTrue();
    }

    @Test
    @Tag("operator-precision")
    void creditRatingTwoValues_staySeparateCriteria() {
        // "good and at-risk" must become two OR'd values on creditRating, not one AND'd range.
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
        assertThat(hasCondition(filter, "city", new String[]{"NOT_EQUALS", "NOT_CONTAINS"}, "hamburg")).isTrue();
        assertThat(hasConditionExactNumeric(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "500000")).isTrue();
        assertThat(hasConditionExactNumeric(filter, "annualRevenue", Operator.LESS_OR_EQUAL.toString(), "1000000")).isTrue();
        assertThat(hasNoConditionsOutside(filter, "city", "annualRevenue")).isTrue();
    }

    @Test
    @Tag("negation")
    void notInCityWithRevenueAndYear() {
        // DoD query: negation + AND-across-fields + a year range, all flat-expressible.
        CustomerFilter filter = service.requestFilter(
                "customers who are not from Berlin, have at least 1000 in revenue, and last ordered in 2024");
        assertThat(hasCondition(filter, "city", new String[]{"NOT_EQUALS", "NOT_CONTAINS"}, "berlin")).isTrue();
        assertThat(hasConditionExactNumeric(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "1000")).isTrue();
        assertThat(hasCondition(filter, "lastOrderDate", Operator.GREATER_OR_EQUAL.toString(), "2024-01-01")).isTrue();
        assertThat(hasCondition(filter, "lastOrderDate", Operator.LESS_OR_EQUAL.toString(), "2024-12-31")).isTrue();
        assertThat(hasNoConditionsOutside(filter, "city", "annualRevenue", "lastOrderDate")).isTrue();
    }

    @Test
    @Tag("negation")
    void notInCityWithRevenueAndYear_German() {
        CustomerFilter filter = service.requestFilter(
                "Kunden, die nicht aus Berlin kommen und mind. 1000 € Umsatz haben und 2024 zuletzt gekauft haben");
        assertThat(hasCondition(filter, "city", new String[]{"NOT_EQUALS", "NOT_CONTAINS"}, "berlin")).isTrue();
        assertThat(hasConditionExactNumeric(filter, "annualRevenue", Operator.GREATER_OR_EQUAL.toString(), "1000")).isTrue();
        assertThat(hasCondition(filter, "lastOrderDate", Operator.GREATER_OR_EQUAL.toString(), "2024-01-01")).isTrue();
        assertThat(hasCondition(filter, "lastOrderDate", Operator.LESS_OR_EQUAL.toString(), "2024-12-31")).isTrue();
        assertThat(hasNoConditionsOutside(filter, "city", "annualRevenue", "lastOrderDate")).isTrue();
    }

    @Test
    @Tag("anti-hallucination")
    void revenueExact_notOverGenerated() {
        CustomerFilter filter = service.requestFilter("customers with exactly 100000 in annual revenue");
        assertThat(hasConditionExactNumeric(filter, "annualRevenue", Operator.EQUALS.toString(), "100000")).isTrue();
        assertThat(hasNoConditionsOutside(filter, "annualRevenue")).isTrue();
    }
}
