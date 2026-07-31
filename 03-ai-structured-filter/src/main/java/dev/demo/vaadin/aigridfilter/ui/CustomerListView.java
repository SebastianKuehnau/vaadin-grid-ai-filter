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
 * Everything below the heading is inherited from {@link AbstractCustomerSearchView}, which all three
 * AI modules share: the same field, the same grid, the same off-thread search. That the view is this
 * short is the point — whether the model returned a filter or called a tool with one is invisible from
 * here.
 */
@Route("")
public class CustomerListView extends AbstractCustomerSearchView {

    public CustomerListView(CustomerRepository customerRepository, CustomerSearchAgent searchAgent) {
        super(customerRepository, searchAgent, "Customer Grid – Structured AI Filter");
    }
}
