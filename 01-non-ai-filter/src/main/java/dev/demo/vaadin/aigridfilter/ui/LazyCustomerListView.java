package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.datepicker.DatePickerVariant;
import com.vaadin.flow.component.grid.HeaderRow;
import com.vaadin.flow.component.grid.dataview.GridLazyDataView;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldVariant;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.data.VaadinSpringDataHelpers;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Route("lazy")
public class LazyCustomerListView extends VerticalLayout {

    final FilterableCustomerGrid grid;
    private final GridLazyDataView<Customer> customerGridLazyDataView;
    private final CustomerRepository customerRepository;

    public LazyCustomerListView(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
        add(new H1("Customer Grid – Lazy Filter"));

        grid = new FilterableCustomerGrid();
        // annualRevenue is only marked sortable in the shared grid; this adds its sort property.
        grid.getColumnByKey("annualRevenue").setSortProperty("annualRevenue");
        grid.getColumnByKey("address").setFlexGrow(2);

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
                // Selected ratings are alternatives, so combine their score ranges with OR.
                List<Predicate> ratingAlternatives = new ArrayList<>();
                creditRatingFilterSet.forEach(creditRatingFilter ->
                        ratingAlternatives.add(criteriaBuilder.between(root.get("creditScore"),
                                creditRatingFilter.minScoreInclusive(), creditRatingFilter.maxScoreInclusive())));
                predicates.add(criteriaBuilder.or(ratingAlternatives.toArray(new Predicate[0])));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    static class FilterableCustomerGrid extends CustomerGrid {

        private final Customer filterCustomer = new Customer();
        private String addressFilter;
        private Set<CreditRating> creditRatingFilterSet = Set.of();

        private final List<Runnable> filterChangeListeners = new ArrayList<>();

        public FilterableCustomerGrid() {
            HeaderRow headerRow = appendHeaderRow();
            headerRow.getCell(getColumnByKey("companyName"))
                    .setComponent(createFilterField(event -> {
                        filterCustomer.setCompanyName(event.getValue());
                        notifyFilterChange();
                    }));

            headerRow.getCell(getColumnByKey("contactName"))
                    .setComponent(createFilterField(event -> {
                        filterCustomer.setContactName(event.getValue());
                        notifyFilterChange();
                    }));

            headerRow.getCell(getColumnByKey("email"))
                    .setComponent(createFilterField(event -> {
                        filterCustomer.setEmail(event.getValue());
                        notifyFilterChange();
                    }));

            headerRow.getCell(getColumnByKey("phone"))
                    .setComponent(createFilterField(event -> {
                        filterCustomer.setPhone(event.getValue());
                        notifyFilterChange();
                    }));

            headerRow.getCell(getColumnByKey("customerSince"))
                    .setComponent(createDateFilterField(event -> {
                        filterCustomer.setCustomerSince(event.getValue());
                        notifyFilterChange();
                    }));

            headerRow.getCell(getColumnByKey("lastOrderDate"))
                    .setComponent(createDateFilterField(event -> {
                        filterCustomer.setLastOrderDate(event.getValue());
                        notifyFilterChange();
                    }));

            headerRow.getCell(getColumnByKey("annualRevenue"))
                    .setComponent(createIntegerFilterField(event -> {
                        filterCustomer.setAnnualRevenue(
                                event.getValue() != null ? BigDecimal.valueOf(event.getValue()) : null);
                        notifyFilterChange();
                    }));

            headerRow.getCell(getColumnByKey("address"))
                    .setComponent(createFilterField(event -> {
                        addressFilter = event.getValue();
                        notifyFilterChange();
                    }));

            headerRow.getCell(getColumnByKey("creditRating"))
                    .setComponent(createRatingFilterField());
        }

        public Customer getFilterCustomer() {
            return filterCustomer;
        }

        public String getAddressFilter() {
            return addressFilter;
        }

        public Set<CreditRating> getCreditRatingFilterSet() {
            return creditRatingFilterSet;
        }

        /** Notified after every header-row filter field change (including credit rating). */
        public void addFilterChangeListener(Runnable listener) {
            filterChangeListeners.add(listener);
        }

        private void notifyFilterChange() {
            filterChangeListeners.forEach(Runnable::run);
        }

        private Component createFilterField(HasValue.ValueChangeListener<AbstractField.ComponentValueChangeEvent<TextField, String>> listener) {
            var filterField = new TextField();
            filterField.setWidthFull();
            filterField.addThemeVariants(TextFieldVariant.SMALL);
            filterField.addValueChangeListener(listener);
            filterField.setClearButtonVisible(true);
            return filterField;
        }

        private Component createDateFilterField(HasValue.ValueChangeListener<AbstractField.ComponentValueChangeEvent<DatePicker, LocalDate>> listener) {
            var filterField = new DatePicker();
            filterField.setWidthFull();
            filterField.addThemeVariants(DatePickerVariant.LUMO_SMALL);
            filterField.addValueChangeListener(listener);
            filterField.setClearButtonVisible(true);
            return filterField;
        }

        private Component createIntegerFilterField(HasValue.ValueChangeListener<? super AbstractField.ComponentValueChangeEvent<IntegerField, Integer>> listener) {
            var filterField = new IntegerField();
            filterField.addThemeVariants(TextFieldVariant.SMALL);
            filterField.setWidthFull();
            filterField.addValueChangeListener(listener);
            filterField.setClearButtonVisible(true);
            return filterField;
        }

        /** Dropdown filter for the credit rating; empty selection means "any rating". */
        private Component createRatingFilterField() {
            var ratingFilterComboBox = new MultiSelectComboBox<CreditRating>();
            ratingFilterComboBox.setItems(CreditRating.values());
            ratingFilterComboBox.setItemLabelGenerator(CreditRating::getLabel);
            ratingFilterComboBox.setClearButtonVisible(true);
            ratingFilterComboBox.addValueChangeListener(event -> {
                creditRatingFilterSet = event.getValue();
                notifyFilterChange();
            });
            return ratingFilterComboBox;
        }
    }
}
