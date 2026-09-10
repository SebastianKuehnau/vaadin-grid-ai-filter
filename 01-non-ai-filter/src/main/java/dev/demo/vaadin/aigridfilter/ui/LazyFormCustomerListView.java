package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.grid.dataview.GridLazyDataView;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.data.VaadinSpringDataHelpers;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import dev.demo.vaadin.aigridfilter.filter.CustomerFilter;
import dev.demo.vaadin.aigridfilter.filter.CustomerSpecifications;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

/** Same lazy, Specification-based data access as {@link LazyCustomerListView} - but filtered by a search form. */
@Route("lazy-form")
public class LazyFormCustomerListView extends VerticalLayout {

    final CustomerGrid grid;
    final CustomerSearchForm searchForm;
    private final CustomerRepository customerRepository;
    private final GridLazyDataView<Customer> customerGridLazyDataView;

    /** The filter the grid currently queries with; replaced on every search. */
    private Specification<Customer> specification = Specification.unrestricted();

    public LazyFormCustomerListView(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
        add(new H1("Customer Grid – Lazy Form Filter"));

        searchForm = new CustomerSearchForm(distinctCountries(), this::applyFilter);
        add(searchForm);

        grid = new CustomerGrid();
        // annualRevenue is only marked sortable in the shared grid; this adds its sort property.
        grid.getColumnByKey("annualRevenue").setSortProperty("annualRevenue");
        grid.getColumnByKey("address").setFlexGrow(2);

        customerGridLazyDataView = grid.setItems(gridQuery ->
                        customerRepository.findAll(specification,
                                VaadinSpringDataHelpers.toSpringPageRequest(gridQuery)).stream(),
                _ -> Math.toIntExact(customerRepository.count(specification)));
        add(grid);

        setSizeFull();
    }

    private void applyFilter(CustomerFilter filter) {
        specification = CustomerSpecifications.matching(filter);
        customerGridLazyDataView.refreshAll();
    }

    /** Distinct country names present in the data, to populate the country selector. */
    private List<String> distinctCountries() {
        return customerRepository.findAll().stream()
                .map(customer -> customer.getAddress().getCountry())
                .distinct()
                .sorted()
                .toList();
    }
}
