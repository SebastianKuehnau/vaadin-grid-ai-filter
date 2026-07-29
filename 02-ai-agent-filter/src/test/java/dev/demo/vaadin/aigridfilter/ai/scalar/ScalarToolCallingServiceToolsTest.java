package dev.demo.vaadin.aigridfilter.ai.scalar;

import dev.demo.vaadin.aigridfilter.ai.TokenUsageRecorder;
import dev.demo.vaadin.aigridfilter.data.CreditRating;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Plain JUnit test (no Spring context, no LLM) of variant 02(a)'s tool in isolation: calling
 * {@code searchCustomers} directly with fixed literal arguments must capture them, verbatim, into
 * {@link ScalarToolCallingService#criteria}. The {@link ChatModel} and {@link TokenUsageRecorder} are
 * mocked purely to satisfy the constructor — neither is invoked (the tool never calls the model or
 * records usage).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ScalarToolCallingServiceToolsTest {

    private final ScalarToolCallingService service =
            new ScalarToolCallingService(mock(ChatModel.class), mock(TokenUsageRecorder.class));

    @Test
    void capturesEveryArgumentIntoResult() {
        service.searchCustomers("Acme", "Jane Doe", "jane@acme.example", "+4916057123456",
                LocalDate.of(2020, 1, 1), LocalDate.of(2021, 6, 15), "Germany", "Berlin", "10115",
                "Main Street", "1", CreditRating.GOOD, BigDecimal.valueOf(50_000));

        assertThat(service.criteria).isEqualTo(new ScalarCriteria("Acme", "Jane Doe", "jane@acme.example",
                "+4916057123456", LocalDate.of(2020, 1, 1), LocalDate.of(2021, 6, 15), "Germany", "Berlin",
                "10115", "Main Street", "1", CreditRating.GOOD, BigDecimal.valueOf(50_000)));
    }

    @Test
    void allNullArgumentsCaptureAllNullCriteria() {
        service.searchCustomers(null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(service.criteria).isEqualTo(new ScalarCriteria(
                null, null, null, null, null, null, null, null, null, null, null, null, null));
    }
}
