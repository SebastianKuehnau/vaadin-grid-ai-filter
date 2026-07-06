package dev.demo.vaadin.aigridfilter.ai.filter;

import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.And;
import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.Condition;
import dev.demo.vaadin.aigridfilter.ai.filter.FilterNode.Not;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic, fast test of the filter translation ({@link CustomerFilter} -> JPA
 * {@link Specification}) against the seeded H2 database — no LLM, no Docker. This is the safety net
 * for the recursive tree traversal in {@link CustomerFilterSpecifications}.
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
    void notEqualsExcludesCity() {
        var result = findAll(city(Operator.NOT_EQUALS, "Berlin"));
        assertThat(result).isNotEmpty();
        assertThat(result).noneMatch(c -> "Berlin".equalsIgnoreCase(c.getAddress().getCity()));
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

    @Test
    void crossFieldOr() {
        // "city = Berlin OR revenue >= 100000" — impossible with the old flat structure.
        var result = findAll(new Or(List.of(
                city(Operator.EQUALS, "Berlin"),
                revenue(Operator.GREATER_OR_EQUAL, "100000"))));

        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c ->
                assertThat("Berlin".equals(c.getAddress().getCity())
                        || c.getAnnualRevenue().compareTo(new BigDecimal("100000")) >= 0).isTrue());
        // Both alternatives actually contribute matches that the other alone wouldn't.
        assertThat(result).anyMatch(c -> "Berlin".equals(c.getAddress().getCity())
                && c.getAnnualRevenue().compareTo(new BigDecimal("100000")) < 0);
        assertThat(result).anyMatch(c -> !"Berlin".equals(c.getAddress().getCity())
                && c.getAnnualRevenue().compareTo(new BigDecimal("100000")) >= 0);
    }

    @Test
    void nestedOrsCombinedWithAnd() {
        // (city=Berlin OR city=Hamburg) AND (revenue>=500000 OR creditRating=GOOD)
        var result = findAll(new And(List.of(
                new Or(List.of(city(Operator.CONTAINS, "Berlin"), city(Operator.CONTAINS, "Hamburg"))),
                new Or(List.of(revenue(Operator.GREATER_OR_EQUAL, "500000"), creditRating("GOOD"))))));

        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(c -> {
            assertThat(c.getAddress().getCity()).isIn("Berlin", "Hamburg");
            assertThat(c.getAnnualRevenue().compareTo(new BigDecimal("500000")) >= 0
                    || c.getCreditRating() == CreditRating.GOOD).isTrue();
        });
    }

    @Test
    void notNegatesGroup() {
        // NOT (city=Berlin AND revenue < 100000)
        var negated = new Not(new And(List.of(
                city(Operator.EQUALS, "Berlin"),
                revenue(Operator.LESS_OR_EQUAL, "100000"))));
        var result = findAll(negated);

        assertThat(result).isNotEmpty();
        assertThat(result).noneMatch(c -> "Berlin".equals(c.getAddress().getCity())
                && c.getAnnualRevenue().compareTo(new BigDecimal("100000")) <= 0);
    }
}
