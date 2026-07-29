package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.browserless.ViewPackages;
import com.vaadin.browserless.internal.MockVaadin;
import com.vaadin.flow.component.grid.GridTester;
import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.ai.operator.FieldCriterion;
import dev.demo.vaadin.aigridfilter.ai.operator.Operator;
import dev.demo.vaadin.aigridfilter.ai.operator.OperatorCriteria;
import dev.demo.vaadin.aigridfilter.ai.operator.OperatorSpecifications;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Browserless UI test of variant 02(b)'s {@link OperatorCustomerListView} — the sibling of
 * {@link FlatCustomerListViewBrowserlessTest}, with a fake {@link CustomerSearchAgent} instead of a
 * real model. {@link #NEGATED_QUERY} goes through the real {@link OperatorSpecifications} with a
 * negated condition, so 02(b)'s distinguishing capability is exercised end to end through the UI and
 * not only at the unit-test level.
 */
@SpringBootTest
@ViewPackages(classes = OperatorCustomerListView.class)
class OperatorCustomerListViewBrowserlessTest extends SpringBrowserlessTest {

    private static final String NEGATED_QUERY = "negated query";

    @Autowired
    private CustomerRepository customerRepository;

    @TestConfiguration
    static class FakeSearchAgentConfig {

        @Bean
        @Primary
        @Qualifier("operatorSearchAgent")
        CustomerSearchAgent fakeOperatorSearchAgent() {
            return query -> {
                if (NEGATED_QUERY.equals(query)) {
                    return OperatorSpecifications.from(new OperatorCriteria(null, null, null, null, null, null,
                            null, new FieldCriterion<>("Berlin", Operator.CONTAINS, true),
                            null, null, null, null, null));
                }
                return (root, criteriaQuery, criteriaBuilder) ->
                        criteriaBuilder.equal(root.get("companyName"), "Berlin Data Works");
            };
        }
    }

    @Test
    void typingQueryNarrowsGridToFakeAgentsResult() {
        OperatorCustomerListView view = navigate(OperatorCustomerListView.class);
        test(view.filterField).setValue("anything - the fake agent ignores the actual text");

        GridTester<?, Customer> grid = test(view.grid);
        // pollInSameThread(): MockVaadin.runUIQueue() needs UI.getCurrent(), a ThreadLocal only
        // set on this test thread, not on Awaitility's default background poll thread.
        await().pollInSameThread().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            MockVaadin.runUIQueue();
            assertThat(grid.size()).isEqualTo(1);
        });
        assertThat(grid.getRow(0).getCompanyName()).isEqualTo("Berlin Data Works");
    }

    @Test
    void negatedQueryExcludesMatchingRowsFromTheGrid() {
        OperatorCustomerListView view = navigate(OperatorCustomerListView.class);
        test(view.filterField).setValue(NEGATED_QUERY);

        GridTester<?, Customer> grid = test(view.grid);
        await().pollInSameThread().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            MockVaadin.runUIQueue();
            assertThat(grid.size()).isLessThan((int) customerRepository.count());
        });
        assertThat(grid.size()).isGreaterThan(0);
        for (int i = 0; i < grid.size(); i++) {
            assertThat(grid.getRow(i).getAddress().getCity()).doesNotContainIgnoringCase("berlin");
        }
    }

    @Test
    void blankQueryResetsToAllRows() {
        OperatorCustomerListView view = navigate(OperatorCustomerListView.class);
        test(view.filterField).setValue("narrow it first");

        GridTester<?, Customer> grid = test(view.grid);
        await().pollInSameThread().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            MockVaadin.runUIQueue();
            assertThat(grid.size()).isEqualTo(1);
        });

        test(view.filterField).setValue("");

        assertThat(grid.size()).isEqualTo((int) customerRepository.count());
    }
}
