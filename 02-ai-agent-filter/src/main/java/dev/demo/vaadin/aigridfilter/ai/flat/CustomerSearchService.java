package dev.demo.vaadin.aigridfilter.ai.flat;

import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.ai.TokenUsageAdvisor;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Variant 02(a): the model calls one {@code searchCustomers} tool with one scalar value per field. */
@Service("flatSearchAgent")
@Scope("prototype")
class CustomerSearchService implements CustomerSearchAgent {

    private static final Logger logger = LoggerFactory.getLogger(CustomerSearchService.class);

    private static final String SYSTEM_PROMPT = """
            You are a helpful assistant that helps users find customers based on their
            company name, contact name, email, phone, customer since, last order date,
            country, city, postal code, street, house number, annual revenue, and credit
            rating. The credit rating is one of: creditworthy (GOOD), limited (MEDIUM), or
            at risk / not creditworthy (POOR).
            Call the searchCustomers tool ONCE to filter the grid, then stop - it has already been
            applied, so never call it a second time. Every parameter takes exactly ONE value; there is
            no way to pass a second value for the same field. If a request mentions several values for
            one field (e.g. two cities), pass the first one and accept that the rest cannot be
            expressed - do not call the tool again for them.

            For a relative date ("yesterday", "this year", "last week", "in the last 12 months"), you
            MUST call the currentLocalDateTime tool first and compute the date from its result - NEVER
            guess or assume today's date from memory or context. Only after that call, call
            searchCustomers with the computed date.
            """;

    private final ChatClient chatClient;
    private final TokenUsageAdvisor tokenUsageAdvisor;

    CustomerCriteria criteria;

    CustomerSearchService(ChatModel chatModel, TokenUsageAdvisor tokenUsageAdvisor) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.tokenUsageAdvisor = tokenUsageAdvisor;
    }

    /** Asks the LLM to call the search tool and turns the criteria it extracted into a {@link Specification}. */
    @Override
    public Specification<Customer> resolveFilter(String naturalLanguageQuery) {
        return CustomerSpecifications.from(requestCriteria(naturalLanguageQuery));
    }

    /** Asks the LLM to call {@code searchCustomers}; {@code null} if it produced nothing usable. */
    CustomerCriteria requestCriteria(String naturalLanguageQuery) {
        criteria = null;
        try {
            // By the time this returns, searchCustomers(...) has run; the answer text is irrelevant.
            chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(naturalLanguageQuery)
                    .tools(this)
                    .advisors(SimpleLoggerAdvisor.builder().build(), tokenUsageAdvisor)
                    .call()
                    .chatResponse();
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
            All parameters are optional - pass null to ignore one; passing all null shows every
            customer. Every parameter takes a single scalar value, never a list: one city is "Berlin",
            and there is no way to search for two cities at once. Different parameters are combined
            with AND.
            Text parameters match the whole field, case-insensitively - not a substring.
            Date parameters (customerSince, lastOrderDate) match that one exact day, in ISO format
            yyyy-MM-dd; there is no way to express a range or a whole year.
            annualRevenue is a MINIMUM: it matches customers with at least that revenue. There is no
            way to express an upper bound or a range.
            """)
    void searchCustomers(
            @ToolParam(description = "company name") String companyName,
            @ToolParam(description = "contact name") String contactName,
            @ToolParam(description = "email address") String email,
            @ToolParam(description = """
                    phone number, part of it to match, or null. Numbers are stored in E.164 format, so
                    normalize the user input to E.164 before passing it, e.g. '016057123456' or
                    '0160 57 123456' -> '+4916057123456' (assume Germany / +49 for national numbers).""") String phone,
            @ToolParam(description = """
                    the exact 'customer since' day to match, or null. An ISO yyyy-MM-dd date;
                    interpret ambiguous user input as day-first (German format), e.g. '03.05.05' ->
                    "2005-05-03". Only one exact day can be matched - "since 2020" cannot be
                    expressed. For a relative date ("yesterday"), call currentLocalDateTime
                    first.""") LocalDate customerSince,
            @ToolParam(description = """
                    the exact last-order day to match, or null. An ISO yyyy-MM-dd date; interpret
                    ambiguous user input as day-first (German format), e.g. '03.05.05' ->
                    "2005-05-03". Only one exact day can be matched, never a range. For a relative
                    date ("yesterday"), call currentLocalDateTime first.""") LocalDate lastOrderDate,
            @ToolParam(description = """
                    country, e.g. "Germany" or "France". A bare city name (Hamburg, Berlin, Munich,
                    ...) is NOT a country - put it in the city parameter instead.""") String country,
            @ToolParam(description = """
                    city, e.g. "Hamburg" or "Berlin". A bare place name defaults to city unless it
                    unambiguously names a country.""") String city,
            @ToolParam(description = "postal code") String postalCode,
            @ToolParam(description = "street") String street,
            @ToolParam(description = "house number") String houseNumber,
            @ToolParam(description = """
                    credit rating to match, or null. One of: GOOD (creditworthy),
                    MEDIUM (limited creditworthiness), POOR (at risk / not creditworthy).""") CreditRating creditRating,
            @ToolParam(description = """
                    minimum annual revenue to match, as a plain number, or null: "over 500000" ->
                    500000. Only a lower bound is supported - "under 50000" and "between 50000 and
                    200000" cannot be expressed.""") BigDecimal annualRevenue
    ) {
        CustomerCriteria incoming = new CustomerCriteria(companyName, contactName, email, phone, customerSince,
                lastOrderDate, country, city, postalCode, street, houseNumber, creditRating, annualRevenue);

        // The model sometimes calls the tool again with no arguments, so never overwrite what it found.
        if (criteria != null && !criteria.isEmpty() && incoming.isEmpty()) {
            logger.warn("Ignoring a repeated searchCustomers call with no arguments; keeping {}", criteria);
            return;
        }

        this.criteria = incoming;
        logger.info("searchCustomers -> {}", criteria);
    }

    @Tool(description = "Current date and time")
    LocalDateTime currentLocalDateTime() {
        return LocalDateTime.now();
    }
}
