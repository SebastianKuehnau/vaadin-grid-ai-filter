package dev.demo.vaadin.aigridfilter.ai.operator;

import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.ai.TokenUsageAdvisor;
import dev.demo.vaadin.aigridfilter.ai.operator.FieldCriterion.Operator;
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

/** Variant 02(b): a value, an operator and a negate flag per field - nine flat tool parameters. */
@Service("operatorSearchAgent")
@Scope("prototype")
class CustomerSearchService implements CustomerSearchAgent {

    private static final Logger logger = LoggerFactory.getLogger(CustomerSearchService.class);

    private static final String SYSTEM_PROMPT = """
            You filter a customer grid. Call the searchCustomers tool ONCE, then stop - the filter has
            already been applied.

            Each field has THREE parameters: the value, <field>Operator and <field>Negate. Always pass
            the value - an operator or a negate flag on its own filters nothing.

            What this filter type CANNOT express. Say so instead of approximating it:
              - a second value for one field ("Berlin or Hamburg")
              - two bounds for one field, so no range of any kind
            Never encode either into a single value: "Berlin, Hamburg" and "100000-500000" are wrong.

            Negation is ALWAYS the negate flag, never an operator - there is no NOT_CONTAINS,
            NOT_EQUALS or any other NOT_*. "not X" / "except X" / "ausser X" means: pass X as the
            value AND set <field>Negate=true. "All customers except Berlin" does NOT mean "no filter";
            it means city="Berlin" with cityNegate=true.

            The values:
              - city: CONTAINS is the default and is what a plain "in Berlin" means; reserve EQUALS
                for explicitly exact wording. A bare place name is a city, never a country.
                City names are stored in English - Berlin, Hamburg, Munich, Frankfurt, Cologne,
                Dusseldorf - so translate a German one before passing it: "München" is Munich,
                "Köln" is Cologne.
              - lastOrderDate is an ISO yyyy-MM-dd day. Use EQUALS for an exact day, LESS_OR_EQUAL
                for "before"/"until", and GREATER_OR_EQUAL for "since"/"after" and for an
                open-ended past range, with the FIRST day of that period.
              - A date the user wrote ambiguously is day-first (German): '03.05.05' is 2005-05-03.
              - creditRating is GOOD (creditworthy), MEDIUM (limited creditworthiness) or POOR (at
                risk / not creditworthy). It is a discrete label, so only EQUALS is meaningful.

            A RELATIVE date ("yesterday", "last week", "in the last 12 months") must be computed,
            never guessed and never copied from an example in this prompt: call currentLocalDateTime
            first, then subtract the WHOLE period from the date it returns. "In the last 12 months"
            is GREATER_OR_EQUAL (that date minus 12 months), not minus one month.

            "Not creditworthy" / "at risk" NAMES the POOR rating - pass creditRating=POOR with
            creditRatingNegate=false. The word "not" belongs to the rating's name here; it is not a
            negation. Negating GOOD instead would wrongly include the MEDIUM customers.
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
            if (criteria != null) {
                logger.warn("searchCustomers was called more than once; keeping the first result {}. Query: '{}'",
                        criteria, naturalLanguageQuery, e);
            } else {
                logger.warn("Could not turn query into search criteria; showing all customers. Query: '{}'",
                        naturalLanguageQuery, e);
                return null;
            }
        }
        logger.info("requestCriteria('{}') -> {}", naturalLanguageQuery, criteria);
        return criteria;
    }

    @Tool(description = """
            Filters the customer grid in place, replacing any previous filter. Every field has three
            parameters - the value, its operator and its negate flag - and the fields are AND-combined.
            Every parameter is optional; null ignores it. One field carries at most ONE condition.
            """)
    void searchCustomers(
            @ToolParam(description = "city, e.g. \"Berlin\"") String city,
            @ToolParam(description = "how to compare the city: CONTAINS, EQUALS, STARTS_WITH or ENDS_WITH") Operator cityOperator,
            @ToolParam(description = "true to exclude the matching cities instead of requiring them") Boolean cityNegate,

            @ToolParam(description = "last order date, ISO yyyy-MM-dd") LocalDate lastOrderDate,
            @ToolParam(description = "how to compare the date: EQUALS, GREATER_OR_EQUAL or LESS_OR_EQUAL") Operator lastOrderDateOperator,
            @ToolParam(description = "true to exclude the matching dates instead of requiring them") Boolean lastOrderDateNegate,

            @ToolParam(description = "credit rating: GOOD, MEDIUM or POOR") CreditRating creditRating,
            @ToolParam(description = "how to compare the rating: only EQUALS is meaningful") Operator creditRatingOperator,
            @ToolParam(description = "true to exclude the matching ratings instead of requiring them") Boolean creditRatingNegate
    ) {
        if (criteria != null) {
            throw new IllegalStateException(
                    "searchCustomers was already called once for this request; rejecting repeat call");
        }

        this.criteria = new CustomerCriteria(
                FieldCriterion.of(city, cityOperator, cityNegate),
                FieldCriterion.of(lastOrderDate, lastOrderDateOperator, lastOrderDateNegate),
                FieldCriterion.of(creditRating, creditRatingOperator, creditRatingNegate));
        logger.info("searchCustomers -> {}", criteria);
    }

    @Tool(description = "Current date and time")
    LocalDateTime currentLocalDateTime() {
        return LocalDateTime.now();
    }
}
