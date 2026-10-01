package dev.demo.vaadin.aigridfilter.benchmark.cases;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The catalog must hold exactly the cases the IT classes hold - that is the point of copying them. */
class CaseCatalogTest {

    @Test
    void holdsTwentyThreeCanonicalAndSixteenRobustnessCases() {
        assertThat(CaseCatalog.allIds()).containsExactly(
                "C1.1", "C1.2", "C1.3", "C2.1", "C2.2", "C2.3", "C2.4", "C3.1", "C3.2", "C3.3", "C3.4", "C3.5",
                "C4.1", "C4.2", "C4.3", "C5.1", "C5.2", "C5.3", "C5.4", "C5.5", "C6.1", "C6.2", "C6.3",
                "R1.1", "R1.2", "R1.3", "R1.4", "R2.1", "R2.2", "R2.3", "R2.4", "R2.5", "R3.1", "R3.2", "R3.3", "R3.4",
                "R4.1", "R4.2", "R5.1");
    }

    @Test
    void namesTheItTestMethodEveryCaseCameFrom() {
        assertThat(CaseCatalog.all())
                .allSatisfy(benchmarkCase ->
                        assertThat(benchmarkCase.itTestMethod()).isNotBlank());
    }

    @Test
    void marksThePromptInjectionAndNonExistentFieldCasesAsKnownFailures() {
        assertThat(CaseCatalog.all().stream().filter(BenchmarkCase::knownFailure).map(BenchmarkCase::id))
                .containsExactly("R4.1", "R5.1");
    }

    @Test
    void makesTheEmptyAndBlankQueriesVisible() {
        assertThat(CaseCatalog.byId("R2.4").displayQuery()).isEqualTo("(empty string)");
        assertThat(CaseCatalog.byId("R2.5").displayQuery()).isEqualTo("(a single blank)");
    }

    @Test
    void resolvesCaseIdsCaseInsensitively() {
        assertThat(CaseCatalog.byId("c1.1").id()).isEqualTo("C1.1");
    }

    @Test
    void rejectsAnUnknownCaseIdWithTheListOfKnownOnes() {
        assertThatThrownBy(() -> CaseCatalog.byId("C99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("C99")
                .hasMessageContaining("C1.1");
    }

    @Test
    void widensOnlyTheRelativeDateCase() {
        // C5.2 is the one case with more than one correct answer; everywhere else both predicates match.
        List<String> widened = CaseCatalog.all().stream()
                .filter(benchmarkCase -> benchmarkCase.mustMatch() != benchmarkCase.mayMatch())
                .map(BenchmarkCase::id)
                .toList();
        assertThat(widened).containsExactly("C5.2");
    }
}
