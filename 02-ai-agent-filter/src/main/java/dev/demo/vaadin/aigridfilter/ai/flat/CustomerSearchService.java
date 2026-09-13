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

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Variant 02(a): the model calls one {@code searchCustomers} tool with one scalar value per field. */
@Service("flatSearchAgent")
@Scope("prototype")
public class CustomerSearchService implements CustomerSearchAgent {

    private static final Logger logger = LoggerFactory.getLogger(CustomerSearchService.class);

    private static final String SYSTEM_PROMPT = """
            You filter a customer grid. Call searchCustomers exactly once, then stop.
            Pass only values the user actually asked for; omit every other parameter.
              - city: stored in English - translate first: "München" is Munich, "Köln" is Cologne.
              - lastOrderDate: one exact day, ISO yyyy-MM-dd. Dates the user writes are day-first
                ("03.05.05" is 2005-05-03).
              - creditRating: GOOD (creditworthy), MEDIUM, POOR (not creditworthy / at risk).

            Only a RELATIVE date ("yesterday", "today", "last week") needs a second tool: call
            currentLocalDateTime first and compute the day from the date it returns - never guess it
            and never copy it from this prompt. Do not call currentLocalDateTime and searchCustomers
            in the same turn; in that turn you do not know the date yet.

            This filter holds one value per field and no operators, so a range, a second value or a
            negation cannot be expressed - say so instead of approximating it.

            Once searchCustomers has returned, the filter is applied - never call it a second time.
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

    @Tool(description = "Filters the customer grid in place. Every parameter is optional and AND-combined; null ignores one.")
    void searchCustomers(
            @ToolParam(description = "city, e.g. \"Berlin\"") String city,
            @ToolParam(description = "last order date, ISO yyyy-MM-dd") LocalDate lastOrderDate,
            @ToolParam(description = "credit rating: GOOD, MEDIUM or POOR") CreditRating creditRating
    ) {
        var incoming = new CustomerCriteria(city, lastOrderDate, creditRating);

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
