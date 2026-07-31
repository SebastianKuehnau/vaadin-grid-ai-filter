package dev.demo.vaadin.aigridfilter.ai.operator;

import dev.demo.vaadin.aigridfilter.ai.operator.FieldCriterion.Operator;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Plain JUnit (no Spring context, no LLM) guard for the one tool-calling failure mode this repository has
 * actually observed and mitigated: a {@code void} tool is answered with a bare "Done", which a model can
 * read as "nothing happened" and call the tool again with no arguments — wiping the criteria it just
 * extracted. See {@code docs/capability-matrix.md}, "Same tool called twice".
 * <p>
 * Only that behaviour is covered. Tests that merely asserted arguments land in a record were plumbing and
 * were removed; what makes an AI call robust is the guard against the model repeating itself.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CustomerSearchServiceToolsTest {

    private final CustomerSearchService service =
            new CustomerSearchService(mock(ChatModel.class), mock(TokenUsageAdvisor.class));

    /** Calls the tool with only the three city parameters set; everything else null. */
    private void searchByCity(String city, Operator operator, Boolean negate) {
        service.searchCustomers(null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, city, operator, negate,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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

}
