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
public class CustomerSearchStructuredOutputService implements CustomerSearchAgent {

    private static final Logger logger = LoggerFactory.getLogger(CustomerSearchStructuredOutputService.class);

    private final ChatClient chatClient;
    private final TokenUsageRecorder tokenUsageRecorder;

    public CustomerSearchStructuredOutputService(ChatModel chatModel, TokenUsageRecorder tokenUsageRecorder) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.tokenUsageRecorder = tokenUsageRecorder;
    }

    /**
     * Turns the query into a JPA {@link Specification}: ask the LLM for a {@link CustomerFilter}
     * ({@link #requestFilter}) and translate it. An empty conditions list (e.g. on a bad response)
     * matches all.
     */
    @Override
    public Specification<Customer> resolveFilter(String naturalLanguageQuery) {
        return CustomerFilterSpecifications.from(requestFilter(naturalLanguageQuery));
    }

    /**
     * Asks the LLM to express the query as a {@link CustomerFilter}. Package-private so the AI layer
     * can be tested directly on the produced filter. Returns a filter with an empty conditions list
     * (match all) if the model produces nothing usable, so the UI never breaks on a bad response.
     */
    CustomerFilter requestFilter(String naturalLanguageQuery) {
        try {
            // responseEntity(...) gives both the parsed entity and the ChatResponse, so we can read
            // the token usage alongside the structured result (plain .entity(...) would drop it).
            long start = System.nanoTime();
            var responseEntity = chatClient.prompt()
                    .advisors(SimpleLoggerAdvisor.builder().build())
                    .system(systemPrompt(LocalDate.now()))
                    .user(naturalLanguageQuery)
                    // Temperature (0 for deterministic structure) is set per active profile in
                    // application-<provider>.properties, not here.
                    .call()
                    .responseEntity(CustomerFilter.class);
            long durationMillis = (System.nanoTime() - start) / 1_000_000;
            CustomerFilter filter = responseEntity.entity();
            tokenUsageRecorder.record(naturalLanguageQuery, responseEntity.response().getMetadata().getUsage(),
                    durationMillis);
            logger.info("requestFilter('{}') -> {}", naturalLanguageQuery, filter);
            return filter;
        } catch (Exception e) {
            logger.warn("Could not turn query into a filter; showing all customers. Query: '{}'",
                    naturalLanguageQuery, e);
            return new CustomerFilter(List.of());
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

                A CustomerFilter has a flat "conditions" list; ALL conditions must match (AND). Each
                condition is: { field, operator, values: [...], negate }.
                  - values: one or more values; the condition matches if the field matches ANY of them
                    (OR within the field).
                  - negate: true to exclude matches instead of requiring them (e.g. "not from Berlin").
                There is no nesting and no OR across different fields — only within one field's values.
                To show all customers, return an empty conditions list.

                IMPORTANT: include EVERY condition the user mentions. Never drop one (e.g. keep the
                revenue condition even when cities are also given).

                Building the conditions list:
                  - Several values for the SAME field ("Berlin or Köln", or the colloquial "Berlin and
                    Köln" meaning either city) -> one condition on that field with both values.
                  - Several requirements across DIFFERENT fields that must all hold -> one condition per
                    field; the list is always AND-combined.
                  - A value RANGE on one field is two conditions on that field, e.g. revenue between
                    100000 and 500000 -> [ annualRevenue GREATER_OR_EQUAL [100000],
                    annualRevenue LESS_OR_EQUAL [500000] ].
                  - "not X" / "except X" / "excluding X" -> the condition for X with negate=true, NOT a
                    different operator (there is no NOT_CONTAINS/NOT_EQUALS operator).

                Each condition has:
                  - field: one of companyName, contactName, email, phone, annualRevenue, creditRating,
                           customerSince, lastOrderDate, country, city, postalCode, street, houseNumber,
                           state, countryCode
                  - operator: CONTAINS, EQUALS, STARTS_WITH, ENDS_WITH, GREATER_OR_EQUAL, LESS_OR_EQUAL
                  - values: the comparison value(s), as text
                  - negate: true/false, default false

                Rules:
                  - Text fields match case-insensitively. Use CONTAINS for partial matches; set
                    negate=true to exclude (e.g. "not in Berlin" -> field=city, operator=CONTAINS,
                    values=[Berlin], negate=true).
                  - For "begins with" / "first character/letter is X" use STARTS_WITH; for "ends with"
                    use ENDS_WITH. The value is just the prefix/suffix, e.g. "name starts with M" ->
                    field=contactName, operator=STARTS_WITH, values=[M].
                  - phone: always use CONTAINS with the value exactly as the user typed it (no
                    normalization, no leading +). Phone numbers are stored in E.164, so a partial
                    number like '5020000001' will match via substring.
                  - customerSince and lastOrderDate use ISO date yyyy-MM-dd. Read ambiguous dates
                    day-first (German), e.g. '03.05.05' -> '2005-05-03'.
                    Operator choice for dates:
                    * exact day (today, yesterday, a specific date like 2024-03-15) -> EQUALS
                    * open-ended past range (since/after/last week/last month/this year) -> GREATER_OR_EQUAL with the first day of that period
                    * open-ended future/past boundary (before/until) -> LESS_OR_EQUAL
                    * a bare year with no "since"/"before" qualifier, for lastOrderDate ("last ordered
                      in 2024", "2024 zuletzt gekauft") -> a CLOSED range: two conditions on
                      lastOrderDate, GREATER_OR_EQUAL <year>-01-01 and LESS_OR_EQUAL <year>-12-31 (same
                      two-condition idiom as a revenue range). customerSince is inherently open-ended
                      even for a bare year ("customer since 2020" -> GREATER_OR_EQUAL only).
                    Never emit a GREATER_OR_EQUAL + LESS_OR_EQUAL pair for a single named day.
                  - annualRevenue is a plain number, e.g. 100000; use GREATER_OR_EQUAL / LESS_OR_EQUAL
                    for "more/less than".
                  - creditRating is the bank credit rating. Use field=creditRating, operator=EQUALS, and
                    a value one of GOOD, MEDIUM, POOR:
                    * "creditworthy" / "good credit" -> GOOD
                    * "limited" / "medium" -> MEDIUM
                    * "at risk" / "risky" / "not creditworthy" / "poor credit" -> POOR
                    For SEVERAL ratings put them all in ONE condition's values (they are alternatives,
                    OR-combined within the field), e.g. "good or at-risk rating" -> creditRating EQUALS
                    [GOOD, POOR]. Never express a rating via a numeric score.
                  - Today is %s. Resolve relative dates ("yesterday", "today", "last month", "this year",
                    "last week") against this date.

                Examples (conditions written as "field OP [values]" for brevity, negate noted separately):
                  "customers in Berlin"
                    -> city CONTAINS [Berlin]
                  "customers in Berlin or Köln"
                    -> city CONTAINS [Berlin, Köln]
                  "all customers in Berlin or Köln with a minimal revenue of 100000"
                    -> city CONTAINS [Berlin, Köln]; annualRevenue GREATER_OR_EQUAL [100000]
                  "customers whose contact name starts with M"
                    -> contactName STARTS_WITH [M]
                  "customers who are not from Berlin"
                    -> city CONTAINS [Berlin], negate=true
                  "companies not in Munich with revenue between 100000 and 500000"
                    -> city CONTAINS [Munich], negate=true; annualRevenue GREATER_OR_EQUAL [100000];
                       annualRevenue LESS_OR_EQUAL [500000]
                  "creditworthy customers in Berlin"
                    -> city CONTAINS [Berlin]; creditRating EQUALS [GOOD]
                  "customers at risk"
                    -> creditRating EQUALS [POOR]
                  "customers in Berlin with a good and an at-risk credit rating"
                    -> city CONTAINS [Berlin]; creditRating EQUALS [GOOD, POOR]
                  "customers since 2020"
                    -> customerSince GREATER_OR_EQUAL [2020-01-01]
                  "customers who last ordered in 2024" (bare year, no "since"/"before" -> CLOSED range,
                  both bounds required)
                    -> lastOrderDate GREATER_OR_EQUAL [2024-01-01]; lastOrderDate LESS_OR_EQUAL [2024-12-31]
                  "customers who placed an order yesterday" (today = %s)
                    -> lastOrderDate EQUALS [%s]
                  "customers who placed an order today" (today = %s)
                    -> lastOrderDate EQUALS [%s]
                  "customers who ordered last week" (today = %s, week starts Mon %s)
                    -> lastOrderDate GREATER_OR_EQUAL [%s]
                  "customers who ordered last month" (today = %s)
                    -> lastOrderDate GREATER_OR_EQUAL [%s]
                  "show all customers"
                    -> (empty conditions list)
                """.formatted(today, today, yesterday, today, today, today, thisWeekMonday, lastWeekMonday, today,
                lastMonthStart);
    }
}