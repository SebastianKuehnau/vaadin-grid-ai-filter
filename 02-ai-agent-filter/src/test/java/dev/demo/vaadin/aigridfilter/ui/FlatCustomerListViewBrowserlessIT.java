package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.ViewPackages;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Variant 02(a) through its UI: two queries, testing the view↔agent wiring rather than capability. */
@ViewPackages(classes = FlatCustomerListView.class)
class FlatCustomerListViewBrowserlessIT extends AbstractCustomerSearchViewIT {

    @Override
    protected Class<? extends AbstractCustomerSearchView> viewClass() {
        return FlatCustomerListView.class;
    }

    @Test
    void findsCustomersInOneCity() {
        assertThat(search("show me all customers in Berlin"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(
                        expectedIds(customer -> city(customer).equals("Berlin")));
    }

    @Test
    void findsCreditworthyCustomersInOneCity() {
        assertThat(search("creditworthy customers in Hamburg"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(expectedIds(customer ->
                        city(customer).equals("Hamburg")
                                && customer.getCreditRating() == CreditRating.GOOD));
    }
}
