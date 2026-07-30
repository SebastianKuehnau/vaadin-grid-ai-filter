package dev.demo.vaadin.aigridfilter.ai.operator;

import dev.demo.vaadin.aigridfilter.ai.TokenUsageRecorder;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Plain JUnit test (no Spring context, no LLM) of variant 02(b)'s tools in isolation: calling
 * {@code searchCustomers} directly with fixed literal arguments must group them, verbatim, into one
 * {@link FieldCriterion} per field, and {@code currentLocalDateTime} must return the actual current
 * time. The {@link ChatModel} and {@link TokenUsageRecorder} are mocked purely to satisfy the
 * constructor — neither is invoked (the tools never call the model or record usage).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class OperatorToolCallingServiceToolsTest {

    private final OperatorToolCallingService service =
            new OperatorToolCallingService(mock(ChatModel.class), mock(TokenUsageRecorder.class));

    /** Calls the tool with only the three city parameters set; everything else null. */
    private void searchByCity(String city, Operator operator, Boolean negate) {
        service.searchCustomers(null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, city, operator, negate,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void groupsValueOperatorAndNegatePerField() {
        searchByCity("Berlin", Operator.EQUALS, true);

        assertThat(service.criteria.city()).isEqualTo(new FieldCriterion<>("Berlin", Operator.EQUALS, true));
        assertThat(service.criteria.companyName()).isNull();
        assertThat(service.criteria.annualRevenue()).isNull();
    }

    @Test
    void missingOperatorDefaultsToContainsAndMissingNegateToFalse() {
        searchByCity("Berlin", null, null);

        assertThat(service.criteria.city()).isEqualTo(new FieldCriterion<>("Berlin", Operator.CONTAINS, false));
    }

    @Test
    void aFieldWithoutAValueStaysUnset() {
        // An operator or negate flag alone is not a filter: without a value there is nothing to compare.
        searchByCity(null, Operator.EQUALS, true);
        assertThat(service.criteria.city()).isNull();
    }

    @Test
    void aBlankValueStaysUnset() {
        searchByCity("   ", Operator.EQUALS, false);
        assertThat(service.criteria.city()).isNull();
    }

    @Test
    void capturesTypedFieldsOfEveryKind() {
        service.searchCustomers("Acme", Operator.CONTAINS, false,
                "Jane Doe", Operator.EQUALS, false,
                "jane@acme.example", Operator.ENDS_WITH, false,
                "+4916057123456", Operator.CONTAINS, false,
                LocalDate.of(2020, 1, 1), Operator.GREATER_OR_EQUAL, false,
                LocalDate.of(2024, 3, 15), Operator.LESS_OR_EQUAL, false,
                "Germany", Operator.EQUALS, false,
                "Berlin", Operator.CONTAINS, true,
                "10115", Operator.STARTS_WITH, false,
                "Main Street", Operator.CONTAINS, false,
                "1", Operator.EQUALS, false,
                CreditRating.GOOD, Operator.EQUALS, false,
                BigDecimal.valueOf(100_000), Operator.GREATER_OR_EQUAL, false);

        assertThat(service.criteria).isEqualTo(new OperatorCriteria(
                new FieldCriterion<>("Acme", Operator.CONTAINS, false),
                new FieldCriterion<>("Jane Doe", Operator.EQUALS, false),
                new FieldCriterion<>("jane@acme.example", Operator.ENDS_WITH, false),
                new FieldCriterion<>("+4916057123456", Operator.CONTAINS, false),
                new FieldCriterion<>(LocalDate.of(2020, 1, 1), Operator.GREATER_OR_EQUAL, false),
                new FieldCriterion<>(LocalDate.of(2024, 3, 15), Operator.LESS_OR_EQUAL, false),
                new FieldCriterion<>("Germany", Operator.EQUALS, false),
                new FieldCriterion<>("Berlin", Operator.CONTAINS, true),
                new FieldCriterion<>("10115", Operator.STARTS_WITH, false),
                new FieldCriterion<>("Main Street", Operator.CONTAINS, false),
                new FieldCriterion<>("1", Operator.EQUALS, false),
                new FieldCriterion<>(CreditRating.GOOD, Operator.EQUALS, false),
                new FieldCriterion<>(BigDecimal.valueOf(100_000), Operator.GREATER_OR_EQUAL, false)));
    }

    @Test
    void aSecondCallIsRejectedAndTheFirstCriteriaIsKept() {
        // criteria's null/non-null lifecycle is the one-shot latch: a second call, empty or not, must
        // not be able to overwrite what the first call already extracted.
        searchByCity("Berlin", Operator.CONTAINS, false);

        assertThatThrownBy(() -> searchByCity("Hamburg", Operator.EQUALS, false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.criteria.city()).isEqualTo(new FieldCriterion<>("Berlin", Operator.CONTAINS, false));
    }

    @Test
    void returnsTheCurrentDateTime() {
        LocalDateTime result = service.currentLocalDateTime();

        assertThat(Duration.between(result, LocalDateTime.now()).abs()).isLessThan(Duration.ofSeconds(5));
    }
}
