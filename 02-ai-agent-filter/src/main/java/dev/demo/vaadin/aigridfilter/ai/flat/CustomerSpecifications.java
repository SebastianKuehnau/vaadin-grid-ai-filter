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

            addEquals(predicates, cb, root.get("companyName"), criteria.companyName());
            addEquals(predicates, cb, root.get("contactName"), criteria.contactName());
            addEquals(predicates, cb, root.get("email"), criteria.email());
            addEquals(predicates, cb, root.get("phone"), criteria.phone());
            addEquals(predicates, cb, address.get("country"), criteria.country());
            addEquals(predicates, cb, address.get("city"), criteria.city());
            addEquals(predicates, cb, address.get("postalCode"), criteria.postalCode());
            addEquals(predicates, cb, address.get("street"), criteria.street());
            addEquals(predicates, cb, address.get("houseNumber"), criteria.houseNumber());

            addDate(predicates, cb, root.get("customerSince"), criteria.customerSince());
            addDate(predicates, cb, root.get("lastOrderDate"), criteria.lastOrderDate());

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

    /** Adds a case-insensitive match on the whole field, if a value is given. */
    private static void addEquals(List<Predicate> predicates, CriteriaBuilder cb, Path<String> path, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        predicates.add(cb.equal(cb.lower(path), value.toLowerCase()));
    }

    /** Adds a match on that exact day, if a date is given. */
    private static void addDate(List<Predicate> predicates, CriteriaBuilder cb, Path<LocalDate> path, LocalDate date) {
        if (date == null) {
            return;
        }
        predicates.add(cb.equal(path, date));
    }
}
