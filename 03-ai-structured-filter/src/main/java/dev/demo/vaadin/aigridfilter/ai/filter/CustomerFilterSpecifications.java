package dev.demo.vaadin.aigridfilter.ai.filter;

import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.And;
import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.Condition;
import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.Not;
import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.Or;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Translates a {@link CustomerFilter} tree (as produced by the LLM) into a JPA {@link Specification},
 * so filtering and paging happen in the database.
 * <p>
 * The translation is a straightforward recursive walk of the {@link FilterNode} tree:
 * <ul>
 *   <li>{@link And} → {@link CriteriaBuilder#and}</li>
 *   <li>{@link Or} → {@link CriteriaBuilder#or}</li>
 *   <li>{@link Not} → {@link CriteriaBuilder#not}</li>
 *   <li>{@link Condition} → one of the field-specific predicate builders below</li>
 * </ul>
 * An empty {@code And}/{@code Or} (no children) matches everything, as does a {@code null} filter/root.
 */
public final class CustomerFilterSpecifications {

    private static final Logger logger = LoggerFactory.getLogger(CustomerFilterSpecifications.class);

    private static final Set<String> ADDRESS_FIELDS =
            Set.of("street", "houseNumber", "postalCode", "city", "state", "country", "countryCode");
    private static final Set<String> TEXT_FIELDS =
            Set.of("companyName", "contactName", "email", "phone");
    private static final Set<String> DATE_FIELDS =
            Set.of("customerSince", "lastOrderDate");
    private static final Set<String> NUMBER_FIELDS =
            Set.of("annualRevenue", "creditScore");

    /** Discrete credit-rating field: its value is a {@link CreditRating} (GOOD/MEDIUM/POOR). */
    private static final String CREDIT_RATING_FIELD = "creditRating";

    private CustomerFilterSpecifications() {
    }

    /** Builds a {@link Specification} from a filter tree. A {@code null} filter/root matches everything. */
    public static Specification<Customer> from(CustomerFilter filter) {
        return (root, query, cb) -> {
            if (filter == null || filter.root() == null) {
                return cb.conjunction();
            }
            return toPredicate(root, cb, filter.root());
        };
    }

    /** Recursively translates one {@link FilterNode} (and its children) into a {@link Predicate}. */
    private static Predicate toPredicate(Root<Customer> root, CriteriaBuilder cb, FilterNode node) {
        return switch (node) {
            case Condition c -> conditionPredicate(root, cb, c);
            case And a -> combine(root, cb, a.children(), cb::and);
            case Or o -> combine(root, cb, o.children(), cb::or);
            case Not n -> cb.not(toPredicate(root, cb, n.child()));
        };
    }

    /** Combines the predicates of {@code children} with {@code combiner}; an empty list matches everything. */
    private static Predicate combine(Root<Customer> root, CriteriaBuilder cb, List<FilterNode> children,
                                     Function<Predicate[], Predicate> combiner) {
        if (children == null || children.isEmpty()) {
            return cb.conjunction();
        }
        List<Predicate> predicates = new ArrayList<>();
        for (FilterNode child : children) {
            if (child != null) {
                predicates.add(toPredicate(root, cb, child));
            }
        }
        return predicates.isEmpty() ? cb.conjunction() : combiner.apply(predicates.toArray(new Predicate[0]));
    }

    private static Predicate conditionPredicate(Root<Customer> root, CriteriaBuilder cb, Condition condition) {
        if (condition == null || condition.field() == null
                || condition.value() == null || condition.value().isBlank()) {
            return cb.conjunction();
        }

        String field = condition.field();
        Operator operator = condition.operator() == null ? Operator.CONTAINS : condition.operator();
        String value = condition.value();

        if (CREDIT_RATING_FIELD.equals(field)) {
            return creditRatingPredicate(root, cb, operator, value);
        }
        if (DATE_FIELDS.contains(field)) {
            return datePredicate(root, cb, field, operator, value);
        }
        if (NUMBER_FIELDS.contains(field)) {
            return numberPredicate(root, cb, field, operator, value);
        }
        if (TEXT_FIELDS.contains(field)) {
            return textPredicate(root.get(field), cb, operator, value);
        }
        if (ADDRESS_FIELDS.contains(field)) {
            return textPredicate(root.get("address").get(field), cb, operator, value);
        }

        logger.warn("Ignoring unknown filter field: {}", field);
        return cb.conjunction();
    }

    private static Predicate datePredicate(Root<Customer> root, CriteriaBuilder cb,
                                           String field, Operator operator, String value) {
        LocalDate date = LocalDate.parse(value);
        Path<LocalDate> path = root.get(field);
        return switch (operator) {
            case EQUALS -> cb.equal(path, date);
            case NOT_EQUALS, NOT_CONTAINS -> cb.notEqual(path, date);
            case LESS_OR_EQUAL -> cb.lessThanOrEqualTo(path, date);
            case GREATER_OR_EQUAL, CONTAINS -> cb.greaterThanOrEqualTo(path, date);
            case STARTS_WITH, ENDS_WITH -> cb.conjunction(); // not meaningful for dates -> ignore
        };
    }

    private static Predicate numberPredicate(Root<Customer> root, CriteriaBuilder cb,
                                             String field, Operator operator, String value) {
        BigDecimal number = new BigDecimal(value);
        Path<BigDecimal> path = root.get(field);
        return switch (operator) {
            case EQUALS, CONTAINS -> cb.equal(path, number);
            case NOT_EQUALS, NOT_CONTAINS -> cb.notEqual(path, number);
            case LESS_OR_EQUAL -> cb.lessThanOrEqualTo(path, number);
            case GREATER_OR_EQUAL -> cb.greaterThanOrEqualTo(path, number);
            case STARTS_WITH, ENDS_WITH -> cb.conjunction(); // not meaningful for numbers -> ignore
        };
    }

    /**
     * Translates a credit rating into a creditScore condition. The bound is open-ended where it makes
     * sense (GOOD -> {@code >= 70}, POOR -> {@code <= 39}). NOT_* negates it.
     */
    private static Predicate creditRatingPredicate(Root<Customer> root, CriteriaBuilder cb,
                                                   Operator operator, String value) {
        CreditRating rating = parseRating(value);
        if (rating == null) {
            return cb.conjunction();
        }
        Path<Integer> score = root.get("creditScore");
        int min = rating.minScoreInclusive();
        int max = rating.maxScoreInclusive();
        Predicate inBand;
        if (min == Integer.MIN_VALUE) {
            inBand = cb.lessThanOrEqualTo(score, max);
        } else if (max == Integer.MAX_VALUE) {
            inBand = cb.greaterThanOrEqualTo(score, min);
        } else {
            inBand = cb.between(score, min, max);
        }
        return (operator == Operator.NOT_EQUALS || operator == Operator.NOT_CONTAINS) ? cb.not(inBand) : inBand;
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
        String likePattern = "%" + lowerValue + "%";
        return switch (operator) {
            case CONTAINS -> cb.like(lower, likePattern);
            case NOT_CONTAINS -> cb.notLike(lower, likePattern);
            case EQUALS -> cb.equal(lower, lowerValue);
            case NOT_EQUALS -> cb.notEqual(lower, lowerValue);
            case STARTS_WITH -> cb.like(lower, lowerValue + "%");
            case ENDS_WITH -> cb.like(lower, "%" + lowerValue);
            case GREATER_OR_EQUAL -> cb.greaterThanOrEqualTo(lower, lowerValue);
            case LESS_OR_EQUAL -> cb.lessThanOrEqualTo(lower, lowerValue);
        };
    }
}
