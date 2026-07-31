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

/**
 * The {@code Grid<Customer>} every module of this repository shows: the same columns, the same revenue
 * formatting, the same credit-rating indicator and the same responsive show/hide behaviour.
 * <p>
 * It is shared because it is <em>not</em> what the talk compares. What differs between the modules is how
 * a filter comes into being — a per-column form, a tool call, structured output — and every one of those
 * ends in the same {@code Specification} against the same grid. Four byte-identical copies of this class
 * only made each approach look like it had more moving parts than it does.
 * <p>
 * <b>Sorting is configured here, filter fields are not.</b> The sort configuration is what all four
 * modules agree on: {@code annualRevenue} sortable, {@code address} sorted by country/city/postal code
 * and {@code creditRating} by the underlying score. A view is free to override it — {@code 01}'s
 * in-memory view replaces those columns' sorting with {@code Comparator}s, because it sorts a list rather
 * than issuing a query, and {@code Column.setComparator(...)} wins over the sort property for an
 * in-memory data provider. Per-column filter fields are a different matter and stay out: only {@code 01}
 * has them, in its own {@code FilterableCustomerGrid} subclass.
 */
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
                .setTextAlign(ColumnTextAlign.END);
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

    /**
     * The credit-rating cell: a coloured dot plus the rating's label. The colour comes from
     * {@code credit-score-indicator.css}, which ships in this module's {@code META-INF/resources/} and is
     * served from the jar by Spring Boot's default static-resource locations.
     */
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
