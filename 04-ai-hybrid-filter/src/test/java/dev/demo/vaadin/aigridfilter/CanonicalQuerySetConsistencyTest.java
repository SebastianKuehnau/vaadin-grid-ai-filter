package dev.demo.vaadin.aigridfilter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the canonical query set against drift. {@code docs/canonical-query-set.md} is the single source
 * of truth for the natural-language queries every AI module is tested with, and each of the five copies —
 * the four modules' IT sources plus the standalone benchmark script — has to spell them out verbatim.
 * This test compares the copies this module owns, plus the benchmark script, against that document, in
 * wording <em>and</em> order.
 * <p>
 * Plain JUnit: no Spring context, no LLM, no Ollama. It therefore runs in every {@code mvn test} and
 * turns a one-word edit in any copy into a build failure instead of something a reviewer has to notice.
 * <p>
 * Deliberately source-text based rather than reflective: the requirement is that the <em>source</em>
 * carries the wording (that is what a reader of the test compares against the document), and the same
 * mechanism then also covers the benchmark script, which is a dependency-free single-file program with no
 * classes to reflect on.
 */
class CanonicalQuerySetConsistencyTest {

    private static final String DOCUMENT = "docs/canonical-query-set.md";

    private static final String BENCHMARK_SOURCE = "05-ollama-benchmark/BenchmarkLocalModels.java";

    /** The IT sources owned by this module; every one of them must carry the canonical wording. */
    private static final List<String> IT_SOURCES = List.of(
            "04-ai-hybrid-filter/src/test/java/dev/demo/vaadin/aigridfilter/ai/CanonicalQueryIT.java");

    /** A fenced {@code text} block in the document — one per canonical query, in order. */
    private static final Pattern DOCUMENTED_QUERY =
            Pattern.compile("```text\\R(.*?)\\R```", Pattern.DOTALL);

    /** An enum constant of an IT's {@code CanonicalQuery}: {@code C1_SINGLE_VALUE("...", ...)}. */
    private static final Pattern IT_QUERY =
            Pattern.compile("C\\d+_[A-Z0-9_]+\\(\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** A canonical case in the benchmark script: {@code EvalCase.canonical("C1_SINGLE_VALUE", "...", ...)}. */
    private static final Pattern BENCHMARK_QUERY =
            Pattern.compile("EvalCase\\.canonical\\(\"[A-Z0-9_]+\",\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    void everyItSourceOfThisModuleMatchesTheDocument() throws IOException {
        List<String> documented = queriesIn(DOCUMENTED_QUERY, read(DOCUMENT));
        assertThat(documented).as("%s must document at least the seven required categories", DOCUMENT)
                .hasSizeGreaterThanOrEqualTo(7);

        for (String source : IT_SOURCES) {
            assertThat(queriesIn(IT_QUERY, read(source)))
                    .as("%s must contain every canonical query verbatim, in the order of %s "
                            + "(update both together, never one of them)", source, DOCUMENT)
                    .containsExactlyElementsOf(documented);
        }
    }

    @Test
    void theBenchmarkScriptMatchesTheDocument() throws IOException {
        List<String> documented = queriesIn(DOCUMENTED_QUERY, read(DOCUMENT));

        assertThat(queriesIn(BENCHMARK_QUERY, read(BENCHMARK_SOURCE)))
                .as("%s must run exactly the canonical queries of %s, so its token and latency figures "
                        + "line up query-for-query with the IT suites' pass/fail results",
                        BENCHMARK_SOURCE, DOCUMENT)
                .containsExactlyElementsOf(documented);
    }

    /** All group-1 matches of {@code pattern}, in order, with Java string escapes resolved. */
    private static List<String> queriesIn(Pattern pattern, String text) {
        List<String> queries = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            queries.add(matcher.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return queries;
    }

    /** Reads a repo-relative file, so the test works from the module directory and from the repo root. */
    private static String read(String repoRelativePath) throws IOException {
        return Files.readString(repoRoot().resolve(repoRelativePath), StandardCharsets.UTF_8);
    }

    /** Walks up from the working directory until the canonical query set document is found. */
    private static Path repoRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(DOCUMENT))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not find " + DOCUMENT + " in any parent of "
                + Path.of("").toAbsolutePath());
    }
}
