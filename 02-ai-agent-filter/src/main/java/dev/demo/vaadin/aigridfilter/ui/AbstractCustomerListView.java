package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.theme.lumo.LumoUtility;
import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;

/**
 * What this module's two variant views add to the shared {@link AbstractCustomerSearchView}: a switcher
 * between the variants, so a live demo can flip between them without a restart, and a one-line
 * description of what the variant on screen can and cannot express.
 * <p>
 * Everything else — the natural-language filter field, the grid, the off-thread search and the error
 * notification — is inherited, because it is identical in all three AI modules. The two subclasses differ
 * only in which {@link CustomerSearchAgent} implementation Spring injects (variant 02(a)'s flat tool call
 * vs. variant 02(b)'s value/operator/negate tool call) and in their heading and description.
 */
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

    /**
     * Links to both variant views, with the current one rendered as plain text. Both views live in one
     * running application, so switching variants during a talk is a single click.
     */
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
