package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.browserless.ViewPackages;
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
        GridTester<?, Customer> grid = test(navigate(InMemoryCustomerListView.class).getGrid());

        assertThat(grid.size()).isEqualTo(100);
    }

    @Test
    void sortingByCompanyNameWorks() {
        InMemoryCustomerListView view = navigate(InMemoryCustomerListView.class);
        GridTester<?, Customer> grid = test(view.getGrid());

        grid.sortByColumn("companyName", SortDirection.ASCENDING);

        List<String> companyNames = rows(grid).stream().map(Customer::getCompanyName).toList();
        assertThat(companyNames).isSorted();
    }

    @Test
    void filterBySpecificPersonWorks() {
        InMemoryCustomerListView view = navigate(InMemoryCustomerListView.class);
        test(view.getFilterField()).setValue("Laura Schmidt");

        GridTester<?, Customer> grid = test(view.getGrid());
        assertThat(grid.size()).isEqualTo(1);
        assertThat(grid.getRow(0).getContactName()).isEqualTo("Laura Schmidt");
    }

    @Test
    void filterByYesterdaysDateWorks() {
        InMemoryCustomerListView view = navigate(InMemoryCustomerListView.class);
        test(view.getFilterField()).setValue(LocalDate.now().minusDays(1).toString());

        GridTester<?, Customer> grid = test(view.getGrid());
        assertThat(grid.size()).isGreaterThanOrEqualTo(1);
        assertThat(rows(grid)).extracting(Customer::getCompanyName).contains("Berlin Data Works");
    }

    @Test
    void filterByCityBerlinWorks() {
        InMemoryCustomerListView view = navigate(InMemoryCustomerListView.class);
        test(view.getFilterField()).setValue("Berlin");

        GridTester<?, Customer> grid = test(view.getGrid());
        assertThat(grid.size()).isGreaterThan(0);
        assertThat(rows(grid)).extracting(customer -> customer.getAddress().getCity())
                .containsOnly("Berlin");
    }

    private static List<Customer> rows(GridTester<?, Customer> grid) {
        return IntStream.range(0, grid.size()).mapToObj(grid::getRow).toList();
    }
}
