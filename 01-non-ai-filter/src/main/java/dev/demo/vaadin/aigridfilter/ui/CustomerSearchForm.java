package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.BigDecimalField;
import com.vaadin.flow.component.textfield.TextField;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.filter.CustomerFilter;

import java.util.List;
import java.util.function.Consumer;

/** A query builder for {@code Customer}: <em>Search</em> collects the fields into a {@link CustomerFilter}. */
@StyleSheet("customer-search-form.css")
public class CustomerSearchForm extends FormLayout {

    final TextField companyName = new TextField("Company name");
    final TextField contactName = new TextField("Contact name");
    final TextField email = new TextField("Email");
    final TextField city = new TextField("City");
    final MultiSelectComboBox<String> countries = new MultiSelectComboBox<>("Country");
    final Select<ActiveStatus> active = new Select<>();
    final MultiSelectComboBox<CreditRating> creditRatings = new MultiSelectComboBox<>("Credit rating");
    final NumberRange revenue = new NumberRange("Annual revenue (€)");
    final DateRange customerSince = new DateRange("Customer since");
    final DateRange lastOrder = new DateRange("Last order");
    Button search;

    private final Consumer<CustomerFilter> onSearch;

    /** Tri-state for the boolean {@code active} flag, so "no preference" is expressible. */
    enum ActiveStatus {
        ANY("Any"), ACTIVE("Active only"), INACTIVE("Inactive only");

        final String label;

        ActiveStatus(String label) {
            this.label = label;
        }

        Boolean toFilterValue() {
            return this == ANY ? null : this == ACTIVE;
        }
    }

    public CustomerSearchForm(List<String> availableCountries, Consumer<CustomerFilter> onSearch) {
        this.onSearch = onSearch;

        countries.setItems(availableCountries);
        countries.setClearButtonVisible(true);

        active.setLabel("Status");
        active.setItems(ActiveStatus.values());
        active.setItemLabelGenerator(status -> status.label);
        active.setValue(ActiveStatus.ANY);

        creditRatings.setItems(CreditRating.values());
        creditRatings.setItemLabelGenerator(CreditRating::getLabel);
        creditRatings.setClearButtonVisible(true);

        // Stable ids, so a test or a browser automation can target each field precisely.
        companyName.setId("filter-company-name");
        contactName.setId("filter-contact-name");
        email.setId("filter-email");
        city.setId("filter-city");
        countries.setId("filter-countries");
        active.setId("filter-status");
        creditRatings.setId("filter-credit-rating");
        revenue.from.setId("filter-revenue-min");
        revenue.to.setId("filter-revenue-max");
        customerSince.from.setId("filter-customer-since-from");
        customerSince.to.setId("filter-customer-since-to");
        lastOrder.from.setId("filter-last-order-from");
        lastOrder.to.setId("filter-last-order-to");

        setResponsiveSteps(
                new ResponsiveStep("0", 1),
                new ResponsiveStep("500px", 2),
                new ResponsiveStep("900px", 3));

        add(companyName, contactName, email, city, countries, active, creditRatings, revenue, customerSince, lastOrder);
        add(new ButtonBar());
    }

    /** Search / Reset actions, spanning the full form width. */
    private class ButtonBar extends HorizontalLayout {
        ButtonBar() {
            search = new Button("Search", _ -> onSearch.accept(buildFilter()));
            search.setId("search-button");
            search.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            search.addClickShortcut(Key.ENTER);

            Button reset = new Button("Reset", _ -> reset());

            add(search, reset);
            setJustifyContentMode(FlexComponent.JustifyContentMode.END);
            setWidthFull();
            addClassName("customer-search-form__buttons");
            CustomerSearchForm.this.setColspan(this, 3);
        }
    }

    private CustomerFilter buildFilter() {
        return new CustomerFilter(
                companyName.getValue(),
                contactName.getValue(),
                email.getValue(),
                city.getValue(),
                countries.getValue(),
                active.getValue().toFilterValue(),
                creditRatings.getValue(),
                revenue.from.getValue(),
                revenue.to.getValue(),
                customerSince.from.getValue(),
                customerSince.to.getValue(),
                lastOrder.from.getValue(),
                lastOrder.to.getValue());
    }

    /** Clears every field back to "no constraint" and re-runs the (now empty) search. */
    public void reset() {
        companyName.clear();
        contactName.clear();
        email.clear();
        city.clear();
        countries.clear();
        active.setValue(ActiveStatus.ANY);
        creditRatings.clear();
        revenue.clear();
        customerSince.clear();
        lastOrder.clear();
        onSearch.accept(CustomerFilter.empty());
    }

    /** A "min - max" pair of numeric inputs shown as one form item. */
    static class NumberRange extends HorizontalLayout {
        final BigDecimalField from = new BigDecimalField();
        final BigDecimalField to = new BigDecimalField();

        NumberRange(String label) {
            from.setLabel(label);
            from.setPlaceholder("min");
            to.setPlaceholder("max");
            from.setWidthFull();
            to.setWidthFull();
            setWidthFull();
            add(from, to);

            setAlignItems(Alignment.BASELINE);
        }

        void clear() {
            from.clear();
            to.clear();
        }
    }

    /** A "from - until" pair of date pickers shown as one form item. */
    static class DateRange extends HorizontalLayout {
        final DatePicker from = new DatePicker();
        final DatePicker to = new DatePicker();

        DateRange(String label) {
            from.setLabel(label);
            from.setPlaceholder("from");
            to.setPlaceholder("until");
            from.setWidthFull();
            to.setWidthFull();
            setWidthFull();
            add(from, to);

            setAlignItems(Alignment.BASELINE);
        }

        void clear() {
            from.clear();
            to.clear();
        }
    }
}
