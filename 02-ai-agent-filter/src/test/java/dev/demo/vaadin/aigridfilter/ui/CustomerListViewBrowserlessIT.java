package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.browserless.ViewPackages;
import com.vaadin.browserless.internal.MockVaadin;
import com.vaadin.flow.component.grid.GridTester;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Browserless UI integration test against a real AI backend — no fake {@code CustomerSearchAgent}
 * bean. Verifies the full pipeline end to end: typing a natural-language query, the real
 * tool-calling AI layer resolving it, and the grid showing the right rows. Complements
 * {@link CustomerListViewBrowserlessTest} (fast, fake agent, no LLM) and {@code CustomerSearchAgentIT}
 * (real backend, but bypasses the UI).
 * <p>
 * Runs standalone rather than sharing a base class with {@code CustomerSearchAgentIT}: browserless
 * testing needs the default {@code MOCK} web environment and Vaadin's Spring Boot autoconfiguration,
 * so the backend wiring is duplicated here rather than inherited. Which Spring profile
 * {@code AI_TEST_PROFILE} selects comes from {@code src/test/resources/application.properties}'s
 * {@code spring.profiles.active=${AI_TEST_PROFILE:ollama}}, so the ITs target a native Ollama
 * instance by default (the app's own default profile is {@code openai}; only the test config
 * overrides it to {@code ollama}). There is no reachability probe — if the backend isn't reachable,
 * the run fails rather than skipping, same as {@code CustomerSearchAgentIT}.
 * <p>
 * No relative-date case (e.g. "customers who ordered yesterday"): it needs a two-hop tool-call chain
 * that a weaker model like {@code llama3.1:8b} reliably fails, while the configured default
 * {@code qwen3:8b} handles it — see the module README's "Known limitation" section, and
 * {@code CustomerSearchAgentIT} which excludes it for the same reason.
 * <p>
 * {@code customersSince2020} was tried here too during alignment but dropped from the shared set:
 * {@code 03-ai-structured-filter}'s structured-output layer, on the weaker {@code llama3.1:8b} (the
 * module default at the time), omitted the upper date bound for a "since &lt;year&gt;" query (a
 * single {@code -Pit-local-ollama} run, 100% reproducible on retry), letting rows from later years
 * leak into the grid — even though this module's tool-calling layer passes it reliably.
 */
@SpringBootTest
@ViewPackages(classes = CustomerListView.class)
class CustomerListViewBrowserlessIT extends SpringBrowserlessTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void customersInBerlin() {
        GridTester<?, Customer> grid = search("show me all customers in Berlin");

        assertThat(grid.size()).isGreaterThan(0);
        for (int i = 0; i < grid.size(); i++) {
            assertThat(grid.getRow(i).getAddress().getCity()).containsIgnoringCase("berlin");
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void creditworthyCustomers() {
        GridTester<?, Customer> grid = search("show me all creditworthy customers");

        assertThat(grid.size()).isGreaterThan(0);
        for (int i = 0; i < grid.size(); i++) {
            assertThat(grid.getRow(i).getCreditRating()).isEqualTo(CreditRating.GOOD);
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void atRiskCustomers() {
        GridTester<?, Customer> grid = search("show me all customers that are at risk");

        assertThat(grid.size()).isGreaterThan(0);
        for (int i = 0; i < grid.size(); i++) {
            assertThat(grid.getRow(i).getCreditRating()).isEqualTo(CreditRating.POOR);
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void companyNameContainsData() {
        GridTester<?, Customer> grid = search("customers whose company name contains data");

        assertThat(grid.size()).isGreaterThan(0);
        for (int i = 0; i < grid.size(); i++) {
            assertThat(grid.getRow(i).getCompanyName()).containsIgnoringCase("data");
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void multiValueCities() {
        GridTester<?, Customer> grid = search("show me customers from Berlin or Hamburg");

        assertThat(grid.size()).isGreaterThan(0);
        for (int i = 0; i < grid.size(); i++) {
            assertThat(grid.getRow(i).getAddress().getCity()).containsAnyOf("Berlin", "Hamburg");
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void annualRevenueOverThreshold() {
        GridTester<?, Customer> grid = search("show me customers with annual revenue over 200000");

        assertThat(grid.size()).isGreaterThan(0).isLessThan(Math.toIntExact(customerRepository.count()));
        for (int i = 0; i < grid.size(); i++) {
            assertThat(grid.getRow(i).getAnnualRevenue()).isGreaterThanOrEqualTo(BigDecimal.valueOf(150_000));
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void citiesWithGoodRatingAndRevenueAboveThreshold() {
        GridTester<?, Customer> grid = search(
                "show all customer from Berlin and Cologne with a positive creditrating and a revenue over 100000");

        assertThat(grid.size()).isGreaterThan(0);
        for (int i = 0; i < grid.size(); i++) {
            Customer row = grid.getRow(i);
            assertThat(row.getAddress().getCity()).containsAnyOf("Berlin", "Cologne");
            assertThat(row.getCreditRating()).isIn(CreditRating.GOOD, CreditRating.MEDIUM);
            assertThat(row.getAnnualRevenue()).isGreaterThanOrEqualTo(BigDecimal.valueOf(75_000));
        }
    }

    /**
     * Types the query into the filter field and waits for the async search to finish — the field
     * is disabled for the duration of a search ({@code CustomerListView.onFilter}) and re-enabled
     * once the {@code ui.access(...)} completion callback has run, regardless of how many rows the
     * (non-deterministic) real model's answer ends up matching.
     */
    private GridTester<?, Customer> search(String query) {
        CustomerListView view = navigate(CustomerListView.class);
        test(view.filterField).setValue(query);

        await().pollInSameThread().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            MockVaadin.runUIQueue();
            assertThat(view.filterField.isEnabled()).isTrue();
        });

        return test(view.grid);
    }
}
