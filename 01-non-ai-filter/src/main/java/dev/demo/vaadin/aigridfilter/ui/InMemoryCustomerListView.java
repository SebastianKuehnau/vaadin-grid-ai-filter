package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;

import java.text.NumberFormat;
import java.util.Comparator;
import java.util.Locale;

@Route("")
@RouteAlias("in-memory")
public class InMemoryCustomerListView extends VerticalLayout {

    final CustomerGrid grid;
    final TextField filterField;
    private final CustomerRepository customerRepository;

    public InMemoryCustomerListView(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
        add(new H1("Customer Grid – In-Memory Filter"));

        filterField = new TextField("", "filter for ...");
        filterField.addValueChangeListener(this::onFilter);
        filterField.setClearButtonVisible(true);
        filterField.setWidthFull();
        add(filterField);

        grid = new CustomerGrid();
        grid.getColumnByKey("annualRevenue").setSortable(true)
                .setComparator(Comparator.comparing(Customer::getAnnualRevenue, Comparator.nullsFirst(Comparator.naturalOrder())));
        grid.getColumnByKey("address").setSortable(true).setComparator(InMemoryCustomerListView::compareAddress);
        grid.getColumnByKey("creditRating").setSortable(true).setComparator(Comparator.comparingInt(Customer::getCreditScore));
        grid.setItems(customerRepository.findAll());
        add(grid);

        setSizeFull();
    }

    private void onFilter(AbstractField.ComponentValueChangeEvent<TextField, String> event) {
        var filteredCustomers = customerRepository.findAll().stream().filter(customer ->
                        customer.getCompanyName().toLowerCase().contains(event.getValue().toLowerCase())
                                || customer.getContactName().toLowerCase().contains(event.getValue().toLowerCase())
                                || customer.getEmail().toLowerCase().contains(event.getValue().toLowerCase())
                                || customer.getPhone().toLowerCase().contains(event.getValue().toLowerCase())
                                || customer.getAddress().toString().toLowerCase().contains(event.getValue().toLowerCase())
                                || customer.getCustomerSince().toString().toLowerCase().contains(event.getValue().toLowerCase())
                                || customer.getLastOrderDate().toString().toLowerCase().contains(event.getValue().toLowerCase())
                                || customer.getCreditRating().getLabel().toLowerCase().contains(event.getValue().toLowerCase())
                ).toList();

        this.grid.setItems(filteredCustomers);
    }

    private static int compareAddress(Customer c1, Customer c2) {
        var a1 = c1.getAddress();
        var a2 = c2.getAddress();

        int countryComparison = a1.getCountry().compareTo(a2.getCountry());
        if (countryComparison != 0) {
            return countryComparison;
        }
        int cityComparison = a1.getCity().compareTo(a2.getCity());
        if (cityComparison != 0) {
            return cityComparison;
        }
        return a1.getPostalCode().compareTo(a2.getPostalCode());
    }

    static class CustomerGrid extends Grid<Customer> {

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
                    .setHeader("Annual Revenue").setKey("annualRevenue")
                    .setTextAlign(ColumnTextAlign.END);
            addColumn(Customer::getAddress).setHeader("Address").setKey("address").setFlexGrow(2);
            addComponentColumn(CreditScoreIndicator::new).setHeader("Credit Rating").setKey("creditRating");
            setSizeFull();
        }

        @Override
        protected void onAttach(AttachEvent attachEvent) {
            super.onAttach(attachEvent);
            var page = attachEvent.getUI().getPage();
            // Apply once for the initial window size, then on every resize.
            page.retrieveExtendedClientDetails(details -> applyResponsiveColumns(details.getWindowInnerWidth()));
            page.addBrowserWindowResizeListener(event -> applyResponsiveColumns(event.getWidth()));
        }

        /**
         * Shows or hides columns by priority based on the viewport width:
         * <ul>
         *     <li>always: Company Name, Contact Name, Credit Rating</li>
         *     <li>medium (≥ {@value #MEDIUM_BREAKPOINT}px): + Address, Phone, Email</li>
         *     <li>large (≥ {@value #LARGE_BREAKPOINT}px): + Customer Since, Last Order Date, Annual Revenue</li>
         * </ul>
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
        static class CreditScoreIndicator extends Span {

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
