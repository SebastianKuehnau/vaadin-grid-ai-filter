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
import org.springframework.context.annotation.Scope;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/** The hybrid step: 03's structured output for the filter, 02's tool calling for the date the model cannot know. */
@Service
@Scope("prototype")
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

    /** Asks the LLM for a {@link CustomerFilter}; an empty one (match all) if it produced nothing usable. */
    CustomerFilter requestFilter(String naturalLanguageQuery) {
        CustomerFilter filter;
        try {
            // .tools(...) lets the model ask for the date, .entity(...) parses the JSON answer it gives afterwards.
            filter = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(naturalLanguageQuery)
                    .tools(this)
                    .advisors(SimpleLoggerAdvisor.builder().build(), tokenUsageAdvisor)
                    // Temperature is set per profile in application-<provider>.properties.
                    .call()
                    .entity(CustomerFilter.class);
        } catch (Exception e) {
            logger.warn("Could not turn query into a filter; showing all customers. Query: '{}'",
                    naturalLanguageQuery, e);
            filter = new CustomerFilter(List.of());
        }
        logger.info("requestFilter('{}') -> {}", naturalLanguageQuery, filter);
        return filter;
    }

    /** The only tool: the model asks for today rather than reading it off a prompt baked at build time. */
    @Tool(description = "Today's date and the period boundaries around it, already computed (weeks start on Monday)")
    RelativeDates currentLocalDateTime() {
        return RelativeDates.of(LocalDate.now());
    }

    private static final String SYSTEM_PROMPT = """
            You translate a request into a CustomerFilter.

            For a date relative to today ("today", "yesterday", "this month", "last 12 months"), call
            currentLocalDateTime FIRST, WAIT for its answer, then answer - never guess today. It
            returns the dates already computed: copy the matching one, never calculate a date.
            For no date or an absolute one ("in 2024"), do NOT call it.

            Conditions are AND-combined, values in one condition OR-combined; negate=true excludes.
            An empty list shows all customers. Keep every requirement the user names.
              - Several values for one field ("Berlin or Hamburg") -> one condition with all values.
              - A range -> GREATER_OR_EQUAL and LESS_OR_EQUAL; "in 2024" -> 2024-01-01 to 2024-12-31.
              - "not X" -> negate=true; there are no NOT_* operators.
              - city: CONTAINS, one of Berlin, Hamburg, Munich, Frankfurt, Cologne, Dusseldorf -
                always pass one of these English names: translate German ones ("München" -> Munich),
                keep negate as written.
              - country name ("from Germany") -> country, never countryCode; a street -> street.
              - Dates: yyyy-MM-dd, day-first ('03.05.05' -> 2005-05-03). A single day ("yesterday")
                -> EQUALS; a period still running ("this month", "last 12 months") -> GREATER_OR_EQUAL
                its start only; a period already over ("last week", "last month") -> its start to its end.
              - creditRating EQUALS GOOD (creditworthy), MEDIUM (limited or restricted, German
                "eingeschränkt") or POOR (only at risk);
                "not creditworthy" is POOR, not a negated GOOD.

            Examples:
              "Kunden aus Köln" -> city CONTAINS [Cologne]
              "What's the weather?" -> (empty conditions list)
              "creditworthy customers in Hamburg" -> city CONTAINS [Hamburg]; creditRating EQUALS [GOOD]
              "customers not from Berlin" -> city CONTAINS [Berlin], negate=true
              "customers from Germany or France" -> country CONTAINS [Germany, France]
              "ordered between 2024-07-01 and 2025-03-31"
                -> lastOrderDate GREATER_OR_EQUAL [2024-07-01]; lastOrderDate LESS_OR_EQUAL [2025-03-31]
              "ordered yesterday" -> lastOrderDate EQUALS [the yesterday date the tool returned]
              "ordered this year" -> lastOrderDate GREATER_OR_EQUAL [the startOfThisYear date the tool returned]
              "ordered last week" -> lastOrderDate GREATER_OR_EQUAL [startOfLastWeek];
                lastOrderDate LESS_OR_EQUAL [endOfLastWeek]

            Answer with exactly ONE JSON object and nothing after it - close every bracket once:
            {"conditions": [{"field": "city", "operator": "CONTAINS", "values": ["Berlin"], "negate": false}]}
            """;
}
