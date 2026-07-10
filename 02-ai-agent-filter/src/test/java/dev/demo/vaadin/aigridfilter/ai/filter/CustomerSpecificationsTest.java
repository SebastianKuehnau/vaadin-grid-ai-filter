package dev.demo.vaadin.aigridfilter.ai.filter;

import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic, fast test of the filter translation ({@link CustomerSearchCriteria} -> JPA
 * {@code Specification}) against the seeded H2 database — no LLM, no Docker. One test per
 * predicate/field for the single-value case, plus multi-value (OR-within-field), the
 * AND-across-fields combination, and the null-matches-all cases.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // keep configured H2 + data.sql
class CustomerSpecificationsTest {

    @Autowired
    CustomerRepository repository;

    private List<Customer> findAll(CustomerSearchCriteria criteria) {
        return repository.findAll(CustomerSpecifications.from(criteria));
    }

    private static CustomerSearchCriteria allNull() {
        return new CustomerSearchCriteria(
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static CustomerSearchCriteria companyName(String... values) {
        return new CustomerSearchCriteria(
                List.of(values), null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static CustomerSearchCriteria contactName(String value) {
        return new CustomerSearchCriteria(
                null, List.of(value), null, null, null, null, null, null, null, null, null, null, null);
    }

    private static CustomerSearchCriteria email(String value) {
        return new CustomerSearchCriteria(
                null, null, List.of(value), null, null, null, null, null, null, null, null, null, null);
    }

    private static CustomerSearchCriteria phone(String value) {
        return new CustomerSearchCriteria(
                null, null, null, List.of(value), null, null, null, null, null, null, null, null, null);
    }

    private static CustomerSearchCriteria customerSince(LocalDate... values) {
        return new CustomerSearchCriteria(
                null, null, null, null, List.of(values), null, null, null, null, null, null, null, null);
    }

    private static CustomerSearchCriteria lastOrderDate(LocalDate value) {
        return new CustomerSearchCriteria(
                null, null, null, null, null, List.of(value), null, null, null, null, null, null, null);
    }

    private static CustomerSearchCriteria country(String value) {
        return new CustomerSearchCriteria(
                null, null, null, null, null, null, List.of(value), null, null, null, null, null, null);
    }

    private static CustomerSearchCriteria city(String... values) {
        return new CustomerSearchCriteria(
                null, null, null, null, null, null, null, List.of(values), null, null, null, null, null);
    }

    private static CustomerSearchCriteria postalCode(String value) {
        return new CustomerSearchCriteria(
                null, null, null, null, null, null, null, null, List.of(value), null, null, null, null);
    }

    private static CustomerSearchCriteria street(String value) {
        return new CustomerSearchCriteria(
                null, null, null, null, null, null, null, null, null, List.of(value), null, null, null);
    }

    private static CustomerSearchCriteria houseNumber(String value) {
        return new CustomerSearchCriteria(
                null, null, null, null, null, null, null, null, null, null, List.of(value), null, null);
    }

    private static CustomerSearchCriteria creditRating(CreditRating... values) {
        return new CustomerSearchCriteria(
                null, null, null, null, null, null, null, null, null, null, null, List.of(values), null);
    }

    private static CustomerSearchCriteria annualRevenue(RevenueRange... values) {
        return new CustomerSearchCriteria(
                null, null, null, null, null, null, null, null, null, null, null, null, List.of(values));
    }

    private static CustomerSearchCriteria cityAndCreditRating(String city, CreditRating... ratings) {
        return new CustomerSearchCriteria(
                null, null, null, null, null, null, null, List.of(city), null, null, null, List.of(ratings), null);
    }

    private static CustomerSearchCriteria citiesAndCreditRatings(List<String> cities, List<CreditRating> ratings) {
        return new CustomerSearchCriteria(
                null, null, null, null, null, null, null, cities, null, null, null, ratings, null);
    }

    @Test
    void companyNameMatchesSubstringCaseInsensitively() {
        var result = findAll(companyName("berlin"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getCompanyName().toLowerCase()).contains("berlin"));
    }

    @Test
    void companyNameMultiValueMatchesAnyWithOr() {
        var result = findAll(companyName("berlin", "hamburg"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getCompanyName().toLowerCase())
                .containsAnyOf("berlin", "hamburg"));
        // Sanity check: both terms individually contribute matches, so this is a real OR.
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
        var result = findAll(customerSince(LocalDate.of(2020, 6, 15)));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getCustomerSince().getYear()).isEqualTo(2020));
    }

    @Test
    void customerSinceMultiValueMatchesAnyYearWithOr() {
        var result = findAll(customerSince(LocalDate.of(2004, 6, 15), LocalDate.of(2009, 6, 15)));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getCustomerSince().getYear()).isIn(2004, 2009));
        assertThat(result).anyMatch(c -> c.getCustomerSince().getYear() == 2004);
        assertThat(result).anyMatch(c -> c.getCustomerSince().getYear() == 2009);
    }

