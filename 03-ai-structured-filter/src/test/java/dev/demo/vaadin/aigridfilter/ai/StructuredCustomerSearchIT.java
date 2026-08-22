package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** Variant 03 through its service: a CustomerFilter returned as structured output. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.autoconfigure.exclude=com.vaadin.flow.spring.SpringBootAutoConfiguration")
@Timeout(value = 300, unit = TimeUnit.SECONDS)
@ExtendWith({TokenUsageExtension.class, TestNameLoggingExtension.class})
@Import(OllamaContainerConfig.class)
class StructuredCustomerSearchIT {

    @Autowired
    CustomerSearchAgent agent;

    @Autowired
    CustomerRepository customerRepository;

    @Test
    void findsCustomersInOneCity() {
        assertThat(search("show me all customers in Berlin"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(
                        expectedIds(customer -> city(customer).equals("Berlin")));
    }

    @Test
    void findsCustomersInEitherOfTwoCities() {
        assertThat(search("show me customers from Berlin or Hamburg"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(expectedIds(customer ->
                        city(customer).equals("Berlin") || city(customer).equals("Hamburg")));
    }

    @Test
    void findsCustomersOutsideOneCity() {
        assertThat(search("show me all customers except from Berlin"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(
                        expectedIds(customer -> !city(customer).equals("Berlin")));
    }

    @Test
    void findsCustomersWhoseContactNameStartsWithALetter() {
        assertThat(search("show me all customers with an \"m\" as the first character in the contact name"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(expectedIds(customer ->
                        customer.getContactName().toLowerCase().startsWith("m")));
    }

    @Test
    void findsCreditworthyCustomersInOneCity() {
        assertThat(search("creditworthy customers in Hamburg"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(expectedIds(customer ->
                        city(customer).equals("Hamburg")
                                && customer.getCreditRating() == CreditRating.GOOD));
    }

    @Test
    void findsCustomersWithinARevenueRange() {
        BigDecimal lower = BigDecimal.valueOf(100_000);
        BigDecimal upper = BigDecimal.valueOf(200_000);

        assertThat(search("customers with revenue between 100000 and 200000"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(expectedIds(customer ->
                        customer.getAnnualRevenue().compareTo(lower) >= 0
                                && customer.getAnnualRevenue().compareTo(upper) <= 0));
    }

    @Test
    void findsCustomersWithAnOrderInTheLastTwelveMonths() {
        LocalDate oneYearAgo = LocalDate.now().minusYears(1);

        // No exact set: the seed data holds one future-dated order, so both readings of
        // "the last 12 months" — with and without an upper bound — count as correct.
        assertThat(search("show me all customers who placed an order in the last 12 months"))
                .extracting(Customer::getId)
                .isSubsetOf(expectedIds(customer ->
                        !customer.getLastOrderDate().isBefore(oneYearAgo)))
                .containsAll(expectedIds(customer ->
                        !customer.getLastOrderDate().isBefore(oneYearAgo)
                                && !customer.getLastOrderDate().isAfter(LocalDate.now())));
    }

    @Test
    void findsCustomersWhoLastOrderedWithinADateRange() {
        LocalDate from = LocalDate.of(2024, 7, 1);
        LocalDate to = LocalDate.of(2025, 3, 31);

        assertThat(search("customers who last ordered between 2024-07-01 and 2025-03-31"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(expectedIds(customer ->
                        !customer.getLastOrderDate().isBefore(from)
                                && !customer.getLastOrderDate().isAfter(to)));
    }

    @Test
    void ignoresSmallTalk() {
        assertThat(search("Nice weather today, isn't it?"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(expectedIds(customer -> true));
    }

    @Test
    void ignoresAnUnrelatedQuestion() {
        assertThat(search("What's the capital of France?"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(expectedIds(customer -> true));
    }

    @Test
    void showsEveryCustomerWhenAskedForAll() {
        assertThat(search("show me all customers"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(expectedIds(customer -> true));
    }

    @Test
    void showsEveryCustomerWhenTheFilterIsReset() {
        assertThat(search("remove the filter and show everything again"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(expectedIds(customer -> true));
    }

    @Test
    void understandsAGermanQuery() {
        assertThat(search("zeig mir alle Kunden aus Berlin"))
                .extracting(Customer::getId)
                .containsExactlyInAnyOrderElementsOf(
                        expectedIds(customer -> city(customer).equals("Berlin")));
    }

    /** The mechanism under test: prompt to the model, Specification back, executed by the database. */
    private List<Customer> search(String prompt) {
        return customerRepository.findAll(agent.resolveFilter(prompt));
    }

    /** The ids a correct answer selects from the seeded data — never a hard-coded list. */
    private List<Long> expectedIds(Predicate<Customer> matches) {
        return customerRepository.findAll().stream().filter(matches).map(Customer::getId).toList();
    }

    private static String city(Customer customer) {
        return customer.getAddress().getCity();
    }
}
