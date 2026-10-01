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

/** The hybrid step: tool calling like 02, but the tool takes 03's {@code List<Condition>} as its one parameter. */
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

    /** Asks the LLM to call the search tool and turns the conditions it passed into a {@link Specification}. */
    @Override
    public Specification<Customer> resolveFilter(String naturalLanguageQuery) {
        return CustomerFilterSpecifications.from(requestFilter(naturalLanguageQuery));
    }

    /** Asks the LLM to call {@code searchCustomers}; an empty filter (match all) if it produced nothing usable. */
    CustomerFilter requestFilter(String naturalLanguageQuery) {
        CustomerFilter filter;
        try {
            // By the time this returns, searchCustomers(...) has run; the answer text is irrelevant.
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
            You translate a user's request into a CustomerFilter that filters a customer grid.

            You have one tool, currentLocalDateTime. Use it ONLY when the request names a date
            relative to today ("yesterday", "today", "last week", "this month", "this year", "in the
            last 12 months", "since the start of last year"). Then call it
            FIRST, WAIT for the date it returns, and only THEN answer - never guess today's date
            and never take one from an example below.

            If the request names no date at all, or an absolute one ("18.11.2025", "between
            2024-07-01 and 2025-03-31", "in 2024"), do NOT call the tool - answer directly.

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

            A condition's field is one of companyName, contactName, email, phone, annualRevenue,
            creditRating, customerSince, lastOrderDate, country, city, postalCode, street,
            houseNumber, state, countryCode. A country name ("from Germany") goes into country, never
            into countryCode; a street ("on Market Street") into street, both with CONTAINS.

            city is text, matched case-insensitively: a plain "in Berlin" is CONTAINS, EQUALS only
            for explicitly exact wording. City names are stored in English - Berlin, Hamburg, Munich,
            Frankfurt, Cologne, Dusseldorf - so translate a German one before passing it: "München"
            is Munich, "Köln" is Cologne. The value you pass must be one of those six names: if the
            user's word is none of them, pass the one it is closest to - "Brelin" is Berlin. That
            repairs the NAME only; it never changes what the condition asks for, so negate stays
            exactly as the sentence had it.

            lastOrderDate is an ISO yyyy-MM-dd day: EQUALS an exact day, LESS_OR_EQUAL
            "before"/"until", GREATER_OR_EQUAL "since"/"after". A date the user wrote ambiguously is
            day-first (German): '03.05.05' is 2005-05-03. Emit a GREATER_OR_EQUAL + LESS_OR_EQUAL
            pair only for an explicit "between X and Y", a bare year ("in 2024" is 2024-01-01 to
            2024-12-31) or a relative period already over (below), never for a single day, named or
            relative.

            currentLocalDateTime returns today and the period boundaries around it, already
            computed. Copy the matching date from its answer - never calculate a date yourself, and
            pass the date itself as the value, never the name it has in the answer:
              - a single relative DAY ("yesterday", "today") is ONE exact day: EQUALS yesterday or
                today, never "from that day on".
              - a period still running is open-ended, GREATER_OR_EQUAL its start and NO upper
                bound: "this month" is
                startOfThisMonth, "this year" startOfThisYear, "in the last 12 months"
                twelveMonthsAgo, "since the start of last year" startOfLastYear.
              - a period already over is a CLOSED range, two conditions: "last week" is
                GREATER_OR_EQUAL startOfLastWeek and LESS_OR_EQUAL endOfLastWeek; "last month" and
                "last year" likewise.

            creditRating EQUALS GOOD (creditworthy), MEDIUM (limited creditworthiness) or POOR (at
            risk / not creditworthy). Several ratings are alternatives, so they share ONE condition.
            "Not creditworthy" NAMES POOR: EQUALS [POOR] with negate=false, never a negated GOOD.

            Examples, written as "field OPERATOR [values]":
              "customers in Berlin"
                -> city CONTAINS [Berlin]
              "customers in Berlin or Hamburg"
                -> city CONTAINS [Berlin, Hamburg]
              "Kunden aus Köln"
                -> city CONTAINS [Cologne]
              "customers in Brelin"
                -> city CONTAINS [Berlin]
              "creditworthy customers in Hamburg"
                -> city CONTAINS [Hamburg]; creditRating EQUALS [GOOD]
              "customers who are not from Berlin"
                -> city CONTAINS [Berlin], negate=true
              "customers from Germany or France"
                -> country CONTAINS [Germany, France]
              "customers who ordered in the last 12 months"
                -> lastOrderDate GREATER_OR_EQUAL [the twelveMonthsAgo date the tool returned]
              "customers who ordered yesterday"
                -> lastOrderDate EQUALS [the yesterday date the tool returned]
              "customers who ordered this year" (still running - no upper bound, not even today)
                -> lastOrderDate GREATER_OR_EQUAL [the startOfThisYear date the tool returned]
              "customers who ordered last week"
                -> lastOrderDate GREATER_OR_EQUAL [the startOfLastWeek date the tool returned];
                   lastOrderDate LESS_OR_EQUAL [the endOfLastWeek date the tool returned]
              "customers who last ordered between 2024-07-01 and 2025-03-31"
                -> lastOrderDate GREATER_OR_EQUAL [2024-07-01]; lastOrderDate LESS_OR_EQUAL [2025-03-31]
              "show all customers"
                -> (empty conditions list)

            Answer with exactly ONE JSON object, {"conditions": [...]}, and nothing after it: close
            every bracket once - no extra "}" or "]" at the end.
            """;
}
