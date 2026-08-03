package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.canonicalquery.AbstractAiFilterIT;
import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQuery;
import dev.demo.vaadin.aigridfilter.canonicalquery.ExpectedResult;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Variant <b>04</b> through its service: the canonical query set and the robustness set, both against a real
 * model. Everything shared — configuration, token bookkeeping, the assert-and-log step — is in
 * {@link AbstractAiFilterIT}.
 * <p>
 * What this variant can express is the statement it makes about itself: 03's filter type, copied 1:1 —
 * several values plus a negate flag per condition, and a range as two sibling conditions on one field — so
 * all eight categories are expressible. That this module reaches the same eight as 03 while delivering the
 * filter through a tool call is the repository's closing argument.
 * <p>
 * An exhaustive {@code switch} on purpose: adding a query to the shared set without deciding what it means
 * for this variant then fails to compile instead of failing at runtime. The UI-level
 * {@code CustomerListViewBrowserlessIT} states the same mapping, so both stop compiling together.
 */
class HybridAiFilterIT extends AbstractAiFilterIT {

    @Autowired
    CustomerSearchAgent agent;

    @Override
    protected CustomerSearchAgent agent() {
        return agent;
    }

    @Override
    protected ExpectedResult expectedResultFor(CanonicalQuery canonical) {
        return switch (canonical) {
            case C1_SINGLE_VALUE, C2_MULTI_VALUE_OR, C3_NEGATION, C4_OPERATOR_PRECISION,
                 C5_COMBINED_AND, C6_REVENUE_RANGE, C7_RELATIVE_DATE, C8_DATE_RANGE -> ExpectedResult.MATCH;
        };
    }
}
