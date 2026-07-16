package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import dev.demo.vaadin.aigridfilter.data.Customer;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * {@code Grid<Customer>} with the column layout, backend sort configuration, and responsive
 * show/hide behavior for {@code CustomerListView}. Unlike {@code 01-non-ai-filter}'s
 * {@code CustomerGrid}/{@code FilterableCustomerGrid} split, this module has only one consumer with
 * one fixed sort strategy and no per-column filter fields (filtering is one natural-language
 * {@code TextField} owned by the view), so sort configuration for the custom columns lives here
 * rather than being applied by the view afterward.
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
}
