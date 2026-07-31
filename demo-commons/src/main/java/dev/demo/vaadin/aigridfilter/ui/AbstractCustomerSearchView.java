package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.spring.data.VaadinSpringDataHelpers;
import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.domain.Specification;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The view every AI module shows: one natural-language {@link TextField} above the shared
 * {@link CustomerGrid}, and the plumbing that gets from the one to the other.
 * <p>
 * That plumbing is the same in all three AI modules and says nothing about any of them:
 * {@link CustomerSearchAgent#resolveFilter} blocks on the model, so it runs off the UI thread and the
 * result is applied through {@code ui.access(...)}; the filter field is disabled while a search is in
 * flight; a failure becomes an error notification instead of a broken view. What differs between the
 * modules is only which {@link CustomerSearchAgent} Spring injects — and that difference is invisible
 * from here, which is itself one of the repository's findings.
 * <p>
 * Subclasses add their own {@code @Route} and heading, and may insert content between the heading and the
 * filter field with {@link #addComponentAtIndex} — {@code 02-ai-agent-filter} uses that for its variant
 * switcher, since it serves two variants from one application.
 * <p>
 * {@link #grid} and {@link #filterField} are {@code public} rather than package-private on purpose. The
 * browserless tests reach for them, and they now live in a different jar than the subclasses: with
 * {@code spring-boot-devtools} active, this jar is loaded by the base classloader while the module's
 * classes go through the restart classloader, which makes the two <em>different runtime packages</em> —
 * package-private access across them fails with an {@code IllegalAccessError} at runtime, not at compile
 * time.
 */
public abstract class AbstractCustomerSearchView extends VerticalLayout {

    private static final Logger logger = LoggerFactory.getLogger(AbstractCustomerSearchView.class);

    public final CustomerGrid grid;
    public final TextField filterField;
    private final CustomerRepository customerRepository;
    private final CustomerSearchAgent searchAgent;

    protected AbstractCustomerSearchView(CustomerRepository customerRepository,
                                         CustomerSearchAgent searchAgent, String heading) {
        this.customerRepository = customerRepository;
        this.searchAgent = searchAgent;

        add(new H1(heading));

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
