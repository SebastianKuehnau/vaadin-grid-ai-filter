package dev.demo.vaadin.aigridfilter.ai;

import dev.demo.vaadin.aigridfilter.ai.filter.CustomerSearchCriteria;
import dev.demo.vaadin.aigridfilter.ai.filter.RevenueRange;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Plain JUnit test (no Spring context, no LLM) of the tools' own correctness, in isolation: calling
 * {@code searchCustomers} directly with fixed literal arguments must capture them, verbatim, into
 * {@link CustomerSearchToolCallingService#criteria}, and {@code currentLocalDateTime} must return the
 * actual current time. The {@link ChatModel} and {@link TokenUsageRecorder} are mocked purely to
 * satisfy the constructor — neither is invoked (the tools never call the model or record usage).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CustomerSearchToolCallingServiceToolsTest {

    private final CustomerSearchToolCallingService service =
            new CustomerSearchToolCallingService(mock(ChatModel.class), mock(TokenUsageRecorder.class));

    @Test
    void capturesSingleValueArgumentsIntoResult() {
        service.searchCustomers(List.of("Acme"), List.of("Jane Doe"), List.of("jane@acme.example"),
                List.of("+4916057123456"), List.of(LocalDate.of(2020, 1, 1)), List.of(LocalDate.of(2021, 6, 15)),
                List.of("Germany"), List.of("Berlin"), List.of("10115"), List.of("Main Street"), List.of("1"),
                List.of(CreditRating.GOOD), List.of(new RevenueRange(BigDecimal.valueOf(50_000), BigDecimal.valueOf(200_000))));

        assertThat(service.criteria).isEqualTo(new CustomerSearchCriteria(List.of("Acme"), List.of("Jane Doe"),
                List.of("jane@acme.example"), List.of("+4916057123456"), List.of(LocalDate.of(2020, 1, 1)),
                List.of(LocalDate.of(2021, 6, 15)), List.of("Germany"), List.of("Berlin"), List.of("10115"),
                List.of("Main Street"), List.of("1"), List.of(CreditRating.GOOD),
                List.of(new RevenueRange(BigDecimal.valueOf(50_000), BigDecimal.valueOf(200_000)))));
    }

    @Test
    void capturesMultiValueArgumentsIntoResult() {
        service.searchCustomers(List.of("Acme", "Globex"), List.of("Jane Doe", "John Roe"),
                List.of("jane@acme.example", "john@globex.example"), List.of("+4916057123456", "+4916057123457"),
                List.of(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 6, 15)),
                List.of(LocalDate.of(2022, 3, 1), LocalDate.of(2023, 9, 30)),
                List.of("Germany", "Austria"), List.of("Berlin", "Vienna"), List.of("10115", "1010"),
                List.of("Main Street", "Ring Road"), List.of("1", "2"),
                List.of(CreditRating.GOOD, CreditRating.MEDIUM),
                List.of(new RevenueRange(BigDecimal.valueOf(500_000), null), new RevenueRange(null, BigDecimal.valueOf(50_000))));

        assertThat(service.criteria).isEqualTo(new CustomerSearchCriteria(
                List.of("Acme", "Globex"), List.of("Jane Doe", "John Roe"),
                List.of("jane@acme.example", "john@globex.example"), List.of("+4916057123456", "+4916057123457"),
                List.of(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 6, 15)),
                List.of(LocalDate.of(2022, 3, 1), LocalDate.of(2023, 9, 30)),
                List.of("Germany", "Austria"), List.of("Berlin", "Vienna"), List.of("10115", "1010"),
                List.of("Main Street", "Ring Road"), List.of("1", "2"),
                List.of(CreditRating.GOOD, CreditRating.MEDIUM),
                List.of(new RevenueRange(BigDecimal.valueOf(500_000), null), new RevenueRange(null, BigDecimal.valueOf(50_000)))));
    }

    @Test
    void allNullArgumentsCaptureNullCriteria() {
        service.searchCustomers(null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(service.criteria).isEqualTo(new CustomerSearchCriteria(
                null, null, null, null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void returnsTheCurrentDateTime() {
        LocalDateTime result = service.currentLocalDateTime();

        assertThat(Duration.between(result, LocalDateTime.now()).abs()).isLessThan(Duration.ofSeconds(5));
    }
}
