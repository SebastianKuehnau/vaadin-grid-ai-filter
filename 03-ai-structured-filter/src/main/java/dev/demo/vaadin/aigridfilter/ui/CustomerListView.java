package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.router.Route;
import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.springframework.data.jpa.domain.Specification;

/** The UI layer: a natural-language filter field above the customer grid. No Spring AI here. */
@Route("")
public class CustomerListView extends AbstractCustomerSearchView {

    public CustomerListView(CustomerRepository customerRepository, CustomerSearchAgent searchAgent) {
        super(customerRepository, searchAgent, "Customer Grid – Structured AI Filter");
    }
}
