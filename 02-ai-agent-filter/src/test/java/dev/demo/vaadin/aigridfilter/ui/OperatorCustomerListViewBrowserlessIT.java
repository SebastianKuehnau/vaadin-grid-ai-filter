package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.ViewPackages;
import dev.demo.vaadin.aigridfilter.ai.operator.OperatorOutcomes;
import dev.demo.vaadin.aigridfilter.canonicalquery.AbstractCustomerListViewBrowserlessIT;
import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQuery;
import dev.demo.vaadin.aigridfilter.canonicalquery.Outcome;

/**
 * Variant <b>02(b)</b> through the UI: the canonical query set typed into the filter field of {@code
 * OperatorCustomerListView}, resolved by the real operator tool-calling AI layer, scored on the rows the
 * grid shows.
 * <p>
 * Same eight queries and same expectations as {@code OperatorCanonicalQueryIT}, which asks the same
 * backend directly — the pair leaves the view layer as the only variable. What this variant can express is
 * stated once, in {@link OperatorOutcomes}, and read from both.
 */
@ViewPackages(classes = OperatorCustomerListView.class)
class OperatorCustomerListViewBrowserlessIT extends AbstractCustomerListViewBrowserlessIT {

    @Override
    protected Class<? extends AbstractCustomerSearchView> viewClass() {
        return OperatorCustomerListView.class;
    }

    @Override
    protected Outcome outcomeOf(CanonicalQuery canonical) {
        return OperatorOutcomes.of(canonical);
    }
}
