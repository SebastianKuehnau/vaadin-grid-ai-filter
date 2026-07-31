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
 * The {@code Grid<Customer>} every module shows. Shared because it is <em>not</em> what the talk compares:
 * however a filter comes into being, it ends as the same {@code Specification} against this grid.
 * <p>
 * <b>Sorting is configured here, per-column filter fields are not.</b> The sort setup is what all four
 * modules agree on; a view may override it, and {@code 01}'s in-memory view does, with
 * {@code Comparator}s — {@code Column.setComparator(...)} wins over a sort property once the data
 * provider is a list rather than a query. Filter fields stay out because only {@code 01} has them, in its
 * own {@code FilterableCustomerGrid}.
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
     * The credit-rating cell: a coloured dot plus the label. The colour comes from
     * {@code credit-score-indicator.css} in this module's {@code META-INF/resources/}, which Spring Boot
     * serves straight out of the jar.
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
