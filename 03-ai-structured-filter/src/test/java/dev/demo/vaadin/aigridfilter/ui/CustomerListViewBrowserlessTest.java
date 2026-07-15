package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.browserless.ViewPackages;
import com.vaadin.browserless.internal.MockVaadin;
import com.vaadin.flow.component.grid.GridTester;
import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
import dev.demo.vaadin.aigridfilter.ai.filter.Condition;
import dev.demo.vaadin.aigridfilter.ai.filter.CustomerFilter;
import dev.demo.vaadin.aigridfilter.ai.filter.CustomerFilterSpecifications;
import dev.demo.vaadin.aigridfilter.ai.filter.Operator;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Browserless UI test of {@link CustomerListView}, following {@code 02-ai-agent-filter}'s
 * equivalent test. Wires a fake, deterministic {@link CustomerSearchAgent} bean instead of
 * calling a real model, so this test never talks to an LLM. The real search runs off the UI
 * thread ({@code CompletableFuture} + {@code ui.access(...)}), so grid assertions after a
 * non-blank query use Awaitility rather than asserting immediately.
 */
@SpringBootTest
@ViewPackages(classes = CustomerListView.class)
class CustomerListViewBrowserlessTest extends SpringBrowserlessTest {

    private static final String MULTI_VALUE_QUERY = "multi-value query";

    @Autowired
    private CustomerRepository customerRepository;

    @TestConfiguration
    static class FakeSearchAgentConfig {

        /**
         * Ignores the actual query text and returns a fixed result, except for
         * {@link #MULTI_VALUE_QUERY}, which goes through the real {@link CustomerFilterSpecifications}
         * with one {@code companyName} condition holding two values (OR-within-field), so that
         * translation is exercised end to end through the UI, not just at the unit-test level. Same
         * fixture as {@code 02-ai-agent-filter}'s equivalent test, expressed via {@link CustomerFilter}'s
         * flat conditions list instead of a criteria record.
         */
        @Bean
        @Primary
        CustomerSearchAgent fakeSearchAgent() {
            return query -> {
                if (MULTI_VALUE_QUERY.equals(query)) {
                    return CustomerFilterSpecifications.from(new CustomerFilter(List.of(new Condition(
                            "companyName", Operator.EQUALS,
                            List.of("Berlin Data Works", "Hamburg Retail Group"), false))));
                }
                return (root, criteriaQuery, criteriaBuilder) ->
                        criteriaBuilder.equal(root.get("companyName"), "Berlin Data Works");
            };
        }
    }

    @Test
    void typingQueryNarrowsGridToFakeAgentsResult() {
        CustomerListView view = navigate(CustomerListView.class);
        test(view.getFilterField()).setValue("anything - the fake agent ignores the actual text");

        GridTester<?, Customer> grid = test(view.getGrid());
        // pollInSameThread(): MockVaadin.runUIQueue() needs UI.getCurrent(), a ThreadLocal only
        // set on this test thread, not on Awaitility's default background poll thread. It flushes
        // the ui.access(...) commands the background search thread queued.
        await().pollInSameThread().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            MockVaadin.runUIQueue();
            assertThat(grid.size()).isEqualTo(1);
        });
        assertThat(grid.getRow(0).getCompanyName()).isEqualTo("Berlin Data Works");
    }

    @Test
    void multiValueQueryNarrowsGridToOrMatchedRows() {
        CustomerListView view = navigate(CustomerListView.class);
        test(view.getFilterField()).setValue(MULTI_VALUE_QUERY);

        GridTester<?, Customer> grid = test(view.getGrid());
        await().pollInSameThread().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            MockVaadin.runUIQueue();
            assertThat(grid.size()).isEqualTo(2);
        });
        assertThat(List.of(grid.getRow(0).getCompanyName(), grid.getRow(1).getCompanyName()))
                .containsExactlyInAnyOrder("Berlin Data Works", "Hamburg Retail Group");
    }

    @Test
    void blankQueryResetsToAllRows() {
        CustomerListView view = navigate(CustomerListView.class);
        test(view.getFilterField()).setValue("narrow it first");

        GridTester<?, Customer> grid = test(view.getGrid());
        // pollInSameThread(): MockVaadin.runUIQueue() needs UI.getCurrent(), a ThreadLocal only
        // set on this test thread, not on Awaitility's default background poll thread. It flushes
        // the ui.access(...) commands the background search thread queued.
        await().pollInSameThread().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            MockVaadin.runUIQueue();
            assertThat(grid.size()).isEqualTo(1);
        });

        test(view.getFilterField()).setValue("");

        assertThat(grid.size()).isEqualTo((int) customerRepository.count());
    }
}
