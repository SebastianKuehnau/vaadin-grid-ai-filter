package dev.demo.vaadin.aigridfilter.ai.filter;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** A single leaf condition: compare one customer {@code field} with a {@code value} using {@code operator}. */
public record FilterCriterion(
        @JsonPropertyDescription("the customer field to filter on; one of: companyName, contactName, email, phone, annualRevenue, creditRating, customerSince, lastOrderDate, country, city, postalCode, street, houseNumber, state, countryCode")
        String field,
        @JsonPropertyDescription("how to compare the field with the value")
        Operator operator,
        @JsonPropertyDescription("the value to compare against, as text (ISO date yyyy-MM-dd for date fields, a plain number for annualRevenue, one of GOOD/MEDIUM/POOR for creditRating)")
        String value) {
}