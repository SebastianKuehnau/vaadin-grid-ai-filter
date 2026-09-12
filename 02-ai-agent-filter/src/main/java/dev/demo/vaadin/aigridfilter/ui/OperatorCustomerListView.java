package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import dev.demo.vaadin.aigridfilter.ai.operator.CustomerSearchService;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Variant 02(b)'s view (route {@code /operator}): a value, an operator and a negate flag per field. */
@Route("operator")
public class OperatorCustomerListView extends VerticalLayout {

    private static final Logger logger = LoggerFactory.getLogger(OperatorCustomerListView.class);

    public final CustomerGrid grid = new CustomerGrid();
    public final TextField filterField = new TextField("", "filter for ...");

    private final CustomerRepository customerRepository;
    private final CustomerSearchService searchService;

    public OperatorCustomerListView(CustomerRepository customerRepository,
                                    CustomerSearchService searchService) {
        this.customerRepository = customerRepository;
        this.searchService = searchService;

        add(new H1("Customer Grid – AI Filter 02(b)"),
                new RouterLink("02(a) flat", FlatCustomerListView.class));

        filterField.setClearButtonVisible(true);
        filterField.setWidthFull();
        filterField.addValueChangeListener(this::search);
        add(filterField, grid);

        grid.setItems(customerRepository.findAll());

        setSizeFull();
    }

    private void search(AbstractField.ComponentValueChangeEvent<TextField, String> event) {
        var query = event.getValue();

        if (query == null || query.isBlank()) {
            grid.setItems(customerRepository.findAll());
            return;
        }
        logger.info("Searching customers for: {}", query);

        var ui = event.getUI();
        filterField.setEnabled(false);

        // resolveFilter() blocks on the LLM, so run it off the UI thread and apply via ui.access().
        CompletableFuture
                .supplyAsync(() -> searchService.resolveFilter(query))
                .whenComplete((filter, error) -> ui.access(() -> {
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                        logger.error("Customer search failed", cause);
                        Notification.show("Error - " + cause.getLocalizedMessage())
                                .addThemeVariants(NotificationVariant.ERROR);
                    } else {
                        grid.setItems(customerRepository.findAll(filter));
                    }
                    filterField.setEnabled(true);
                }));
    }
}
