package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.grid.dataview.GridLazyDataView;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.data.VaadinSpringDataHelpers;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Route("lazy")
public class LazyCustomerListView extends VerticalLayout {

    final FilterableCustomerGrid grid;
    private final GridLazyDataView<Customer> customerGridLazyDataView;
    private final CustomerRepository customerRepository;

    public LazyCustomerListView(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
        add(new H1("Customer Grid – Lazy Filter"));

        grid = new FilterableCustomerGrid();
        grid.getColumnByKey("annualRevenue").setSortProperty("annualRevenue");
        grid.getColumnByKey("address").setSortProperty("address.country", "address.city", "address.postalCode");
        grid.getColumnByKey("creditRating").setSortProperty("creditScore");

        customerGridLazyDataView = grid.setItems(gridQuery ->
                        customerRepository.findAll(
                                buildCustomerSpecification(),
                                VaadinSpringDataHelpers.toSpringPageRequest(gridQuery)).stream(),
                _ -> Math.toIntExact(customerRepository.count(buildCustomerSpecification())));
        grid.addFilterChangeListener(customerGridLazyDataView::refreshAll);
        add(grid);

        setSizeFull();
    }

    private Specification<Customer> buildCustomerSpecification() {

        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            Customer filterCustomer = grid.getFilterCustomer();

            if (filterCustomer.getCompanyName() != null && !filterCustomer.getCompanyName().isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("companyName")), "%" + filterCustomer.getCompanyName().toLowerCase() + "%"));
            }

            if (filterCustomer.getContactName() != null && !filterCustomer.getContactName().isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("contactName")), "%" + filterCustomer.getContactName().toLowerCase() + "%"));
            }

            if (filterCustomer.getEmail() != null && !filterCustomer.getEmail().isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("email")), "%" + filterCustomer.getEmail().toLowerCase() + "%"));
            }

            if (filterCustomer.getPhone() != null && !filterCustomer.getPhone().isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("phone")), "%" + filterCustomer.getPhone().toLowerCase() + "%"));
            }

            if (filterCustomer.getCustomerSince() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.<LocalDate>get("customerSince"), filterCustomer.getCustomerSince()));
            }

            if (filterCustomer.getLastOrderDate() != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.<LocalDate>get("lastOrderDate"), filterCustomer.getLastOrderDate()));
            }

            if (filterCustomer.getAnnualRevenue() != null) {
                predicates.add(criteriaBuilder.equal(root.<BigDecimal>get("annualRevenue"), filterCustomer.getAnnualRevenue()));
            }

            String addressFilter = grid.getAddressFilter();
            if (addressFilter != null && !addressFilter.isEmpty()) {
                String addressPattern = "%" + addressFilter.toLowerCase() + "%";
                var address = root.get("address");
                predicates.add(criteriaBuilder.or(
                        criteriaBuilder.like(criteriaBuilder.lower(address.get("street")), addressPattern),
                        criteriaBuilder.like(criteriaBuilder.lower(address.get("houseNumber")), addressPattern),
                        criteriaBuilder.like(criteriaBuilder.lower(address.get("postalCode")), addressPattern),
                        criteriaBuilder.like(criteriaBuilder.lower(address.get("city")), addressPattern),
                        criteriaBuilder.like(criteriaBuilder.lower(address.get("state")), addressPattern),
                        criteriaBuilder.like(criteriaBuilder.lower(address.get("country")), addressPattern),
                        criteriaBuilder.like(criteriaBuilder.lower(address.get("countryCode")), addressPattern)
                ));
            }

            var creditRatingFilterSet = grid.getCreditRatingFilterSet();
            if (creditRatingFilterSet != null && !creditRatingFilterSet.isEmpty()) {
                // Selected ratings are alternatives: a score matches if it falls into ANY of their
                // ranges, so combine the per-rating ranges with OR (not AND, which would be empty).
                List<Predicate> ratingAlternatives = new ArrayList<>();
                creditRatingFilterSet.forEach(creditRatingFilter ->
                        ratingAlternatives.add(criteriaBuilder.between(root.get("creditScore"),
                                creditRatingFilter.minScoreInclusive(), creditRatingFilter.maxScoreInclusive())));
                predicates.add(criteriaBuilder.or(ratingAlternatives.toArray(new Predicate[0])));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }
}
