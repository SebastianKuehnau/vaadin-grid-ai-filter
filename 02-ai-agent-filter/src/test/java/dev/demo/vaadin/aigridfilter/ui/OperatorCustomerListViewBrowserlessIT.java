package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.browserless.ViewPackages;
import com.vaadin.browserless.internal.MockVaadin;
import com.vaadin.flow.component.grid.GridTester;
import dev.demo.vaadin.aigridfilter.ai.TokenUsageAdvisor;
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
 * Browserless UI integration test of variant <b>02(b)</b> against a real AI backend — the sibling of
 * {@link FlatCustomerListViewBrowserlessIT}, for the value/operator/negate tool call. On top of the
 * queries both variants can express, it covers the two capabilities 02(b) adds: negation and operator
 * precision, both asserted on the resulting grid rows.
 * <p>
 * Deliberately absent: multi-value OR and range queries. 02(b) cannot express them (one value and one
 * operator per field), so their expected failures are recorded per canonical query in
 * {@code OperatorCanonicalQueryIT} rather than being asserted here.
 */
@SpringBootTest
@ViewPackages(classes = OperatorCustomerListView.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperatorCustomerListViewBrowserlessIT extends SpringBrowserlessTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private TokenUsageAdvisor tokenUsageAdvisor;

    @BeforeAll
    void resetTokenUsage() {
        tokenUsageAdvisor.reset();
    }

    @AfterAll
    void logTokenSummary() {
        tokenUsageAdvisor.logSummary("OperatorCustomerListViewBrowserlessIT");
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

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void customersExceptFromBerlin() {
        // 02(b)'s added capability #1: negation, asserted on the grid rows.
        GridTester<?, Customer> grid = search("show me all customers except from Berlin");

        assertThat(grid.size()).isGreaterThan(0).isLessThan(Math.toIntExact(customerRepository.count()));
        for (int i = 0; i < grid.size(); i++) {
            assertThat(grid.getRow(i).getAddress().getCity()).doesNotContainIgnoringCase("berlin");
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void contactNameStartsWithM() {
        // 02(b)'s added capability #2: operator precision (STARTS_WITH instead of CONTAINS).
        GridTester<?, Customer> grid = search(
                "show me all customers with an \"m\" as the first character in the contact name");

        assertThat(grid.size()).isGreaterThan(0);
        for (int i = 0; i < grid.size(); i++) {
            assertThat(grid.getRow(i).getContactName().toLowerCase()).startsWith("m");
        }
    }

    /** See {@link FlatCustomerListViewBrowserlessIT#search} — same async-search handshake. */
    private GridTester<?, Customer> search(String query) {
        OperatorCustomerListView view = navigate(OperatorCustomerListView.class);
        test(view.filterField).setValue(query);

        await().pollInSameThread().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            MockVaadin.runUIQueue();
            assertThat(view.filterField.isEnabled()).isTrue();
        });

        return test(view.grid);
    }
}
