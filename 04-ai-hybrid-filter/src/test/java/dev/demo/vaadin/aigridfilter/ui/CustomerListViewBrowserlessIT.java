package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.ViewPackages;
import dev.demo.vaadin.aigridfilter.data.Customer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Variant 04 through its UI: two queries, testing the view↔agent wiring rather than capability. */
@ViewPackages(classes = CustomerListView.class)
class CustomerListViewBrowserlessIT extends AbstractCustomerSearchViewIT {

    @Override
    protected Class<? extends AbstractCustomerSearchView> viewClass() {
        return CustomerListView.class;
    }

    @Test
    void findsCustomersInOneCity() {
        assertThat(search("show me all customers in Berlin"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(
                        expectedIds(customer -> city(customer).equals("Berlin")));
    }

    /** The capability 04 shares with 03: two values for one field. */
    @Test
    void findsCustomersInEitherOfTwoCities() {
        assertThat(search("show me customers from Berlin or Hamburg"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(expectedIds(customer ->
                        city(customer).equals("Berlin") || city(customer).equals("Hamburg")));
    }
}
