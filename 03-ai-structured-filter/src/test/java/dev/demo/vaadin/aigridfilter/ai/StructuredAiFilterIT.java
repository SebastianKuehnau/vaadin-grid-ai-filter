package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.canonicalquery.AbstractAiFilterIT;
import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQuery;
import dev.demo.vaadin.aigridfilter.canonicalquery.ExpectedResult;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Variant <b>03</b> through its service: the canonical query set and the robustness set, both against a real
 * model. Everything shared — configuration, token bookkeeping, the assert-and-log step — is in
 * {@link AbstractAiFilterIT}.
 * <p>
 * What this variant can express is the statement it makes about itself: a {@code CustomerFilter} is a flat
 * list of conditions, each with several values (OR within a field) and a negate flag, and a range is two
 * sibling conditions on one field — so nothing in the canonical set is out of reach.
 * {@code 04-ai-hybrid-filter} reaches the same eight with the same filter type, delivered as a tool call.
 * <p>
 * An exhaustive {@code switch} on purpose: adding a query to the shared set without deciding what it means
 * for this variant then fails to compile instead of failing at runtime. The UI-level
 * {@code CustomerListViewBrowserlessIT} states the same mapping, so both stop compiling together.
 */
class StructuredAiFilterIT extends AbstractAiFilterIT {

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
