package dev.demo.vaadin.aigridfilter.ai.operator;

import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.canonicalquery.AbstractAiFilterIT;
import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQuery;
import dev.demo.vaadin.aigridfilter.canonicalquery.ExpectedResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Variant <b>02(b)</b> through its service: the canonical query set and the robustness set, both against a
 * real model. Everything shared — configuration and the assert-and-log step — is in
 * {@link AbstractAiFilterIT}.
 * <p>
 * What this variant can express is the statement it makes about itself: an operator and a negate flag per
 * field buy negation, operator precision and day-level date bounds. What one value and one operator per
 * field still cannot hold is a second value or a second bound — that is this variant's ceiling and the
 * reason the ladder continues.
 * <p>
 * An exhaustive {@code switch} on purpose: adding a query to the shared set without deciding what it means
 * for this variant then fails to compile instead of failing at runtime. The UI-level
 * {@code OperatorCustomerListViewBrowserlessIT} states the same mapping, so both stop compiling together.
 */
class OperatorAiFilterIT extends AbstractAiFilterIT {

    @Autowired
    @Qualifier("operatorSearchAgent")
    CustomerSearchAgent agent;

    @Override
    protected CustomerSearchAgent agent() {
        return agent;
    }

    @Override
    protected ExpectedResult expectedResultFor(CanonicalQuery canonical) {
        return switch (canonical) {
            case C1_SINGLE_VALUE, C3_NEGATION, C4_OPERATOR_PRECISION, C5_COMBINED_AND,
                 C7_RELATIVE_DATE -> ExpectedResult.MATCH;
            case C2_MULTI_VALUE_OR, C6_REVENUE_RANGE, C8_DATE_RANGE -> ExpectedResult.NO_MATCH_BY_DESIGN;
        };
    }
}
