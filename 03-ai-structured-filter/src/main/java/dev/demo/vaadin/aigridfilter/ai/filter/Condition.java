package dev.demo.vaadin.aigridfilter.ai.filter;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * One condition on a single customer {@code field}. Several {@code values} are OR-combined (e.g.
 * "Berlin or Köln"); {@code negate} excludes matches instead of requiring them (e.g. "not from
 * Berlin"). A value range on one field (e.g. a year) is expressed as two sibling {@code Condition}s
 * on that field, AND-combined at the {@link CustomerFilter#conditions()} level like everything else.
 * <p>
 * {@code negate} is a primitive {@code boolean}, not {@code Boolean}, on purpose: if a model's JSON
 * omits the key, Jackson's record deserialization falls back to {@code false} instead of {@code
 * null}/an NPE.
 */
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
}
