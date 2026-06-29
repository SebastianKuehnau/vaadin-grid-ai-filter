package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerFilter;
import dev.demo.vaadin.aigridfilter.ai.filter.Operator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared integration tests of the AI layer (natural language -> {@link CustomerFilter}) against a real
 * Ollama. Subclasses only provide the connection:
 * <ul>
 *   <li>{@link CustomerSearchServiceLocalOllamaIT} — a native Ollama instance (no Docker), and</li>
 *   <li>{@link CustomerSearchServiceTestContainerIT} — an Ollama Testcontainer (Docker).</li>
 * </ul>
 * Both run the {@link #MODEL} model. Assertions are tolerant: the LLM is non-deterministic, so we only
 * check that the expected criteria are present (field + value substring), ignoring operator and extras.
 */
abstract class CustomerSearchIT {

    /** Model used by both variants. The container variant needs a matching pre-built image. */
    static final String MODEL = "llama3.1:8b";

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
        // "letzte Woche" is relative to today (injected into the prompt), so the model computes the
        // actual date. We only assert the shape — a lower bound (GREATER_OR_EQUAL) on lastOrderDate;
        // the value is left unchecked ("") because it depends on the current date.
        CustomerFilter filter = service.requestFilter(
                "Show me all customers who placed an order in the last week");
        assertThat(hasCriterion(filter, "lastOrderDate", Operator.GREATER_OR_EQUAL.toString(), "")).isTrue();
    }

    /** True if any criterion is on {@code fieldString} (ignoring case) with a value containing {@code valueSubstring}. */
    static boolean hasCriterion(CustomerFilter filter, String fieldString, String operatorString, String valueSubstring) {
        if (filter == null || filter.criteria() == null) {
            return false;
        }
        return filter.criteria().stream().anyMatch(c ->
                c.field() != null && c.field().equalsIgnoreCase(fieldString)
                        && (operatorString == null || c.operator() != null && c.operator().name().equalsIgnoreCase(operatorString))
                        && c.value() != null && c.value().toLowerCase().contains(valueSubstring.toLowerCase()));
    }
}
