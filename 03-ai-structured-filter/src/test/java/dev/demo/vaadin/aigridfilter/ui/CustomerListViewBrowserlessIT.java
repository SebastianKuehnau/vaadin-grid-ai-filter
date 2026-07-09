package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.browserless.ViewPackages;
import com.vaadin.browserless.internal.MockVaadin;
import com.vaadin.flow.component.grid.GridTester;
import dev.demo.vaadin.aigridfilter.ai.OllamaTestSupport;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Browserless UI integration test against a real, native Ollama instance — no fake
 * {@code CustomerSearchAgent} bean. Verifies the full pipeline end to end: typing a
 * natural-language query, the real structured-output AI layer resolving it, and the grid
 * showing the right rows. Complements {@link CustomerListViewBrowserlessTest} (fast, fake
 * agent, no LLM) and {@code CustomerSearchAgentIT} (real Ollama, but bypasses the UI).
 * <p>
 * Deliberately extends {@link SpringBrowserlessTest} rather than {@code LocalOllamaTests}: the
 * two can't be combined by inheritance (browserless testing needs the default {@code MOCK} web
 * environment and Vaadin's Spring Boot autoconfiguration, both of which {@code LocalOllamaTests}
 * turns off for its own UI-less use case), so the Ollama wiring below is duplicated rather than
 * inherited. Skips gracefully when no native Ollama is reachable, same as {@code CustomerSearchAgentIT}.
 * <p>
 * Uses the <em>same 5 queries</em> as {@code 02-ai-agent-filter}'s
 * {@code CustomerListViewBrowserlessIT}, so the two modules' {@code -Pit-local-ollama} runs are
 * directly comparable on speed and result quality between tool calling and structured output.
 */
@SpringBootTest(properties = {
        "spring.ai.model.chat=ollama",
        "spring.ai.ollama.chat.model=llama3.1:8b",
        "spring.ai.ollama.chat.think=false",
        "spring.ai.ollama.chat.num-ctx=4096",
        "spring.ai.ollama.init.pull-model-strategy=never"
})
@ViewPackages(classes = CustomerListView.class)
class CustomerListViewBrowserlessIT extends SpringBrowserlessTest {

    @Autowired
    private CustomerRepository customerRepository;

    @DynamicPropertySource
    static void ollamaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", OllamaTestSupport::localBaseUrl);
    }

    @BeforeAll
    static void requireReachableOllama() {
        String baseUrl = OllamaTestSupport.localBaseUrl();
        assumeTrue(OllamaTestSupport.reachable(baseUrl), "native Ollama not reachable at " + baseUrl + " — skipping");
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
    void customersSince2020() {
        GridTester<?, Customer> grid = search("customers since 2020");

        assertThat(grid.size()).isGreaterThan(0).isLessThan(Math.toIntExact(customerRepository.count()));
        for (int i = 0; i < grid.size(); i++) {
            assertThat(grid.getRow(i).getCustomerSince()).isAfterOrEqualTo(LocalDate.of(2020, 1, 1));
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

    /**
     * Types the query into the filter field and waits for the async search to finish — the field
     * is disabled for the duration of a search ({@code CustomerListView.onFilter}) and re-enabled
     * once the {@code ui.access(...)} completion callback has run, regardless of how many rows the
     * (non-deterministic) real model's answer ends up matching.
     */
    private GridTester<?, Customer> search(String query) {
        CustomerListView view = navigate(CustomerListView.class);
        test(view.getFilterField()).setValue(query);

        await().pollInSameThread().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            MockVaadin.runUIQueue();
            assertThat(view.getFilterField().isEnabled()).isTrue();
        });

        return test(view.getGrid());
    }
}
