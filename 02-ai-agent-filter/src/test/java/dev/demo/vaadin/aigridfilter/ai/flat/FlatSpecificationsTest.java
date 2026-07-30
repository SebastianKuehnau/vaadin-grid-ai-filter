package dev.demo.vaadin.aigridfilter.ai.flat;

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
 * Deterministic, fast test of variant 02(a)'s filter translation ({@link FlatCriteria} -> JPA
 * {@code Specification}) against the seeded H2 database — no LLM, no Docker. One test per field
 * group, plus the AND-across-fields combination and the null-matches-all cases.
 * <p>
 * The last two tests pin down what 02(a) deliberately <em>cannot</em> do (only a whole-year date
 * match, only a revenue minimum), so its ceiling is asserted rather than just documented.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // keep configured H2 + data.sql
class FlatSpecificationsTest {

    @Autowired
    CustomerRepository repository;

    private List<Customer> findAll(FlatCriteria criteria) {
        return repository.findAll(FlatSpecifications.from(criteria));
    }

    /** Base criteria with every field unset; individual tests set the one field they exercise. */
    private static Builder criteria() {
        return new Builder();
    }

    /** Tiny builder so each test only names the field it cares about (13 positional nulls otherwise). */
    private static final class Builder {
        private String companyName;
        private String contactName;
        private String email;
        private String phone;
        private LocalDate customerSince;
        private LocalDate lastOrderDate;
        private String country;
        private String city;
        private String postalCode;
        private String street;
        private String houseNumber;
        private CreditRating creditRating;
        private BigDecimal annualRevenue;

        Builder companyName(String value) { this.companyName = value; return this; }
        Builder contactName(String value) { this.contactName = value; return this; }
        Builder email(String value) { this.email = value; return this; }
        Builder phone(String value) { this.phone = value; return this; }
        Builder customerSince(LocalDate value) { this.customerSince = value; return this; }
        Builder lastOrderDate(LocalDate value) { this.lastOrderDate = value; return this; }
        Builder country(String value) { this.country = value; return this; }
        Builder city(String value) { this.city = value; return this; }
        Builder postalCode(String value) { this.postalCode = value; return this; }
        Builder street(String value) { this.street = value; return this; }
        Builder houseNumber(String value) { this.houseNumber = value; return this; }
        Builder creditRating(CreditRating value) { this.creditRating = value; return this; }
        Builder annualRevenue(BigDecimal value) { this.annualRevenue = value; return this; }

        FlatCriteria build() {
            return new FlatCriteria(companyName, contactName, email, phone, customerSince, lastOrderDate,
                    country, city, postalCode, street, houseNumber, creditRating, annualRevenue);
        }
    }

    @Test
    void companyNameMatchesSubstringCaseInsensitively() {
        var result = findAll(criteria().companyName("berlin").build());
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getCompanyName().toLowerCase()).contains("berlin"));
    }

    @Test
    void contactNameMatchesSubstringCaseInsensitively() {
        var result = findAll(criteria().contactName("laura").build());
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getContactName().toLowerCase()).contains("laura"));
    }

    @Test
    void emailMatchesSubstringCaseInsensitively() {
        var result = findAll(criteria().email("berlin-data").build());
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getEmail().toLowerCase()).contains("berlin-data"));
    }

    @Test
    void phoneMatchesSubstring() {
        var result = findAll(criteria().phone("+493010023757").build());
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getPhone()).contains("+493010023757"));
    }

    @Test
    void customerSinceMatchesFullYear() {
        var result = findAll(criteria().customerSince(LocalDate.of(2020, 6, 15)).build());
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getCustomerSince().getYear()).isEqualTo(2020));
    }

    @Test
    void lastOrderDateMatchesFullYear() {
        var result = findAll(criteria().lastOrderDate(LocalDate.of(2026, 3, 1)).build());
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getLastOrderDate().getYear()).isEqualTo(2026));
    }

    @Test
    void addressFieldsMatchSubstring() {
        assertThat(findAll(criteria().country("germany").build())).isNotEmpty()
                .allSatisfy(c -> assertThat(c.getAddress().getCountry().toLowerCase()).contains("germany"));
        assertThat(findAll(criteria().city("berlin").build())).isNotEmpty()
                .allSatisfy(c -> assertThat(c.getAddress().getCity().toLowerCase()).contains("berlin"));
        assertThat(findAll(criteria().postalCode("10115").build())).isNotEmpty()
                .allSatisfy(c -> assertThat(c.getAddress().getPostalCode()).contains("10115"));
        assertThat(findAll(criteria().street("torstrasse").build())).isNotEmpty()
                .allSatisfy(c -> assertThat(c.getAddress().getStreet().toLowerCase()).contains("torstrasse"));
        assertThat(findAll(criteria().houseNumber("99").build())).isNotEmpty()
                .allSatisfy(c -> assertThat(c.getAddress().getHouseNumber()).contains("99"));
    }

    @Test
    void creditRatingMapsToScoreBand() {
        for (CreditRating rating : CreditRating.values()) {
            var result = findAll(criteria().creditRating(rating).build());
            assertThat(result).isNotEmpty();
            assertThat(result).allSatisfy(c -> assertThat(c.getCreditRating()).isEqualTo(rating));
        }
    }

    @Test
    void annualRevenueMatchesMinimum() {
        var result = findAll(criteria().annualRevenue(BigDecimal.valueOf(200_000)).build());
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getAnnualRevenue())
                .isGreaterThanOrEqualTo(BigDecimal.valueOf(200_000)));
    }

    @Test
    void allGivenFieldsCombineWithAnd() {
        var result = findAll(criteria().city("berlin").creditRating(CreditRating.MEDIUM).build());
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> {
            assertThat(c.getAddress().getCity().toLowerCase()).contains("berlin");
            assertThat(c.getCreditRating()).isEqualTo(CreditRating.MEDIUM);
        });
        // Sanity check: some Berlin customers are NOT MEDIUM -> proves this is a real AND, not a
        // no-op filter that happens to match everything in Berlin.
        assertThat(findAll(criteria().city("berlin").build()))
                .anyMatch(c -> c.getCreditRating() != CreditRating.MEDIUM);
    }

    @Test
    void nullCriteriaMatchesAll() {
        assertThat(findAll(null)).hasSize((int) repository.count());
    }

    @Test
    void allNullFieldsMatchAll() {
        assertThat(findAll(criteria().build())).hasSize((int) repository.count());
    }

    @Test
    void cannotExpressADayLevelDateBound() {
        // 02(a)'s ceiling: a date always means its whole calendar year, so "since 2024-06-01" cannot
        // be narrowed down - customers from earlier in 2024 stay in the result.
        var result = findAll(criteria().customerSince(LocalDate.of(2024, 6, 1)).build());
        assertThat(result).isNotEmpty();
        assertThat(result).anyMatch(c -> c.getCustomerSince().isBefore(LocalDate.of(2024, 6, 1)));
    }

    @Test
    void cannotExpressARevenueUpperBound() {
        // 02(a)'s ceiling: annualRevenue is a minimum, so a range or an upper bound is out of reach -
        // asking for 100000 keeps everything above 200000 in the result as well.
        var result = findAll(criteria().annualRevenue(BigDecimal.valueOf(100_000)).build());
        assertThat(result).isNotEmpty();
        assertThat(result).anyMatch(c -> c.getAnnualRevenue().compareTo(BigDecimal.valueOf(200_000)) > 0);
    }
}
