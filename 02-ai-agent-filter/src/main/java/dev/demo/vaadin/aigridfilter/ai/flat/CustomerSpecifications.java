package dev.demo.vaadin.aigridfilter.ai.flat;

import dev.demo.vaadin.aigridfilter.data.Customer;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

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

            // The whole field, case-insensitively - not a substring.
            if (criteria.city() != null && !criteria.city().isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("address").get("city")),
                        criteria.city().toLowerCase()));
            }

            // That one exact day - this filter type has no way to express a range.
            if (criteria.lastOrderDate() != null) {
                predicates.add(cb.equal(root.get("lastOrderDate"), criteria.lastOrderDate()));
            }

            // The rating is derived from a score, so it filters as the score band behind it.
            if (criteria.creditRating() != null) {
                predicates.add(cb.between(root.<Integer>get("creditScore"),
                        criteria.creditRating().minScoreInclusive(),
                        criteria.creditRating().maxScoreInclusive()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
