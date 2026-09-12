package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import com.vaadin.flow.router.RouterLink;
import dev.demo.vaadin.aigridfilter.ai.flat.CustomerSearchService;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Variant 02(a)'s view (routes {@code /} and {@code /flat}): one scalar value per field. */
@Route("")
@RouteAlias("flat")
public class FlatCustomerListView extends VerticalLayout {

    private static final Logger logger = LoggerFactory.getLogger(FlatCustomerListView.class);

    public final CustomerGrid grid = new CustomerGrid();
    public final TextField filterField = new TextField("", "filter for ...");

    private final CustomerRepository customerRepository;
    private final CustomerSearchService searchService;

    public FlatCustomerListView(CustomerRepository customerRepository,
                                CustomerSearchService searchService) {
        this.customerRepository = customerRepository;
        this.searchService = searchService;

        add(new H1("Customer Grid – AI Filter 02(a)"),
                new RouterLink("02(b) value + operator + negate", OperatorCustomerListView.class));

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
