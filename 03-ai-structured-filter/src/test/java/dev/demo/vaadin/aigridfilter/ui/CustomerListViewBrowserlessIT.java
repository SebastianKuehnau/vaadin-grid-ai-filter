package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.ViewPackages;
import dev.demo.vaadin.aigridfilter.ai.StructuredOutcomes;
import dev.demo.vaadin.aigridfilter.canonicalquery.AbstractCustomerListViewBrowserlessIT;
import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQuery;
import dev.demo.vaadin.aigridfilter.canonicalquery.Outcome;

/**
 * Variant <b>03</b> through the UI: the canonical query set typed into the filter field of {@code
 * CustomerListView}, resolved by the real structured-output AI layer, scored on the rows the grid shows.
 * <p>
 * Same eight queries and same expectations as {@code StructuredCanonicalQueryIT}, which asks the same
 * backend directly — the pair leaves the view layer as the only variable. What this variant can express is
 * stated once, in {@link StructuredOutcomes}, and read from both.
 */
@ViewPackages(classes = CustomerListView.class)
class CustomerListViewBrowserlessIT extends AbstractCustomerListViewBrowserlessIT {

    @Override
    protected Class<? extends AbstractCustomerSearchView> viewClass() {
        return CustomerListView.class;
    }

    @Override
    protected Outcome outcomeOf(CanonicalQuery canonical) {
        return StructuredOutcomes.of(canonical);
    }
}
