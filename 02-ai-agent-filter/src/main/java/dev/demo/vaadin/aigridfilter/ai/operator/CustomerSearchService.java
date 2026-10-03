package dev.demo.vaadin.aigridfilter.ai.operator;

import dev.demo.vaadin.aigridfilter.ai.operator.FieldCriterion.Operator;
import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.ai.RelativeDates;
import dev.demo.vaadin.aigridfilter.ai.TokenUsageAdvisor;
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

import java.math.BigDecimal;
import java.time.LocalDate;

/** Variant 02(b): a value, an operator and a negate flag per field - 39 flat tool parameters. */
@Service("operatorSearchAgent")
@Scope("prototype")
class CustomerSearchService implements CustomerSearchAgent {

    private static final Logger logger = LoggerFactory.getLogger(CustomerSearchService.class);

    private static final String SYSTEM_PROMPT = """
            You help users find customers by company name, contact name, email, phone, customer since,
            last order date, address, annual revenue and credit rating. The credit rating is
            creditworthy (GOOD), limited (MEDIUM) or at risk / not creditworthy (POOR).

            For a date relative to today ("today", "this month", "last 12 months"), call
            currentLocalDateTime FIRST and WAIT for its answer - never guess today. It returns the
            dates already computed: copy the matching one ("this month" -> startOfThisMonth) instead
            of calculating it yourself.

            Call searchCustomers to filter the grid. Each field has THREE parameters: the value,
            <field>Operator and <field>Negate. ALWAYS pass the value - an operator or negate flag
            without it filters nothing.

            Set operator and negate whenever the request implies them:
              - "not X" / "except X" -> X as the value and <field>Negate=true. There are no NOT_*
                operators: "does not start with X" is STARTS_WITH + Negate=true. "All customers
                except X" still filters on X with Negate=true - never pass every parameter null.
              - "not creditworthy" is no negation but a rating: creditRating=POOR, Negate=false.
              - "begins with" -> STARTS_WITH, "ends with" -> ENDS_WITH, "exactly" -> EQUALS,
                otherwise CONTAINS (the default).
              - city, country and street: "in X" / "from X" is always CONTAINS, never EQUALS.
              - "in X" / "from X" with a city name goes into city, never country.
              - "from X" with a city name goes into city, never country and a positive negate flag.
              - A bare place name is a city, unless it clearly names a country. A state or region
                ("the state Ile-de-France") goes into state.
              - City names are stored in English - Berlin, Hamburg, Munich, Frankfurt, Cologne,
                Dusseldorf - so translate a German one first: "München" is Munich, "Köln" is Cologne.
              - Dates: an exact day ("today", "yesterday") -> EQUALS; "since" / "after" ->
                GREATER_OR_EQUAL; "before" / "until" -> LESS_OR_EQUAL; "before" a year is the last
                day of the year BEFORE it ("before 2025" -> 2024-12-31); a past period ("last 12
                months", "this month") -> GREATER_OR_EQUAL its first day, never LESS_OR_EQUAL.
              - annualRevenue: "at least" / "over" -> GREATER_OR_EQUAL, "at most" / "under" ->
                LESS_OR_EQUAL, "exactly" -> EQUALS.

            Each field carries ONE condition; if a request needs two, pass the closest one.
            Call searchCustomers exactly ONCE and then stop.
            """;

    // Identical for every field, so kept as constants instead of being repeated 13 times.
    private static final String TEXT_OPERATOR = """
            how to compare this field with its value: CONTAINS (case-insensitive substring, the
            default), EQUALS (the whole field equals the value), STARTS_WITH, or ENDS_WITH.""";
    private static final String DATE_OPERATOR = """
            how to compare this date: EQUALS (exactly that day), GREATER_OR_EQUAL (that day or later),
            or LESS_OR_EQUAL (that day or earlier).""";
    private static final String NUMBER_OPERATOR = """
            how to compare this number: GREATER_OR_EQUAL (at least), LESS_OR_EQUAL (at most), or
            EQUALS (exactly).""";
    private static final String RATING_OPERATOR = """
            how to compare the rating. A rating is a discrete label, so only equality is meaningful:
            pass EQUALS (or null).""";
    private static final String NEGATE = """
            true to EXCLUDE the matches of this field instead of requiring them (e.g. "not from
            Berlin", "except Hamburg"); false or null otherwise.""";

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
            // returnDirect ends the exchange right after searchCustomers(...) has run - no answer text follows.
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

