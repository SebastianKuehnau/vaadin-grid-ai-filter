package dev.demo.vaadin.aigridfilter.ai.operator;

import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
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
import java.time.LocalDateTime;

/**
 * Variant <b>02(b)</b> of the tool-calling AI layer — the same delivery mechanism as variant 02(a),
 * with three tool parameters per field instead of one: a value, an {@link Operator}, and a
 * {@code negate} flag. 13 fields therefore mean <b>39 flat parameters</b> on a single
 * {@code searchCustomers} tool; that parameter explosion is the visible cost of the two capabilities
 * it buys — negation and operator precision (including day-level date bounds).
 * <p>
 * What it deliberately still cannot do: a second value or a second operator for the same field, so
 * neither multi-value OR ("Berlin or Hamburg") nor any range ("between 100000 and 500000", "in 2024")
 * is expressible. The system prompt below therefore never teaches range phrasing — the model has no
 * parameter to put it in. Lifting that ceiling means leaving the per-field parameter shape behind
 * entirely, which is what {@code 03-ai-structured-filter} (structured output) and
 * {@code 04-ai-hybrid-filter} (the same condition list, delivered as a tool call) do.
 * <p>
 * {@code @Scope("prototype")}: see {@code CustomerSearchService} — one instance per Vaadin view, so
 * {@link #criteria} can live on the bean.
 */
@Service("operatorSearchAgent")
@Scope("prototype")
class CustomerSearchService implements CustomerSearchAgent {

    private static final Logger logger = LoggerFactory.getLogger(CustomerSearchService.class);

    private static final String SYSTEM_PROMPT = """
            You are a helpful assistant that helps users find customers based on their
            company name, contact name, email, phone, customer since, last order date,
            country, city, postal code, street, house number, annual revenue, and credit
            rating. The credit rating is one of: creditworthy (GOOD), limited (MEDIUM), or
            at risk / not creditworthy (POOR).

            Call the searchCustomers tool to filter the grid. Each field has THREE parameters:
            the value, <field>Operator, and <field>Negate. ALWAYS pass the value parameter for every
            field you filter on - the value is what gets matched, while <field>Operator only says HOW
            to compare it. An operator or a negate flag without its value filters nothing, so
            "company name contains data" is companyName="data" (operator CONTAINS is the default and
            may be omitted), never companyNameOperator="CONTAINS" on its own.

            Fill the operator and the negate flag whenever the request implies them:
              - "not X" / "except X" / "excluding X" / "ausser X" -> pass X as the value and set
                <field>Negate=true. Negation is ALWAYS expressed via the negate flag, never via the
                operator: there is no NOT_CONTAINS, NOT_STARTS_WITH, NOT_ENDS_WITH, NOT_EQUALS, or any
                other NOT_* operator - Operator only ever has the plain values CONTAINS, EQUALS,
                STARTS_WITH, ENDS_WITH, GREATER_OR_EQUAL, LESS_OR_EQUAL. So "does not start with X" /
                "startet nicht mit X" -> <field>Operator=STARTS_WITH and <field>Negate=true (not an
                invented "NOT_STARTS_WITH"); "does not end with X" -> ENDS_WITH + Negate=true. This
                applies even when the request is phrased as "all customers except X" / "show me
                everyone but X" - the word "all"/"everyone" does NOT mean pass no filter; it means
                "all of the remaining customers once X is excluded", so you must still call
                searchCustomers with X as the value and <field>Negate=true, never with every
                parameter null.
              - "begins with" / "first character/letter is X" -> <field>Operator=STARTS_WITH;
                "ends with" -> ENDS_WITH; "is exactly" / "precisely X" -> EQUALS; a plain partial
                match -> CONTAINS (the default).
              - city, country, and street are components of a larger address, so a plain "in X" /
                "from X" / "is X" phrasing (e.g. "customers in Berlin") always means
                <field>Operator=CONTAINS (the default) for these three fields, never EQUALS - reserve
                EQUALS for these fields for explicit exact-match wording only ("exactly Berlin",
                "precisely Berlin").
              - a bare place name is a CITY, never a country, unless it unambiguously names a country
                (e.g. "Germany", "France") or both are given together (e.g. "Hamburg, Germany"): put
                "Hamburg", "Berlin", "Munich" etc. into city, not into country. When in doubt, prefer
                city over country - city is the field actually shown in the grid.
              - dates: an exact day ("on 2024-03-15", "yesterday", "today") -> EQUALS;
                "since" / "after" / "from" -> GREATER_OR_EQUAL; "before" / "until" -> LESS_OR_EQUAL.
              - annualRevenue: "at least" / "over" / "more than" -> GREATER_OR_EQUAL;
                "at most" / "under" / "less than" -> LESS_OR_EQUAL; "exactly" -> EQUALS.

            Each field takes exactly ONE value, ONE operator and ONE negate flag, so a field can only
            ever carry a single condition. If a request needs two values or two bounds for the same
            field, pass the single closest condition you can and ignore the rest - that limitation
            cannot be worked around, so do not retry the call for the part you had to drop.

            Call searchCustomers exactly ONCE and then stop; it has already been applied.

            For a relative date ("yesterday", "last week", "in the last 12 months") call the
            currentLocalDateTime tool first and compute the date from its result.
            """;

    // The operator and negate descriptions are identical for every field of the same type, so they
    // live in constants instead of being repeated 13 times. (They are compile-time constants, so the
    // model still receives the literal text in the generated tool schema.)
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

    /**
     * Turns the query into a JPA {@link Specification}: ask the LLM to call the search tool
     * ({@link #requestCriteria}) and translate the extracted criteria. {@code null} criteria (e.g.
     * on a bad response, or the model never called the tool) matches all.
     */
    @Override
    public Specification<Customer> resolveFilter(String naturalLanguageQuery) {
        return CustomerSpecifications.from(requestCriteria(naturalLanguageQuery));
    }

    /**
     * Asks the LLM to call {@code searchCustomers} and returns the criteria it extracted.
     * Package-private so the AI layer can be tested directly on the produced criteria. Returns
     * {@code null} if the model produces nothing usable, so the UI never breaks on a bad response.
     */
    CustomerCriteria requestCriteria(String naturalLanguageQuery) {
        criteria = null;
        try {
            // The tool call is the point: by the time this returns, searchCustomers(...) has already
            // written into `criteria`, so the model's answer text is irrelevant. Token usage and
            // duration are recorded by tokenUsageAdvisor, not here.
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
                FieldCriterion.of(creditRating, creditRatingOperator, creditRatingNegate),
                FieldCriterion.of(annualRevenue, annualRevenueOperator, annualRevenueNegate));

        this.criteria = incoming;
        logger.info("searchCustomers -> {}", criteria);
    }

    @Tool(description = "Current date and time")
    LocalDateTime currentLocalDateTime() {
        return LocalDateTime.now();
    }
}
