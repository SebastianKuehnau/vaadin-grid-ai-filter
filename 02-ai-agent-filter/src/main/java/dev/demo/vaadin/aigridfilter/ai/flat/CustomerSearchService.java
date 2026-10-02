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
            You filter a customer grid with searchCustomers.
            For a relative date ("yesterday"), first call currentLocalDateTime - never guess today.
            Then call searchCustomers exactly once and stop.
            Pass every value in full, e.g. contactName "Max Mustermann", street "Main Street".
            Each parameter takes one value - for two cities, pass only the first.
            Translate German city names: "München" -> Munich, "Köln" -> Cologne.
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
            Filters the customer grid. All parameters are optional and AND-combined.
            Text matches the whole field, case-insensitively - pass the full value, not a part.
            """)
    void searchCustomers(
            @ToolParam(description = "company name") String companyName,
            @ToolParam(description = "contact name") String contactName,
            @ToolParam(description = "email address") String email,
            @ToolParam(description = "phone in E.164, e.g. '0160 57 123456' -> '+4916057123456'") String phone,
            @ToolParam(description = "exact day, yyyy-MM-dd, day-first: '03.05.05' -> 2005-05-03") LocalDate customerSince,
            @ToolParam(description = "exact day, yyyy-MM-dd, day-first: '03.05.05' -> 2005-05-03") LocalDate lastOrderDate,
            @ToolParam(description = "country, e.g. Germany - a city name goes into city") String country,
            @ToolParam(description = "city, e.g. Berlin") String city,
            @ToolParam(description = "postal code") String postalCode,
            @ToolParam(description = "street") String street,
            @ToolParam(description = "house number") String houseNumber,
            @ToolParam(description = "state or region, e.g. Ile-de-France") String state,
            @ToolParam(description = "two-letter ISO country code, e.g. DE") String countryCode,
            @ToolParam(description = "GOOD (creditworthy), MEDIUM (limited), POOR (at risk / not creditworthy)") CreditRating creditRating,
            @ToolParam(description = "minimum annual revenue, e.g. 'over 500000' -> 500000") BigDecimal annualRevenue
    ) {
        CustomerCriteria incoming = new CustomerCriteria(companyName, contactName, email, phone, customerSince,
                lastOrderDate, country, city, postalCode, street, houseNumber, state, countryCode, creditRating, annualRevenue);

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
