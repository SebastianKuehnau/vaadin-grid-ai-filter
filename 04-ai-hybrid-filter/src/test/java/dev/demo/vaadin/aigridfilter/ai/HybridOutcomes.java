package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQuery;
import dev.demo.vaadin.aigridfilter.canonicalquery.Outcome;

/**
 * What the hybrid tool call can express, for every query of the canonical set — the single statement this variant
 * makes about itself. Both of its integration tests read it, the one through the service and the one
 * through the UI, so they cannot drift apart.
 * <p>
 * 03's filter type, copied 1:1 — several values plus a negate flag per condition, and a range as two
 * sibling conditions on one field — so all eight categories are expressible. That this module reaches the
 * same eight as 03 while delivering the filter through a tool call is the repository's closing argument.
 *
 * An exhaustive {@code switch} on purpose: adding a query to the shared set without deciding what it means
 * for this variant then fails to compile instead of failing at runtime.
 */
final class HybridOutcomes {

    private HybridOutcomes() {
    }

    static Outcome of(CanonicalQuery canonical) {
        return switch (canonical) {
            case C1_SINGLE_VALUE, C2_MULTI_VALUE_OR, C3_NEGATION, C4_OPERATOR_PRECISION,
                 C5_COMBINED_AND, C6_REVENUE_RANGE, C7_RELATIVE_DATE, C8_DATE_RANGE -> Outcome.SUCCESS;
        };
    }
}
