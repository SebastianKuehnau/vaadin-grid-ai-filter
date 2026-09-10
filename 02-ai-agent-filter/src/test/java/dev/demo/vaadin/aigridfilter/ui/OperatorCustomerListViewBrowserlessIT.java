package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.ViewPackages;
import dev.demo.vaadin.aigridfilter.data.Customer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Variant 02(b) through its UI: two queries, testing the view↔agent wiring rather than capability. */
@ViewPackages(classes = OperatorCustomerListView.class)
class OperatorCustomerListViewBrowserlessIT extends AbstractCustomerSearchViewIT {

    @Override
    protected Class<? extends AbstractCustomerSearchView> viewClass() {
        return OperatorCustomerListView.class;
    }

    @Test
    void findsCustomersInOneCity() {
        assertThat(search("show me all customers in Berlin"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(
                        expectedIds(customer -> city(customer).equals("Berlin")));
    }

    /** The capability 02(b) adds over 02(a): a negate flag. */
    @Test
    void findsCustomersOutsideOneCity() {
        assertThat(search("show me all customers except from Berlin"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(
                        expectedIds(customer -> !city(customer).equals("Berlin")));
    }
}
