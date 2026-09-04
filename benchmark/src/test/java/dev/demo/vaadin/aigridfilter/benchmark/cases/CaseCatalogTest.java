package dev.demo.vaadin.aigridfilter.benchmark.cases;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The catalog must hold exactly the cases the IT classes hold - that is the point of copying them. */
class CaseCatalogTest {

    @Test
    void holdsTheTwelveCanonicalAndTenRobustnessCases() {
        assertThat(CaseCatalog.allIds()).containsExactly(
                "C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10", "C11", "C12",
                "R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8", "R9", "R10");
    }

    @Test
    void namesTheItTestMethodEveryCaseCameFrom() {
        assertThat(CaseCatalog.all())
                .allSatisfy(benchmarkCase ->
                        assertThat(benchmarkCase.itTestMethod()).isNotBlank());
    }

    @Test
    void marksOnlyThePromptInjectionCaseAsAKnownFailure() {
        assertThat(CaseCatalog.all().stream().filter(BenchmarkCase::knownFailure).map(BenchmarkCase::id))
                .containsExactly("R8");
    }

    @Test
    void makesTheEmptyAndBlankQueriesVisible() {
        assertThat(CaseCatalog.byId("R9").displayQuery()).isEqualTo("(empty string)");
        assertThat(CaseCatalog.byId("R10").displayQuery()).isEqualTo("(a single blank)");
    }

    @Test
    void resolvesCaseIdsCaseInsensitively() {
        assertThat(CaseCatalog.byId("c1").id()).isEqualTo("C1");
    }

    @Test
    void rejectsAnUnknownCaseIdWithTheListOfKnownOnes() {
        assertThatThrownBy(() -> CaseCatalog.byId("C99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("C99")
                .hasMessageContaining("C1");
    }

    @Test
    void widensOnlyTheRelativeDateCase() {
        // C7 is the one case with more than one correct answer; everywhere else both predicates match.
        List<String> widened = CaseCatalog.all().stream()
                .filter(benchmarkCase -> benchmarkCase.mustMatch() != benchmarkCase.mayMatch())
                .map(BenchmarkCase::id)
                .toList();
        assertThat(widened).containsExactly("C7");
    }
}
