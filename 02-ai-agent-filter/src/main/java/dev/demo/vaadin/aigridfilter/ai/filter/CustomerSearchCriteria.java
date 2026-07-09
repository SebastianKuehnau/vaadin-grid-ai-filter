package dev.demo.vaadin.aigridfilter.ai.filter;

import dev.demo.vaadin.aigridfilter.data.CreditRating;

import java.time.LocalDate;

/**
 * The flat set of filter values extracted from a natural-language query via tool calling. Every
 * field is optional ({@code null} means "don't filter on this"); all given fields are combined
 * with AND by {@link CustomerSpecifications#from}. Deliberately flat (no AND/OR/NOT tree) — the
 * demo-relevant contrast with {@code 03-ai-structured-filter}'s {@code FilterNode} tree.
 */
public record CustomerSearchCriteria(
        String companyName,
        String contactName,
        String email,
        String phone,
        LocalDate customerSince,
        LocalDate lastOrderDate,
        String country,
        String city,
        String postalCode,
        String street,
        String houseNumber,
        CreditRating creditRating) {
}
