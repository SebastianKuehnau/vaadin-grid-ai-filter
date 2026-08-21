package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;

import java.text.NumberFormat;
import java.util.Locale;

/** The {@code Grid<Customer>} every module shows - not what the talk compares, so it is shared. */
@StyleSheet("customer-grid.css")
public class CustomerGrid extends Grid<Customer> {

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
                .setTextAlign(ColumnTextAlign.END)
                // Values right-aligned for scanning, header left - see customer-grid.css.
                .setHeaderPartName("annual-revenue-header");
        addColumn(Customer::getAddress).setKey("address").setHeader("Address").setSortable(true)
                .setSortProperty("address.country", "address.city", "address.postalCode");
        addComponentColumn(CreditScoreIndicator::new).setKey("creditRating").setHeader("Credit Rating")
                .setSortProperty("creditScore");
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

    /** Shows or hides columns by priority based on the viewport width. */
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

    /** The credit-rating cell: a coloured dot plus the label, styled by {@code credit-score-indicator.css}. */
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
