package dev.demo.vaadin.aigridfilter.canonicalquery;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.browserless.internal.MockVaadin;
import com.vaadin.flow.component.grid.GridTester;
import dev.demo.vaadin.aigridfilter.ai.TokenUsageExtension;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import dev.demo.vaadin.aigridfilter.ui.AbstractCustomerSearchView;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the canonical query set through the <b>user interface</b> against a real model: the query is typed
 * into the filter field, the view's own AI layer resolves it, and the ids the grid ends up showing are
 * compared with the ids a reference predicate selects.
 * <p>
 * Deliberately the same eight {@link CanonicalQuery} constants and the same per-variant
 * {@link ExpectedResult} mapping as {@link AbstractCustomerSearchIT}, so the only difference left between
 * the two IT kinds is the path taken: {@code TextField → Grid} here, {@code agent → repository} there.
 * Every module therefore runs the same number of UI queries with the same logic, including the ones that
 * fail by design.
 * <p>
 * A subclass supplies the view to open and what its filter type can express. It also has to carry
 * {@code @ViewPackages}, because that annotation has to name a concrete {@code @Route} class. Counting
 * tokens is not this class's business; that is {@link TokenUsageExtension}.
 */
@SpringBootTest
// Generous on purpose, and per query rather than per class: each one is a full round trip through the
// model, and a cold model load adds a few seconds on top of the first.
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@ExtendWith(TokenUsageExtension.class)
public abstract class AbstractCustomerListViewBrowserlessIT extends SpringBrowserlessTest {

    private static final Logger logger =
            LoggerFactory.getLogger(AbstractCustomerListViewBrowserlessIT.class);

    @Autowired
    private CustomerRepository customerRepository;

    /** The route to open — a concrete view, so it also fixes which variant answers. */
    protected abstract Class<? extends AbstractCustomerSearchView> viewClass();

    /** What this variant's filter type can express; the same mapping its service-level IT states. */
    protected abstract ExpectedResult expectedResultFor(CanonicalQuery canonical);

    @ParameterizedTest
    @EnumSource(CanonicalQuery.class)
    void canonicalQuery(CanonicalQuery canonical) {
        List<Customer> seeded = customerRepository.findAll();
        Set<Long> shown = idsShownFor(canonical.query());
        List<Set<Long>> acceptable = canonical.acceptableIdSets(seeded);
        ExpectedResult expectedResult = expectedResultFor(canonical);

        logger.info("{} [{}] '{}' -> {} of {} rows in the grid, acceptable sizes {}",
                canonical.name(), expectedResult, canonical.query(), shown.size(), seeded.size(),
                acceptable.stream().map(Set::size).toList());

        if (expectedResult == ExpectedResult.MATCH) {
            assertThat(acceptable)
                    .as("%s: the grid (%d rows) must show one of the expected customer sets %s",
                            canonical.name(), shown.size(), acceptable.stream().map(Set::size).toList())
                    .contains(shown);
        } else {
            assertThat(acceptable)
                    .as("%s cannot be expressed by this variant's filter type, yet the grid (%d rows) "
                                    + "showed an expected customer set %s — an accidental capability worth "
                                    + "looking at, not a green test",
                            canonical.name(), shown.size(), acceptable.stream().map(Set::size).toList())
                    .doesNotContain(shown);
        }
    }

    /**
     * Types the query into the filter field, waits for the async search to finish and reads the grid. The
     * field is disabled for the duration of a search and re-enabled once the {@code ui.access(...)}
     * completion callback has run — which happens regardless of how many rows the (non-deterministic)
     * model's answer ends up matching, so it is a safe signal to wait on.
     */
    private Set<Long> idsShownFor(String query) {
        AbstractCustomerSearchView view = navigate(viewClass());
        test(view.filterField).setValue(query);

        await().pollInSameThread().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
            MockVaadin.runUIQueue();
            assertThat(view.filterField.isEnabled()).isTrue();
        });

        GridTester<?, Customer> grid = test(view.grid);
        Set<Long> ids = new HashSet<>();
        for (int row = 0; row < grid.size(); row++) {
            ids.add(grid.getRow(row).getId());
        }
        return ids;
    }
}
