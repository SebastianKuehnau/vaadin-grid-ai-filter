package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.router.Route;
import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.springframework.beans.factory.annotation.Qualifier;

/** Variant 02(b)'s view (route {@code /operator}): a value, an operator and a negate flag per field. */
@Route("operator")
public class OperatorCustomerListView extends AbstractCustomerListView {

    public OperatorCustomerListView(CustomerRepository customerRepository,
                                    @Qualifier("operatorSearchAgent") CustomerSearchAgent searchAgent) {
        super(customerRepository, searchAgent,
                "Customer Grid – AI Filter 02(b)",
                "Tool calling with a value, an operator and a negate flag per field (39 parameters): "
                        + "negation and operator precision are expressible, multi-value OR and ranges "
                        + "still are not.");
    }
}
