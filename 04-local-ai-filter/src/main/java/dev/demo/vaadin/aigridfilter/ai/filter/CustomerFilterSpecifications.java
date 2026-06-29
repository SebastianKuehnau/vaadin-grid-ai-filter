package dev.demo.vaadin.aigridfilter.ai.filter;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates a flat {@link CustomerFilter} (as produced by the LLM) into a JPA
 * {@link Specification}, so filtering and paging happen in the database.
 * <p>
 * There is no explicit AND/OR switch. How the criteria combine is fixed and intuitive — and it is
 * resolved <em>here</em>, not by the model:
 * <ul>
 *   <li>Criteria on the <b>same field</b> using {@code CONTAINS}/{@code EQUALS} are alternatives and
 *       are combined with <b>OR</b> (so "Berlin or Hamburg" — or even the colloquial "Berlin and
 *       Hamburg" meaning either city — works).</li>
 *   <li>All other criteria on a field ({@code >=}, {@code <=}, {@code NOT_*}) are constraints and are
 *       combined with <b>AND</b> (so a value range like 100000..500000 works).</li>
 *   <li>Different fields are combined with <b>AND</b> (every field must hold).</li>
 * </ul>
 * This expresses the common {@code (city = Berlin OR city = Hamburg) AND revenue >= 100000} without
 * any nesting. The one thing it cannot do is cross-field OR (e.g. "in Berlin OR revenue > 1M").
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

    /** Builds a {@link Specification} from a flat filter. An empty filter matches everything. */
    public static Specification<Customer> from(CustomerFilter filter) {
        return (root, query, cb) -> {
            if (filter == null || filter.criteria() == null || filter.criteria().isEmpty()) {
                return cb.conjunction();
            }

            // Group criteria by field: same field = alternatives, different fields = all required.
            Map<String, List<FilterCriterion>> byField = new LinkedHashMap<>();
            for (FilterCriterion criterion : filter.criteria()) {
                if (criterion != null && criterion.field() != null) {
                    byField.computeIfAbsent(criterion.field(), _ -> new ArrayList<>()).add(criterion);
                }
            }

            List<Predicate> perField = new ArrayList<>();
            for (List<FilterCriterion> group : byField.values()) {
                Predicate predicate = fieldPredicate(root, cb, group);
                if (predicate != null) {
                    perField.add(predicate);
                }
            }

            return perField.isEmpty() ? cb.conjunction() : cb.and(perField.toArray(new Predicate[0]));
        };
    }

    /** Combines all criteria on a single field: CONTAINS/EQUALS as OR-alternatives, the rest as AND. */
    private static Predicate fieldPredicate(Root<Customer> root, CriteriaBuilder cb, List<FilterCriterion> group) {
        List<Predicate> alternatives = new ArrayList<>(); // CONTAINS / EQUALS / STARTS_WITH / ENDS_WITH -> OR
        List<Predicate> constraints = new ArrayList<>();   // NOT_*, >=, <=                               -> AND

        for (FilterCriterion criterion : group) {
            Predicate predicate = toPredicate(root, cb, criterion);
            if (predicate == null) {
                continue;
            }
            Operator operator = criterion.operator() == null ? Operator.CONTAINS : criterion.operator();
            if (operator == Operator.CONTAINS || operator == Operator.EQUALS
                    || operator == Operator.STARTS_WITH || operator == Operator.ENDS_WITH) {
                alternatives.add(predicate);
            } else {
                constraints.add(predicate);
            }
        }

        List<Predicate> parts = new ArrayList<>();
        if (!alternatives.isEmpty()) {
            parts.add(cb.or(alternatives.toArray(new Predicate[0])));
        }
        parts.addAll(constraints);

        return parts.isEmpty() ? null : cb.and(parts.toArray(new Predicate[0]));
    }

    private static Predicate toPredicate(Root<Customer> root, CriteriaBuilder cb, FilterCriterion criterion) {
        if (criterion == null || criterion.field() == null
                || criterion.value() == null || criterion.value().isBlank()) {
            return null;
        }

        String field = criterion.field();
        Operator operator = criterion.operator() == null ? Operator.CONTAINS : criterion.operator();
        String value = criterion.value();

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
        return null;
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
     * sense (GOOD -> {@code >= 70}, POOR -> {@code <= 39}), so two ratings on this field combine as
     * {@code (creditScore >= 70 OR creditScore <= 39)} via the same-field OR rule. NOT_* negates it.
     */
    private static Predicate creditRatingPredicate(Root<Customer> root, CriteriaBuilder cb,
                                                   Operator operator, String value) {
        CreditRating rating = parseRating(value);
        if (rating == null) {
            return null;
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
