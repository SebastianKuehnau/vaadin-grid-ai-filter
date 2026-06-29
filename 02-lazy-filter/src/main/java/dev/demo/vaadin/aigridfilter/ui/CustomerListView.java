package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.HeaderRow;
import com.vaadin.flow.component.grid.dataview.GridLazyDataView;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.datepicker.DatePickerVariant;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.select.SelectVariant;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldVariant;
import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.data.VaadinSpringDataHelpers;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Route("")
public class CustomerListView extends VerticalLayout {

    private static final NumberFormat REVENUE_FORMAT = NumberFormat.getNumberInstance(Locale.GERMANY);

    private final Grid<Customer> grid;
    private final GridLazyDataView<Customer> customerGridLazyDataView;

    private Customer filterCustomer = new Customer();

    private String addressFilter;

    private Set<CreditRating> creditRatingFilterSet = Set.of();

    public CustomerListView(CustomerRepository customerRepository) {
        add(new H1("Customer Grid – Lazy Filter"));

        this.grid = new Grid<>(Customer.class);
        var grid = this.grid;
        grid.setColumns("companyName", "contactName", "email", "phone", "customerSince", "lastOrderDate");
        grid.addColumn(customer -> customer.getAnnualRevenue() == null ?
                        "" : REVENUE_FORMAT.format(customer.getAnnualRevenue()) + " €")
                .setHeader("Annual Revenue").setKey("annualRevenue").setSortable(true)
                .setSortProperty("annualRevenue")
                .setTextAlign(ColumnTextAlign.END);
        grid.addColumn(Customer::getAddress).setKey("address").setHeader("Address").setSortable(true)
                .setSortProperty("address.country", "address.city", "address.postalCode");
        grid.addComponentColumn(CreditScoreIndicator::new).setKey("creditRating").setHeader("Credit Rating")
                .setSortProperty("creditScore");

        HeaderRow headerRow = grid.appendHeaderRow();
        headerRow.getCell(grid.getColumnByKey("companyName"))
                .setComponent(createFilterField(event ->
                    onFilterFieldChanged(event, customer -> filterCustomer.setCompanyName(event.getValue()))));

        headerRow.getCell(grid.getColumnByKey("contactName"))
                .setComponent(createFilterField(event ->
                        onFilterFieldChanged(event, customer -> filterCustomer.setContactName(event.getValue()))));

        headerRow.getCell(grid.getColumnByKey("email"))
                .setComponent(createFilterField(event ->
                        onFilterFieldChanged(event, customer -> filterCustomer.setEmail(event.getValue()))));

        headerRow.getCell(grid.getColumnByKey("phone"))
                .setComponent(createFilterField(event ->
                        onFilterFieldChanged(event, customer -> filterCustomer.setPhone(event.getValue()))));

        headerRow.getCell(grid.getColumnByKey("customerSince"))
                .setComponent(createDateFilterField(event ->
                        onDateFilterFieldChanged(event, customer -> filterCustomer.setCustomerSince(event.getValue()))));

        headerRow.getCell(grid.getColumnByKey("lastOrderDate"))
                .setComponent(createDateFilterField(event ->
                        onDateFilterFieldChanged(event, customer -> filterCustomer.setLastOrderDate(event.getValue()))));

        headerRow.getCell(grid.getColumnByKey("address"))
                .setComponent(createFilterField(event ->
                        onFilterFieldChanged(event, customer -> addressFilter = event.getValue())));

        headerRow.getCell(grid.getColumnByKey("creditRating"))
                .setComponent(createRatingFilterField());

        customerGridLazyDataView = grid.setItems(gridQuery ->
                        customerRepository.findAll(
                                buildCustomerSpecification(),
                                VaadinSpringDataHelpers.toSpringPageRequest(gridQuery)).stream(),
                _ -> Math.toIntExact(customerRepository.count(buildCustomerSpecification())));
        grid.setSizeFull();
        add(grid);

        setSizeFull();
    }

    private void onFilterFieldChanged(AbstractField.ComponentValueChangeEvent<TextField, String> event, SerializableConsumer<Customer> customerConsumer) {
        customerConsumer.accept(filterCustomer);
        customerGridLazyDataView.refreshAll();
    }

    private Specification<Customer> buildCustomerSpecification() {

        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

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

    private Component createFilterField(HasValue.ValueChangeListener<AbstractField.ComponentValueChangeEvent<TextField, String>> listener) {
        var filterField = new TextField();
        filterField.addThemeVariants(TextFieldVariant.SMALL);
        filterField.addValueChangeListener(listener);
        filterField.setClearButtonVisible(true);
        return filterField;
    }

    private void onDateFilterFieldChanged(AbstractField.ComponentValueChangeEvent<DatePicker, LocalDate> event, SerializableConsumer<Customer> customerConsumer) {
        customerConsumer.accept(filterCustomer);
        customerGridLazyDataView.refreshAll();
    }

    private Component createDateFilterField(HasValue.ValueChangeListener<AbstractField.ComponentValueChangeEvent<DatePicker, LocalDate>> listener) {
        var filterField = new DatePicker();
        filterField.addThemeVariants(DatePickerVariant.LUMO_SMALL);
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
            customerGridLazyDataView.refreshAll();
        });
        return ratingFilterComboBox;
    }

    /** Viewport width (px) at or above which the medium-priority columns are shown. */
    private static final int MEDIUM_BREAKPOINT = 768;
    /** Viewport width (px) at or above which the large-priority columns are shown. */
    private static final int LARGE_BREAKPOINT = 1200;

    @Override
    protected void onAttach(AttachEvent attachEvent) {
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

        grid.getColumnByKey("address").setVisible(medium);
        grid.getColumnByKey("phone").setVisible(medium);
        grid.getColumnByKey("email").setVisible(medium);

        grid.getColumnByKey("customerSince").setVisible(large);
        grid.getColumnByKey("lastOrderDate").setVisible(large);
        grid.getColumnByKey("annualRevenue").setVisible(large);
    }
}

