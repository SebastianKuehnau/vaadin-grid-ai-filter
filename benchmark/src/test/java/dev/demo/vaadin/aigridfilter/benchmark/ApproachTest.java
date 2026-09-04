package dev.demo.vaadin.aigridfilter.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The capability ladder of docs/canonical-query-set.md, as the benchmark reads it. */
class ApproachTest {

    @Test
    void skipsTheCasesEachVariantCannotExpress() {
        assertThat(Approach.FLAT_02A.unsupportedCases().keySet())
                .containsExactlyInAnyOrder("C2", "C3", "C4", "C6", "C7", "C8", "C10");
        assertThat(Approach.OPERATOR_02B.unsupportedCases().keySet())
                .containsExactlyInAnyOrder("C2", "C6", "C8");
        assertThat(Approach.STRUCTURED_03.unsupportedCases()).isEmpty();
        assertThat(Approach.HYBRID_04.unsupportedCases()).isEmpty();
    }

    @Test
    void givesAReasonForEverySkippedCase() {
        for (Approach approach : Approach.values()) {
            assertThat(approach.unsupportedCases().values())
                    .allSatisfy(reason -> assertThat(reason).isNotBlank());
        }
    }

    @Test
    void measuresTimeToFirstToolOnlyForTheToolCallingApproaches() {
        assertThat(Approach.FLAT_02A.toolBased()).isTrue();
        assertThat(Approach.OPERATOR_02B.toolBased()).isTrue();
        assertThat(Approach.HYBRID_04.toolBased()).isTrue();
        assertThat(Approach.STRUCTURED_03.toolBased()).isFalse();
    }

    @Test
    void qualifiesTheAgentOnlyWhereAModuleHoldsTwo() {
        assertThat(Approach.FLAT_02A.beanName()).isEqualTo("flatSearchAgent");
        assertThat(Approach.OPERATOR_02B.beanName()).isEqualTo("operatorSearchAgent");
        assertThat(Approach.STRUCTURED_03.beanName()).isNull();
        assertThat(Approach.HYBRID_04.beanName()).isNull();
    }

    @Test
    void resolvesIdsAnUnquotedYamlListTurnedIntoNumbers() {
        assertThat(Approach.byId("03")).isEqualTo(Approach.STRUCTURED_03);
        assertThat(Approach.byId("3")).isEqualTo(Approach.STRUCTURED_03);
        assertThat(Approach.byId("02A")).isEqualTo(Approach.FLAT_02A);
    }

    @Test
    void rejectsAnUnknownApproachWithTheListOfKnownOnes() {
        assertThatThrownBy(() -> Approach.byId("05"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("02a, 02b, 03, 04");
    }
}
