package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.Condition;
import dev.demo.vaadin.aigridfilter.ai.filter.CustomerFilter;
import dev.demo.vaadin.aigridfilter.ai.filter.CustomerFilterSpecifications;
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
import java.util.List;

/** The hybrid step: tool calling like 02, but the tool takes 03's {@code List<Condition>} as its one parameter. */
@Service
@Scope("prototype")
public class CustomerSearchService implements CustomerSearchAgent {

    private static final Logger logger = LoggerFactory.getLogger(CustomerSearchService.class);

    private final ChatClient chatClient;
    private final TokenUsageAdvisor tokenUsageAdvisor;

    /** What the model passed to {@link #searchCustomers}; {@code null} until the tool is called. */
    CustomerFilter filter;

    public CustomerSearchService(ChatModel chatModel, TokenUsageAdvisor tokenUsageAdvisor) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.tokenUsageAdvisor = tokenUsageAdvisor;
    }

    /** Asks the LLM to call the search tool and turns the conditions it passed into a {@link Specification}. */
    @Override
    public Specification<Customer> resolveFilter(String naturalLanguageQuery) {
        return CustomerFilterSpecifications.from(requestFilter(naturalLanguageQuery));
    }

    /** Asks the LLM to call {@code searchCustomers}; an empty filter (match all) if it produced nothing usable. */
    CustomerFilter requestFilter(String naturalLanguageQuery) {
        filter = null;
        try {
            // By the time this returns, searchCustomers(...) has run; the answer text is irrelevant.
            chatClient.prompt()
                    .system(systemPrompt(LocalDate.now()))
                    .user(naturalLanguageQuery)
                    .tools(this)
                    .advisors(SimpleLoggerAdvisor.builder().build(), tokenUsageAdvisor)
                    // Temperature is set per profile in application-<provider>.properties.
                    .call()
                    .chatResponse();
        } catch (Exception e) {
            logger.warn("Could not turn query into a filter; showing all customers. Query: '{}'",
                    naturalLanguageQuery, e);
            return new CustomerFilter(List.of());
        }
        logger.info("requestFilter('{}') -> {}", naturalLanguageQuery, filter);
        return filter == null ? new CustomerFilter(List.of()) : filter;
    }

    /** The one tool of this module: a single parameter carrying the whole condition list - 03's payload. */
    // returnDirect: the answer text is irrelevant, so the call ends here instead of going back to the model.
    @Tool(returnDirect = true, description = """
            Filters the customer grid in place, replacing any previous filter. Pass the complete list
            of conditions in one call; ALL of them must match (AND). An empty list shows every customer.
            """)
    void searchCustomers(
            @ToolParam(description = "all conditions the customer must satisfy (AND); empty list matches everything")
            List<Condition> conditions
    ) {
        CustomerFilter incoming = new CustomerFilter(conditions == null ? List.of() : conditions);

        // The model sometimes calls the tool again with an empty list, so never overwrite a built filter.
        // Structured output (03) cannot hit this: one response, one filter.
        if (filter != null && !filter.conditions().isEmpty() && incoming.conditions().isEmpty()) {
            logger.warn("Ignoring a repeated searchCustomers call with an empty conditions list; keeping {}", filter);
            return;
        }

        this.filter = incoming;
        logger.info("searchCustomers -> {}", filter);
    }

    /** Builds the system prompt for the given "today", so it can be unit-tested without calling the model. */
    static String systemPrompt(LocalDate today) {
        return """
                You translate a user's request into a searchCustomers call that filters a customer grid.
                Call the tool exactly ONCE, then stop - the filter has already been applied.

                searchCustomers takes a flat "conditions" list; ALL conditions must match (AND). Each
                condition is { field, operator, values, negate }:
                  - values: one or more; the condition matches if the field matches ANY of them
                    (OR within that field).
                  - negate: true excludes the matches instead of requiring them.
                There is no nesting and no OR across different fields. To show every customer,
                call the tool with an empty conditions list.

                Include EVERY condition the user mentions, never drop one:
                  - Several values for the SAME field ("Berlin or Hamburg") -> ONE condition on that
                    field carrying both values.
                  - Requirements on DIFFERENT fields -> one condition per field.
                  - A RANGE on one field -> TWO conditions on that field: GREATER_OR_EQUAL for the
                    lower bound and LESS_OR_EQUAL for the upper one.
                  - "not X" / "except X" -> the condition for X with negate=true, never a different
                    operator; there is no NOT_CONTAINS, NOT_EQUALS or any other NOT_*.

                field is one of city, lastOrderDate, creditRating.
                operator is one of CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH, GREATER_OR_EQUAL,
                LESS_OR_EQUAL.

                The fields:
                  - city is text, matched case-insensitively. CONTAINS is what a plain "in Berlin"
                    means; reserve EQUALS for explicitly exact wording. City names are stored in
                    English - Berlin, Hamburg, Munich, Frankfurt, Cologne, Dusseldorf - so
                    translate a German one before passing it: "München" is Munich, "Köln" is
                    Cologne.
                  - lastOrderDate is an ISO yyyy-MM-dd day. EQUALS for an exact day, LESS_OR_EQUAL
                    for "before"/"until", GREATER_OR_EQUAL for "since"/"after" and for the first day
                    of an open-ended past range. A bare year ("ordered in 2024") is a CLOSED range:
                    two conditions, GREATER_OR_EQUAL 2024-01-01 and LESS_OR_EQUAL 2024-12-31.
                  - A date the user wrote ambiguously is day-first (German): '03.05.05' is 2005-05-03.
                  - creditRating uses EQUALS with GOOD (creditworthy), MEDIUM (limited
                    creditworthiness) or POOR (at risk / not creditworthy). Several ratings are
                    alternatives, so they go into ONE condition's values. Never express a rating as
                    a number.

                A RELATIVE date ("yesterday", "last week", "in the last 12 months") must be computed,
                never guessed and never copied from an example below: today is %s. "In the last 12
                months" is GREATER_OR_EQUAL (today minus 12 months), not minus one month.

                An open-ended range is ONE condition with no upper bound. Emit a GREATER_OR_EQUAL +
                LESS_OR_EQUAL pair ONLY for an explicit "between X and Y" or a bare year - never for
                a relative period and never for a single named day.

                "Not creditworthy" / "at risk" NAMES the POOR rating: creditRating EQUALS [POOR] with
                negate=false. The word "not" belongs to the rating's name here, it is not a negation.

                Examples, written as "field OPERATOR [values]" with negate noted separately:
                  "customers in Berlin"
                    -> city CONTAINS [Berlin]
                  "customers in Berlin or Hamburg"
                    -> city CONTAINS [Berlin, Hamburg]
                  "creditworthy customers in Hamburg"
                    -> city CONTAINS [Hamburg]; creditRating EQUALS [GOOD]
                  "customers who are not from Berlin"
                    -> city CONTAINS [Berlin], negate=true
                  "customers who ordered in the last 12 months" (one condition, no upper bound)
                    -> lastOrderDate GREATER_OR_EQUAL [today minus 12 months]
                  "customers who last ordered between 2024-07-01 and 2025-03-31"
                    -> lastOrderDate GREATER_OR_EQUAL [2024-07-01];
                       lastOrderDate LESS_OR_EQUAL [2025-03-31]
                  "show all customers"
                    -> (empty conditions list)
                """.formatted(today);
    }
}
