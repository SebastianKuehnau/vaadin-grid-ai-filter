package dev.demo.vaadin.aigridfilter.ai.filter;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/** One condition on one {@code field}: several {@code values} are OR-combined, {@code negate} excludes them. */
@JsonClassDescription("One condition on a single field. Multiple values are OR-combined; negate=true excludes matches.")
public record Condition(
        @JsonPropertyDescription("field: companyName, contactName, email, phone, annualRevenue, creditRating, customerSince, lastOrderDate, country, city, postalCode, street, houseNumber, state, countryCode")
        String field,
        @JsonPropertyDescription("how to compare the field with each value")
        Operator operator,
        @JsonPropertyDescription("one or more values; matches if the field matches ANY of them, e.g. [Berlin, Köln]")
        List<String> values,
        @JsonPropertyDescription("true to exclude/negate this condition, e.g. 'not in Berlin'")
        boolean negate) {

    /** How a condition compares a field with a value. Negation is not an operator - that is {@link #negate()}. */
    public enum Operator {
        CONTAINS, EQUALS, GREATER_OR_EQUAL, LESS_OR_EQUAL, STARTS_WITH, ENDS_WITH
    }
}
