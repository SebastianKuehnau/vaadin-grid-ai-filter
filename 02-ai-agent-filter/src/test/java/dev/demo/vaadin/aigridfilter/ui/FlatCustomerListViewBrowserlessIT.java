package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.ViewPackages;
import dev.demo.vaadin.aigridfilter.ai.flat.FlatOutcomes;
import dev.demo.vaadin.aigridfilter.canonicalquery.AbstractCustomerListViewBrowserlessIT;
import dev.demo.vaadin.aigridfilter.canonicalquery.CanonicalQuery;
import dev.demo.vaadin.aigridfilter.canonicalquery.Outcome;

/**
 * Variant <b>02(a)</b> through the UI: the canonical query set typed into the filter field of {@code
 * FlatCustomerListView}, resolved by the real flat tool-calling AI layer, scored on the rows the grid
 * shows.
 * <p>
 * Same eight queries and same expectations as {@code FlatCanonicalQueryIT}, which asks the same backend
 * directly — the pair leaves the view layer as the only variable. What this variant can express is stated
 * once, in {@link FlatOutcomes}, and read from both.
 */
@ViewPackages(classes = FlatCustomerListView.class)
class FlatCustomerListViewBrowserlessIT extends AbstractCustomerListViewBrowserlessIT {

    @Override
    protected Class<? extends AbstractCustomerSearchView> viewClass() {
        return FlatCustomerListView.class;
    }

    @Override
    protected Outcome outcomeOf(CanonicalQuery canonical) {
        return FlatOutcomes.of(canonical);
    }
}
