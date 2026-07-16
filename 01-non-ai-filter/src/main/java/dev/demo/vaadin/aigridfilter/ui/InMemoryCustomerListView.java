package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;

import java.util.Comparator;

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
}
