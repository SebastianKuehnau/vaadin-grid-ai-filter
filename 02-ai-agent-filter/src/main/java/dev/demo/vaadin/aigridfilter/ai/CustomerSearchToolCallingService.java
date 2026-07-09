package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerSearchCriteria;
import dev.demo.vaadin.aigridfilter.ai.filter.CustomerSpecifications;
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
            country, city, postal code, street, house number, and credit rating.
            The credit rating is one of: creditworthy (GOOD), limited (MEDIUM), or
            at risk / not creditworthy (POOR).
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

        this.criteria = new CustomerSearchCriteria(companyName, contactName, email, phone, customerSince,
                lastOrderDate, country, city, postalCode, street, houseNumber, creditRating);
    }

    @Tool(description = "Current date and time")
    LocalDateTime currentLocalDateTime() {
        return LocalDateTime.now();
    }
}
