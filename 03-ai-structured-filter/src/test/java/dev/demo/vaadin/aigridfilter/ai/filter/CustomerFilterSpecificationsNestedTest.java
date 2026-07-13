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

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic, fast tests of the filter translation for tree shapes that {@code 02-ai-agent-filter}'s
 * flat {@code CustomerSearchCriteria} model cannot express at all — negation ({@code NOT}/{@code NOT_EQUALS}),
 * cross-field {@code OR}, and arbitrary AND/OR nesting — so they have no counterpart to compare against
 * there. Split out from {@link CustomerFilterSpecificationsTest}, which holds the cases both modules share.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // keep configured H2 + data.sql
class CustomerFilterSpecificationsNestedTest {

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
    void notEqualsExcludesCity() {
        var result = findAll(city(Operator.NOT_EQUALS, "Berlin"));
        assertThat(result).isNotEmpty();
        assertThat(result).noneMatch(c -> "Berlin".equalsIgnoreCase(c.getAddress().getCity()));
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
