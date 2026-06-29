package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.data.VaadinSpringDataHelpers;
import dev.demo.vaadin.aigridfilter.ai.CustomerSearchService;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The UI layer: builds the grid and the natural-language filter field. It delegates the actual
 * search to {@link CustomerSearchService} (AI) and only knows how to put the resulting
 * {@link Specification} onto the grid — no Spring AI, no criteria building.
 */
@Route("")
public class CustomerListView extends VerticalLayout {

    private static final Logger logger = LoggerFactory.getLogger(CustomerListView.class);

    private static final NumberFormat REVENUE_FORMAT = NumberFormat.getNumberInstance(Locale.GERMANY);

    private final Grid<Customer> grid;
    private final CustomerRepository customerRepository;
    private final CustomerSearchService searchService;
    private final TextField filterField;

    public CustomerListView(CustomerRepository customerRepository, CustomerSearchService searchService) {
        this.customerRepository = customerRepository;
        this.searchService = searchService;

        add(new H1("Customer Grid – Local AI Filter"));

        filterField = new TextField("", "filter for ...");
        filterField.addValueChangeListener(this::onFilter);
        filterField.setClearButtonVisible(true);
        filterField.setWidthFull();
        add(filterField);

        grid = new Grid<>(Customer.class);
        grid.setColumns("companyName", "contactName", "email", "phone", "customerSince", "lastOrderDate");
        grid.addColumn(customer -> customer.getAnnualRevenue() == null ?
                        "" : REVENUE_FORMAT.format(customer.getAnnualRevenue()) + " €")
                .setHeader("Annual Revenue").setKey("annualRevenue").setSortable(true)
                .setTextAlign(ColumnTextAlign.END);
        grid.addColumn(Customer::getAddress).setKey("address").setHeader("Address").setSortable(true)
                .setSortProperty("address.country", "address.city", "address.postalCode");
        grid.addComponentColumn(CreditScoreIndicator::new).setKey("creditRating").setHeader("Credit Rating")
                .setSortProperty("creditScore");
        grid.setSizeFull();
        add(grid);

        applyFilter(Specification.unrestricted());

        setSizeFull();
    }

    /** Viewport width (px) at or above which the medium-priority columns are shown. */
    private static final int MEDIUM_BREAKPOINT = 768;
    /** Viewport width (px) at or above which the large-priority columns are shown. */
    private static final int LARGE_BREAKPOINT = 1200;

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        var page = attachEvent.getUI().getPage();
        page.retrieveExtendedClientDetails(details -> applyResponsiveColumns(details.getWindowInnerWidth()));
        page.addBrowserWindowResizeListener(event -> applyResponsiveColumns(event.getWidth()));
    }

    /**
     * Shows or hides columns by priority based on the viewport width:
     * always Company Name, Contact Name, Credit Rating; ≥ {@value #MEDIUM_BREAKPOINT}px adds
     * Address, Phone, Email; ≥ {@value #LARGE_BREAKPOINT}px adds Customer Since, Last Order Date,
     * Annual Revenue.
     */
    private void applyResponsiveColumns(int width) {
        boolean medium = width >= MEDIUM_BREAKPOINT;
        boolean large = width >= LARGE_BREAKPOINT;

        grid.getColumnByKey("address").setVisible(medium);
        grid.getColumnByKey("phone").setVisible(medium);
        grid.getColumnByKey("email").setVisible(medium);

        grid.getColumnByKey("customerSince").setVisible(large);
        grid.getColumnByKey("lastOrderDate").setVisible(large);
        grid.getColumnByKey("annualRevenue").setVisible(large);
    }

    private void onFilter(AbstractField.ComponentValueChangeEvent<TextField, String> event) {
        if (event.getValue() == null || event.getValue().isBlank())
            applyFilter(Specification.unrestricted());
        else
            runSearch(event.getValue());
    }

    private void runSearch(String query) {
        filterField.setEnabled(false);
        var ui = getUI().orElseThrow();

        // resolveFilter() blocks on the LLM, so run it off the UI thread and apply via ui.access().
        CompletableFuture
                .supplyAsync(() -> searchService.resolveFilter(query))
                .whenComplete((specification, error) -> ui.access(() -> {
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                        logger.error("Customer search failed", cause);
                        Notification.show("Error - " + cause.getLocalizedMessage())
                                .addThemeVariants(NotificationVariant.ERROR);
                    } else {
                        applyFilter(specification);
                    }
                    filterField.setEnabled(true);
                }));
    }

    /** Re-binds the grid to the given specification — the single point where filtering is applied. */
    private void applyFilter(Specification<Customer> specification) {
        grid.setItems(
                query -> customerRepository.findAll(specification,
                        VaadinSpringDataHelpers.toSpringPageRequest(query)).stream(),
                _ -> Math.toIntExact(customerRepository.count(specification)));
    }
}