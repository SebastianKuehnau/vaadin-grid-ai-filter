package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.data.VaadinSpringDataHelpers;
import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The UI layer: builds the grid and the natural-language filter field. It delegates the actual
 * search to {@link CustomerSearchAgent} (AI) and only knows how to put the resulting
 * {@link Specification} onto the grid — no Spring AI, no criteria building.
 */
@Route("")
public class CustomerListView extends VerticalLayout {

    private static final Logger logger = LoggerFactory.getLogger(CustomerListView.class);

    final CustomerGrid grid;
    private final CustomerRepository customerRepository;
    private final CustomerSearchAgent searchAgent;
    final TextField filterField;

    public CustomerListView(CustomerRepository customerRepository, CustomerSearchAgent searchAgent) {
        this.customerRepository = customerRepository;
        this.searchAgent = searchAgent;

        add(new H1("Customer Grid – Structured AI Filter"));

        filterField = new TextField("", "filter for ...");
        filterField.addValueChangeListener(this::onFilter);
        filterField.setClearButtonVisible(true);
        filterField.setWidthFull();
        add(filterField);

        grid = new CustomerGrid();
        add(grid);

        applyFilter(Specification.unrestricted());

        setSizeFull();
    }

    private void onFilter(AbstractField.ComponentValueChangeEvent<TextField, String> event) {
        if (event.getValue() == null || event.getValue().isBlank())
            applyFilter(Specification.unrestricted());
        else {
            filterField.setEnabled(false);

            // resolveFilter() blocks on the LLM, so run it off the UI thread and apply via ui.access().
            CompletableFuture
                    .supplyAsync(() -> searchAgent.resolveFilter(event.getValue()))
                    .whenComplete(this::onComplete);
        }
    }

    private void onComplete(Specification<Customer> specification, Throwable error) {
        var ui = getUI().orElseThrow();

        ui.access(() -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                logger.error("Customer search failed", cause);
                Notification.show("Error - " + cause.getLocalizedMessage())
                        .addThemeVariants(NotificationVariant.ERROR);
            } else {
                applyFilter(specification);
            }
            filterField.setEnabled(true);
        });
    }

    /** Re-binds the grid to the given specification — the single point where filtering is applied. */
    private void applyFilter(Specification<Customer> specification) {
        grid.setItems(
                query -> customerRepository.findAll(specification,
                        VaadinSpringDataHelpers.toSpringPageRequest(query)).stream(),
                _ -> Math.toIntExact(customerRepository.count(specification)));
    }
}