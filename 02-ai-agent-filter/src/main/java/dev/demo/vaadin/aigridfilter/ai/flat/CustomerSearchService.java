package dev.demo.vaadin.aigridfilter.ai.flat;

import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.ai.TokenUsageAdvisor;
import dev.demo.vaadin.aigridfilter.ai.TokenUsageRecorder;
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

/**
 * Variant <b>02(a)</b> of the tool-calling AI layer — the simplest rung of the escalation ladder:
 * the model calls one {@code searchCustomers} tool with <em>one scalar value per field</em>. No
 * {@code List} parameter (so no OR within a field) and no operator/negation. It does have a
 * {@code currentLocalDateTime} tool (mirroring variant 02(b)'s), so that a relative date ("this
 * year", "last week") is resolved from an actual clock reading instead of guessed — the whole-year
 * date semantics are unaffected, only the year/day *value* the model fills in changes.
 * Everything the filter can mean is baked into {@link CustomerSpecifications}.
 * <p>
 * Variant 02(b) ({@code ai/operator}) is the same delivery mechanism with an operator and a negate
 * flag added per field; see {@code CustomerSearchService}.
 * <p>
 * {@code @Scope("prototype")}: the injecting view is not a Spring singleton either — Vaadin creates a
 * fresh view instance per navigation. Prototype scope gives each view its own instance, so
 * {@link #criteria} can live directly on the bean: different browser tabs/sessions never share an
 * instance, and within one instance the view only ever has one search in flight at a time (it
 * disables the filter field for the duration of a search). {@link #criteria} is reset explicitly at
 * the top of {@link #requestCriteria}, since it now outlives a single call.
 */
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

    CustomerSearchService(ChatModel chatModel, TokenUsageRecorder tokenUsageRecorder) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.tokenUsageAdvisor = new TokenUsageAdvisor(tokenUsageRecorder);
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
    CustomerCriteria requestCriteria(String naturalLanguageQuery) {
        criteria = null;
        try {
            // The tool call is the point: by the time this returns, searchCustomers(...) has already
            // written into `criteria`, so the model's answer text is irrelevant. Token usage and
            // duration are recorded by tokenUsageAdvisor, not here.
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
            Text parameters match case-insensitively on any substring.
            Date parameters (customerSince, lastOrderDate) each match customers anywhere in the year
            the given date falls in (e.g. "2020-01-01" matches all of 2020), in ISO format yyyy-MM-dd.
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
                    'customer since' year to match, or null. Matches customers who became a customer
                    anywhere in that year. An ISO yyyy-MM-dd date; interpret ambiguous user input as
                    day-first (German format), e.g. '03.05.05' -> "2005-05-03", and "since 2020" ->
                    "2020-01-01". For a relative date ("this year"), call currentLocalDateTime
                    first.""") LocalDate customerSince,
            @ToolParam(description = """
                    last-order year to match, or null. Matches customers whose last order falls
                    anywhere in that year. An ISO yyyy-MM-dd date; interpret ambiguous user input as
                    day-first (German format), e.g. '03.05.05' -> "2005-05-03". For a relative date
                    ("yesterday", "last week"), call currentLocalDateTime first.""") LocalDate lastOrderDate,
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

        // A tool call is a message the model can repeat: since the tool is void, Spring AI answers it
        // with a bare "Done", and a model that reads that as "nothing happened" sometimes calls the tool
        // again with no arguments, wiping the criteria it just extracted. Criteria that were already
        // extracted are therefore never overwritten by a later empty call.
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
