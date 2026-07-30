package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.data.VaadinSpringDataHelpers;
import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
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
 * search to {@link CustomerSearchAgent} (AI) and only knows how to put the resulting
 * {@link Specification} onto the grid — no Spring AI, no criteria building.
 * <p>
 * Identical to {@code 03-ai-structured-filter}'s view: the two modules differ only in how the AI layer
 * receives the finished filter (a tool call here, structured output there), which the view never sees.
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

        add(new H1("Customer Grid – Hybrid AI Filter"));

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

    class CustomerGrid extends Grid<Customer> {

        private static final NumberFormat REVENUE_FORMAT = NumberFormat.getNumberInstance(Locale.GERMANY);

        /** Viewport width (px) at or above which the medium-priority columns are shown. */
        private static final int MEDIUM_BREAKPOINT = 768;
        /** Viewport width (px) at or above which the large-priority columns are shown. */
        private static final int LARGE_BREAKPOINT = 1200;

        public CustomerGrid() {
            super(Customer.class);
            setColumns("companyName", "contactName", "email", "phone", "customerSince", "lastOrderDate");
            addColumn(customer -> customer.getAnnualRevenue() == null ?
                    "" : REVENUE_FORMAT.format(customer.getAnnualRevenue()) + " €")
                    .setHeader("Annual Revenue").setKey("annualRevenue").setSortable(true)
                    .setTextAlign(ColumnTextAlign.END);
            addColumn(Customer::getAddress).setKey("address").setHeader("Address").setSortable(true)
                    .setSortProperty("address.country", "address.city", "address.postalCode");
            addComponentColumn(CustomerGrid.CreditScoreIndicator::new).setKey("creditRating").setHeader("Credit Rating")
                    .setSortProperty("creditScore");
            setSizeFull();
        }

        @Override
        protected void onAttach(AttachEvent attachEvent) {
            super.onAttach(attachEvent);
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

            getColumnByKey("address").setVisible(medium);
            getColumnByKey("phone").setVisible(medium);
            getColumnByKey("email").setVisible(medium);

            getColumnByKey("customerSince").setVisible(large);
            getColumnByKey("lastOrderDate").setVisible(large);
            getColumnByKey("annualRevenue").setVisible(large);
        }

        @StyleSheet("credit-score-indicator.css")
        class CreditScoreIndicator extends Span {

            public CreditScoreIndicator(Customer customer) {
                var rating = customer.getCreditRating();
                addClassNames("credit-indicator", modifierClass(rating));

                var dot = new Span();
                dot.addClassName("credit-indicator__dot");
                dot.getElement().setAttribute("aria-hidden", "true");

                add(dot, new Span(rating.getLabel()));
                getElement().setAttribute("aria-label",
                        "Credit rating: " + rating.getLabel() + ", score " + customer.getCreditScore());
            }

            /** Maps the domain rating to the CSS modifier class that selects its color. */
            private static String modifierClass(CreditRating rating) {
                return switch (rating) {
                    case GOOD -> "credit-indicator--good";
                    case MEDIUM -> "credit-indicator--medium";
                    case POOR -> "credit-indicator--poor";
                };
            }
        }
    }
}