package dev.demo.vaadin.aigridfilter.filter;

import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/** Translates a {@link CustomerFilter} into the JPA {@link Specification} the repository filters with. */
public final class CustomerSpecifications {

    private CustomerSpecifications() {
    }

    public static Specification<Customer> matching(CustomerFilter filter) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            Path<?> address = root.get("address");

            if (hasText(filter.companyName())) {
                predicates.add(contains(criteriaBuilder, root.get("companyName"), filter.companyName()));
            }
            if (hasText(filter.contactName())) {
                predicates.add(contains(criteriaBuilder, root.get("contactName"), filter.contactName()));
            }
            if (hasText(filter.email())) {
                predicates.add(contains(criteriaBuilder, root.get("email"), filter.email()));
            }
            if (hasText(filter.city())) {
                predicates.add(contains(criteriaBuilder, address.get("city"), filter.city()));
            }

            // Selected countries are alternatives.
            if (filter.countries() != null && !filter.countries().isEmpty()) {
                predicates.add(address.get("country").in(filter.countries()));
            }

            if (filter.active() != null) {
                predicates.add(criteriaBuilder.equal(root.get("active"), filter.active()));
            }

            // Selected ratings are alternatives, so combine their score ranges with OR.
            if (filter.creditRatings() != null && !filter.creditRatings().isEmpty()) {
                Path<Integer> creditScore = root.get("creditScore");
                List<Predicate> ratingAlternatives = new ArrayList<>();
                for (CreditRating rating : filter.creditRatings()) {
                    ratingAlternatives.add(criteriaBuilder.between(creditScore,
                            rating.minScoreInclusive(), rating.maxScoreInclusive()));
                }
                predicates.add(criteriaBuilder.or(ratingAlternatives.toArray(Predicate[]::new)));
            }

            if (filter.minRevenue() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("annualRevenue"), filter.minRevenue()));
            }
            if (filter.maxRevenue() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("annualRevenue"), filter.maxRevenue()));
            }

            if (filter.customerSinceFrom() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("customerSince"), filter.customerSinceFrom()));
            }
            if (filter.customerSinceTo() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("customerSince"), filter.customerSinceTo()));
            }

            if (filter.lastOrderFrom() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("lastOrderDate"), filter.lastOrderFrom()));
            }
            if (filter.lastOrderTo() != null) {
                predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("lastOrderDate"), filter.lastOrderTo()));
            }

            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Predicate contains(CriteriaBuilder criteriaBuilder, Path<String> field, String value) {
        return criteriaBuilder.like(criteriaBuilder.lower(field), "%" + value.trim().toLowerCase() + "%");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
