package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerFilter;
import dev.demo.vaadin.aigridfilter.ai.filter.CustomerFilterSpecifications;
import dev.demo.vaadin.aigridfilter.data.Customer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * The AI layer: turns a natural-language query into a JPA {@link Specification}.
 * <p>
 * Instead of letting the model call a tool, it asks the model to return a single
 * {@link CustomerFilter} as <em>structured output</em> ({@code .entity(...)}). One JSON object
 * matching a fixed schema is far more reliable for smaller/local models than multi-step tool
 * calling. The class owns the {@link ChatClient} and the prompt and knows nothing about Vaadin,
 * so it can be tested in isolation.
 */
@Service
public class CustomerSearchService {

    private static final Logger logger = LoggerFactory.getLogger(CustomerSearchService.class);

    private final ChatClient chatClient;

    public CustomerSearchService(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * Turns the query into a JPA {@link Specification}: ask the LLM for a {@link CustomerFilter}
     * ({@link #requestFilter}) and translate it. A filter with a {@code null} root (e.g. on a bad
     * response) matches all.
     */
    public Specification<Customer> resolveFilter(String naturalLanguageQuery) {
        return CustomerFilterSpecifications.from(requestFilter(naturalLanguageQuery));
    }

    /**
     * Asks the LLM to express the query as a {@link CustomerFilter}. Package-private so the AI layer
     * can be tested directly on the produced filter. Returns a filter with a {@code null} root
     * (match all) if the model produces nothing usable, so the UI never breaks on a bad response.
     */
    CustomerFilter requestFilter(String naturalLanguageQuery) {
        try {
            CustomerFilter filter = chatClient.prompt()
                    .advisors(SimpleLoggerAdvisor.builder().build())
                    .system(systemPrompt(LocalDate.now()))
                    .user(naturalLanguageQuery)
                    // Low temperature → deterministic structure, fewer formatting slips.
                    .options(ChatOptions.builder())
                    .call()
                    .entity(CustomerFilter.class);
            logger.info("requestFilter('{}') -> {}", naturalLanguageQuery, filter);
            return filter;
        } catch (Exception e) {
            logger.warn("Could not turn query into a filter; showing all customers. Query: '{}'",
                    naturalLanguageQuery, e);
            return new CustomerFilter(null);
        }
    }

    /**
     * Builds the system prompt for the given "today". Package-private and date-parameterized so it
     * can be unit-tested deterministically without calling the model.
     */
    static String systemPrompt(LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        LocalDate thisWeekMonday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        LocalDate lastWeekMonday = thisWeekMonday.minusWeeks(1);
        LocalDate lastMonthStart = today.withDayOfMonth(1).minusMonths(1);
        return """
                You translate a user's request into a CustomerFilter that filters a list of customers.

                A CustomerFilter has a single "root" node, which is a tree of AND / OR / NOT / CONDITION
                nodes:
                  - CONDITION is a leaf: { type: CONDITION, field, operator, value }.
                  - AND has children: [ ... ]; ALL children must match.
                  - OR has children: [ ... ]; AT LEAST ONE child must match.
                  - NOT has a single child: { type: NOT, child: ... }; negates it.
                Children of AND/OR can be any node type, so trees nest to any depth, e.g.
                "(city=Berlin OR city=Hamburg) AND (revenue>=500000 OR creditRating=GOOD)" is an AND of
                two ORs. To show all customers, return an AND with an empty children list (or omit root).

                IMPORTANT: include EVERY condition the user mentions. Never drop one (e.g. keep the
                revenue condition even when cities are also given).

                Building the tree:
                  - Several values for the SAME field ("Berlin or Hamburg", or the colloquial "Berlin
                    and Hamburg" meaning either city) -> an OR of CONDITIONs on that field.
                  - Several requirements across DIFFERENT fields that must all hold -> an AND.
                  - A value RANGE on one field is an AND of two CONDITIONs on that field, e.g. revenue
                    between 100000 and 500000 -> AND[ annualRevenue GREATER_OR_EQUAL 100000,
                    annualRevenue LESS_OR_EQUAL 500000 ].
                  - Cross-field OR ("in Berlin or with revenue above 1 million") -> an OR whose children
                    are on different fields — this is now possible and expected when the user says "or"
                    across different kinds of conditions.
                  - "not (X and Y)" / "neither X nor Y" -> NOT wrapping the AND/OR of X and Y.

                Each CONDITION has:
                  - field: one of companyName, contactName, email, phone, annualRevenue, creditRating,
                           customerSince, lastOrderDate, country, city, postalCode, street, houseNumber,
                           state, countryCode
                  - operator: CONTAINS, NOT_CONTAINS, EQUALS, NOT_EQUALS, STARTS_WITH, ENDS_WITH,
                              GREATER_OR_EQUAL, LESS_OR_EQUAL
                  - value: the comparison value, as text

                Rules:
                  - Text fields match case-insensitively. Use CONTAINS for partial matches; use
                    NOT_CONTAINS or NOT_EQUALS to exclude (e.g. "not in Berlin" -> field=city,
                    operator=NOT_EQUALS, value=Berlin).
                  - For "begins with" / "first character/letter is X" use STARTS_WITH; for "ends with"
                    use ENDS_WITH. The value is just the prefix/suffix, e.g. "name starts with M" ->
                    field=contactName, operator=STARTS_WITH, value=M.
                  - phone: always use CONTAINS with the value exactly as the user typed it (no
                    normalization, no leading +). Phone numbers are stored in E.164, so a partial
                    number like '5020000001' will match via substring.
                  - customerSince and lastOrderDate use ISO date yyyy-MM-dd. Read ambiguous dates
                    day-first (German), e.g. '03.05.05' -> '2005-05-03'.
                    Operator choice for dates:
                    * exact day (today, yesterday, a specific date like 2024-03-15) -> EQUALS
                    * open-ended past range (since/after/last week/last month/this year) -> GREATER_OR_EQUAL with the first day of that period
                    * open-ended future/past boundary (before/until) -> LESS_OR_EQUAL
                    Never emit a GREATER_OR_EQUAL + LESS_OR_EQUAL pair for a single named day.
                  - annualRevenue is a plain number, e.g. 100000; use GREATER_OR_EQUAL / LESS_OR_EQUAL
                    for "more/less than".
                  - creditRating is the bank credit rating. Use field=creditRating, operator=EQUALS, and
                    value one of GOOD, MEDIUM, POOR:
                    * "creditworthy" / "good credit" -> GOOD
                    * "limited" / "medium" -> MEDIUM
                    * "at risk" / "risky" / "not creditworthy" / "poor credit" -> POOR
                    For SEVERAL ratings emit ONE criterion per rating (they are alternatives, OR-combined),
                    e.g. "good or at-risk rating" -> creditRating EQUALS GOOD AND creditRating EQUALS POOR
                    (same field, so combined as OR). Never express a rating via a numeric score.
                  - Today is %s. Resolve relative dates ("yesterday", "today", "last month", "this year",
                    "last week") against this date.

                Examples (root node only; CONDITION written as "field OP value" for brevity):
                  "customers in Berlin"
                    -> city CONTAINS Berlin
                  "customers in Berlin or Hamburg"
                    -> OR[ city CONTAINS Berlin, city CONTAINS Hamburg ]
                  "all customers in Berlin and Hamburg with a minimal revenue of 100000"
                    -> AND[ OR[ city CONTAINS Berlin, city CONTAINS Hamburg ], annualRevenue GREATER_OR_EQUAL 100000 ]
                  "customers whose contact name starts with M"
                    -> contactName STARTS_WITH M
                  "companies not in Munich with revenue between 100000 and 500000"
                    -> AND[ city NOT_EQUALS Munich, annualRevenue GREATER_OR_EQUAL 100000, annualRevenue LESS_OR_EQUAL 500000 ]
                  "creditworthy customers in Berlin"
                    -> AND[ city CONTAINS Berlin, creditRating EQUALS GOOD ]
                  "customers at risk"
                    -> creditRating EQUALS POOR
                  "customers in Berlin with a good and an at-risk credit rating"
                    -> AND[ city CONTAINS Berlin, OR[ creditRating EQUALS GOOD, creditRating EQUALS POOR ] ]
                  "customers in Berlin or with revenue above 1 million" (cross-field OR)
                    -> OR[ city CONTAINS Berlin, annualRevenue GREATER_OR_EQUAL 1000000 ]
                  "(city=Berlin or city=Hamburg) and (revenue>=500000 or creditRating=GOOD)"
                    -> AND[ OR[ city CONTAINS Berlin, city CONTAINS Hamburg ],
                            OR[ annualRevenue GREATER_OR_EQUAL 500000, creditRating EQUALS GOOD ] ]
                  "not (in Berlin and revenue under 100000)"
                    -> NOT( AND[ city CONTAINS Berlin, annualRevenue LESS_OR_EQUAL 100000 ] )
                  "customers since 2020"
                    -> customerSince GREATER_OR_EQUAL 2020-01-01
                  "customers who placed an order yesterday" (today = %s)
                    -> lastOrderDate EQUALS %s
                  "customers who placed an order today" (today = %s)
                    -> lastOrderDate EQUALS %s
                  "customers who ordered last week" (today = %s, week starts Mon %s)
                    -> lastOrderDate GREATER_OR_EQUAL %s
                  "customers who ordered last month" (today = %s)
                    -> lastOrderDate GREATER_OR_EQUAL %s
                  "show all customers"
                    -> AND[]
                """.formatted(today, today, yesterday, today, today, today, thisWeekMonday, lastWeekMonday, today,
                lastMonthStart);
    }
}