package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.browserless.ViewPackages;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.grid.GridTester;
import com.vaadin.flow.data.provider.SortDirection;
import dev.demo.vaadin.aigridfilter.data.Customer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ViewPackages(classes = InMemoryCustomerListView.class)
class InMemoryCustomerListViewBrowserlessTest extends SpringBrowserlessTest {

    @Test
    void allCustomersShownInitially() {
        GridTester<?, Customer> grid = test(navigate(InMemoryCustomerListView.class).grid);

        assertThat(grid.size()).isEqualTo(100);
    }

    @Test
    void sortingByCompanyNameWorks() {
        InMemoryCustomerListView view = navigate(InMemoryCustomerListView.class);
        GridTester<?, Customer> grid = test(view.grid);

        grid.sortByColumn("companyName", SortDirection.ASCENDING);

        List<String> companyNames = rows(grid).stream().map(Customer::getCompanyName).toList();
        assertThat(companyNames).isSorted();
    }

    @Test
    void filterBySpecificPersonWorks() {
        InMemoryCustomerListView view = navigate(InMemoryCustomerListView.class);
        test(view.filterField).setValue("Laura Schmidt");

        GridTester<?, Customer> grid = test(view.grid);
        assertThat(grid.size()).isEqualTo(1);
        assertThat(grid.getRow(0).getContactName()).isEqualTo("Laura Schmidt");
    }

    @Test
    void filterByYesterdaysDateWorks() {
        InMemoryCustomerListView view = navigate(InMemoryCustomerListView.class);
        test(view.filterField).setValue(LocalDate.now().minusDays(1).toString());

        GridTester<?, Customer> grid = test(view.grid);
        assertThat(grid.size()).isGreaterThanOrEqualTo(1);
        assertThat(rows(grid)).extracting(Customer::getCompanyName).contains("Berlin Data Works");
    }

    @Test
    void filterByCityBerlinWorks() {
        InMemoryCustomerListView view = navigate(InMemoryCustomerListView.class);
        test(view.filterField).setValue("Berlin");

        GridTester<?, Customer> grid = test(view.grid);
        assertThat(grid.size()).isGreaterThan(0);
        assertThat(rows(grid)).extracting(customer -> customer.getAddress().getCity())
                .containsOnly("Berlin");
    }

    /**
     * The credit-rating column is the one cell of the shared {@link CustomerGrid} that renders a
     * component rather than text, and the only one whose appearance depends on a CSS file — both of
     * which now live in {@code demo-commons} rather than in this module. This test is what proves the
     * move kept them working: the cell must be the indicator component, carry the base class plus the
     * modifier class matching that row's rating, and still declare the stylesheet that colours it.
     * <p>
     * All four apps render this very same class, so proving it once here covers them all; that the CSS
     * file itself is served out of the dependency jar is a deployment question, checked per app with a
     * request against {@code /credit-score-indicator.css}.
     */
    @Test
    void creditRatingColumnRendersTheColouredIndicator() {
        InMemoryCustomerListView view = navigate(InMemoryCustomerListView.class);
        GridTester<?, Customer> grid = test(view.grid);

        for (int row = 0; row < grid.size(); row++) {
            Component cell = grid.getCellComponent(row, "creditRating");
            Customer customer = grid.getRow(row);
            String expectedModifier = switch (customer.getCreditRating()) {
                case GOOD -> "credit-indicator--good";
                case MEDIUM -> "credit-indicator--medium";
                case POOR -> "credit-indicator--poor";
            };

            assertThat(cell.getElement().getClassList())
                    .as("row %d (%s)", row, customer.getCreditRating())
                    .contains("credit-indicator", expectedModifier);
            assertThat(cell.getElement().getAttribute("aria-label"))
                    .isEqualTo("Credit rating: " + customer.getCreditRating().getLabel()
                            + ", score " + customer.getCreditScore());
            assertThat(cell.getClass().getAnnotation(StyleSheet.class))
                    .as("the indicator must still declare its stylesheet after moving into demo-commons")
                    .isNotNull()
                    .extracting(StyleSheet::value)
                    .isEqualTo("credit-score-indicator.css");
        }
    }

    private static List<Customer> rows(GridTester<?, Customer> grid) {
        return IntStream.range(0, grid.size()).mapToObj(grid::getRow).toList();
    }
}