    @Tool(returnDirect = true, description = """
            Search and filter the customer grid. Returns nothing; it updates the grid in place to show
            only the matching customers, replacing any previous filter (filters are not additive).
            All parameters are optional - pass null to ignore one; passing all null shows every
            customer. Fields are combined with AND.
            Every field has exactly three parameters: the value, its operator, and its negate flag. The
            value is mandatory whenever you filter on that field: an operator or a negate flag on its
            own matches nothing (companyNameOperator="CONTAINS" without companyName="data" is useless).
            Every parameter is a single scalar value, never a list, so ONE field can carry only ONE
            condition: there is no way to match two cities, and no way to give both a lower and an
            upper bound for annualRevenue or a date. Do not try to encode a range or a list into a
            single value ("100000-500000" or "Berlin, Hamburg" are wrong) - pass the single closest
            condition instead.
            Dates are ISO yyyy-MM-dd; interpret ambiguous user input as day-first (German format),
            e.g. '03.05.05' -> "2005-05-03". For a relative date, call currentLocalDateTime first.
            """)
    void searchCustomers(
            @ToolParam(description = "company name to match") String companyName,
            @ToolParam(description = TEXT_OPERATOR) Operator companyNameOperator,
            @ToolParam(description = NEGATE) Boolean companyNameNegate,

            @ToolParam(description = "contact name to match") String contactName,
            @ToolParam(description = TEXT_OPERATOR) Operator contactNameOperator,
            @ToolParam(description = NEGATE) Boolean contactNameNegate,

            @ToolParam(description = "email address to match") String email,
            @ToolParam(description = TEXT_OPERATOR) Operator emailOperator,
            @ToolParam(description = NEGATE) Boolean emailNegate,

            @ToolParam(description = """
                    phone number to match, or part of it. Numbers are stored in E.164 format, so
                    normalize the user input to E.164 before passing it, e.g. '016057123456' or
                    '0160 57 123456' -> '+4916057123456' (assume Germany / +49 for national
                    numbers).""") String phone,
            @ToolParam(description = TEXT_OPERATOR) Operator phoneOperator,
            @ToolParam(description = NEGATE) Boolean phoneNegate,

            @ToolParam(description = """
                    the 'customer since' date to compare against, e.g. "customers since 2020" ->
                    "2020-01-01" with GREATER_OR_EQUAL.""") LocalDate customerSince,
            @ToolParam(description = DATE_OPERATOR) Operator customerSinceOperator,
            @ToolParam(description = NEGATE) Boolean customerSinceNegate,

            @ToolParam(description = """
                    the last-order date to compare against, e.g. "ordered since March 2024" ->
                    "2024-03-01" with GREATER_OR_EQUAL.""") LocalDate lastOrderDate,
            @ToolParam(description = DATE_OPERATOR) Operator lastOrderDateOperator,
            @ToolParam(description = NEGATE) Boolean lastOrderDateNegate,

            @ToolParam(description = """
                    country to match, e.g. "Germany" or "France". A bare city name (Hamburg, Berlin,
                    Munich, ...) is NOT a country - put it in the city parameter instead.""") String country,
            @ToolParam(description = TEXT_OPERATOR) Operator countryOperator,
            @ToolParam(description = NEGATE) Boolean countryNegate,

            @ToolParam(description = """
                    city to match, e.g. "Hamburg" or "Berlin". A bare place name defaults to city
                    unless it unambiguously names a country.""") String city,
            @ToolParam(description = TEXT_OPERATOR) Operator cityOperator,
            @ToolParam(description = NEGATE) Boolean cityNegate,

            @ToolParam(description = "postal code to match") String postalCode,
            @ToolParam(description = TEXT_OPERATOR) Operator postalCodeOperator,
            @ToolParam(description = NEGATE) Boolean postalCodeNegate,

            @ToolParam(description = "street to match") String street,
            @ToolParam(description = TEXT_OPERATOR) Operator streetOperator,
            @ToolParam(description = NEGATE) Boolean streetNegate,

            @ToolParam(description = "house number to match") String houseNumber,
            @ToolParam(description = TEXT_OPERATOR) Operator houseNumberOperator,
            @ToolParam(description = NEGATE) Boolean houseNumberNegate,

            @ToolParam(description = "state or region to match, e.g. \"Ile-de-France\"") String state,
            @ToolParam(description = TEXT_OPERATOR) Operator stateOperator,
            @ToolParam(description = NEGATE) Boolean stateNegate,

            @ToolParam(description = "two-letter ISO country code to match, e.g. \"DE\" or \"GB\"") String countryCode,
            @ToolParam(description = TEXT_OPERATOR) Operator countryCodeOperator,
            @ToolParam(description = NEGATE) Boolean countryCodeNegate,

            @ToolParam(description = """
                    credit rating to match: GOOD (creditworthy), MEDIUM (limited creditworthiness),
                    or POOR (at risk / not creditworthy).""") CreditRating creditRating,
            @ToolParam(description = RATING_OPERATOR) Operator creditRatingOperator,
            @ToolParam(description = NEGATE) Boolean creditRatingNegate,

            @ToolParam(description = """
                    annual revenue to compare against, as a plain number, e.g. "over 500000" ->
                    500000 with GREATER_OR_EQUAL.""") BigDecimal annualRevenue,
            @ToolParam(description = NUMBER_OPERATOR) Operator annualRevenueOperator,
            @ToolParam(description = NEGATE) Boolean annualRevenueNegate
    ) {
        if (criteria != null) {
            throw new IllegalStateException(
                    "searchCustomers was already called once for this request; rejecting repeat call");
        }

        CustomerCriteria incoming = new CustomerCriteria(
                FieldCriterion.of(companyName, companyNameOperator, companyNameNegate),
                FieldCriterion.of(contactName, contactNameOperator, contactNameNegate),
                FieldCriterion.of(email, emailOperator, emailNegate),
                FieldCriterion.of(phone, phoneOperator, phoneNegate),
                FieldCriterion.of(customerSince, customerSinceOperator, customerSinceNegate),
                FieldCriterion.of(lastOrderDate, lastOrderDateOperator, lastOrderDateNegate),
                FieldCriterion.of(country, countryOperator, countryNegate),
                FieldCriterion.of(city, cityOperator, cityNegate),
                FieldCriterion.of(postalCode, postalCodeOperator, postalCodeNegate),
                FieldCriterion.of(street, streetOperator, streetNegate),
                FieldCriterion.of(houseNumber, houseNumberOperator, houseNumberNegate),
                FieldCriterion.of(state, stateOperator, stateNegate),
                FieldCriterion.of(countryCode, countryCodeOperator, countryCodeNegate),
                FieldCriterion.of(creditRating, creditRatingOperator, creditRatingNegate),
                FieldCriterion.of(annualRevenue, annualRevenueOperator, annualRevenueNegate));

        this.criteria = incoming;
        logger.info("searchCustomers -> {}", criteria);
    }

    @Tool(description = "Today's date and the period boundaries around it, already computed (weeks start on Monday)")
    RelativeDates currentLocalDateTime() {
        return RelativeDates.of(LocalDate.now());
    }
}
