package dev.demo.vaadin.aigridfilter.ai.filter;

import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic, fast test of the filter translation ({@link CustomerFilter} -> JPA
 * {@link Specification}) against the seeded H2 database — no LLM, no Docker. This is the safety net
 * for the field-grouping rules in {@link CustomerFilterSpecifications}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // keep configured H2 + data.sql
class CustomerFilterSpecificationsTest {

    @Autowired
    CustomerRepository repository;

    private List<Customer> findAll(FilterCriterion... criteria) {
        return repository.findAll(CustomerFilterSpecifications.from(new CustomerFilter(List.of(criteria))));
    }

    @Test
    void sameFieldIsOr_differentFieldIsAnd() {
        // "(city = Berlin OR city = Hamburg) AND revenue >= 100000" — the previously broken case.
        var result = findAll(
                new FilterCriterion("city", Operator.CONTAINS, "Berlin"),
                new FilterCriterion("city", Operator.CONTAINS, "Hamburg"),
                new FilterCriterion("annualRevenue", Operator.GREATER_OR_EQUAL, "100000"));

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
        var result = findAll(new FilterCriterion("city", Operator.EQUALS, "Berlin"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> assertThat(c.getAddress().getCity()).isEqualTo("Berlin"));
    }

    @Test
    void rangeOnSameNumericFieldIsAnd() {
        // Two criteria on annualRevenue must be AND-combined (a range), not OR.
        var result = findAll(
                new FilterCriterion("annualRevenue", Operator.GREATER_OR_EQUAL, "100000"),
                new FilterCriterion("annualRevenue", Operator.LESS_OR_EQUAL, "500000"));
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c ->
                assertThat(c.getAnnualRevenue()).isBetween(new BigDecimal("100000"), new BigDecimal("500000")));
    }

    @Test
    void twoCreditRatingsAreOr() {
        // "good AND at-risk rating" means EITHER band — the previously broken case (AND -> empty).
        var result = findAll(
                new FilterCriterion("creditRating", Operator.EQUALS, "GOOD"),
                new FilterCriterion("creditRating", Operator.EQUALS, "POOR"));

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
        // Mirrors "customers in Berlin with a good and an at-risk credit rating":
        // (city = Berlin) AND (rating = GOOD OR rating = POOR).
        var result = findAll(
                new FilterCriterion("city", Operator.CONTAINS, "Berlin"),
                new FilterCriterion("creditRating", Operator.EQUALS, "GOOD"),
                new FilterCriterion("creditRating", Operator.EQUALS, "POOR"));

        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> {
            assertThat(c.getAddress().getCity()).isEqualTo("Berlin");
            assertThat(c.getCreditRating()).isIn(CreditRating.GOOD, CreditRating.POOR);
        });
        assertThat(result).noneMatch(c -> c.getCreditRating() == CreditRating.MEDIUM);
    }

    @Test
    void notEqualsExcludesCity() {
        var result = findAll(new FilterCriterion("city", Operator.NOT_EQUALS, "Berlin"));
        assertThat(result).isNotEmpty();
        assertThat(result).noneMatch(c -> "Berlin".equalsIgnoreCase(c.getAddress().getCity()));
    }

    @Test
    void emptyFilterMatchesAll() {
        var result = repository.findAll(CustomerFilterSpecifications.from(new CustomerFilter(List.of())));
        assertThat(result).hasSize((int) repository.count());
    }
}
