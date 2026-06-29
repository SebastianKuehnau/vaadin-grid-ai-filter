package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.dataview.GridLazyDataView;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.data.VaadinSpringDataHelpers;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.jpa.domain.Specification;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Route("")
public class SimpleCustomerListView extends VerticalLayout {

    private final Logger logger = LoggerFactory.getLogger(SimpleCustomerListView.class);

    private static final NumberFormat REVENUE_FORMAT = NumberFormat.getNumberInstance(Locale.GERMANY);

    private final CustomerRepository customerRepository;
    private final ChatClient chatClient;

    private final Grid<Customer> grid;
    private final TextField filterField;

    public SimpleCustomerListView(CustomerRepository customerRepository, ChatModel chatModel) {
        this.customerRepository = customerRepository;
        chatClient = ChatClient.builder(chatModel).build();

        add(new H1("Customer Grid – AI Filter (simple)"));

        filterField = new TextField("", "filter for ...");
        filterField.addValueChangeListener(this::onFilter);
        filterField.setClearButtonVisible(true);
        filterField.setWidthFull();
        add(filterField);

        this.grid = new Grid<>(Customer.class);
        var grid = this.grid;
        grid.setColumns("companyName", "contactName", "email", "phone", "customerSince", "lastOrderDate");
        grid.addColumn(customer -> customer.getAnnualRevenue() == null ?
                        "" : REVENUE_FORMAT.format(customer.getAnnualRevenue()) + " €")
                .setHeader("Annual Revenue").setKey("annualRevenue").setSortable(true)
                .setTextAlign(ColumnTextAlign.END);
        grid.addColumn(Customer::getAddress).setKey("address").setHeader("Address").setSortable(true)
                .setSortProperty("address.country", "address.city", "address.postalCode");
        grid.addComponentColumn(CreditScoreIndicator::new).setKey("creditRating").setHeader("Credit Rating")
                .setSortProperty("creditScore");

        grid.setItems(
                gridQuery -> customerRepository.findAll(
                        VaadinSpringDataHelpers.toSpringPageRequest(gridQuery)).stream(),
                _ -> Math.toIntExact(customerRepository.count()));
        grid.setSizeFull();
        add(grid);

        setSizeFull();
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

    private void onFilter(AbstractField.ComponentValueChangeEvent<TextField, String> event) {
        if (event.getValue() == null || event.getValue().isBlank()) {
            return;
        }

        filterField.setEnabled(false);

        chatClient.prompt()
                .system("""
                        You are a helpful assistant that helps users find customers based on their 
                        last order date and city.
                        """)
                .user(event.getValue())
                .tools(this)
                .advisors(SimpleLoggerAdvisor.builder().build())
                .stream()
                .content()
                .subscribe(token -> {},
                        throwable -> getUI().ifPresent(ui -> ui.access(() ->
                                Notification.show("Error - " + throwable.getLocalizedMessage())
                                        .addThemeVariants(NotificationVariant.ERROR))),
                        () -> getUI().ifPresent(ui -> ui.access(() -> {
                            filterField.setEnabled(true);
                        })));
    }

    @Tool(description = """
            Search and filter the customer grid. Returns nothing; it updates the grid in place to show
            only the matching customers, replacing any previous filter (filters are not additive).
            All parameters are optional - pass null to ignore one; passing all null shows every customer.
            All given parameters are combined with AND.
            Text parameters match case-insensitively on any substring.
            Date parameters (customerSince, lastOrderDate) match customers on or after the given date,
            in ISO format yyyy-MM-dd. For relative dates such as "last month", call the current
            date/time tool first to resolve the actual date.
            """)
    void searchCustomers(
            @ToolParam(description = """
                    matches customers whose last order date is on or after this date, or null.
                    Pass ISO yyyy-MM-dd; interpret ambiguous user input as day-first (German format),
                    e.g. '03.05.05' -> '2005-05-03'.""") LocalDate lastOrderDate,
            @ToolParam(description = "part of the city to match, or null") String city
    ) {
        logger.info("searchCustomers: lastOrderDate={}, city={}", lastOrderDate, city);

        getUI().ifPresent(ui -> ui.access(() -> {
            Specification<Customer> customerSpecification = buildCustomerSpecification(lastOrderDate, city);
            grid.setItems(
                    query -> customerRepository.findAll(customerSpecification,
                            VaadinSpringDataHelpers.toSpringPageRequest(query)).stream(),
                    query -> Math.toIntExact(customerRepository.count(customerSpecification))
            );
        }));
    }

    private Specification<Customer> buildCustomerSpecification(LocalDate lastOrderDate, String city) {

        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (lastOrderDate != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.<LocalDate>get("lastOrderDate"), lastOrderDate));
            }

            var address = root.get("address");

            if (city != null && !city.isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(address.get("city")),  "%" + city.toLowerCase() + "%"));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    @Tool(description = "Current date and time")
    LocalDateTime currentLocalDateTime() {
        return LocalDateTime.now();
    }
}

