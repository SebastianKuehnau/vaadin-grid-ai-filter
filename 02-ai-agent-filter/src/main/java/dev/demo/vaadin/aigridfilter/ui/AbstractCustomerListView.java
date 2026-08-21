package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.theme.lumo.LumoUtility;
import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;

/** Adds a variant switcher and a one-line capability description to the shared search view. */
abstract class AbstractCustomerListView extends AbstractCustomerSearchView {

    AbstractCustomerListView(CustomerRepository customerRepository, CustomerSearchAgent searchAgent,
                             String heading, String description) {
        super(customerRepository, searchAgent, heading);

        // Inserted after the heading (index 0), ahead of the inherited filter field and grid.
        addComponentAtIndex(1, variantSwitcher());
        Paragraph descriptionText = new Paragraph(description);
        descriptionText.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.Margin.NONE);
        addComponentAtIndex(2, descriptionText);
    }

    /** Links to both variant views, with the current one rendered as plain text. */
    private HorizontalLayout variantSwitcher() {
        HorizontalLayout switcher = new HorizontalLayout(
                new Span("Variant:"),
                variantLink("02(a) flat", FlatCustomerListView.class),
                variantLink("02(b) value + operator + negate", OperatorCustomerListView.class));
        switcher.setAlignItems(Alignment.CENTER);
        return switcher;
    }

    private Component variantLink(String label, Class<? extends AbstractCustomerListView> viewClass) {
        if (viewClass.isInstance(this)) {
            Span current = new Span(label);
            current.addClassNames(LumoUtility.FontWeight.BOLD);
            return current;
        }
        return new RouterLink(label, viewClass);
    }
}
