package dev.demo.vaadin.aigridfilter.canonicalquery;

import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The canonical query set of {@code docs/canonical-query-set.md}: eight natural-language queries, one per
 * capability category, each with the customer set a correct answer selects.
 * <p>
 * Defined once here and shared by every AI module — through the service (`*AiFilterIT`) and through
 * the UI (`*CustomerListViewBrowserlessIT`), so the two paths and the four variants are measured on
 * literally the same input. What a constant deliberately does <em>not</em> carry is an {@link Outcome}:
 * whether a query is expressible depends on the asking module's filter type, so each module states that
 * for itself.
 * <p>
 * Expectations are predicates over {@link Customer}, evaluated against whatever {@code data.sql} currently
 * seeds — never a hard-coded list of ids. C7 depends on today's date, and each app's startup moves one
 * row's last order date.
 * <p>
 * This enum, {@code docs/canonical-query-set.md} and the benchmark script's own copy have to be kept in
 * sync by hand.
 */
public enum CanonicalQuery {

    /** C1 — single value. */
    C1_SINGLE_VALUE("show me all customers in Berlin",
            customer -> containsIgnoringCase(city(customer), "Berlin")),

    /** C2 — multiple values for one field (OR). */
    C2_MULTI_VALUE_OR("show me customers from Berlin or Hamburg",
            customer -> containsIgnoringCase(city(customer), "Berlin")
                    || containsIgnoringCase(city(customer), "Hamburg")),

    /** C3 — negation. */
    C3_NEGATION("show me all customers except from Berlin",
            customer -> !containsIgnoringCase(city(customer), "Berlin")),

    /** C4 — non-CONTAINS operator (starts-with). */
    C4_OPERATOR_PRECISION("show me all customers with an \"m\" as the first character in the contact name",
            customer -> startsWithIgnoringCase(customer.getContactName(), "m")),

    /** C5 — combined AND across fields. */
    C5_COMBINED_AND("creditworthy customers in Hamburg",
            customer -> containsIgnoringCase(city(customer), "Hamburg")
                    && customer.getCreditRating() == CreditRating.GOOD),

    /** C6 — revenue range. */
    C6_REVENUE_RANGE("customers with revenue between 100000 and 200000",
            customer -> isBetween(customer.getAnnualRevenue(), 100_000, 200_000)),

    /**
     * C7 — relative date. Both readings of "the last 12 months" are accepted: the open-ended one and the
     * one that also excludes the single future-dated order in the seed data.
     */
    C7_RELATIVE_DATE("show me all customers who placed an order in the last 12 months",
            List.of(customer -> !customer.getLastOrderDate().isBefore(LocalDate.now().minusYears(1)),
                    customer -> !customer.getLastOrderDate().isBefore(LocalDate.now().minusYears(1))
                            && !customer.getLastOrderDate().isAfter(LocalDate.now()))),

    /** C8 — date range, spanning two calendar years on purpose. */
    C8_DATE_RANGE("customers who last ordered between 2024-07-01 and 2025-03-31",
            customer -> isBetween(customer.getLastOrderDate(),
                    LocalDate.of(2024, 7, 1), LocalDate.of(2025, 3, 31)));

    private final String query;
    private final List<Predicate<Customer>> acceptableExpectations;

    CanonicalQuery(String query, Predicate<Customer> expected) {
        this(query, List.of(expected));
    }

    CanonicalQuery(String query, List<Predicate<Customer>> acceptableExpectations) {
        this.query = query;
        this.acceptableExpectations = acceptableExpectations;
    }

    /** The natural-language query, verbatim as documented in {@code docs/canonical-query-set.md}. */
    public String query() {
        return query;
    }

    /** The customer-id sets that count as a correct answer for this query, in the given data. */
    public List<Set<Long>> acceptableIdSets(List<Customer> allCustomers) {
        return acceptableExpectations.stream()
                .map(expected -> allCustomers.stream().filter(expected)
                        .map(Customer::getId).collect(Collectors.toSet()))
                .toList();
    }

    private static String city(Customer customer) {
        return customer.getAddress() == null ? null : customer.getAddress().getCity();
    }

    private static boolean containsIgnoringCase(String value, String part) {
        return value != null && value.toLowerCase().contains(part.toLowerCase());
    }

    private static boolean startsWithIgnoringCase(String value, String prefix) {
        return value != null && value.toLowerCase().startsWith(prefix.toLowerCase());
    }

    private static boolean isBetween(BigDecimal value, long lowerInclusive, long upperInclusive) {
        return value != null && value.compareTo(BigDecimal.valueOf(lowerInclusive)) >= 0
                && value.compareTo(BigDecimal.valueOf(upperInclusive)) <= 0;
    }

    private static boolean isBetween(LocalDate value, LocalDate lowerInclusive, LocalDate upperInclusive) {
        return value != null && !value.isBefore(lowerInclusive) && !value.isAfter(upperInclusive);
    }
}
