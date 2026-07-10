package dev.demo.vaadin.aigridfilter.ai.filter;

import dev.demo.vaadin.aigridfilter.data.Customer;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Translates a {@link CustomerSearchCriteria} into a JPA {@link Specification} — a flat
 * AND-conjunction of one predicate per non-empty field, where a field with multiple values
 * becomes an OR of that field's per-value predicates. {@code null} criteria (e.g. when the LLM
 * never called the search tool) matches every customer.
 */
public final class CustomerSpecifications {

    private CustomerSpecifications() {
    }

    public static Specification<Customer> from(CustomerSearchCriteria criteria) {
        if (criteria == null) {
            return (root, query, criteriaBuilder) -> criteriaBuilder.conjunction();
        }

        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            var address = root.get("address");

            addLikeAnyOf(predicates, criteriaBuilder, root.get("companyName"), criteria.companyName());
            addLikeAnyOf(predicates, criteriaBuilder, root.get("contactName"), criteria.contactName());
            addLikeAnyOf(predicates, criteriaBuilder, root.get("email"), criteria.email());
            addLikeAnyOf(predicates, criteriaBuilder, root.get("phone"), criteria.phone());
            addLikeAnyOf(predicates, criteriaBuilder, address.get("country"), criteria.country());
            addLikeAnyOf(predicates, criteriaBuilder, address.get("city"), criteria.city());
            addLikeAnyOf(predicates, criteriaBuilder, address.get("postalCode"), criteria.postalCode());
            addLikeAnyOf(predicates, criteriaBuilder, address.get("street"), criteria.street());
            addLikeAnyOf(predicates, criteriaBuilder, address.get("houseNumber"), criteria.houseNumber());

            addYearAnyOf(predicates, criteriaBuilder, root.<LocalDate>get("customerSince"), criteria.customerSince());
            addYearAnyOf(predicates, criteriaBuilder, root.<LocalDate>get("lastOrderDate"), criteria.lastOrderDate());

            if (criteria.creditRating() != null && !criteria.creditRating().isEmpty()) {
                var creditScore = root.<Integer>get("creditScore");
                addAnyOf(predicates, criteriaBuilder, criteria.creditRating(), rating ->
                        criteriaBuilder.between(creditScore, rating.minScoreInclusive(), rating.maxScoreInclusive()));
            }

            if (criteria.annualRevenue() != null && !criteria.annualRevenue().isEmpty()) {
                var revenue = root.<BigDecimal>get("annualRevenue");
                addAnyOf(predicates, criteriaBuilder, criteria.annualRevenue(), range ->
                        revenueRangePredicate(criteriaBuilder, revenue, range));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** Adds a case-insensitive substring match against any of {@code values} (OR), if any given. */
    private static void addLikeAnyOf(List<Predicate> predicates, CriteriaBuilder cb, Path<String> path,
                                      List<String> values) {
        if (values == null) {
            return;
        }
        addAnyOf(predicates, cb, values.stream().filter(v -> v != null && !v.isEmpty()).toList(),
                value -> cb.like(cb.lower(path), "%" + value.toLowerCase() + "%"));
    }

    /**
     * Adds a match against any of {@code dates} (OR), where each date stands for the full year it
     * falls in (Jan 1 - Dec 31), if any given.
     */
    private static void addYearAnyOf(List<Predicate> predicates, CriteriaBuilder cb, Path<LocalDate> path,
                                      List<LocalDate> dates) {
        if (dates == null) {
            return;
        }
        addAnyOf(predicates, cb, dates, date -> cb.between(path,
                LocalDate.of(date.getYear(), 1, 1), LocalDate.of(date.getYear(), 12, 31)));
    }

    /** Adds the OR of one predicate per value, built by {@code toPredicate}, if any given. */
    private static <T> void addAnyOf(List<Predicate> predicates, CriteriaBuilder cb, List<T> values,
                                      Function<T, Predicate> toPredicate) {
        if (values == null || values.isEmpty()) {
            return;
        }
        predicates.add(cb.or(values.stream().map(toPredicate).toArray(Predicate[]::new)));
    }

    /** {@code atLeast}/{@code atMost} are each optional, for an open-ended range ("over X", "under Y"). */
    private static Predicate revenueRangePredicate(CriteriaBuilder cb, Path<BigDecimal> revenue, RevenueRange range) {
        List<Predicate> bounds = new ArrayList<>();
        if (range.atLeast() != null) {
            bounds.add(cb.greaterThanOrEqualTo(revenue, range.atLeast()));
        }
        if (range.atMost() != null) {
            bounds.add(cb.lessThanOrEqualTo(revenue, range.atMost()));
        }
        return cb.and(bounds.toArray(new Predicate[0]));
    }
}
