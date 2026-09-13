package dev.demo.vaadin.aigridfilter.ai.filter;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** One condition on one {@code field}: several {@code values} are OR-combined, {@code negate} excludes them. */
@JsonClassDescription("One condition on one field; its values are OR-combined, negate=true excludes the matches.")
public record Condition(
        @JsonPropertyDescription("city, lastOrderDate or creditRating")
        String field,
        @JsonPropertyDescription("how to compare field and value")
        Operator operator,
        @JsonPropertyDescription("one or more values, matching ANY of them, e.g. [Berlin, Hamburg]")
        List<String> values,
        @JsonPropertyDescription("true to exclude the matches, e.g. 'not in Berlin'")
        boolean negate) {

    /** How a condition compares a field with a value. Negation is not an operator - that is {@link #negate()}. */
    public enum Operator {
        CONTAINS, EQUALS, GREATER_OR_EQUAL, LESS_OR_EQUAL, STARTS_WITH, ENDS_WITH
    }
}
