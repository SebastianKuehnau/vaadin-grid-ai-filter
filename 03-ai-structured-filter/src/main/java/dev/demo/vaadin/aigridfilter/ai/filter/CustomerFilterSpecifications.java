package dev.demo.vaadin.aigridfilter.ai.filter;

import dev.demo.vaadin.aigridfilter.ai.filter.Condition.Operator;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.List;

/** Translates a {@link CustomerFilter} into a JPA {@link Specification}, so the database does the work. */
public final class CustomerFilterSpecifications {

    private static final Logger logger = LoggerFactory.getLogger(CustomerFilterSpecifications.class);

    private CustomerFilterSpecifications() {
    }

    /** Builds a {@link Specification} from a flat filter. A {@code null}/empty filter matches everything. */
    public static Specification<Customer> from(CustomerFilter filter) {
        if (filter == null || filter.conditions() == null || filter.conditions().isEmpty()) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> {
            Predicate[] predicates = filter.conditions().stream()
                    .map(condition -> conditionPredicate(root, cb, condition))
                    .toArray(Predicate[]::new);
            return cb.and(predicates);
        };
    }

    /** OR's the predicate for each of {@code condition}'s values, then applies {@code negate}. */
    private static Predicate conditionPredicate(Root<Customer> root, CriteriaBuilder cb, Condition condition) {
        if (condition == null || condition.field() == null
                || condition.values() == null || condition.values().isEmpty()) {
            return cb.conjunction();
        }

        String field = condition.field();
        Operator operator = condition.operator() == null ? Operator.CONTAINS : condition.operator();
        List<Predicate> valuePredicates = condition.values().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> valuePredicate(root, cb, field, operator, value))
                .toList();
        if (valuePredicates.isEmpty()) {
            return cb.conjunction();
        }

        Predicate combined = cb.or(valuePredicates.toArray(new Predicate[0]));
        return condition.negate() ? cb.not(combined) : combined;
    }

    /** The three fields this filter knows; anything else the model invents is ignored. */
    private static Predicate valuePredicate(Root<Customer> root, CriteriaBuilder cb,
                                            String field, Operator operator, String value) {
        return switch (field) {
            case "city" -> textPredicate(root.get("address").get("city"), cb, operator, value);
            case "lastOrderDate" -> datePredicate(root.get("lastOrderDate"), cb, operator, value);
            case "creditRating" -> creditRatingPredicate(root, cb, value);
            default -> {
                logger.warn("Ignoring unknown filter field: {}", field);
                yield cb.conjunction();
            }
        };
    }

    private static Predicate datePredicate(Path<LocalDate> path, CriteriaBuilder cb,
                                           Operator operator, String value) {
        LocalDate date = LocalDate.parse(value);
        return switch (operator) {
            case EQUALS -> cb.equal(path, date);
            case LESS_OR_EQUAL -> cb.lessThanOrEqualTo(path, date);
            case GREATER_OR_EQUAL, CONTAINS -> cb.greaterThanOrEqualTo(path, date);
            case STARTS_WITH, ENDS_WITH -> cb.conjunction(); // not meaningful for dates -> ignore
        };
    }

    /** Translates a credit rating into a creditScore condition. Always positive; negation is applied per condition. */
    private static Predicate creditRatingPredicate(Root<Customer> root, CriteriaBuilder cb, String value) {
        CreditRating rating = parseRating(value);
        if (rating == null) {
            return cb.conjunction();
        }
        Path<Integer> score = root.get("creditScore");
        int min = rating.minScoreInclusive();
        int max = rating.maxScoreInclusive();
        if (min == Integer.MIN_VALUE) {
            return cb.lessThanOrEqualTo(score, max);
        }
        if (max == Integer.MAX_VALUE) {
            return cb.greaterThanOrEqualTo(score, min);
        }
        return cb.between(score, min, max);
    }

    /** Resolves a rating value to a {@link CreditRating}: enum name, label, or common synonyms. */
    private static CreditRating parseRating(String value) {
        String v = value.trim();
        for (CreditRating rating : CreditRating.values()) {
            if (rating.name().equalsIgnoreCase(v) || rating.getLabel().equalsIgnoreCase(v)) {
                return rating;
            }
        }
        String lower = v.toLowerCase();
        if (lower.contains("good") || lower.contains("worthy")) {
            return CreditRating.GOOD;
        }
        if (lower.contains("risk") || lower.contains("poor") || lower.contains("bad")) {
            return CreditRating.POOR;
        }
        if (lower.contains("limited") || lower.contains("medium") || lower.contains("moderate")) {
            return CreditRating.MEDIUM;
        }
        logger.warn("Ignoring unknown credit rating value: {}", value);
        return null;
    }

    private static Predicate textPredicate(Path<String> path, CriteriaBuilder cb,
                                           Operator operator, String value) {
        Expression<String> lower = cb.lower(path);
        String lowerValue = value.toLowerCase();
        return switch (operator) {
            case CONTAINS -> cb.like(lower, "%" + lowerValue + "%");
            case EQUALS -> cb.equal(lower, lowerValue);
            case STARTS_WITH -> cb.like(lower, lowerValue + "%");
            case ENDS_WITH -> cb.like(lower, "%" + lowerValue);
            case GREATER_OR_EQUAL -> cb.greaterThanOrEqualTo(lower, lowerValue);
            case LESS_OR_EQUAL -> cb.lessThanOrEqualTo(lower, lowerValue);
        };
    }
}
