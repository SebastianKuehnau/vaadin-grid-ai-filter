package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerSearchCriteria;
import dev.demo.vaadin.aigridfilter.ai.filter.CustomerSpecifications;
import dev.demo.vaadin.aigridfilter.ai.filter.RevenueRange;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Scope;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The AI layer: turns a natural-language query into a JPA {@link Specification} by letting the
 * model call the {@code searchCustomers} tool and extract the filter values into {@link #criteria}.
 * The class owns the {@link ChatClient}, the system prompt, and both {@code @Tool} methods, and
 * knows nothing about Vaadin, so it can be tested in isolation.
 * <p>
 * {@code @Scope("prototype")}: {@code CustomerListView} (the only place a {@link CustomerSearchAgent}
 * is injected) is not a Spring singleton either — Vaadin creates a fresh view instance per
 * navigation. Prototype scope gives each such view its own instance of this service, so
 * {@link #criteria} can live directly on the bean: different browser tabs/sessions never share an
 * instance, so there is no cross-session race, and within one instance the view only ever has one
 * search in flight at a time (it disables the filter field for the duration of a search). The one
 * thing this costs versus the old "fresh tool-provider object per call" trick: {@link #criteria}
 * must be reset explicitly at the top of {@link #requestCriteria}, since it now outlives a single
 * call.
 */
@Service
@Scope("prototype")
class CustomerSearchToolCallingService implements CustomerSearchAgent {

    private static final Logger logger = LoggerFactory.getLogger(CustomerSearchToolCallingService.class);

    private static final String SYSTEM_PROMPT = """
            You are a helpful assistant that helps users find customers based on their
            company name, contact name, email, phone, customer since, last order date,
            country, city, postal code, street, house number, annual revenue, and credit
            rating. The credit rating is one of: creditworthy (GOOD), limited (MEDIUM), or
            at risk / not creditworthy (POOR). A query can ask for multiple values of the
            same field (e.g. two cities, or two credit ratings) - pass all of them.
            """;

    private final ChatClient chatClient;

    CustomerSearchCriteria criteria;

    CustomerSearchToolCallingService(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * Turns the query into a JPA {@link Specification}: ask the LLM to call the search tool
     * ({@link #requestCriteria}) and translate the extracted criteria. {@code null} criteria (e.g.
     * on a bad response, or the model never called the tool) matches all.
     */
    @Override
    public Specification<Customer> resolveFilter(String naturalLanguageQuery) {
        return CustomerSpecifications.from(requestCriteria(naturalLanguageQuery));
    }

    /**
     * Asks the LLM to call {@code searchCustomers} and returns the criteria it extracted.
     * Package-private so the AI layer can be tested directly on the produced criteria. Returns
     * {@code null} if the model produces nothing usable, so the UI never breaks on a bad response.
     */
    CustomerSearchCriteria requestCriteria(String naturalLanguageQuery) {
        criteria = null;
        try {
            chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(naturalLanguageQuery)
                    .tools(this)
                    .advisors(SimpleLoggerAdvisor.builder().build())
                    .call()
                    .content();
        } catch (Exception e) {
            logger.warn("Could not turn query into search criteria; showing all customers. Query: '{}'",
                    naturalLanguageQuery, e);
            return null;
        }
        logger.info("requestCriteria('{}') -> {}", naturalLanguageQuery, criteria);
        return criteria;
    }

    @Tool(description = """
            Search and filter the customer grid. Returns nothing; it updates the grid in place to show
            only the matching customers, replacing any previous filter (filters are not additive).
            All parameters are optional - pass null (or an empty list) to ignore one; passing all null
            shows every customer. Every parameter is a JSON array: always wrap the value(s) in square
            brackets, even for a single value (one city is ["Berlin"], never "Berlin"; one revenue
            range is [{"atLeast": 500000}], never {"atLeast": 500000}). Never pass a bare scalar or
            object for a parameter. Pass one entry for a single value, or several entries for multiple
            values, which are matched with OR (e.g. two cities means "in this city OR that city").
            Different parameters are still combined with AND.
            Text parameters match case-insensitively on any substring.
            Date parameters (customerSince, lastOrderDate) each match customers anywhere in the year
            the given date falls in (e.g. ["2020-01-01"] matches all of 2020), in ISO format yyyy-MM-dd.
            For relative dates such as "last month", call the current date/time tool first to resolve
            the actual date.
            """)
    void searchCustomers(
            @ToolParam(description = "company names") List<String> companyName,
            @ToolParam(description = "contact names") List<String> contactName,
            @ToolParam(description = "email addresses") List<String> email,
            @ToolParam(description = """
                    phone numbers, part of each to match, or null. Numbers are stored in E.164 format, so
                    normalize the user input to E.164 before passing it, e.g. '016057123456' or
                    '0160 57 123456' -> '+4916057123456' (assume Germany / +49 for national numbers).""") List<String> phone,
            @ToolParam(description = """
                    'customer since' years to match, or null. Each entry matches customers who became a
                    customer anywhere in that entry's year. A JSON array of ISO yyyy-MM-dd dates;
                    interpret ambiguous user input as day-first (German format), e.g. '03.05.05' ->
                    ["2005-05-03"], and "since 2020" -> ["2020-01-01"].""") List<LocalDate> customerSince,
            @ToolParam(description = """
                    last-order years to match, or null. Each entry matches customers whose last order
                    falls anywhere in that entry's year. A JSON array of ISO yyyy-MM-dd dates; interpret
                    ambiguous user input as day-first (German format), e.g. '03.05.05' ->
                    ["2005-05-03"].""") List<LocalDate> lastOrderDate,
            @ToolParam(description = "countries") List<String> country,
            @ToolParam(description = "cities") List<String> city,
            @ToolParam(description = "postal codes") List<String> postalCode,
            @ToolParam(description = "streets") List<String> street,
            @ToolParam(description = "house numbers") List<String> houseNumber,
            @ToolParam(description = """
                    credit ratings to match, or null. Each one of: GOOD (creditworthy),
                    MEDIUM (limited creditworthiness), POOR (at risk / not creditworthy).""") List<CreditRating> creditRating,
            @ToolParam(description = """
                    annual revenue ranges to match, or null. A JSON array of range objects, each with an
                    optional "atLeast" and/or "atMost" (either may be omitted/null for an open-ended
                    range): "over 500000" -> [{"atLeast": 500000}], "under 50000" -> [{"atMost": 50000}],
                    "between 50000 and 200000" -> [{"atLeast": 50000, "atMost": 200000}]. Multiple ranges
                    are matched with OR, e.g. "over 500000 or under 50000" -> [{"atLeast": 500000},
                    {"atMost": 50000}].""") List<RevenueRange> annualRevenue
    ) {
        logger.info("searchCustomers: companyName={}, contactName={}, email={}, phone={}, customerSince={}, " +
                        "lastOrderDate={}, country={}, city={}, postalCode={}, street={}, houseNumber={}, " +
                        "creditRating={}, annualRevenue={}",
                companyName, contactName, email, phone, customerSince,
                lastOrderDate, country, city, postalCode, street, houseNumber, creditRating, annualRevenue);

        this.criteria = new CustomerSearchCriteria(companyName, contactName, email, phone, customerSince,
                lastOrderDate, country, city, postalCode, street, houseNumber, creditRating, annualRevenue);
    }

    @Tool(description = "Current date and time")
    LocalDateTime currentLocalDateTime() {
        return LocalDateTime.now();
    }
}
