package dev.demo.vaadin.aigridfilter.ai.flat;

import dev.demo.vaadin.aigridfilter.ai.TokenUsageAdvisor;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Plain JUnit test (no Spring context, no LLM) of variant 02(a)'s tools in isolation: calling
 * {@code searchCustomers} directly with fixed literal arguments must capture them, verbatim, into
 * {@link CustomerSearchService#criteria}, and {@code currentLocalDateTime} must return the actual
 * current time. The {@link ChatModel} and {@link TokenUsageAdvisor} are mocked purely to satisfy the
 * constructor — neither is invoked (the tools never call the model or record usage).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CustomerSearchServiceToolsTest {

    private final CustomerSearchService service =
            new CustomerSearchService(mock(ChatModel.class), mock(TokenUsageAdvisor.class));

    @Test
    void capturesEveryArgumentIntoResult() {
        service.searchCustomers("Acme", "Jane Doe", "jane@acme.example", "+4916057123456",
                LocalDate.of(2020, 1, 1), LocalDate.of(2021, 6, 15), "Germany", "Berlin", "10115",
                "Main Street", "1", CreditRating.GOOD, BigDecimal.valueOf(50_000));

        assertThat(service.criteria).isEqualTo(new CustomerCriteria("Acme", "Jane Doe", "jane@acme.example",
                "+4916057123456", LocalDate.of(2020, 1, 1), LocalDate.of(2021, 6, 15), "Germany", "Berlin",
                "10115", "Main Street", "1", CreditRating.GOOD, BigDecimal.valueOf(50_000)));
    }

    @Test
    void allNullArgumentsCaptureAllNullCriteria() {
        service.searchCustomers(null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(service.criteria).isEqualTo(new CustomerCriteria(
                null, null, null, null, null, null, null, null, null, null, null, null, null));
        assertThat(service.criteria.isEmpty()).isTrue();
    }

    @Test
    void aRepeatedEmptyCallDoesNotWipeTheExtractedCriteria() {
        // A void tool is answered with a bare "Done", and a model can read that as "nothing happened"
        // and call the tool again with no arguments. That must not clear what it just extracted.
        service.searchCustomers(null, null, null, null, null, null, null, "Berlin", null, null, null, null, null);
        service.searchCustomers(null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(service.criteria.city()).isEqualTo("Berlin");
    }

    @Test
    void aRepeatedNonEmptyCallStillReplacesTheCriteria() {
        // Only empty repeat calls are ignored: a model correcting itself with real values must win.
        service.searchCustomers(null, null, null, null, null, null, null, "Berlin", null, null, null, null, null);
        service.searchCustomers(null, null, null, null, null, null, null, "Hamburg", null, null, null, null, null);

        assertThat(service.criteria.city()).isEqualTo("Hamburg");
    }

    @Test
    void returnsTheCurrentDateTime() {
        LocalDateTime result = service.currentLocalDateTime();

        assertThat(Duration.between(result, LocalDateTime.now()).abs()).isLessThan(Duration.ofSeconds(5));
    }
}
