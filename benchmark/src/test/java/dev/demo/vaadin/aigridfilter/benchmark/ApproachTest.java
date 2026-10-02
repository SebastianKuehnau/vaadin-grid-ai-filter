package dev.demo.vaadin.aigridfilter.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The capability ladder of docs/canonical-query-set.md, as the benchmark reads it. */
class ApproachTest {

    @Test
    void skipsTheCasesEachVariantCannotExpress() {
        assertThat(Approach.FLAT_02A.unsupportedCases().keySet())
                .containsExactlyInAnyOrder("C2.1", "C2.2", "C2.3", "C2.4", "C2.5", "C3.1", "C3.2", "C3.3",
                        "C3.4", "C3.5", "C3.6", "C4.2", "C4.3", "C5.2", "C5.3", "C5.4", "C5.5", "C5.6",
                        "C5.7", "C5.8", "C5.9", "C7.1");
        assertThat(Approach.OPERATOR_02B.unsupportedCases().keySet())
                .containsExactlyInAnyOrder("C2.1", "C2.2", "C2.4", "C2.5", "C4.3", "C5.4", "C5.5", "C5.7",
                        "C5.9", "C7.1");
        assertThat(Approach.STRUCTURED_03.unsupportedCases().keySet()).containsExactly("C7.1");
        assertThat(Approach.HYBRID_04.unsupportedCases().keySet()).containsExactly("C7.1");
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
