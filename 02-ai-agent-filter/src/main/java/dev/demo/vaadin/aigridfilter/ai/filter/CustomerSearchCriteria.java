package dev.demo.vaadin.aigridfilter.ai.filter;

import dev.demo.vaadin.aigridfilter.data.CreditRating;

import java.time.LocalDate;
import java.util.List;

/**
 * The flat set of filter values extracted from a natural-language query via tool calling. Every
 * field is optional ({@code null} or empty means "don't filter on this"); a field with multiple
 * values matches any of them (OR), and all given fields are combined with AND by
 * {@link CustomerSpecifications#from}. Deliberately flat, with no per-field operator or negation —
 * the demo-relevant contrast with {@code 03-ai-structured-filter}'s {@code CustomerFilter}, whose
 * conditions carry an explicit operator and a {@code negate} flag (though it too is a flat list of
 * conditions, not a tree).
 * <p>
 * Each {@code customerSince}/{@code lastOrderDate} value is interpreted as the full year it falls
 * in (Jan 1 - Dec 31). {@code annualRevenue} is a list of {@link RevenueRange} bounds rather than
 * a list of exact values, since revenue is a continuous field, not a discrete one like {@code
 * city} or {@code creditRating}.
 */
public record CustomerSearchCriteria(
        List<String> companyName,
        List<String> contactName,
        List<String> email,
        List<String> phone,
        List<LocalDate> customerSince,
        List<LocalDate> lastOrderDate,
        List<String> country,
        List<String> city,
        List<String> postalCode,
        List<String> street,
        List<String> houseNumber,
        List<CreditRating> creditRating,
        List<RevenueRange> annualRevenue) {
}
