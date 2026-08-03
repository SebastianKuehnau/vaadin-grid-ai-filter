package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.router.Route;
import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.springframework.data.jpa.domain.Specification;

/**
 * The UI layer: a natural-language filter field above the customer grid. It delegates the actual search
 * to {@link CustomerSearchAgent} and only knows how to put the resulting {@link Specification} onto the
 * grid — no Spring AI, no criteria building.
 * <p>
 * Identical to {@code 03-ai-structured-filter}'s view apart from the heading, and both are now just a
 * heading on top of the shared {@link AbstractCustomerSearchView}: the two modules differ only in how
 * the AI layer receives the finished filter (a tool call here, structured output there), which the view
 * never sees.
 */
@Route("")
public class CustomerListView extends AbstractCustomerSearchView {

    public CustomerListView(CustomerRepository customerRepository, CustomerSearchAgent searchAgent) {
        super(customerRepository, searchAgent, "Customer Grid – Hybrid AI Filter");
    }
}
