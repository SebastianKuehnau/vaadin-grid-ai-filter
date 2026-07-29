package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.spring.data.VaadinSpringDataHelpers;
import com.vaadin.flow.theme.lumo.LumoUtility;
import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The UI layer shared by this module's two variant views: a natural-language filter field above the
 * customer grid, plus a switcher between the variants so a live demo can flip between them without a
 * restart. It delegates the actual search to {@link CustomerSearchAgent} and only knows how to put the
 * resulting {@link Specification} onto the grid — no Spring AI, no criteria building.
 * <p>
 * The two subclasses differ only in which {@link CustomerSearchAgent} implementation Spring injects
 * (variant 02(a)'s scalar tool call vs. variant 02(b)'s value/operator/negate tool call) and in their
 * headings; the search plumbing lives here exactly once.
 */
abstract class AbstractCustomerListView extends VerticalLayout {

    private static final Logger logger = LoggerFactory.getLogger(AbstractCustomerListView.class);

    final CustomerGrid grid;
    final TextField filterField;
    private final CustomerRepository customerRepository;
    private final CustomerSearchAgent searchAgent;

    AbstractCustomerListView(CustomerRepository customerRepository, CustomerSearchAgent searchAgent,
                             String heading, String description) {
        this.customerRepository = customerRepository;
        this.searchAgent = searchAgent;

        add(new H1(heading));
        add(variantSwitcher());
        Paragraph descriptionText = new Paragraph(description);
        descriptionText.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.Margin.NONE);
        add(descriptionText);

        filterField = new TextField("", "filter for ...");
        filterField.addValueChangeListener(this::onFilter);
        filterField.setClearButtonVisible(true);
        filterField.setWidthFull();
        add(filterField);

        grid = new CustomerGrid();
        add(grid);

        applyFilter(Specification.unrestricted());

        setSizeFull();
    }

    /**
     * Links to both variant views, with the current one rendered as plain text. Both views live in one
     * running application, so switching variants during a talk is a single click.
     */
    private HorizontalLayout variantSwitcher() {
        HorizontalLayout switcher = new HorizontalLayout(
                new Span("Variant:"),
                variantLink("02(a) scalar", ScalarCustomerListView.class),
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

    private void onFilter(AbstractField.ComponentValueChangeEvent<TextField, String> event) {
        if (event.getValue() == null || event.getValue().isBlank())
            applyFilter(Specification.unrestricted());
        else {
            filterField.setEnabled(false);

            // resolveFilter() blocks on the LLM, so run it off the UI thread and apply via ui.access().
            CompletableFuture
                    .supplyAsync(() -> searchAgent.resolveFilter(event.getValue()))
                    .whenComplete(this::onComplete);
        }
    }

    private void onComplete(Specification<Customer> specification, Throwable error) {
        var ui = getUI().orElseThrow();

        ui.access(() -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                logger.error("Customer search failed", cause);
                Notification.show("Error - " + cause.getLocalizedMessage())
                        .addThemeVariants(NotificationVariant.ERROR);
            } else {
                applyFilter(specification);
            }
            filterField.setEnabled(true);
        });
    }

    /** Re-binds the grid to the given specification — the single point where filtering is applied. */
    private void applyFilter(Specification<Customer> specification) {
        grid.setItems(
                query -> customerRepository.findAll(specification,
                        VaadinSpringDataHelpers.toSpringPageRequest(query)).stream(),
                _ -> Math.toIntExact(customerRepository.count(specification)));
    }
}
