package dev.demo.vaadin.aigridfilter.ai.operator;

import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Translates an {@link OperatorCriteria} into a JPA {@link Specification}: one predicate per given
 * field, all AND-combined, where each predicate is chosen by that field's {@link Operator} and then
 * optionally flipped by its {@code negate} flag. {@code null} criteria (e.g. when the LLM never
 * called the search tool) matches every customer.
 * <p>
 * Unlike variant 02(a)'s {@code FlatSpecifications}, the comparison is no longer hard-wired per
 * field — text fields can do CONTAINS / EQUALS / STARTS_WITH / ENDS_WITH, and dates get real
 * day-level bounds instead of whole-calendar-year matching. What is still impossible here is a
 * <em>second</em> predicate on the same field, so no OR of values and no range.
 */
public final class OperatorSpecifications {

    private OperatorSpecifications() {
    }

    public static Specification<Customer> from(OperatorCriteria criteria) {
        if (criteria == null) {
            return (root, query, cb) -> cb.conjunction();
        }

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            var address = root.get("address");

            addText(predicates, cb, root.get("companyName"), criteria.companyName());
            addText(predicates, cb, root.get("contactName"), criteria.contactName());
            addText(predicates, cb, root.get("email"), criteria.email());
            addText(predicates, cb, root.get("phone"), criteria.phone());
            addText(predicates, cb, address.get("country"), criteria.country());
            addText(predicates, cb, address.get("city"), criteria.city());
            addText(predicates, cb, address.get("postalCode"), criteria.postalCode());
            addText(predicates, cb, address.get("street"), criteria.street());
            addText(predicates, cb, address.get("houseNumber"), criteria.houseNumber());

            addDate(predicates, cb, root.get("customerSince"), criteria.customerSince());
            addDate(predicates, cb, root.get("lastOrderDate"), criteria.lastOrderDate());

            addCreditRating(predicates, cb, root.get("creditScore"), criteria.creditRating());
            addNumber(predicates, cb, root.get("annualRevenue"), criteria.annualRevenue());

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

    /** Real day-level bounds — no whole-year normalization, unlike variant 02(a). */
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

    private static void addNumber(List<Predicate> predicates, CriteriaBuilder cb, Path<BigDecimal> path,
                                  FieldCriterion<BigDecimal> criterion) {
        if (criterion == null) {
            return;
        }
        BigDecimal value = criterion.value();
        Predicate predicate = switch (criterion.operator()) {
            case EQUALS, CONTAINS -> cb.equal(path, value);
            case LESS_OR_EQUAL -> cb.lessThanOrEqualTo(path, value);
            case GREATER_OR_EQUAL -> cb.greaterThanOrEqualTo(path, value);
            case STARTS_WITH, ENDS_WITH -> cb.conjunction(); // not meaningful for numbers -> ignore
        };
        predicates.add(negateIfNeeded(cb, predicate, criterion));
    }

    /**
     * Translates a credit rating into its credit-score band. The operator is ignored (a rating is a
     * discrete label, so only equality is meaningful), but {@code negate} still applies — "not at
     * risk" is expressible.
     */
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
