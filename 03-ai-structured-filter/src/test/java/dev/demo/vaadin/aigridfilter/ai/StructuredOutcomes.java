package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQuery;
import dev.demo.vaadin.aigridfilter.canonicalquery.Outcome;

/**
 * What structured output can express, for every query of the canonical set — the single statement this variant
 * makes about itself. Both of its integration tests read it, the one through the service and the one
 * through the UI, so they cannot drift apart.
 * <p>
 * A {@code CustomerFilter} is a flat list of conditions, each with several values (OR within a field) and
 * a negate flag, and a range is two sibling conditions on one field — so nothing in the canonical set is
 * out of reach. {@code 04-ai-hybrid-filter} reaches the same eight with the same filter type, delivered as
 * a tool call.
 *
 * An exhaustive {@code switch} on purpose: adding a query to the shared set without deciding what it means
 * for this variant then fails to compile instead of failing at runtime.
 */
final class StructuredOutcomes {

    private StructuredOutcomes() {
    }

    static Outcome of(CanonicalQuery canonical) {
        return switch (canonical) {
            case C1_SINGLE_VALUE, C2_MULTI_VALUE_OR, C3_NEGATION, C4_OPERATOR_PRECISION,
                 C5_COMBINED_AND, C6_REVENUE_RANGE, C7_RELATIVE_DATE, C8_DATE_RANGE -> Outcome.SUCCESS;
        };
    }
}
