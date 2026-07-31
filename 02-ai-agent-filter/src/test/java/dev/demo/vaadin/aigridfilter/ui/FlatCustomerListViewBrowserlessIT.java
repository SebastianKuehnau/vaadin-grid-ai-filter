package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.browserless.ViewPackages;
import com.vaadin.browserless.internal.MockVaadin;
import com.vaadin.flow.component.grid.GridTester;
import dev.demo.vaadin.aigridfilter.ai.TokenUsageRecorder;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Browserless UI integration test of variant <b>02(a)</b> against a real AI backend — no fake
 * {@code CustomerSearchAgent} bean. Verifies the full pipeline end to end: typing a natural-language
 * query, the real flat tool-calling AI layer resolving it, and the grid showing the right rows.
 * Complements {@code FlatCanonicalQueryIT}, which uses the same backend but bypasses the UI.
 * <p>
 * Only queries this variant can actually express are covered here: one value per field, AND across
 * fields, revenue as a minimum. Multi-value OR, negation, operator precision and date bounds are
 * architecturally out of reach for 02(a) — their expected failures are recorded, per canonical query,
 * in {@code FlatCanonicalQueryIT} instead of being retried here.
 * <p>
 * Which Spring profile {@code AI_TEST_PROFILE} selects comes from
 * {@code src/test/resources/application.properties}, so the ITs target a native Ollama instance by
 * default. There is no reachability probe — if the backend isn't reachable, the run fails rather than
 * skipping.
 */
@SpringBootTest
@ViewPackages(classes = FlatCustomerListView.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlatCustomerListViewBrowserlessIT extends SpringBrowserlessTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TokenUsageRecorder tokenUsageRecorder;

    @BeforeAll
    void resetTokenUsage() {
        tokenUsageRecorder.reset();
    }

    @AfterAll
    void logTokenSummary() {
        tokenUsageRecorder.logSummary("FlatCustomerListViewBrowserlessIT");
    }

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
    void annualRevenueOverThreshold() {
        GridTester<?, Customer> grid = search("show me customers with annual revenue over 200000");

        assertThat(grid.size()).isGreaterThan(0).isLessThan(Math.toIntExact(customerRepository.count()));
        for (int i = 0; i < grid.size(); i++) {
            assertThat(grid.getRow(i).getAnnualRevenue()).isGreaterThanOrEqualTo(BigDecimal.valueOf(150_000));
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void creditworthyCustomersInCity() {
        GridTester<?, Customer> grid = search("creditworthy customers in Hamburg");

        assertThat(grid.size()).isGreaterThan(0);
        for (int i = 0; i < grid.size(); i++) {
            Customer row = grid.getRow(i);
            assertThat(row.getAddress().getCity()).containsIgnoringCase("hamburg");
            assertThat(row.getCreditRating()).isEqualTo(CreditRating.GOOD);
        }
    }

    /**
     * Types the query into the filter field and waits for the async search to finish — the field
     * is disabled for the duration of a search and re-enabled once the {@code ui.access(...)}
     * completion callback has run, regardless of how many rows the (non-deterministic) real model's
     * answer ends up matching.
     */
    private GridTester<?, Customer> search(String query) {
        FlatCustomerListView view = navigate(FlatCustomerListView.class);
        test(view.filterField).setValue(query);

        await().pollInSameThread().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            MockVaadin.runUIQueue();
            assertThat(view.filterField.isEnabled()).isTrue();
        });

        return test(view.grid);
    }
}
