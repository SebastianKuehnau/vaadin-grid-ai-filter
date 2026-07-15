package dev.demo.vaadin.aigridfilter.ai.filter;

import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic, fast test of the one filter capability {@code 02-ai-agent-filter}'s flat
 * {@code CustomerSearchCriteria} model cannot express at all — negation ({@link Condition#negate()})
 * — so it has no counterpart there. Split out from {@link CustomerFilterSpecificationsTest}, which
 * holds the cases both modules share.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // keep configured H2 + data.sql
class CustomerFilterSpecificationsExtraTest {

    @Autowired
    CustomerRepository repository;

    private List<Customer> findAll(Condition condition) {
        return repository.findAll(CustomerFilterSpecifications.from(new CustomerFilter(List.of(condition))));
    }

    @Test
    void negateExcludesCity() {
        var result = findAll(new Condition("city", Operator.EQUALS, List.of("Berlin"), true));
        assertThat(result).isNotEmpty();
        assertThat(result).noneMatch(c -> "Berlin".equalsIgnoreCase(c.getAddress().getCity()));
    }
}
