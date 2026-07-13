package dev.demo.vaadin.aigridfilter.ai.filter;

import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.And;
import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.Condition;
import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.Or;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic, fast test of the filter translation ({@link CustomerFilter} -> JPA
 * {@link Specification}) against the seeded H2 database — no LLM, no Docker. This is the safety net
 * for the recursive tree traversal in {@link CustomerFilterSpecifications}.
 * <p>
 * The single-field and multi-value-OR cases below use the same field values as
 * {@code 02-ai-agent-filter}'s {@code CustomerSpecificationsTest}, so the two modules' DB-level
 * results are directly comparable. Cases that need a capability {@code 02}'s flat
 * {@code CustomerSearchCriteria} model cannot express at all (negation, cross-field OR, arbitrary
 * nesting) have no counterpart there and live separately in
 * {@link CustomerFilterSpecificationsNestedTest}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // keep configured H2 + data.sql
class CustomerFilterSpecificationsTest {

    @Autowired
    CustomerRepository repository;

    private List<Customer> findAll(FilterNode root) {
        return repository.findAll(CustomerFilterSpecifications.from(new CustomerFilter(root)));
    }

    private static Condition city(Operator operator, String value) {
        return new Condition("city", operator, value);
    }

    private static Condition revenue(Operator operator, String value) {
        return new Condition("annualRevenue", operator, value);
    }

    private static Condition creditRating(String value) {
        return new Condition("creditRating", Operator.EQUALS, value);
    }

    private static Condition companyName(String value) {
        return new Condition("companyName", Operator.CONTAINS, value);
    }

    private static Condition contactName(String value) {
        return new Condition("contactName", Operator.CONTAINS, value);
    }

    private static Condition email(String value) {
        return new Condition("email", Operator.CONTAINS, value);
    }

    private static Condition phone(String value) {
        return new Condition("phone", Operator.CONTAINS, value);
    }

    private static Condition country(String value) {
        return new Condition("country", Operator.CONTAINS, value);
    }

    private static Condition postalCode(String value) {
        return new Condition("postalCode", Operator.CONTAINS, value);
    }

    private static Condition street(String value) {
        return new Condition("street", Operator.CONTAINS, value);
    }

    private static Condition houseNumber(String value) {
        return new Condition("houseNumber", Operator.CONTAINS, value);
    }

    /** The full year (Jan 1 - Dec 31) {@code date} falls in, as an AND of two date bounds. */
    private static And fullYear(String field, LocalDate date) {
        return new And(List.of(
                new Condition(field, Operator.GREATER_OR_EQUAL, LocalDate.of(date.getYear(), 1, 1).toString()),
                new Condition(field, Operator.LESS_OR_EQUAL, LocalDate.of(date.getYear(), 12, 31).toString())));
    }

    @Test
    void companyNameMatchesSubstringCaseInsensitively() {
        var result = findAll(companyName("berlin"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getCompanyName().toLowerCase()).contains("berlin"));
    }

