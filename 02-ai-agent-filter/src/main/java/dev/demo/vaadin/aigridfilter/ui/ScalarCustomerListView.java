package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Variant <b>02(a)</b>'s view (routes {@code /} and {@code /scalar}): natural-language filtering
 * through a tool call with one scalar value per field. Sibling of {@link OperatorCustomerListView},
 * which serves variant 02(b) from the same running application.
 * <p>
 * The agent is injected by bean name rather than by type, because this module has two
 * {@link CustomerSearchAgent} implementations — one per variant — and each view wants exactly one of
 * them. The view itself still knows nothing beyond the {@link CustomerSearchAgent} interface.
 */
@Route("")
@RouteAlias("scalar")
public class ScalarCustomerListView extends AbstractCustomerListView {

    public ScalarCustomerListView(CustomerRepository customerRepository,
                                  @Qualifier("scalarSearchAgent") CustomerSearchAgent searchAgent) {
        super(customerRepository, searchAgent,
                "Customer Grid – AI Filter 02(a)",
                "Tool calling with one scalar value per field: no operator, no negation, "
                        + "no second value for a field. Text matches as a substring, a date matches its "
                        + "whole calendar year, revenue is a minimum.");
    }
}
