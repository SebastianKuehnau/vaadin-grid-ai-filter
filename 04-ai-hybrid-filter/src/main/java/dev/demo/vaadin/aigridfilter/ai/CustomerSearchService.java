package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerFilter;
import dev.demo.vaadin.aigridfilter.ai.filter.CustomerFilterSpecifications;
import dev.demo.vaadin.aigridfilter.data.Customer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** The hybrid step: 03's structured output for the filter, 02's tool calling for the date the model cannot know. */
@Service
public class CustomerSearchService implements CustomerSearchAgent {

    private static final Logger logger = LoggerFactory.getLogger(CustomerSearchService.class);

    private static final String SYSTEM_PROMPT = """
            You translate a user's request into a CustomerFilter that filters a customer grid.

            You have one tool, currentLocalDateTime. Every relative date ("yesterday", "last week",
            "in the last 12 months") MUST come from it: call it FIRST, WAIT for the date it returns,
            and only THEN answer. Never guess today's date and never take one from an example below.

            Conditions are AND-combined, the values inside one condition are OR-combined, and
            negate=true excludes the matches. There is no nesting and no OR across fields. To show
            everyone, return an empty conditions list.

            Keep every requirement the user names:
              - several values for the SAME field ("Berlin or Hamburg") -> ONE condition with both
                values
              - requirements on DIFFERENT fields -> one condition each
              - a range on one field -> TWO conditions, GREATER_OR_EQUAL the lower and LESS_OR_EQUAL
                the upper bound
              - "not X" / "except X" -> X's own condition with negate=true; there is no NOT_* operator

            city is text, matched case-insensitively: a plain "in Berlin" is CONTAINS, EQUALS only
            for explicitly exact wording. City names are stored in English - Berlin, Hamburg, Munich,
            Frankfurt, Cologne, Dusseldorf - so translate a German one before passing it: "München"
            is Munich, "Köln" is Cologne.

            lastOrderDate is an ISO yyyy-MM-dd day: EQUALS an exact day, LESS_OR_EQUAL
            "before"/"until", GREATER_OR_EQUAL "since"/"after". A date the user wrote ambiguously is
            day-first (German): '03.05.05' is 2005-05-03. Emit a GREATER_OR_EQUAL + LESS_OR_EQUAL
            pair only for an explicit "between X and Y" or a bare year ("in 2024" is 2024-01-01 to
            2024-12-31), never for a single day, named or relative.

            Once currentLocalDateTime has answered, pick the operator by what was asked for:
              - a single relative DAY ("yesterday", "today") is ONE exact day: EQUALS that day,
                never "from that day on".
              - a relative PERIOD ("last week", "in the last 12 months") is open-ended:
                GREATER_OR_EQUAL that date minus the WHOLE period - "in the last 12 months" is
                minus 12 months, not minus one month.

            creditRating EQUALS GOOD (creditworthy), MEDIUM (limited creditworthiness) or POOR (at
            risk / not creditworthy). Several ratings are alternatives, so they share ONE condition.
            "Not creditworthy" NAMES POOR: EQUALS [POOR] with negate=false, never a negated GOOD.

            Examples, written as "field OPERATOR [values]":
              "customers in Berlin"
                -> city CONTAINS [Berlin]
              "customers in Berlin or Hamburg"
                -> city CONTAINS [Berlin, Hamburg]
              "creditworthy customers in Hamburg"
                -> city CONTAINS [Hamburg]; creditRating EQUALS [GOOD]
              "customers who are not from Berlin"
                -> city CONTAINS [Berlin], negate=true
              "customers who ordered in the last 12 months"
                -> lastOrderDate GREATER_OR_EQUAL [that date minus 12 months]
              "customers who ordered yesterday"
                -> lastOrderDate EQUALS [that date minus 1 day]
              "customers who last ordered between 2024-07-01 and 2025-03-31"
                -> lastOrderDate GREATER_OR_EQUAL [2024-07-01]; lastOrderDate LESS_OR_EQUAL [2025-03-31]
              "show all customers"
                -> (empty conditions list)
            """;

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

    /** Asks the LLM for a {@link CustomerFilter}; an empty one (match all) on a bad response. */
    CustomerFilter requestFilter(String naturalLanguageQuery) {
        try {
            // Both mechanisms in one exchange: .tools(...) lets the model ask for the date,
            // .entity(...) parses the answer it gives afterwards. No provider-enforced schema -
            // that would leave the response grammar no room for a tool call.
            CustomerFilter filter = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(naturalLanguageQuery)
                    .tools(this)
                    .advisors(SimpleLoggerAdvisor.builder().build(), tokenUsageAdvisor)
                    // Temperature is set per profile in application-<provider>.properties.
                    .call()
                    .entity(CustomerFilter.class);
            logger.info("requestFilter('{}') -> {}", naturalLanguageQuery, filter);
            return filter == null ? new CustomerFilter(List.of()) : filter;
        } catch (Exception e) {
            logger.warn("Could not turn query into a filter; showing all customers. Query: '{}'",
                    naturalLanguageQuery, e);
            return new CustomerFilter(List.of());
        }
    }

    /** The only tool: the model asks for today rather than reading it off a prompt baked at build time. */
    @Tool(description = "Current date and time")
    LocalDateTime currentLocalDateTime() {
        return LocalDateTime.now();
    }
}
