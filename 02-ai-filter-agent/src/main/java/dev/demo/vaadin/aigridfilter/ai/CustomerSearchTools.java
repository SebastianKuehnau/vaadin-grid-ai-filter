package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerSearchCriteria;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDate;

/**
 * Tool-provider POJO holding the {@code searchCustomers} tool. Not a Spring bean — a fresh
 * instance is constructed per {@link ToolCallingCustomerSearchAgent} call (Spring AI's documented
 * idiom for request-scoped tool objects), so the extracted arguments only ever end up in that
 * call's own {@link CustomerSearchResult}. The tool itself does nothing but record its
 * arguments — no Grid, no UI.
 */
class CustomerSearchTools {

    private final Logger logger = LoggerFactory.getLogger(CustomerSearchTools.class);

    private final CustomerSearchResult result;

    CustomerSearchTools(CustomerSearchResult result) {
        this.result = result;
    }

    @Tool(description = """
            Search and filter the customer grid. Returns nothing; it updates the grid in place to show
            only the matching customers, replacing any previous filter (filters are not additive).
            All parameters are optional - pass null to ignore one; passing all null shows every customer.
            All given parameters are combined with AND.
            Text parameters match case-insensitively on any substring.
            Date parameters (customerSince, lastOrderDate) match customers on or after the given date,
            in ISO format yyyy-MM-dd. For relative dates such as "last month", call the current
            date/time tool first to resolve the actual date.
            """)
    void searchCustomers(
            @ToolParam(description = "part name of the company name to match, or null") String companyName,
            @ToolParam(description = "part of the contact name in the company to match, or null") String contactName,
            @ToolParam(description = "part of the email address to match, or null") String email,
            @ToolParam(description = """
                    part of the phone number to match, or null. Numbers are stored in E.164 format, so
                    normalize the user input to E.164 before passing it, e.g. '016057123456' or
                    '0160 57 123456' -> '+4916057123456' (assume Germany / +49 for national numbers).""") String phone,
            @ToolParam(description = """
                    matches customers whose 'customer since' date is on or after this date, or null.
                    Pass ISO yyyy-MM-dd; interpret ambiguous user input as day-first (German format),
                    e.g. '03.05.05' -> '2005-05-03'.""") LocalDate customerSince,
            @ToolParam(description = """
                    matches customers whose last order date is on or after this date, or null.
                    Pass ISO yyyy-MM-dd; interpret ambiguous user input as day-first (German format),
                    e.g. '03.05.05' -> '2005-05-03'.""") LocalDate lastOrderDate,
            @ToolParam(description = "part of the country to match, or null") String country,
            @ToolParam(description = "part of the city to match, or null") String city,
            @ToolParam(description = "part of the postal code to match, or null") String postalCode,
            @ToolParam(description = "part of the street to match, or null") String street,
            @ToolParam(description = "part of the house number to match, or null") String houseNumber,
            @ToolParam(description = """
                    the credit rating to match, or null. One of: GOOD (creditworthy),
                    MEDIUM (limited creditworthiness), POOR (at risk / not creditworthy).""") CreditRating creditRating
    ) {
        logger.info("searchCustomers: companyName={}, contactName={}, email={}, phone={}, customerSince={}, " +
                        "lastOrderDate={}, country={}, city={}, postalCode={}, street={}, houseNumber={}, creditRating={}",
                companyName, contactName, email, phone, customerSince,
                lastOrderDate, country, city, postalCode, street, houseNumber, creditRating);

        result.criteria = new CustomerSearchCriteria(companyName, contactName, email, phone, customerSince,
                lastOrderDate, country, city, postalCode, street, houseNumber, creditRating);
    }
}
