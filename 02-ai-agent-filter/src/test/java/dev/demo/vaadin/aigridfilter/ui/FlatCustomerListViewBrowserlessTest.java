package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.browserless.ViewPackages;
import com.vaadin.browserless.internal.MockVaadin;
import com.vaadin.flow.component.grid.GridTester;
import dev.demo.vaadin.aigridfilter.ai.CustomerSearchAgent;
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
 * Browserless UI test of variant 02(a)'s {@link FlatCustomerListView}, following
 * {@code 01-non-ai-filter}'s {@code SpringBrowserlessTest} pattern. Wires a fake, deterministic
 * {@link CustomerSearchAgent} bean instead of calling a real model, so this test never talks to an
 * LLM. The real search runs off the UI thread ({@code CompletableFuture} + {@code ui.access(...)}), so
 * grid assertions after a non-blank query use Awaitility rather than asserting immediately.
 * <p>
 * The fake carries the {@code flatSearchAgent} qualifier of the real bean and is {@code @Primary},
 * so it wins for this view's injection point without touching variant 02(b)'s wiring.
 */
@SpringBootTest
@ViewPackages(classes = FlatCustomerListView.class)
class FlatCustomerListViewBrowserlessTest extends SpringBrowserlessTest {

    @Autowired
    private CustomerRepository customerRepository;

    @TestConfiguration
    static class FakeSearchAgentConfig {

        /** Ignores the query text and always narrows the grid to a single, known company. */
        @Bean
        @Primary
        @Qualifier("flatSearchAgent")
        CustomerSearchAgent fakeFlatSearchAgent() {
            return _ -> (root, criteriaQuery, criteriaBuilder) ->
                    criteriaBuilder.equal(root.get("companyName"), "Berlin Data Works");
        }
    }

    @Test
    void typingQueryNarrowsGridToFakeAgentsResult() {
        FlatCustomerListView view = navigate(FlatCustomerListView.class);
        test(view.filterField).setValue("anything - the fake agent ignores the actual text");

        GridTester<?, Customer> grid = test(view.grid);
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
    void blankQueryResetsToAllRows() {
        FlatCustomerListView view = navigate(FlatCustomerListView.class);
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
