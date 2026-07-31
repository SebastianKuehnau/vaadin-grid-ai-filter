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

/**
 * The AI layer: turns a natural-language query into a JPA {@link Specification} — the <b>hybrid</b>
 * step of the tutorial.
 * <p>
 * It uses genuine <em>tool calling</em>, like {@code 02-ai-agent-filter}, but the tool takes exactly
 * one parameter: the same {@code List<Condition>} that {@code 03-ai-structured-filter} receives as
 * <em>structured output</em>. {@link Condition}, {@link dev.demo.vaadin.aigridfilter.ai.filter.Operator},
 * {@link CustomerFilter} and {@link CustomerFilterSpecifications} are copied from module 03 unchanged,
 * Jackson annotations included, and Spring AI turns those very annotations into the tool's parameter
 * schema. So this module differs from 03 in <b>one</b> respect only — how the finished filter
 * travels from the model to Java:
 * <ul>
 *   <li>03: {@code .call().entity(CustomerFilter.class)} — the model <em>returns</em> the filter,</li>
 *   <li>04: {@code @Tool searchCustomers(List<Condition>)} — the model <em>calls</em> a method with it.</li>
 * </ul>
 * That makes the point the escalation ladder builds up to: what a filter can express is a property of
 * its <em>type</em>, not of the delivery mechanism. Multi-value OR (several {@code values} in one
 * condition) and real ranges (two sibling conditions on one field) work here exactly as they do in 03 —
 * and both are out of reach for 02(a)'s and 02(b)'s per-field tool parameters, no matter how many of
 * them there are.
 * <p>
 * Like 03, "today" is baked into the prompt via {@link #systemPrompt(LocalDate)} rather than fetched
 * through a second live-clock tool call, so relative dates need no extra round trip.
 * <p>
 * {@code @Scope("prototype")}: the tool call writes its result into {@link #filter}, and
 * {@code CustomerListView} (the only injection point) is not a singleton either — Vaadin creates a
 * fresh view instance per navigation, so each view gets its own service instance. Different browser
 * tabs/sessions never share one, and within one instance the view only ever has one search in flight
 * at a time (it disables the filter field for the duration of a search). {@link #filter} is reset at
 * the top of {@link #requestFilter}, since it outlives a single call.
 */
@Service
@Scope("prototype")
public class CustomerSearchService implements CustomerSearchAgent {

    private static final Logger logger = LoggerFactory.getLogger(CustomerSearchService.class);

    private final ChatClient chatClient;
    private final TokenUsageAdvisor tokenUsageAdvisor;

    /** What the model passed to {@link #searchCustomers}; {@code null} until the tool is called. */
    CustomerFilter filter;

    public CustomerSearchService(ChatModel chatModel, TokenUsageRecorder tokenUsageRecorder) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.tokenUsageAdvisor = new TokenUsageAdvisor(tokenUsageRecorder);
    }

    /**
     * Turns the query into a JPA {@link Specification}: ask the LLM to call the search tool
     * ({@link #requestFilter}) and translate the conditions it passed. An empty conditions list (e.g.
     * on a bad response, or the model never called the tool) matches all.
     */
    @Override
    public Specification<Customer> resolveFilter(String naturalLanguageQuery) {
        return CustomerFilterSpecifications.from(requestFilter(naturalLanguageQuery));
    }

    /**
     * Asks the LLM to call {@code searchCustomers} and returns the {@link CustomerFilter} it passed.
     * Package-private so the AI layer can be tested directly on the produced filter — same name and
     * same return type as module 03's {@code requestFilter}, so both modules' tests and the benchmark
     * stay directly comparable. Returns a filter with an empty conditions list (match all) if the model
     * produces nothing usable, so the UI never breaks on a bad response.
     */
    CustomerFilter requestFilter(String naturalLanguageQuery) {
        filter = null;
        try {
            // The tool call is the point: by the time this returns, searchCustomers(...) has already
            // written into `criteria`, so the model's answer text is irrelevant. Token usage and
            // duration are recorded by tokenUsageAdvisor, not here.
            chatClient.prompt()
                    .system(systemPrompt(LocalDate.now()))
                    .user(naturalLanguageQuery)
                    .tools(this)
                    .advisors(SimpleLoggerAdvisor.builder().build(), tokenUsageAdvisor)
                    // Temperature (0 for deterministic structure) is set per active profile in
                    // application-<provider>.properties, not here.
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

    /**
     * The one tool of this module: a single parameter carrying the whole condition list — exactly the
     * payload module 03 receives as structured output. Spring AI derives the parameter schema from
     * {@link Condition}'s Jackson annotations, so the model sees the same field/operator/values/negate
     * vocabulary in both modules.
     */
    @Tool(description = """
            Search and filter the customer grid. Returns nothing; it updates the grid in place to show
            only the matching customers, replacing any previous filter (filters are not additive).
            Pass the complete list of conditions in one call. ALL conditions must match (AND). Several
            values inside ONE condition are alternatives (OR) for that field, and a value range on one
            field is TWO conditions on that field (GREATER_OR_EQUAL for the lower bound,
            LESS_OR_EQUAL for the upper one). Pass an empty list to show every customer.
            """)
    void searchCustomers(
            @ToolParam(description = "all conditions the customer must satisfy (AND); empty list matches everything")
            List<Condition> conditions
    ) {
        CustomerFilter incoming = new CustomerFilter(conditions == null ? List.of() : conditions);

        // A tool call is a message the model can repeat: since the tool is void, Spring AI answers it
        // with a bare "Done", and a model that reads that as "nothing happened" sometimes calls the tool
        // again with an empty list, wiping the filter it just built (observed with qwen3:8b). A filter
        // that was already built is therefore never overwritten by a later empty one. Structured output
        // (module 03) cannot run into this at all — one response, one filter — so this is a genuine
        // property of the delivery mechanism, not of the filter type.
        if (filter != null && !filter.conditions().isEmpty() && incoming.conditions().isEmpty()) {
            logger.warn("Ignoring a repeated searchCustomers call with an empty conditions list; keeping {}", filter);
            return;
        }

        this.filter = incoming;
        logger.info("searchCustomers -> {}", filter);
    }

    /**
     * Builds the system prompt for the given "today". Package-private and date-parameterized so it
     * can be unit-tested deterministically without calling the model.
     * <p>
     * Deliberately the same rules, wording and examples as module 03's {@code systemPrompt(LocalDate)}
     * — only the framing differs ("call the searchCustomers tool" instead of "return a CustomerFilter"),
     * because that is the only thing that actually differs between the two modules.
     */
    static String systemPrompt(LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        LocalDate thisWeekMonday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        LocalDate lastWeekMonday = thisWeekMonday.minusWeeks(1);
        LocalDate lastMonthStart = today.withDayOfMonth(1).minusMonths(1);
        return """
                You translate a user's request into a call of the searchCustomers tool, which filters a
                list of customers.

                Call searchCustomers exactly ONCE, with the complete list of conditions. Do not call it
                a second time afterwards - it has already been applied.

                searchCustomers takes a flat "conditions" list; ALL conditions must match (AND). Each
                condition is: { field, operator, values: [...], negate }.
                  - values: one or more values; the condition matches if the field matches ANY of them
                    (OR within the field).
                  - negate: true to exclude matches instead of requiring them (e.g. "not from Berlin").
                There is no nesting and no OR across different fields — only within one field's values.
                To show all customers, call the tool with an empty conditions list.

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
