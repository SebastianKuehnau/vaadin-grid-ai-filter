package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.springframework.beans.factory.annotation.Qualifier;

/** Variant 02(a)'s view (routes {@code /} and {@code /flat}): one scalar value per field. */
@Route("")
@RouteAlias("flat")
public class FlatCustomerListView extends AbstractCustomerListView {

    public FlatCustomerListView(CustomerRepository customerRepository,
                                 @Qualifier("flatSearchAgent") CustomerSearchAgent searchAgent) {
        super(customerRepository, searchAgent,
                "Customer Grid – AI Filter 02(a)",
                "Tool calling with one scalar value per field: no operator, no negation, "
                        + "no second value for a field. Text matches as a substring, a date matches its "
                        + "whole calendar year, revenue is a minimum.");
    }
}
