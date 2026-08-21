package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.browserless.ViewPackages;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.grid.ColumnTextAlign;
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

    /** The only cell that renders a component and needs CSS - checked once here for all four apps. */
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
                                        .isNotNull()
                    .extracting(StyleSheet::value)
                    .isEqualTo("credit-score-indicator.css");
        }
    }

    /** The left-aligned Annual Revenue header needs the part name and the stylesheet using it. */
    @Test
    void annualRevenueHeaderIsStyleableSeparatelyFromItsValues() {
        var grid = navigate(InMemoryCustomerListView.class).grid;
        var column = grid.getColumnByKey("annualRevenue");

        assertThat(column.getTextAlign()).isEqualTo(ColumnTextAlign.END);
        assertThat(column.getHeaderPartName()).isEqualTo("annual-revenue-header");
        assertThat(CustomerGrid.class.getAnnotation(StyleSheet.class))
                .as("the part name is only styleable while the grid declares the stylesheet")
                .isNotNull()
                .extracting(StyleSheet::value)
                .isEqualTo("customer-grid.css");
    }

    private static List<Customer> rows(GridTester<?, Customer> grid) {
        return IntStream.range(0, grid.size()).mapToObj(grid::getRow).toList();
    }
}
