package dev.demo.vaadin.aigridfilter.ai.flat;

import dev.demo.vaadin.aigridfilter.data.Customer;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Translates a {@link CustomerCriteria} into a JPA {@link Specification}, one predicate per set field. */
public final class CustomerSpecifications {

    private CustomerSpecifications() {
    }

    public static Specification<Customer> from(CustomerCriteria criteria) {
        if (criteria == null) {
            return (root, query, cb) -> cb.conjunction();
        }

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            var address = root.get("address");

            addLike(predicates, cb, root.get("companyName"), criteria.companyName());
            addLike(predicates, cb, root.get("contactName"), criteria.contactName());
            addLike(predicates, cb, root.get("email"), criteria.email());
            addLike(predicates, cb, root.get("phone"), criteria.phone());
            addLike(predicates, cb, address.get("country"), criteria.country());
            addLike(predicates, cb, address.get("city"), criteria.city());
            addLike(predicates, cb, address.get("postalCode"), criteria.postalCode());
            addLike(predicates, cb, address.get("street"), criteria.street());
            addLike(predicates, cb, address.get("houseNumber"), criteria.houseNumber());

            addYear(predicates, cb, root.get("customerSince"), criteria.customerSince());
            addYear(predicates, cb, root.get("lastOrderDate"), criteria.lastOrderDate());

            if (criteria.creditRating() != null) {
                var creditScore = root.<Integer>get("creditScore");
                predicates.add(cb.between(creditScore, criteria.creditRating().minScoreInclusive(),
                        criteria.creditRating().maxScoreInclusive()));
            }

            if (criteria.annualRevenue() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<BigDecimal>get("annualRevenue"),
                        criteria.annualRevenue()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** Adds a case-insensitive substring match, if a value is given. */
    private static void addLike(List<Predicate> predicates, CriteriaBuilder cb, Path<String> path, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        predicates.add(cb.like(cb.lower(path), "%" + value.toLowerCase() + "%"));
    }

    /** Adds a match on the whole calendar year the given date falls in, if a date is given. */
    private static void addYear(List<Predicate> predicates, CriteriaBuilder cb, Path<LocalDate> path, LocalDate date) {
        if (date == null) {
            return;
        }
        predicates.add(cb.between(path, LocalDate.of(date.getYear(), 1, 1), LocalDate.of(date.getYear(), 12, 31)));
    }
}
