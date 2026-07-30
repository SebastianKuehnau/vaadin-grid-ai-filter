package dev.demo.vaadin.aigridfilter.canonicalquery;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The canonical query set — one query per capability category, shared by every AI module's
 * canonical-query IT and by the standalone benchmark script.
 * <p>
 * {@code docs/canonical-query-set.md} is the single source of truth for every query string below;
 * {@code CanonicalQuerySetConsistencyTest} fails the build if this enum and that document drift apart,
 * in wording or in order.
 * <p>
 * Each constant carries the query and the customer set(s) that count as correct — always as a predicate
 * over the seeded data, never as a hard-coded id list, because C7 depends on today's date and each app's
 * startup moves one row's last order date.
 * <p>
 * What a constant deliberately does <em>not</em> carry is an {@link Outcome}: whether a query is
 * expressible depends on the filter type of the module asking, so each IT supplies its own mapping.
 */
public enum CanonicalQuery {

    /** C1 — single value. */
    C1_SINGLE_VALUE("show me all customers in Berlin",
            customer -> containsIgnoringCase(customer.city(), "Berlin")),

    /** C2 — multiple values for one field (OR). */
    C2_MULTI_VALUE_OR("show me customers from Berlin or Hamburg",
            customer -> containsIgnoringCase(customer.city(), "Berlin")
                    || containsIgnoringCase(customer.city(), "Hamburg")),

    /** C3 — negation. */
    C3_NEGATION("show me all customers except from Berlin",
            customer -> !containsIgnoringCase(customer.city(), "Berlin")),

    /** C4 — non-CONTAINS operator (starts-with). */
    C4_OPERATOR_PRECISION("show me all customers with an \"m\" as the first character in the contact name",
            customer -> startsWithIgnoringCase(customer.contactName(), "m")),

    /** C5 — combined AND across fields. */
    C5_COMBINED_AND("creditworthy customers in Hamburg",
            customer -> containsIgnoringCase(customer.city(), "Hamburg") && customer.creditworthy()),

    /** C6 — revenue range. */
    C6_REVENUE_RANGE("customers with revenue between 100000 and 200000",
            customer -> isBetween(customer.annualRevenue(), 100_000, 200_000)),

    /**
     * C7 — relative date. Both readings of "the last 12 months" are accepted: the open-ended one and
     * the one that also excludes the single future-dated order in the seed data.
     */
    C7_RELATIVE_DATE("show me all customers who placed an order in the last 12 months",
            List.of(customer -> !customer.lastOrderDate().isBefore(LocalDate.now().minusYears(1)),
                    customer -> !customer.lastOrderDate().isBefore(LocalDate.now().minusYears(1))
                            && !customer.lastOrderDate().isAfter(LocalDate.now()))),

    /** C8 — date range, spanning two calendar years on purpose. */
    C8_DATE_RANGE("customers who last ordered between 2024-07-01 and 2025-03-31",
            customer -> isBetween(customer.lastOrderDate(),
                    LocalDate.of(2024, 7, 1), LocalDate.of(2025, 3, 31)));

    private final String query;
    private final List<Predicate<CanonicalCustomer>> acceptableExpectations;

    CanonicalQuery(String query, Predicate<CanonicalCustomer> expected) {
        this(query, List.of(expected));
    }

    CanonicalQuery(String query, List<Predicate<CanonicalCustomer>> acceptableExpectations) {
        this.query = query;
        this.acceptableExpectations = acceptableExpectations;
    }

    /** The natural-language query, verbatim as documented in {@code docs/canonical-query-set.md}. */
    public String query() {
        return query;
    }

    /** The customer-id sets that count as a correct answer for this query, in the given data. */
    public List<Set<Long>> acceptableIdSets(List<CanonicalCustomer> allCustomers) {
        return acceptableExpectations.stream()
                .map(expected -> allCustomers.stream().filter(expected)
                        .map(CanonicalCustomer::id).collect(Collectors.toSet()))
                .toList();
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
