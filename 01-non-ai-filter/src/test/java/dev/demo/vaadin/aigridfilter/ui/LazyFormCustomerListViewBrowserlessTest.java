package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.browserless.ViewPackages;
import com.vaadin.flow.component.grid.GridTester;
import dev.demo.vaadin.aigridfilter.data.Customer;
import dev.demo.vaadin.aigridfilter.data.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ViewPackages(classes = LazyFormCustomerListView.class)
class LazyFormCustomerListViewBrowserlessTest extends SpringBrowserlessTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void allCustomersShownInitially() {
        GridTester<?, Customer> grid = test(navigate(LazyFormCustomerListView.class).grid);

        assertThat(grid.size()).isEqualTo(100);
    }

    @Test
    void filterByPersonWorks() {
        LazyFormCustomerListView view = navigate(LazyFormCustomerListView.class);
        test(view.searchForm.contactName).setValue("Laura Schmidt");
        test(view.searchForm.search).click();

        GridTester<?, Customer> grid = test(view.grid);
        assertThat(grid.size()).isEqualTo(1);
        assertThat(grid.getRow(0).getContactName()).isEqualTo("Laura Schmidt");
    }

    @Test
    void filterByCityWorks() {
        LazyFormCustomerListView view = navigate(LazyFormCustomerListView.class);
        test(view.searchForm.city).setValue("Berlin");
        test(view.searchForm.search).click();

        GridTester<?, Customer> grid = test(view.grid);
        assertThat(grid.size()).isGreaterThan(0);
        assertThat(rows(grid)).extracting(customer -> customer.getAddress().getCity())
                .containsOnly("Berlin");
    }

    @Test
    void combinedYesterdaysDateAndPositiveCreditworthinessFilterWorks() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        List<String> expectedCompanyNames = customerRepository.findAll().stream()
                .filter(customer -> !customer.getLastOrderDate().isBefore(yesterday) && customer.getCreditScore() >= 70)
                .map(Customer::getCompanyName)
                .sorted()
                .toList();
        // "Berlin Data Works" matches the date but not the rating, so real AND semantics are exercised.
        assertThat(expectedCompanyNames).doesNotContain("Berlin Data Works");

        LazyFormCustomerListView view = navigate(LazyFormCustomerListView.class);
        test(view.searchForm.lastOrder.from).setValue(yesterday);
        test(view.searchForm.creditRatings).selectItem("Creditworthy");
        test(view.searchForm.search).click();

        GridTester<?, Customer> grid = test(view.grid);
        List<String> actualCompanyNames = rows(grid).stream().map(Customer::getCompanyName).sorted().toList();
        assertThat(actualCompanyNames).isEqualTo(expectedCompanyNames);
    }

    @Test
    void resetShowsEveryCustomerAgain() {
        LazyFormCustomerListView view = navigate(LazyFormCustomerListView.class);
        test(view.searchForm.city).setValue("Berlin");
        test(view.searchForm.search).click();
        assertThat(test(view.grid).size()).isLessThan(100);

        view.searchForm.reset();

        assertThat(test(view.grid).size()).isEqualTo(100);
    }

    private static List<Customer> rows(GridTester<?, Customer> grid) {
        return IntStream.range(0, grid.size()).mapToObj(grid::getRow).toList();
    }
}