    @Test
    void lastOrderDateMatchesFullYear() {
        var result = findAll(lastOrderDate(LocalDate.of(2026, 3, 1)));
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
        var result = findAll(city("berlin"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getAddress().getCity().toLowerCase()).contains("berlin"));
    }

    @Test
    void cityMultiValueMatchesAnyWithOr() {
        var result = findAll(city("berlin", "hamburg"));
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
            var result = findAll(creditRating(rating));
            assertThat(result).isNotEmpty();
            assertThat(result).allSatisfy(c -> assertThat(c.getCreditRating()).isEqualTo(rating));
        }
    }

    @Test
    void creditRatingMultiValueMatchesAnyWithOr() {
        var result = findAll(creditRating(CreditRating.GOOD, CreditRating.MEDIUM));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getCreditRating()).isIn(CreditRating.GOOD, CreditRating.MEDIUM));
        assertThat(result).anyMatch(c -> c.getCreditRating() == CreditRating.GOOD);
        assertThat(result).anyMatch(c -> c.getCreditRating() == CreditRating.MEDIUM);
    }

    @Test
    void annualRevenueClosedRangeMatchesBetween() {
        var result = findAll(annualRevenue(new RevenueRange(BigDecimal.valueOf(50_000), BigDecimal.valueOf(100_000))));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getAnnualRevenue())
                .isBetween(BigDecimal.valueOf(50_000), BigDecimal.valueOf(100_000)));
    }

    @Test
    void annualRevenueOpenMinOnlyMatchesAtLeast() {
        var result = findAll(annualRevenue(new RevenueRange(BigDecimal.valueOf(200_000), null)));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getAnnualRevenue())
                .isGreaterThanOrEqualTo(BigDecimal.valueOf(200_000)));
    }

    @Test
    void annualRevenueOpenMaxOnlyMatchesAtMost() {
        var result = findAll(annualRevenue(new RevenueRange(null, BigDecimal.valueOf(5_000))));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getAnnualRevenue())
                .isLessThanOrEqualTo(BigDecimal.valueOf(5_000)));
    }

    @Test
    void annualRevenueMultiValueMatchesAnyRangeWithOr() {
        var result = findAll(annualRevenue(
                new RevenueRange(BigDecimal.valueOf(200_000), null),
                new RevenueRange(null, BigDecimal.valueOf(5_000))));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(
                c.getAnnualRevenue().compareTo(BigDecimal.valueOf(200_000)) >= 0
                        || c.getAnnualRevenue().compareTo(BigDecimal.valueOf(5_000)) <= 0).isTrue());
        assertThat(result).anyMatch(c -> c.getAnnualRevenue().compareTo(BigDecimal.valueOf(200_000)) >= 0);
        assertThat(result).anyMatch(c -> c.getAnnualRevenue().compareTo(BigDecimal.valueOf(5_000)) <= 0);
    }

    @Test
    void allGivenFieldsCombineWithAnd() {
        var result = findAll(cityAndCreditRating("berlin", CreditRating.MEDIUM));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> {
            assertThat(c.getAddress().getCity().toLowerCase()).contains("berlin");
            assertThat(c.getCreditRating()).isEqualTo(CreditRating.MEDIUM);
        });
        // Sanity check: some Berlin customers are NOT MEDIUM -> proves this is a real AND, not a
        // no-op filter that happens to match everything in Berlin.
        assertThat(findAll(city("berlin"))).anyMatch(c -> c.getCreditRating() != CreditRating.MEDIUM);
    }

    @Test
    void multiValueFieldsCombineWithAndAcrossFields() {
        var result = findAll(citiesAndCreditRatings(
                List.of("Berlin", "Hamburg"), List.of(CreditRating.GOOD, CreditRating.MEDIUM)));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> {
            assertThat(c.getAddress().getCity()).isIn("Berlin", "Hamburg");
            assertThat(c.getCreditRating()).isIn(CreditRating.GOOD, CreditRating.MEDIUM);
        });
        // Sanity check: some Berlin/Hamburg customers are POOR -> proves the AND is real.
        assertThat(findAll(city("berlin", "hamburg")))
                .anyMatch(c -> c.getCreditRating() == CreditRating.POOR);
    }

    @Test
    void nullCriteriaMatchesAll() {
        var result = findAll(null);
        assertThat(result).hasSize((int) repository.count());
    }

    @Test
    void allNullFieldsMatchAll() {
        var result = findAll(allNull());
        assertThat(result).hasSize((int) repository.count());
    }
}
