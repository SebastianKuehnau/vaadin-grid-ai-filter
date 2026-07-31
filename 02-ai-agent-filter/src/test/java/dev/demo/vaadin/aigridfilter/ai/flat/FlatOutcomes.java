package dev.demo.vaadin.aigridfilter.ai.flat;

import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQuery;
import dev.demo.vaadin.aigridfilter.canonicalquery.Outcome;

/**
 * What variant 02(a) can express, for every query of the canonical set — the single statement this variant
 * makes about itself. Both of its integration tests read it, the one through the service and the one
 * through the UI, so they cannot drift apart.
 * <p>
 * One scalar value per field and no operator at all, so only the two simplest categories are within
 * reach. Multi-value OR, negation, operator precision and every kind of range or date bound are
 * architecturally impossible — the tool has no parameter that could carry them.
 *
 * An exhaustive {@code switch} on purpose: adding a query to the shared set without deciding what it means
 * for this variant then fails to compile instead of failing at runtime.
 */
final class FlatOutcomes {

    private FlatOutcomes() {
    }

    static Outcome of(CanonicalQuery canonical) {
        return switch (canonical) {
            case C1_SINGLE_VALUE, C5_COMBINED_AND -> Outcome.SUCCESS;
            case C2_MULTI_VALUE_OR, C3_NEGATION, C4_OPERATOR_PRECISION, C6_REVENUE_RANGE,
                 C7_RELATIVE_DATE, C8_DATE_RANGE -> Outcome.FAIL_BY_DESIGN;
        };
    }
}
