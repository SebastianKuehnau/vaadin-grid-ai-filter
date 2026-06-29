package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;

import java.text.NumberFormat;
import java.util.Comparator;
import java.util.Locale;

@Route("")
public class CustomerListView extends VerticalLayout {

    private static final NumberFormat REVENUE_FORMAT = NumberFormat.getNumberInstance(Locale.GERMANY);

    private final Grid<Customer> grid;
    private final CustomerRepository customerRepository;

    public CustomerListView(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
        add(new H1("Customer Grid – Simple Filter"));

        var filterField = new TextField("", "filter for ...");
        filterField.addValueChangeListener(this::onFilter);
        filterField.setWidthFull();
        add(filterField);

        this.grid = new Grid<>(Customer.class);
        var grid = this.grid;
        grid.setColumns("companyName", "contactName", "email", "phone", "customerSince", "lastOrderDate");
        grid.addColumn(customer -> customer.getAnnualRevenue() == null ?
                        "" : REVENUE_FORMAT.format(customer.getAnnualRevenue()) + " €")
                .setHeader("Annual Revenue").setKey("annualRevenue").setSortable(true)
                .setComparator(Comparator.comparing(Customer::getAnnualRevenue, Comparator.nullsFirst(Comparator.naturalOrder())))
                .setTextAlign(ColumnTextAlign.END);
        grid.addColumn(Customer::getAddress).setHeader("Address").setKey("address").setSortable(true).setComparator(CustomerListView::compareAddress).setFlexGrow(2);
        grid.addComponentColumn(CreditScoreIndicator::new).setHeader("Credit Rating").setKey("creditRating")
                .setComparator(Comparator.comparingInt(Customer::getCreditScore));
        grid.setItems(customerRepository.findAll());
        grid.setSizeFull();
        add(grid);

        setSizeFull();
    }

    /** Viewport width (px) at or above which the medium-priority columns are shown. */
    private static final int MEDIUM_BREAKPOINT = 768;
    /** Viewport width (px) at or above which the large-priority columns are shown. */
    private static final int LARGE_BREAKPOINT = 1200;

    @Override
    protected void onAttach(AttachEvent attachEvent) {
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

        grid.getColumnByKey("address").setVisible(medium);
        grid.getColumnByKey("phone").setVisible(medium);
        grid.getColumnByKey("email").setVisible(medium);

        grid.getColumnByKey("customerSince").setVisible(large);
        grid.getColumnByKey("lastOrderDate").setVisible(large);
        grid.getColumnByKey("annualRevenue").setVisible(large);
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

        return a1.getCountry().compareTo(a2.getCountry()) != 0 ?
                a1.getCountry().compareTo(a2.getCountry()) : a1.getCountry().compareTo(a2.getCountry()) != 0 ?
                a1.getCity().compareTo(a2.getCity()) : a1.getPostalCode().compareTo(a2.getPostalCode());
    }
}
