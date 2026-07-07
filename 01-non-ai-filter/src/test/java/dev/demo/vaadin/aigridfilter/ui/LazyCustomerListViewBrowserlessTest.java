package dev.demo.vaadin.aigridfilter.ui;

import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.browserless.ViewPackages;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridTester;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.SortDirection;
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
@ViewPackages(classes = LazyCustomerListView.class)
class LazyCustomerListViewBrowserlessTest extends SpringBrowserlessTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void allCustomersShownInitially() {
        GridTester<?, Customer> grid = test(navigate(LazyCustomerListView.class).getGrid());

        assertThat(grid.size()).isEqualTo(100);
    }

    @Test
    void sortingByCompanyNameWorks() {
        LazyCustomerListView view = navigate(LazyCustomerListView.class);
        GridTester<?, Customer> grid = test(view.getGrid());

        grid.sortByColumn("companyName", SortDirection.ASCENDING);

        assertThat(rows(grid).stream().map(Customer::getCompanyName).toList()).isSorted();
    }

    @Test
    void filterByPersonWorks() {
        LazyCustomerListView view = navigate(LazyCustomerListView.class);
        test(textFilter(view, "contactName")).setValue("Laura Schmidt");

        GridTester<?, Customer> grid = test(view.getGrid());
        assertThat(grid.size()).isEqualTo(1);
        assertThat(grid.getRow(0).getContactName()).isEqualTo("Laura Schmidt");
    }

    @Test
    void filterByDateWorks() {
        LazyCustomerListView view = navigate(LazyCustomerListView.class);
        test(dateFilter(view, "lastOrderDate")).setValue(LocalDate.now().minusDays(1));

        GridTester<?, Customer> grid = test(view.getGrid());
        assertThat(rows(grid)).extracting(Customer::getCompanyName).contains("Berlin Data Works");
    }

    @Test
    void filterByCityWorks() {
        LazyCustomerListView view = navigate(LazyCustomerListView.class);
        test(textFilter(view, "address")).setValue("Berlin");

        GridTester<?, Customer> grid = test(view.getGrid());
        assertThat(grid.size()).isGreaterThan(0);
        assertThat(rows(grid)).extracting(customer -> customer.getAddress().getCity())
                .containsOnly("Berlin");
    }

    @Test
    void combinedNameAndCityFilterWorks() {
        LazyCustomerListView view = navigate(LazyCustomerListView.class);
        test(textFilter(view, "companyName")).setValue("Berlin Data Works");
        test(textFilter(view, "address")).setValue("Berlin");

        GridTester<?, Customer> grid = test(view.getGrid());
        assertThat(grid.size()).isEqualTo(1);
        assertThat(grid.getRow(0).getCompanyName()).isEqualTo("Berlin Data Works");
    }

    @Test
    void combinedYesterdaysDateAndPositiveCreditworthinessFilterWorks() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        List<String> expectedCompanyNames = customerRepository.findAll().stream()
                .filter(customer -> !customer.getLastOrderDate().isBefore(yesterday) && customer.getCreditScore() >= 70)
                .map(Customer::getCompanyName)
                .sorted()
                .toList();
        // Sanity check: "Berlin Data Works" has last_order_date pinned to yesterday by the
        // ApplicationRunner, but its credit score (55, MEDIUM) means it must NOT be a match here
        // — this combined filter is expected to exercise real AND semantics, not just echo it back.
        assertThat(expectedCompanyNames).doesNotContain("Berlin Data Works");

        LazyCustomerListView view = navigate(LazyCustomerListView.class);
        test(dateFilter(view, "lastOrderDate")).setValue(yesterday);
        test((MultiSelectComboBox<?>) headerFilter(view, "creditRating")).selectItem("Creditworthy");

        GridTester<?, Customer> grid = test(view.getGrid());
        List<String> actualCompanyNames = rows(grid).stream().map(Customer::getCompanyName).sorted().toList();
        assertThat(actualCompanyNames).isEqualTo(expectedCompanyNames);
    }

    private static List<Customer> rows(GridTester<?, Customer> grid) {
        return IntStream.range(0, grid.size()).mapToObj(grid::getRow).toList();
    }

    private static Component headerFilter(LazyCustomerListView view, String columnKey) {
        Grid<Customer> grid = view.getGrid();
        // The default header row (column titles) is prepended; the filter fields live in the
        // row appended afterwards by LazyCustomerListView, so it's the last one, not the first.
        return grid.getHeaderRows().getLast().getCell(grid.getColumnByKey(columnKey)).getComponent();
    }

    private static TextField textFilter(LazyCustomerListView view, String columnKey) {
        return (TextField) headerFilter(view, columnKey);
    }

    private static DatePicker dateFilter(LazyCustomerListView view, String columnKey) {
        return (DatePicker) headerFilter(view, columnKey);
    }
}
