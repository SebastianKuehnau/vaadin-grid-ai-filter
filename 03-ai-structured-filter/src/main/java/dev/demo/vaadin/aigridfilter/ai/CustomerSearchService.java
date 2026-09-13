package dev.demo.vaadin.aigridfilter.ai;

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
            CustomerFilter filter = chatClient.prompt()
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
                You translate the user's request into a CustomerFilter for a customer grid.

                Conditions are AND-combined, the values inside one condition are OR-combined, and
                negate=true excludes the matches. There is no nesting and no OR across fields. To
                show everyone, return an empty conditions list.

                Keep every requirement the user names:
                  - several values for the SAME field ("Berlin or Hamburg") -> ONE condition with
                    both values
                  - requirements on DIFFERENT fields -> one condition each
                  - a range on one field -> TWO conditions, GREATER_OR_EQUAL the lower and
                    LESS_OR_EQUAL the upper bound
                  - "not X" / "except X" -> X's own condition with negate=true; there is no NOT_*
                    operator

                city is text, matched case-insensitively: a plain "in Berlin" is CONTAINS, EQUALS
                only for explicitly exact wording. Cities are stored in English, so translate a
                German name first - "München" is Munich, "Köln" is Cologne.

                lastOrderDate is an ISO yyyy-MM-dd day: EQUALS an exact day, LESS_OR_EQUAL
                "before"/"until", GREATER_OR_EQUAL "since"/"after". A date the user wrote
                ambiguously is day-first (German): '03.05.05' is 2005-05-03. Today is %s - compute
                a relative date from it, never guess one. Emit a GREATER_OR_EQUAL + LESS_OR_EQUAL
                pair only for an explicit "between X and Y" or a bare year ("in 2024" is 2024-01-01
                to 2024-12-31); a relative period is open-ended, so ONE condition:
                GREATER_OR_EQUAL today minus the WHOLE period.

                creditRating EQUALS GOOD (creditworthy), MEDIUM (limited creditworthiness) or POOR
                (at risk / not creditworthy). Several ratings are alternatives, so they share ONE
                condition. "Not creditworthy" NAMES POOR: EQUALS [POOR] with negate=false, never a
                negated GOOD.

                Examples, written as "field OPERATOR [values]":
                  "customers in Berlin or Hamburg"
                    -> city CONTAINS [Berlin, Hamburg]
                  "creditworthy customers in Hamburg"
                    -> city CONTAINS [Hamburg]; creditRating EQUALS [GOOD]
                  "customers who are not from Berlin"
                    -> city CONTAINS [Berlin], negate=true
                  "customers who ordered in the last 12 months"
                    -> lastOrderDate GREATER_OR_EQUAL [today minus 12 months]
                  "customers who last ordered between 2024-07-01 and 2025-03-31"
                    -> lastOrderDate GREATER_OR_EQUAL [2024-07-01]; lastOrderDate LESS_OR_EQUAL [2025-03-31]
                  "show all customers"
                    -> (empty conditions list)
                """.formatted(today);
    }
}
