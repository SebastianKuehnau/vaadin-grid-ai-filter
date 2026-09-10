package dev.demo.vaadin.aigridfilter.ai.operator;

import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Translates a {@link CustomerCriteria} into a JPA {@link Specification} using each field's operator. */
public final class CustomerSpecifications {

    private CustomerSpecifications() {
    }

    public static Specification<Customer> from(CustomerCriteria criteria) {
        if (criteria == null) {
            return (root, query, cb) -> cb.conjunction();
        }

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            addText(predicates, cb, root.get("address").get("city"), criteria.city());
            addDate(predicates, cb, root.get("lastOrderDate"), criteria.lastOrderDate());
            addCreditRating(predicates, cb, root.get("creditScore"), criteria.creditRating());

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static void addText(List<Predicate> predicates, CriteriaBuilder cb, Path<String> path,
                                FieldCriterion<String> criterion) {
        if (criterion == null) {
            return;
        }
        Expression<String> lower = cb.lower(path);
        String value = criterion.value().toLowerCase();
        Predicate predicate = switch (criterion.operator()) {
            case CONTAINS -> cb.like(lower, "%" + value + "%");
            case EQUALS -> cb.equal(lower, value);
            case STARTS_WITH -> cb.like(lower, value + "%");
            case ENDS_WITH -> cb.like(lower, "%" + value);
            case GREATER_OR_EQUAL -> cb.greaterThanOrEqualTo(lower, value);
            case LESS_OR_EQUAL -> cb.lessThanOrEqualTo(lower, value);
        };
        predicates.add(negateIfNeeded(cb, predicate, criterion));
    }

    private static void addDate(List<Predicate> predicates, CriteriaBuilder cb, Path<LocalDate> path,
                                FieldCriterion<LocalDate> criterion) {
        if (criterion == null) {
            return;
        }
        LocalDate date = criterion.value();
        Predicate predicate = switch (criterion.operator()) {
            case EQUALS -> cb.equal(path, date);
            case LESS_OR_EQUAL -> cb.lessThanOrEqualTo(path, date);
            case GREATER_OR_EQUAL, CONTAINS -> cb.greaterThanOrEqualTo(path, date);
            case STARTS_WITH, ENDS_WITH -> cb.conjunction(); // not meaningful for dates -> ignore
        };
        predicates.add(negateIfNeeded(cb, predicate, criterion));
    }

    /** Translates a credit rating into its credit-score band; only {@code negate} still applies. */
    private static void addCreditRating(List<Predicate> predicates, CriteriaBuilder cb, Path<Integer> score,
                                        FieldCriterion<CreditRating> criterion) {
        if (criterion == null) {
            return;
        }
        CreditRating rating = criterion.value();
        int min = rating.minScoreInclusive();
        int max = rating.maxScoreInclusive();
        Predicate predicate;
        if (min == Integer.MIN_VALUE) {
            predicate = cb.lessThanOrEqualTo(score, max);
        } else if (max == Integer.MAX_VALUE) {
            predicate = cb.greaterThanOrEqualTo(score, min);
        } else {
            predicate = cb.between(score, min, max);
        }
        predicates.add(negateIfNeeded(cb, predicate, criterion));
    }

    private static Predicate negateIfNeeded(CriteriaBuilder cb, Predicate predicate, FieldCriterion<?> criterion) {
        return criterion.negate() ? cb.not(predicate) : predicate;
    }
}
