package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.browserless.internal.MockVaadin;
import com.vaadin.flow.component.grid.GridTester;
import dev.demo.vaadin.aigridfilter.ai.OllamaContainerConfig;
import dev.demo.vaadin.aigridfilter.ai.TestNameLoggingExtension;
import dev.demo.vaadin.aigridfilter.ai.TokenUsageExtension;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Types a query into the filter field, waits for the async search and returns the rows the grid shows. */
@SpringBootTest
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@ExtendWith({TokenUsageExtension.class, TestNameLoggingExtension.class})
@Import(OllamaContainerConfig.class)
public abstract class AbstractCustomerSearchViewIT extends SpringBrowserlessTest {

    @Autowired
    private CustomerRepository customerRepository;

    /** The route to open — a concrete view, so it also fixes which AI variant answers. */
    protected abstract Class<? extends AbstractCustomerSearchView> viewClass();

    /** Types the query into the filter field and returns the customers the grid ends up showing. */
    protected List<Customer> search(String query) {
        AbstractCustomerSearchView view = navigate(viewClass());
        test(view.filterField).setValue(query);

        // The field is re-enabled once the search's ui.access(...) callback has run.
        await().pollInSameThread().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
            MockVaadin.runUIQueue();
            assertThat(view.filterField.isEnabled()).isTrue();
        });

        GridTester<?, Customer> grid = test(view.grid);
        return IntStream.range(0, grid.size()).mapToObj(grid::getRow).toList();
    }

    /** The ids a correct answer selects from the seeded data — never a hard-coded list. */
    protected List<Long> expectedIds(Predicate<Customer> matches) {
        return customerRepository.findAll().stream().filter(matches).map(Customer::getId).toList();
    }

    /** The city of a customer's address, or an empty string if it has none. */
    protected static String city(Customer customer) {
        return customer.getAddress() == null ? "" : customer.getAddress().getCity();
    }
}
