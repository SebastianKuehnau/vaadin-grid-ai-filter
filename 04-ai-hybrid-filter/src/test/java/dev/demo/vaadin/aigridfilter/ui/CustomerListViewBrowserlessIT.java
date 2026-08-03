package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.ViewPackages;
import dev.demo.vaadin.aigridfilter.canonicalquery.AbstractCustomerListViewBrowserlessIT;
import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQuery;
import dev.demo.vaadin.aigridfilter.canonicalquery.ExpectedResult;

/**
 * Variant <b>04</b> through the UI: the canonical query set typed into the filter field of {@code
 * CustomerListView}, resolved by the real hybrid tool-calling AI layer, scored on the rows the grid shows.
 * <p>
 * Same eight queries and same expectations as {@code HybridAiFilterIT}, which asks the same backend
 * directly — the pair leaves the view layer as the only variable. What this variant can express is stated
 * here as well as in that IT, in the same exhaustive {@code switch}.
 */
@ViewPackages(classes = CustomerListView.class)
class CustomerListViewBrowserlessIT extends AbstractCustomerListViewBrowserlessIT {

    @Override
    protected Class<? extends AbstractCustomerSearchView> viewClass() {
        return CustomerListView.class;
    }

    @Override
    protected ExpectedResult expectedResultFor(CanonicalQuery canonical) {
        return switch (canonical) {
            case C1_SINGLE_VALUE, C2_MULTI_VALUE_OR, C3_NEGATION, C4_OPERATOR_PRECISION,
                 C5_COMBINED_AND, C6_REVENUE_RANGE, C7_RELATIVE_DATE, C8_DATE_RANGE -> ExpectedResult.MATCH;
        };
    }
}
