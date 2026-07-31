package dev.demo.vaadin.aigridfilter.ai.operator;

import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQuery;
import dev.demo.vaadin.aigridfilter.canonicalquery.Outcome;

/**
 * What variant 02(b) can express, for every query of the canonical set — the single statement this variant
 * makes about itself. Both of its integration tests read it, the one through the service and the one
 * through the UI, so they cannot drift apart.
 * <p>
 * An operator and a negate flag per field buy negation, operator precision and day-level date bounds.
 * What one value and one operator per field still cannot hold is a second value or a second bound — that
 * is this variant's ceiling and the reason the ladder continues.
 *
 * An exhaustive {@code switch} on purpose: adding a query to the shared set without deciding what it means
 * for this variant then fails to compile instead of failing at runtime.
 */
public final class OperatorOutcomes {

    private OperatorOutcomes() {
    }

    public static Outcome of(CanonicalQuery canonical) {
        return switch (canonical) {
            case C1_SINGLE_VALUE, C3_NEGATION, C4_OPERATOR_PRECISION, C5_COMBINED_AND,
                 C7_RELATIVE_DATE -> Outcome.SUCCESS;
            case C2_MULTI_VALUE_OR, C6_REVENUE_RANGE, C8_DATE_RANGE -> Outcome.FAIL_BY_DESIGN;
        };
    }
}
