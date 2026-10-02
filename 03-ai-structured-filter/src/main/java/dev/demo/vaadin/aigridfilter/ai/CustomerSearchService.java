package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.Condition.Operator;
import dev.demo.vaadin.aigridfilter.ai.filter.CustomerFilter;
import dev.demo.vaadin.aigridfilter.ai.filter.CustomerFilterSpecifications;
import dev.demo.vaadin.aigridfilter.data.Customer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/** The AI layer: turns a natural-language query into a JPA {@link Specification}. */
@Service
public class CustomerSearchService implements CustomerSearchAgent {

    private static final Logger logger = LoggerFactory.getLogger(CustomerSearchService.class);

    private final ChatClient chatClient;
    private final TokenUsageAdvisor tokenUsageAdvisor;

    public CustomerSearchService(ChatModel chatModel, TokenUsageAdvisor tokenUsageAdvisor) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.tokenUsageAdvisor = tokenUsageAdvisor;
    }

    /** Asks the LLM for a {@link CustomerFilter} and translates it into a {@link Specification}. */
    @Override
    public Specification<Customer> resolveFilter(String naturalLanguageQuery) {
        return CustomerFilterSpecifications.from(requestFilter(naturalLanguageQuery));
    }

    /** Asks the LLM to express the query as a {@link CustomerFilter}; an empty one (match all) on a bad response. */
    CustomerFilter requestFilter(String naturalLanguageQuery) {
        try {
            // .entity(...) is the whole mechanism: one JSON object matching CustomerFilter's schema.
            var filter = chatClient.prompt()
                    .advisors(SimpleLoggerAdvisor.builder().build(), tokenUsageAdvisor)
                    .system(systemPrompt(LocalDate.now()))
                    .user(naturalLanguageQuery)
                    // Temperature is set per profile in application-<provider>.properties.
                    .call()
                    .entity(CustomerFilter.class, ChatClient.EntityParamSpec::useProviderStructuredOutput);
            logger.info("requestFilter('{}') -> {}", naturalLanguageQuery, filter);
            return filter;
        } catch (Exception e) {
            logger.warn("Could not turn query into a filter; showing all customers. Query: '{}'",
                    naturalLanguageQuery, e);
            return new CustomerFilter(List.of());
        }
    }

    /** Builds the system prompt for the given "today", so it can be unit-tested without calling the model. */
    static String systemPrompt(LocalDate today) {
        return """
                You translate a request into a CustomerFilter. Today is %s.
                Conditions are AND-combined, values in one condition OR-combined; negate=true excludes.
                An empty list shows all customers - also for small talk. Keep every requirement the user names.
                Fields: companyName, contactName, email, phone, annualRevenue, creditRating, customerSince,
                lastOrderDate, country, city, postalCode, street, houseNumber, state, countryCode.
                  - Several values for one field ("Berlin or Köln") -> one condition with all values.
                  - A range -> GREATER_OR_EQUAL and LESS_OR_EQUAL on the same field.
                  - "not X" -> negate=true; there are no NOT_* operators.
                  - Text: CONTAINS by default, STARTS_WITH / ENDS_WITH for "starts/ends with".
                  - City names are English: "München" -> Munich, "Köln" -> Cologne, "Düsseldorf" -> Dusseldorf.
                  - phone: CONTAINS, exactly as typed.
                  - Dates: yyyy-MM-dd, day-first ('03.05.05' -> 2005-05-03). A day -> EQUALS,
                    "since" / "last week" / "last month" -> GREATER_OR_EQUAL its first day, "before" -> LESS_OR_EQUAL.
                  - lastOrderDate in a year ("in 2024", "last year") -> January 1 to December 31 of it; "customer since 2020" -> GREATER_OR_EQUAL only.
                  - creditRating EQUALS GOOD (creditworthy), MEDIUM (limited) or POOR (at risk);
                    "not creditworthy" -> creditRating EQUALS [POOR], negate=false.

                Examples:
                  "Kunden aus Köln" -> city CONTAINS [Cologne]
                  "customers in Berlin or Köln with revenue over 100000"
                    -> city CONTAINS [Berlin, Cologne]; annualRevenue GREATER_OR_EQUAL [100000]
                  "customers not from Berlin" -> city CONTAINS [Berlin], negate=true
                  "customers who ordered yesterday" -> lastOrderDate EQUALS [%s]
                """.formatted(today, today.minusDays(1));
    }
}
