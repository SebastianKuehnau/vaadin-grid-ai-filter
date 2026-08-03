package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.ViewPackages;
import dev.demo.vaadin.aigridfilter.canonicalquery.AbstractCustomerListViewBrowserlessIT;
import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQuery;
import dev.demo.vaadin.aigridfilter.canonicalquery.ExpectedResult;

/**
 * Variant <b>02(b)</b> through the UI: the canonical query set typed into the filter field of {@code
 * OperatorCustomerListView}, resolved by the real operator tool-calling AI layer, scored on the rows the
 * grid shows.
 * <p>
 * Same eight queries and same expectations as {@code OperatorCustomerSearchIT}, which asks the same
 * backend directly — the pair leaves the view layer as the only variable. What this variant can express is
 * stated here as well as in that IT, in the same exhaustive {@code switch}.
 */
@ViewPackages(classes = OperatorCustomerListView.class)
class OperatorCustomerListViewBrowserlessIT extends AbstractCustomerListViewBrowserlessIT {

    @Override
    protected Class<? extends AbstractCustomerSearchView> viewClass() {
        return OperatorCustomerListView.class;
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
