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
import dev.demo.vaadin.aigridfilter.data.CreditRating;
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

@Route("customer-list")
public class CustomerListView extends VerticalLayout {

    private final Logger logger = LoggerFactory.getLogger(CustomerListView.class);

    private static final NumberFormat REVENUE_FORMAT = NumberFormat.getNumberInstance(Locale.GERMANY);

    private final CustomerRepository customerRepository;
    private final ChatClient chatClient;

    private final Grid<Customer> grid;
    private final TextField filterField;

    public CustomerListView(CustomerRepository customerRepository, ChatModel chatModel) {
        this.customerRepository = customerRepository;
        this.chatClient = ChatClient.builder(chatModel).build();

        add(new H1("Customer Grid – AI Filter"));

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

        GridLazyDataView<Customer> customerGridLazyDataView = grid.setItems(gridQuery ->
                        customerRepository.findAll(VaadinSpringDataHelpers.toSpringPageRequest(gridQuery)).stream(),
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

    private void onFilter(AbstractField.ComponentValueChangeEvent<TextField, String> event) {
        if (event.getValue() == null || event.getValue().isBlank()) {
            return;
        }

        chatClient.prompt()
                .system("""
                        You are a helpful assistant that helps users find customers based on their
                        company name, contact name, email, phone, customer since, last order date,
                        country, city, postal code, street, house number, and credit rating.
                        The credit rating is one of: creditworthy (GOOD), limited (MEDIUM), or
                        at risk / not creditworthy (POOR).
                        """)
                .user(event.getValue())
                .tools(this)
                .advisors(SimpleLoggerAdvisor.builder().build())
                .stream()
                .content()
                .subscribe(token -> {
                        },
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
            @ToolParam(description = "part name of the company name to match, or null") String companyName,
            @ToolParam(description = "part of the contact name in the company to match, or null") String contactName,
            @ToolParam(description = "part of the email address to match, or null") String email,
            @ToolParam(description = """
                    part of the phone number to match, or null. Numbers are stored in E.164 format, so
                    normalize the user input to E.164 before passing it, e.g. '016057123456' or
                    '0160 57 123456' -> '+4916057123456' (assume Germany / +49 for national numbers).""") String phone,
            @ToolParam(description = """
                    matches customers whose 'customer since' date is on or after this date, or null.
                    Pass ISO yyyy-MM-dd; interpret ambiguous user input as day-first (German format),
                    e.g. '03.05.05' -> '2005-05-03'.""") LocalDate customerSince,
            @ToolParam(description = """
                    matches customers whose last order date is on or after this date, or null.
                    Pass ISO yyyy-MM-dd; interpret ambiguous user input as day-first (German format),
                    e.g. '03.05.05' -> '2005-05-03'.""") LocalDate lastOrderDate,
            @ToolParam(description = "part of the country to match, or null") String country,
            @ToolParam(description = "part of the city to match, or null") String city,
            @ToolParam(description = "part of the postal code to match, or null") String postalCode,
            @ToolParam(description = "part of the street to match, or null") String street,
            @ToolParam(description = "part of the house number to match, or null") String houseNumber,
            @ToolParam(description = """
                    the credit rating to match, or null. One of: GOOD (creditworthy),
                    MEDIUM (limited creditworthiness), POOR (at risk / not creditworthy).""") CreditRating creditRating
    ) {
        logger.info("searchCustomers: companyName={}, contactName={}, email={}, phone={}, customerSince={}, " +
                        "lastOrderDate={}, country={}, city={}, postalCode={}, street={}, houseNumber={}, creditRating={}",
                companyName, contactName, email, phone, customerSince,
                lastOrderDate, country, city, postalCode, street, houseNumber, creditRating);

        getUI().ifPresent(ui -> ui.access(() -> {
            Specification<Customer> customerSpecification = buildCustomerSpecification(companyName, contactName, email, phone, customerSince, lastOrderDate, country, city, postalCode, street, houseNumber, creditRating);
            grid.setItems(
                    query -> customerRepository.findAll(customerSpecification,
                            VaadinSpringDataHelpers.toSpringPageRequest(query)).stream(),
                    query -> Math.toIntExact(customerRepository.count(customerSpecification))
            );
        }));
    }

    private Specification<Customer> buildCustomerSpecification(String companyName, String contactName, String email,
                                                               String phone, LocalDate customerSince, LocalDate lastOrderDate,
                                                               String country, String city, String postalCode, String street, String houseNumber,
                                                               CreditRating creditRating) {

        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (companyName != null && !companyName.isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("companyName")), "%" + companyName.toLowerCase() + "%"));
            }

            if (contactName != null && !contactName.isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("contactName")), "%" + contactName.toLowerCase() + "%"));
            }

            if (email != null && !email.isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("email")), "%" + email.toLowerCase() + "%"));
            }

            if (phone != null && !phone.isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("phone")), "%" + phone.toLowerCase() + "%"));
            }

            if (customerSince != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.<LocalDate>get("customerSince"), customerSince));
            }

            if (lastOrderDate != null) {
                predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.<LocalDate>get("lastOrderDate"), lastOrderDate));
            }

            var address = root.get("address");

            if (country != null && !country.isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(address.get("country")),  "%" + country.toLowerCase() + "%"));
            }

            if (city != null && !city.isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(address.get("city")),  "%" + city.toLowerCase() + "%"));
            }

            if (postalCode != null && !postalCode.isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(address.get("postalCode")),  "%" + postalCode.toLowerCase() + "%"));
            }

            if (street != null && !street.isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(address.get("street")),  "%" + street.toLowerCase() + "%"));
            }

            if (houseNumber != null && !houseNumber.isEmpty()) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(address.get("houseNumber")),  "%" + houseNumber.toLowerCase() + "%"));
            }

            if (creditRating != null) {
                predicates.add(criteriaBuilder.between(root.get("creditScore"),
                        creditRating.minScoreInclusive(), creditRating.maxScoreInclusive()));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    @Tool(description = "Current date and time")
    LocalDateTime currentLocalDateTime() {
        return LocalDateTime.now();
    }
}

