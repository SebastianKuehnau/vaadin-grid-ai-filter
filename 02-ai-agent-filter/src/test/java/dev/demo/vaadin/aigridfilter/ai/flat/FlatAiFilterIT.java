package dev.demo.vaadin.aigridfilter.ai.flat;

import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.canonicalquery.AbstractAiFilterIT;
import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQuery;
import dev.demo.vaadin.aigridfilter.canonicalquery.ExpectedResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Variant <b>02(a)</b> through its service: the canonical query set and the robustness set, both against a
 * real model. Everything shared — configuration, token bookkeeping, the assert-and-log step — is in
 * {@link AbstractAiFilterIT}.
 * <p>
 * What this variant can express is the statement it makes about itself: one scalar value per field and no
 * operator at all, so only the two simplest categories are within reach. Multi-value OR, negation, operator
 * precision and every kind of range or date bound are architecturally impossible — the tool has no
 * parameter that could carry them.
 * <p>
 * An exhaustive {@code switch} on purpose: adding a query to the shared set without deciding what it means
 * for this variant then fails to compile instead of failing at runtime. The UI-level
 * {@code FlatCustomerListViewBrowserlessIT} states the same mapping, so both stop compiling together.
 */
class FlatAiFilterIT extends AbstractAiFilterIT {

    @Autowired
    @Qualifier("flatSearchAgent")
    CustomerSearchAgent agent;

    @Override
    protected CustomerSearchAgent agent() {
        return agent;
    }

    @Override
    protected ExpectedResult expectedResultFor(CanonicalQuery canonical) {
        return switch (canonical) {
            case C1_SINGLE_VALUE, C5_COMBINED_AND -> ExpectedResult.MATCH;
            case C2_MULTI_VALUE_OR, C3_NEGATION, C4_OPERATOR_PRECISION, C6_REVENUE_RANGE,
                 C7_RELATIVE_DATE, C8_DATE_RANGE -> ExpectedResult.NO_MATCH_BY_DESIGN;
        };
    }
}
