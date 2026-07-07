package dev.demo.vaadin.aigridfilter.ai.filter;

import dev.demo.vaadin.aigridfilter.data.Customer;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Translates a {@link CustomerSearchCriteria} into a JPA {@link Specification} — a flat
 * AND-conjunction of one predicate per non-null field. {@code null} criteria (e.g. when the LLM
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

            if (criteria.companyName() != null && !criteria.companyName().isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("companyName")),
                        "%" + criteria.companyName().toLowerCase() + "%"));
            }

            if (criteria.contactName() != null && !criteria.contactName().isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("contactName")),
                        "%" + criteria.contactName().toLowerCase() + "%"));
            }

            if (criteria.email() != null && !criteria.email().isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("email")),
                        "%" + criteria.email().toLowerCase() + "%"));
            }

            if (criteria.phone() != null && !criteria.phone().isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("phone")),
                        "%" + criteria.phone().toLowerCase() + "%"));
            }

            if (criteria.customerSince() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                        root.<LocalDate>get("customerSince"), criteria.customerSince()));
            }

            if (criteria.lastOrderDate() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(
                        root.<LocalDate>get("lastOrderDate"), criteria.lastOrderDate()));
            }

            var address = root.get("address");

            if (criteria.country() != null && !criteria.country().isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(address.get("country")),
                        "%" + criteria.country().toLowerCase() + "%"));
            }

            if (criteria.city() != null && !criteria.city().isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(address.get("city")),
                        "%" + criteria.city().toLowerCase() + "%"));
            }

            if (criteria.postalCode() != null && !criteria.postalCode().isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(address.get("postalCode")),
                        "%" + criteria.postalCode().toLowerCase() + "%"));
            }

            if (criteria.street() != null && !criteria.street().isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(address.get("street")),
                        "%" + criteria.street().toLowerCase() + "%"));
            }

            if (criteria.houseNumber() != null && !criteria.houseNumber().isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(address.get("houseNumber")),
                        "%" + criteria.houseNumber().toLowerCase() + "%"));
            }

            if (criteria.creditRating() != null) {
                predicates.add(criteriaBuilder.between(root.get("creditScore"),
                        criteria.creditRating().minScoreInclusive(), criteria.creditRating().maxScoreInclusive()));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
