package dev.demo.vaadin.aigridfilter.ai.filter;

import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic, fast test of the filter translation ({@link CustomerSearchCriteria} -> JPA
 * {@code Specification}) against the seeded H2 database — no LLM, no Docker. One test per
 * predicate/field, plus the AND-combination and null-matches-all cases.
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
        return new CustomerSearchCriteria(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static CustomerSearchCriteria companyName(String value) {
        return new CustomerSearchCriteria(value, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static CustomerSearchCriteria contactName(String value) {
        return new CustomerSearchCriteria(null, value, null, null, null, null, null, null, null, null, null, null);
    }

    private static CustomerSearchCriteria email(String value) {
        return new CustomerSearchCriteria(null, null, value, null, null, null, null, null, null, null, null, null);
    }

    private static CustomerSearchCriteria phone(String value) {
        return new CustomerSearchCriteria(null, null, null, value, null, null, null, null, null, null, null, null);
    }

    private static CustomerSearchCriteria customerSince(LocalDate value) {
        return new CustomerSearchCriteria(null, null, null, null, value, null, null, null, null, null, null, null);
    }

    private static CustomerSearchCriteria lastOrderDate(LocalDate value) {
        return new CustomerSearchCriteria(null, null, null, null, null, value, null, null, null, null, null, null);
    }

    private static CustomerSearchCriteria country(String value) {
        return new CustomerSearchCriteria(null, null, null, null, null, null, value, null, null, null, null, null);
    }

    private static CustomerSearchCriteria city(String value) {
        return new CustomerSearchCriteria(null, null, null, null, null, null, null, value, null, null, null, null);
    }

    private static CustomerSearchCriteria postalCode(String value) {
        return new CustomerSearchCriteria(null, null, null, null, null, null, null, null, value, null, null, null);
    }

    private static CustomerSearchCriteria street(String value) {
        return new CustomerSearchCriteria(null, null, null, null, null, null, null, null, null, value, null, null);
    }

    private static CustomerSearchCriteria houseNumber(String value) {
        return new CustomerSearchCriteria(null, null, null, null, null, null, null, null, null, null, value, null);
    }

    private static CustomerSearchCriteria creditRating(CreditRating value) {
        return new CustomerSearchCriteria(null, null, null, null, null, null, null, null, null, null, null, value);
    }

    private static CustomerSearchCriteria cityAndCreditRating(String city, CreditRating rating) {
        return new CustomerSearchCriteria(null, null, null, null, null, null, null, city, null, null, null, rating);
    }

    @Test
    void companyNameMatchesSubstringCaseInsensitively() {
        var result = findAll(companyName("berlin"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getCompanyName().toLowerCase()).contains("berlin"));
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
    void customerSinceMatchesOnOrAfter() {
        LocalDate cutoff = LocalDate.of(2020, 1, 1);
        var result = findAll(customerSince(cutoff));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getCustomerSince()).isAfterOrEqualTo(cutoff));
    }

    @Test
    void lastOrderDateMatchesOnOrAfter() {
        LocalDate cutoff = LocalDate.of(2026, 1, 1);
        var result = findAll(lastOrderDate(cutoff));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getLastOrderDate()).isAfterOrEqualTo(cutoff));
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
