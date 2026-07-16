package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.datepicker.DatePickerVariant;
import com.vaadin.flow.component.grid.HeaderRow;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldVariant;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@link CustomerGrid} extended with a header-row filter field per column. Owns the current
 * filter state ({@link #getFilterCustomer()}, {@link #getAddressFilter()},
 * {@link #getCreditRatingFilterSet()}) and notifies registered listeners
 * ({@link #addFilterChangeListener(Runnable)}) whenever it changes. Building a {@code Specification}
 * from that state and refreshing the data provider stays the host view's responsibility.
 */
public class FilterableCustomerGrid extends CustomerGrid {

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
