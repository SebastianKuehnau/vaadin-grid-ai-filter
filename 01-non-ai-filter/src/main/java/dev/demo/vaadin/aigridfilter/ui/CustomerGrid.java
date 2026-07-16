package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import dev.demo.vaadin.aigridfilter.data.Customer;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * {@code Grid<Customer>} with the column layout and responsive show/hide behavior shared by both
 * {@code 01-non-ai-filter} views. Carries no sort configuration and no filter fields — each view
 * applies its own sort strategy to the custom columns ({@code annualRevenue}, {@code address},
 * {@code creditRating}) after construction, and {@link FilterableCustomerGrid} adds filtering.
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
}