    @Test
    void companyNameMultiValueMatchesAnyWithOr() {
        var result = findAll(new Or(List.of(companyName("berlin"), companyName("hamburg"))));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getCompanyName().toLowerCase())
                .containsAnyOf("berlin", "hamburg"));
        assertThat(result).anyMatch(c -> c.getCompanyName().toLowerCase().contains("berlin"));
        assertThat(result).anyMatch(c -> c.getCompanyName().toLowerCase().contains("hamburg"));
    }

    @Test
    void contactNameMatchesSubstringCaseInsensitively() {
        var result = findAll(contactName("laura"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getContactName().toLowerCase()).contains("laura"));
    }

    @Test
    void emailMatchesSubstringCaseInsensitively() {
        var result = findAll(email("berlin-data"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getEmail().toLowerCase()).contains("berlin-data"));
    }

    @Test
    void phoneMatchesSubstring() {
        var result = findAll(phone("+493010023757"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getPhone()).contains("+493010023757"));
    }

    @Test
    void customerSinceMatchesFullYear() {
        var result = findAll(fullYear("customerSince", LocalDate.of(2020, 6, 15)));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getCustomerSince().getYear()).isEqualTo(2020));
    }

    @Test
    void customerSinceMultiValueMatchesAnyYearWithOr() {
        var result = findAll(new Or(List.of(
                fullYear("customerSince", LocalDate.of(2004, 6, 15)),
                fullYear("customerSince", LocalDate.of(2009, 6, 15)))));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getCustomerSince().getYear()).isIn(2004, 2009));
        assertThat(result).anyMatch(c -> c.getCustomerSince().getYear() == 2004);
        assertThat(result).anyMatch(c -> c.getCustomerSince().getYear() == 2009);
    }

    @Test
    void lastOrderDateMatchesFullYear() {
        var result = findAll(fullYear("lastOrderDate", LocalDate.of(2026, 3, 1)));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getLastOrderDate().getYear()).isEqualTo(2026));
    }

    @Test
    void countryMatchesSubstringCaseInsensitively() {
        var result = findAll(country("germany"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getAddress().getCountry().toLowerCase()).contains("germany"));
    }

    @Test
    void cityMatchesSubstringCaseInsensitively() {
        var result = findAll(city(Operator.CONTAINS, "berlin"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getAddress().getCity().toLowerCase()).contains("berlin"));
    }

    @Test
    void cityMultiValueMatchesAnyWithOr() {
        var result = findAll(new Or(List.of(city(Operator.CONTAINS, "berlin"), city(Operator.CONTAINS, "hamburg"))));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getAddress().getCity().toLowerCase())
                .containsAnyOf("berlin", "hamburg"));
        assertThat(result).anyMatch(c -> c.getAddress().getCity().equalsIgnoreCase("berlin"));
        assertThat(result).anyMatch(c -> c.getAddress().getCity().equalsIgnoreCase("hamburg"));
    }

    @Test
    void postalCodeMatchesSubstring() {
        var result = findAll(postalCode("10115"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getAddress().getPostalCode()).contains("10115"));
    }

    @Test
    void streetMatchesSubstringCaseInsensitively() {
        var result = findAll(street("torstrasse"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getAddress().getStreet().toLowerCase()).contains("torstrasse"));
    }

    @Test
    void houseNumberMatchesSubstring() {
        var result = findAll(houseNumber("99"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getAddress().getHouseNumber()).contains("99"));
    }

    @Test
    void creditRatingMapsToScoreBand() {
        for (CreditRating rating : CreditRating.values()) {
            var result = findAll(creditRating(rating.name()));
            assertThat(result).isNotEmpty();
            assertThat(result).allSatisfy(c -> assertThat(c.getCreditRating()).isEqualTo(rating));
        }
    }

    @Test
    void creditRatingMultiValueMatchesAnyWithOr() {
        var result = findAll(new Or(List.of(creditRating("GOOD"), creditRating("MEDIUM"))));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getCreditRating()).isIn(CreditRating.GOOD, CreditRating.MEDIUM));
        assertThat(result).anyMatch(c -> c.getCreditRating() == CreditRating.GOOD);
        assertThat(result).anyMatch(c -> c.getCreditRating() == CreditRating.MEDIUM);
    }

    @Test
    void annualRevenueClosedRangeMatchesBetween() {
        var result = findAll(new And(List.of(
                revenue(Operator.GREATER_OR_EQUAL, "50000"), revenue(Operator.LESS_OR_EQUAL, "100000"))));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getAnnualRevenue())
                .isBetween(BigDecimal.valueOf(50_000), BigDecimal.valueOf(100_000)));
    }

    @Test
    void annualRevenueOpenMinOnlyMatchesAtLeast() {
        var result = findAll(revenue(Operator.GREATER_OR_EQUAL, "200000"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getAnnualRevenue())
                .isGreaterThanOrEqualTo(BigDecimal.valueOf(200_000)));
    }

    @Test
    void annualRevenueOpenMaxOnlyMatchesAtMost() {
        var result = findAll(revenue(Operator.LESS_OR_EQUAL, "5000"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getAnnualRevenue())
                .isLessThanOrEqualTo(BigDecimal.valueOf(5_000)));
    }

    @Test
    void annualRevenueMultiValueMatchesAnyRangeWithOr() {
        var result = findAll(new Or(List.of(
                revenue(Operator.GREATER_OR_EQUAL, "200000"), revenue(Operator.LESS_OR_EQUAL, "5000"))));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(
                c.getAnnualRevenue().compareTo(BigDecimal.valueOf(200_000)) >= 0
                        || c.getAnnualRevenue().compareTo(BigDecimal.valueOf(5_000)) <= 0).isTrue());
        assertThat(result).anyMatch(c -> c.getAnnualRevenue().compareTo(BigDecimal.valueOf(200_000)) >= 0);
        assertThat(result).anyMatch(c -> c.getAnnualRevenue().compareTo(BigDecimal.valueOf(5_000)) <= 0);
    }

    @Test
    void sameFieldIsOr_differentFieldIsAnd() {
        // "(city = Berlin OR city = Hamburg) AND revenue >= 100000".
        var result = findAll(new And(List.of(
                new Or(List.of(city(Operator.CONTAINS, "Berlin"), city(Operator.CONTAINS, "Hamburg"))),
                revenue(Operator.GREATER_OR_EQUAL, "100000"))));

        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> {
            assertThat(c.getAddress().getCity()).isIn("Berlin", "Hamburg");
            assertThat(c.getAnnualRevenue()).isGreaterThanOrEqualTo(new BigDecimal("100000"));
        });
        // Both cities actually occur -> proves OR (not collapsed to a single city).
        assertThat(result).anyMatch(c -> c.getAddress().getCity().equals("Berlin"));
        assertThat(result).anyMatch(c -> c.getAddress().getCity().equals("Hamburg"));
    }

    @Test
    void singleCity() {
        var result = findAll(city(Operator.EQUALS, "Berlin"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getAddress().getCity()).isEqualTo("Berlin"));
    }

    @Test
    void rangeOnSameNumericFieldIsAnd() {
        // Two conditions on annualRevenue must be AND-combined (a range), not OR.
        var result = findAll(new And(List.of(
                revenue(Operator.GREATER_OR_EQUAL, "100000"),
                revenue(Operator.LESS_OR_EQUAL, "500000"))));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c ->
                assertThat(c.getAnnualRevenue()).isBetween(new BigDecimal("100000"), new BigDecimal("500000")));
    }

    @Test
    void twoCreditRatingsAreOr() {
        // "good OR at-risk rating" means EITHER band.
        var result = findAll(new Or(List.of(creditRating("GOOD"), creditRating("POOR"))));

        assertThat(result).isNotEmpty();
        // Every match is in one of the two bands, never the MEDIUM middle.
        assertThat(result).allSatisfy(c ->
                assertThat(c.getCreditRating()).isIn(CreditRating.GOOD, CreditRating.POOR));
        // Both bands actually occur -> proves OR (not an empty AND intersection).
        assertThat(result).anyMatch(c -> c.getCreditRating() == CreditRating.GOOD);
        assertThat(result).anyMatch(c -> c.getCreditRating() == CreditRating.POOR);
    }

    @Test
    void creditRatingCombinesWithCityAsAnd() {
        // "customers in Berlin with a good and an at-risk credit rating":
        // (city = Berlin) AND (rating = GOOD OR rating = POOR).
        var result = findAll(new And(List.of(
                city(Operator.CONTAINS, "Berlin"),
                new Or(List.of(creditRating("GOOD"), creditRating("POOR"))))));

        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> {
            assertThat(c.getAddress().getCity()).isEqualTo("Berlin");
            assertThat(c.getCreditRating()).isIn(CreditRating.GOOD, CreditRating.POOR);
        });
        assertThat(result).noneMatch(c -> c.getCreditRating() == CreditRating.MEDIUM);
    }

    @Test
    void nullRootMatchesAll() {
        var result = repository.findAll(CustomerFilterSpecifications.from(new CustomerFilter(null)));
        assertThat(result).hasSize((int) repository.count());
    }

    @Test
    void emptyAndMatchesAll() {
        var result = findAll(new And(List.of()));
        assertThat(result).hasSize((int) repository.count());
    }

    @Test
    void emptyOrMatchesAll() {
        var result = findAll(new Or(List.of()));
        assertThat(result).hasSize((int) repository.count());
    }

}
